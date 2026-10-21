"""PID Lab — one-shot Mode 22 probes through the WiCAN, for verifying signals.

A diagnostic, not a capture path: it sends a single UDS ReadDataByIdentifier
(service 0x22) request through the dongle's firmware-6 AutoPID test endpoint
(``POST /api/autopid/test``), decodes the answer with the recipe from the
catalog below, and returns the raw response next to the decoded values so a
human can compare them against the dash. Nothing is stored server-side; the
page keeps a session log the user can download as a test fixture.

Firmware 6 returns the whole multi-frame response (39-59 bytes seen on the
Pilot, 2026-10-21); 4.x cut it off at ~34 payload bytes. The test endpoint
pauses AutoPID around the shot and puts the chip's header state back, which
4.x did not (the 62->19 key collapse in ADR-022). ``/api/uds/request`` was
tried first and refused most replies on the OBD-chip transport with
``ESP_ERR_INVALID_RESPONSE`` while this endpoint read the same DIDs cleanly.

Only service 0x22 is ever sent. A custom request whose first byte is
anything else is refused before it reaches the dongle, so this page cannot
write a DID, run a routine, reset a module or clear codes.

Catalog decodes come from the OBDb Honda-Pilot signalset and are unverified
on this car unless marked ``verified``. ``bix``/``len`` use OBDb's
convention: a big-endian bit index into the data AFTER the ``62 <DID>``
echo. Payload index in docs/research/honda-pilot-pids.md = bix / 8 + 3.
"""

from __future__ import annotations

import ipaddress
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..auth import require_query_token
from ..config import settings

router = APIRouter(prefix="/pidlab", tags=["pidlab"])

TIMEOUT = httpx.Timeout(10.0)


@dataclass(frozen=True)
class Signal:
    name: str
    bix: int
    len: int
    unit: str = ""
    mul: float = 1.0
    div: float = 1.0
    add: float = 0.0
    sign: bool = False
    # Raw-value labels, e.g. gear codes. A value with no label is shown raw.
    labels: dict[int, str] = field(default_factory=dict)


@dataclass(frozen=True)
class Probe:
    id: str
    module: str
    module_id: str  # the module byte in 18DA<mm>F1
    did: str
    title: str
    signals: tuple[Signal, ...]
    check: str  # how to validate the value by hand
    verified: bool = False

    @property
    def tx_id(self) -> str:
        return f"18DA{self.module_id}F1"

    @property
    def rx_id(self) -> str:
        return f"18DAF1{self.module_id}"


_ZF_GEAR = {0: "P/N", 15: "R", **{g: f"D{g}" for g in range(1, 10)}}

CATALOG: tuple[Probe, ...] = (
    Probe(
        "atf_temp",
        "ZF 9HP TCM",
        "1E",
        "3083",
        "Transmission fluid temperature",
        (Signal("atf_temp", 112, 8, "°C", add=-40),),
        "Rises from coolant-ish temperature as the transmission warms.",
        verified=True,
    ),
    Probe(
        "gear",
        "ZF 9HP TCM",
        "1E",
        "3086",
        "Gear position",
        (
            Signal("gear", 160, 8, labels=_ZF_GEAR),
            Signal("gear_target", 168, 8, labels=_ZF_GEAR),
        ),
        "Shift D→R→N→D with the foot on the brake: expect 2, 15, 0, 2. "
        "OBDb maps 14 to N; this car has read 0.",
        verified=True,
    ),
    Probe(
        "odometer_dash",
        "Instrument cluster",
        "60",
        "7022",
        "Dash odometer",
        (Signal("odometer", 48, 24, "mi"),),
        "Should equal the dash odometer exactly.",
        verified=True,
    ),
    Probe(
        "fuel_cluster",
        "Instrument cluster",
        "60",
        "7029",
        "Fuel level (cluster)",
        (Signal("fuel_level", 96, 16, "L", div=100),),
        "Compare with the gauge; after a fill-up, the change should match litres added.",
    ),
    Probe(
        "outside_temp",
        "Instrument cluster",
        "60",
        "7028",
        "Outside air temperature",
        (Signal("outside_temp", 132, 12, "°F", sign=True),),
        "Should match the dash outside-temperature display (°F on a 2019).",
        verified=True,
    ),
    Probe(
        "maintenance",
        "Instrument cluster",
        "60",
        "7060",
        "Maintenance Minder",
        (
            Signal("oil_filter", 112, 16),
            Signal("spark_plugs", 128, 16),
            Signal("inspection", 208, 16),
            Signal("tire_rotation", 224, 16),
            Signal("cabin_filter", 240, 16),
            Signal("trans_fluid", 256, 16),
            Signal("coolant", 288, 16),
            Signal("brake_fluid", 320, 16),
            Signal("air_cleaner", 336, 16),
        ),
        "Unit unknown. Answered 7F 22 31 (not supported) on this car 2026-10-21.",
    ),
    Probe(
        "oil_temp",
        "PCM",
        "10",
        "2666",
        "Engine oil temperature",
        (
            Signal("oil_temp_a", 88, 8, "°C", add=-40),
            Signal("oil_temp_b", 112, 8, "°C", add=-40),
        ),
        "Cold start ≈ ambient; climbs slower than coolant. Answered 7F 22 31 on this car "
        "2026-10-21 (OBDb's module 0x11 variant may be the one).",
    ),
    Probe(
        "vtec",
        "PCM",
        "10",
        "2612",
        "VTEC pressure switch / solenoid",
        (Signal("vtec_switch", 174, 1), Signal("vtec_solenoid", 175, 1)),
        "0 at idle; should flip under hard acceleration (passenger reads).",
    ),
    Probe(
        "alternator",
        "PCM",
        "10",
        "2610",
        "Alternator duty / A/C clutch",
        (
            Signal("alternator_duty", 184, 8, "%", mul=100, div=255),
            Signal("ac_clutch", 262, 1),
        ),
        "Toggle the A/C: the clutch bit should follow.",
    ),
    Probe(
        "active_cylinders",
        "PCM",
        "10",
        "2615",
        "Active cylinders (VCM)",
        (Signal("active_cylinders", 400, 8),),
        "6 at idle and under load; 3 on a light steady cruise. Past the 4.x 34-byte cutoff.",
    ),
    Probe(
        "misfires",
        "PCM",
        "10",
        "2663",
        "Misfire count per cylinder",
        tuple(Signal(f"misfire_{i}", 272 + 16 * (i - 1), 16) for i in range(1, 7)),
        "Expect 0 or small counts on a healthy engine. Past the 4.x 34-byte cutoff.",
    ),
    Probe(
        "pcm_odometer",
        "PCM",
        "10",
        "2660",
        "PCM odometer / time since start",
        (Signal("odometer", 344, 24, "km"), Signal("runtime", 368, 16, "s")),
        "Runtime should match seconds since the engine started.",
    ),
    Probe(
        "tpms",
        "TPMS",
        "26",
        "6001",
        "Tire pressures and temperatures",
        (
            Signal("pressure_fr", 64, 16, "kPa"),
            Signal("pressure_fl", 80, 16, "kPa"),
            Signal("pressure_rr", 96, 16, "kPa"),
            Signal("pressure_rl", 112, 16, "kPa"),
            Signal("temp_fr", 192, 8, "°C", sign=True),
            Signal("temp_fl", 200, 8, "°C", sign=True),
            Signal("temp_rr", 208, 8, "°C", sign=True),
            Signal("temp_rl", 216, 8, "°C", sign=True),
        ),
        "Compare with the dash TPMS page; confirm wheel mapping by adjusting one tire.",
        verified=True,
    ),
    Probe(
        "brake_pressure",
        "VSA",
        "28",
        "4005",
        "Brake pressure",
        (Signal("brake_pressure", 56, 16, "bar", add=-25),),
        "≈0 off the pedal; rises as the pedal is pressed (engine running, in Park).",
    ),
)

_BY_ID = {p.id: p for p in CATALOG}

NRC_NAMES = {
    0x11: "serviceNotSupported",
    0x12: "subFunctionNotSupported",
    0x13: "incorrectMessageLengthOrInvalidFormat",
    0x22: "conditionsNotCorrect",
    0x31: "requestOutOfRange (DID not supported by this module)",
    0x33: "securityAccessDenied",
    0x78: "responsePending",
}


def decode_signal(data: bytes, sig: Signal) -> float | None:
    """Extract one OBDb-style field from the data after the DID echo."""
    if sig.bix + sig.len > len(data) * 8:
        return None
    raw = int.from_bytes(data, "big")
    shift = len(data) * 8 - sig.bix - sig.len
    value = (raw >> shift) & ((1 << sig.len) - 1)
    if sig.sign and value >= 1 << (sig.len - 1):
        value -= 1 << sig.len
    return value * sig.mul / sig.div + sig.add


def decode_response(probe: Probe, response: bytes) -> list[dict[str, Any]]:
    """Decode a positive ``62 <DID> …`` response; signals past the end are None."""
    data = response[3:]
    out = []
    for sig in probe.signals:
        value = decode_signal(data, sig)
        if value is not None:
            value = round(value, 3)
        label = sig.labels.get(int(value)) if value is not None and sig.labels else None
        out.append(
            {
                "name": sig.name,
                "value": value,
                "unit": sig.unit,
                "label": label,
                "payload_index": sig.bix // 8 + 3,
            }
        )
    return out


def _parse_hex(text: str) -> bytes:
    cleaned = text.replace(" ", "").replace(":", "")
    try:
        return bytes.fromhex(cleaned)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"not hex: {text!r}") from e


def _resolve_base_url(override: str | None) -> str:
    """The dongle's base URL: the request's override or WICAN_URL.

    An override must point at a private LAN address or a ``.lan``/``.local``
    name, so this endpoint can't be used to reach the internet.
    """
    url = (override or settings.wican_url).strip().rstrip("/")
    if not url:
        raise HTTPException(status_code=400, detail="no WiCAN address: set WICAN_URL or enter one")
    if "://" not in url:
        url = f"http://{url}"
    host = urlparse(url).hostname or ""
    try:
        ok = ipaddress.ip_address(host).is_private
    except ValueError:
        ok = host.endswith((".lan", ".local")) or host == "localhost"
    if not ok:
        raise HTTPException(status_code=400, detail=f"{host!r} is not a LAN address")
    return url


class RunRequest(BaseModel):
    probe: str | None = None
    # Custom request, used when ``probe`` is empty.
    module_id: str | None = Field(default=None, pattern=r"^[0-9A-Fa-f]{2}$")
    data: str | None = None
    base_url: str | None = None


@router.get("/catalog", dependencies=[Depends(require_query_token)])
async def catalog() -> dict[str, Any]:
    return {
        "wican_url": settings.wican_url,
        "probes": [
            {
                "id": p.id,
                "module": p.module,
                "module_id": p.module_id,
                "did": p.did,
                "title": p.title,
                "tx_id": p.tx_id,
                "check": p.check,
                "verified": p.verified,
                "signals": [
                    {"name": s.name, "unit": s.unit, "payload_index": s.bix // 8 + 3}
                    for s in p.signals
                ],
            }
            for p in CATALOG
        ],
    }


@router.get("/device", dependencies=[Depends(require_query_token)])
async def device(base_url: str | None = None) -> dict[str, Any]:
    """Firmware, UDS path and AutoPID state, to read before and after a probe."""
    url = _resolve_base_url(base_url)
    out: dict[str, Any] = {"base_url": url}
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        for key, path in (("info", "/api/info"), ("autopid", "/api/autopid")):
            try:
                r = await client.get(url + path)
                r.raise_for_status()
                out[key] = r.json()
            except (httpx.HTTPError, ValueError) as e:
                out[key] = None
                out.setdefault("errors", {})[key] = str(e) or type(e).__name__
    autopid = out.get("autopid") or {}
    params = autopid.get("params") or []
    out["autopid_live_params"] = sum(1 for p in params if p.get("value") is not None)
    return out


@router.post("/run", dependencies=[Depends(require_query_token)])
async def run(req: RunRequest) -> dict[str, Any]:
    probe = _BY_ID.get(req.probe or "")
    if req.probe and probe is None:
        raise HTTPException(status_code=404, detail=f"unknown probe {req.probe!r}")
    if probe:
        module_id, request = probe.module_id, bytes.fromhex("22" + probe.did)
    else:
        if not req.module_id or not req.data:
            raise HTTPException(status_code=400, detail="pick a probe or give module_id + data")
        module_id, request = req.module_id.upper(), _parse_hex(req.data)
    if not request or request[0] != 0x22 or len(request) < 3:
        raise HTTPException(
            status_code=400, detail="only service 22 (read DID) requests are allowed"
        )

    url = _resolve_base_url(req.base_url)
    tx_id, rx_id = f"18DA{module_id}F1", f"18DAF1{module_id}"
    body = {"cmd": request.hex().upper(), "init": f"ATSH{tx_id};ATCRA{rx_id}"}
    started = time.monotonic()
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            r = await client.post(url + "/api/autopid/test", json=body)
    except httpx.HTTPError as e:
        return {
            "ok": False,
            "request": body,
            "error": f"dongle unreachable: {e or type(e).__name__}",
        }
    elapsed_ms = round((time.monotonic() - started) * 1000)
    try:
        test = r.json()
    except ValueError:
        test = {"error": r.text[:300]}
    result: dict[str, Any] = {
        "ok": False,
        "request": {"tx_id": tx_id, "rx_id": rx_id, "data": request.hex(" ").upper()},
        "http_status": r.status_code,
        "elapsed_ms": elapsed_ms,
        "transcript": test.get("transcript"),
    }
    response = _parse_hex(test.get("payload") or "")
    if response:
        result["response"] = response.hex(" ").upper()
        result["length"] = len(response)
    if response[:2] == b"\x7f\x22" and len(response) >= 3:
        result["error"] = NRC_NAMES.get(response[2], f"negative response 0x{response[2]:02X}")
        return result
    if r.status_code != 200 or not response:
        result["error"] = test.get("error") or f"HTTP {r.status_code}"
        return result
    if response[0] != 0x62 or response[1:3] != request[1:3]:
        result["error"] = "mismatched response"
        return result
    result["ok"] = True
    if probe:
        result["signals"] = decode_response(probe, response)
    return result
