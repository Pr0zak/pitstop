"""The HondaLink connectivity test is a diagnostic that talks to Honda's
servers. These tests run it against an httpx MockTransport instead, to pin
the contract without a network call or real credentials:

* a rejected login stops cleanly with ``ok`` false and no vehicles;
* a full success extracts only the wanted dashboard fields;
* the VIN is redacted to its last four everywhere it appears;
* the password never appears in the response;
* a pre-MY21-style 400 on the dashboard, with no async request id, ends
  with ``dashboard`` null rather than an error.
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from pitstop.api import hondalink
from pitstop.api.hondalink import HondaLinkTestRequest
from pitstop.api.hondalink import test_hondalink as run_probe

VIN = "5FNYF6H09KB000999"
PASSWORD = "s3cret-should-never-leak"


def _run(handler, email="owner@example.com", password=PASSWORD):
    """Drive test_hondalink with a mock transport wired into the client."""
    transport = httpx.MockTransport(handler)
    real = httpx.AsyncClient

    def factory(**kwargs):
        kwargs.pop("transport", None)
        return real(transport=transport, **kwargs)

    orig = hondalink.httpx.AsyncClient
    hondalink.httpx.AsyncClient = factory  # type: ignore[assignment]
    try:
        return asyncio.run(
            run_probe(HondaLinkTestRequest(email=email, password=password))
        )
    finally:
        hondalink.httpx.AsyncClient = orig  # type: ignore[assignment]


def _json_response(status: int, body: dict) -> httpx.Response:
    return httpx.Response(status, json=body)


def _register(_req: httpx.Request) -> httpx.Response:
    return _json_response(200, {"clientregistrationkey": {"client_reg_key": "KEY"}})


def _login_ok(_req: httpx.Request) -> httpx.Response:
    return _json_response(200, {
        "request_status": "success",
        "token": {"access_token": "AT", "refresh_token": "RT", "expires_in": 3600},
        "user": {"hidas_ident": "HID", "country_code": "US", "language_code": "en"},
    })


def _vehicles(_req: httpx.Request) -> httpx.Response:
    return _json_response(200, [
        {"VIN": VIN, "ModelYear": 2019, "ModelGroupNameFriendly": "Pilot"},
    ])


def _route(req: httpx.Request, *, login, dashboard) -> httpx.Response:
    path = req.url.path
    if path.endswith("/client/register"):
        return _register(req)
    if path.endswith("/token/generate"):
        return login(req)
    if path.endswith("/MyVehicle/1.0"):
        return _vehicles(req)
    if "/dbd/latest/" in path:
        return dashboard(req)
    if path.endswith("/dbd/async"):
        return _json_response(200, {"status": "success", "responseBody": {}})
    return _json_response(404, {"error": "unexpected path", "path": path})


def test_rejected_login_stops_cleanly():
    def login_fail(_req):
        return _json_response(200, {"request_status": "failed"})

    def handler(req):
        return _route(req, login=login_fail, dashboard=_vehicles)

    result = _run(handler)
    assert result["ok"] is False
    assert result["vehicles"] == []
    assert result["dashboard"] is None
    steps = {s["step"]: s["ok"] for s in result["steps"]}
    assert steps["register"] is True
    assert steps["login"] is False
    # The password must never surface in the trace.
    assert PASSWORD not in json.dumps(result)


def test_full_success_extracts_dashboard_and_redacts_vin():
    def dashboard_ok(_req):
        return _json_response(200, {
            "status": "success",
            "responseBody": {
                "fuelLevel": {"currentLevel": {"value": 62},
                              "driveRange": {"value": 410}},
                "odometer": {"value": 79458},
                "oilLife": {"value": 40},
                "tireStatus": {"frontLeft": {"pressureData": {"value": 34}}},
                "somethingElse": {"value": "ignored"},
            },
        })

    def handler(req):
        return _route(req, login=_login_ok, dashboard=dashboard_ok)

    result = _run(handler)
    assert result["ok"] is True
    assert result["vehicles"] == [
        {"model_year": 2019, "model": "Pilot", "vin_last4": "...0999"},
    ]
    # VIN never leaks in full — only the last four.
    assert VIN not in json.dumps(result)
    assert "vin" not in result["vehicles"][0]
    dash = result["dashboard"]
    assert set(dash) == {"fuelLevel", "odometer", "oilLife", "tireStatus"}
    assert dash["odometer"]["value"] == 79458
    assert "somethingElse" not in dash
    assert PASSWORD not in json.dumps(result)


def test_pre_my21_dashboard_400_ends_without_data():
    def dashboard_400(_req):
        return _json_response(400, {"errorCode": "0001-01-1151",
                                    "errorMessage": "Bad Request"})

    def async_no_id(_req):
        return _json_response(200, {"status": "success", "responseBody": {}})

    def handler(req):
        if req.url.path.endswith("/dbd/async"):
            return async_no_id(req)
        return _route(req, login=_login_ok, dashboard=dashboard_400)

    result = _run(handler)
    assert result["ok"] is False
    assert result["dashboard"] is None
    steps = {s["step"]: s["ok"] for s in result["steps"]}
    assert steps["login"] is True
    assert steps["vehicles"] is True
    assert steps["dashboard"] is False
    assert steps["dashboard_async"] is False
    # The vehicle was still discovered; only the data read failed.
    assert result["vehicles"][0]["vin_last4"] == "...0999"


def test_network_error_is_reported_not_raised():
    def handler(_req):
        raise httpx.ConnectError("boom")

    result = _run(handler)
    assert result["ok"] is False
    assert any(s["step"] == "network" and not s["ok"] for s in result["steps"])
    assert PASSWORD not in json.dumps(result)


@pytest.mark.parametrize("vin,expected", [
    ("5FNYF6H09KB000999", "...0999"),
    ("ABC", "?"),
    ("", "?"),
    (None, "?"),
])
def test_vin_redaction(vin, expected):
    assert hondalink._redact_vin(vin) == expected
