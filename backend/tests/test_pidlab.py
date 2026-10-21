"""PID Lab sends one read-only UDS request through the WiCAN and decodes it.

These tests pin, without a dongle:

* the OBDb ``bix`` convention against the real ATF frame recorded on the car
  (payload[17] = 0x65 → 61 °C) and the verified gear codes;
* signed and sub-byte fields;
* that only service 0x22 ever reaches the dongle;
* that the base URL must be a LAN address;
* that a negative response is reported, not decoded.
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest
from fastapi import HTTPException

from pitstop.api import pidlab
from pitstop.api.pidlab import RunRequest, Signal, decode_response, decode_signal

# First 20 bytes of the 22 3083 answer read on the vehicle 2026-08-04.
ATF_RESPONSE = bytes.fromhex("62 3083 FFFF8003 0000000000 0402C4 0000 65 8D86".replace(" ", ""))


def test_atf_frame_decodes_to_recorded_value():
    [atf] = decode_response(pidlab._BY_ID["atf_temp"], ATF_RESPONSE)
    assert atf["payload_index"] == 17
    assert atf["value"] == 61


def test_gear_codes_carry_labels():
    response = bytes([0x62, 0x30, 0x86]) + bytes(20) + bytes([15, 15])
    gear, target = decode_response(pidlab._BY_ID["gear"], response)
    assert gear["payload_index"] == 23
    assert (gear["value"], gear["label"]) == (15, "R")
    assert target["label"] == "R"


def test_signed_twelve_bit_field():
    # 12 bits at bix 4: 0xFF6 = -10 as signed 12-bit.
    data = bytes([0x0F, 0xF6])
    assert decode_signal(data, Signal("t", 4, 12, sign=True)) == -10


def test_single_bit_and_scaled_fields():
    data = bytes([0b0000_0010, 255])
    assert decode_signal(data, Signal("bit6", 6, 1)) == 1
    assert decode_signal(data, Signal("bit7", 7, 1)) == 0
    assert decode_signal(data, Signal("pct", 8, 8, mul=100, div=255)) == 100


def test_signal_past_end_is_none():
    # A 4.x-style truncated answer must not decode byte 53.
    [cyl] = decode_response(
        pidlab._BY_ID["active_cylinders"], bytes([0x62, 0x26, 0x15]) + bytes(31)
    )
    assert cyl["value"] is None


def _run(req: RunRequest, handler):
    transport = httpx.MockTransport(handler)
    real = httpx.AsyncClient

    def factory(**kwargs):
        return real(transport=transport, **kwargs)

    orig = pidlab.httpx.AsyncClient
    pidlab.httpx.AsyncClient = factory  # type: ignore[assignment]
    try:
        return asyncio.run(pidlab.run(req))
    finally:
        pidlab.httpx.AsyncClient = orig  # type: ignore[assignment]


def test_probe_sends_extended_ids_and_decodes():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"ok": True, "payload": ATF_RESPONSE.hex(" ").upper()})

    out = _run(RunRequest(probe="atf_temp", base_url="http://192.168.1.20"), handler)
    assert seen["path"] == "/api/autopid/test"
    assert seen["body"] == {"cmd": "223083", "init": "ATSH18DA1EF1;ATCRA18DAF11E"}
    assert out["ok"] is True
    assert out["signals"][0]["value"] == 61


def test_negative_response_is_not_decoded():
    # What the Pilot's cluster answered for 22 7060 on 2026-10-21.
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"ok": True, "payload": "7F 22 31"})

    out = _run(RunRequest(probe="maintenance", base_url="http://192.168.1.20"), handler)
    assert out["ok"] is False
    assert out["error"].startswith("requestOutOfRange")
    assert "signals" not in out


def test_tcm_frame_from_firmware_6_decodes():
    # 22 3083 through /api/autopid/test on 6.0.0-beta.2: the full 39 bytes.
    payload = (
        "62 30 83 FF FF 80 03 00 00 00 00 00 04 02 B1 00 00 4D 8E 86 00 84 F0 80 00 1C" + " 00" * 13
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"ok": True, "payload": payload})

    out = _run(RunRequest(probe="atf_temp", base_url="http://192.168.1.20"), handler)
    assert out["length"] == 39
    assert out["signals"][0]["value"] == 37


@pytest.mark.parametrize("data", ["2E 30 83 00", "11 01", "31 01 FF 00", "14 FF FF FF", "22"])
def test_only_read_did_requests_are_sent(data):
    def handler(_request: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("must not reach the dongle")

    with pytest.raises(HTTPException) as e:
        _run(RunRequest(module_id="10", data=data, base_url="http://192.168.1.20"), handler)
    assert e.value.status_code == 400


@pytest.mark.parametrize(
    "url", ["http://8.8.8.8", "https://example.com", "http://wican.example.org"]
)
def test_base_url_must_be_lan(url):
    with pytest.raises(HTTPException):
        pidlab._resolve_base_url(url)


@pytest.mark.parametrize("url", ["192.168.1.20", "http://172.16.0.9", "http://wican.lan"])
def test_lan_base_urls_are_accepted(url):
    assert pidlab._resolve_base_url(url).startswith("http://")
