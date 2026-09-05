"""HondaLink connectivity test — a diagnostic, not an integration.

A single stateless endpoint that reproduces what the HondaLink Android app
does to READ a vehicle: HIDAS login, list vehicles, then request the
dashboard (``dbd/latest``, falling back to ``dbd/async`` + results). It
never calls a command endpoint (lock, unlock, engine, horn), so it cannot
change the car.

Credentials are used for the one request chain and then discarded — they
are never written to the database, never logged, and never returned. The
VIN is redacted to its last four characters in every response field. This
exists so the user can find out, from the web UI, whether Honda's backend
serves fuel / odometer / oil life for their specific vehicle before anyone
builds a real integration on top of it. On a 2019 Pilot Elite the likely
answer is HTTP 400 (the pre-MY21 telematics generation); Smartcar is the
sanctioned alternative. See docs/research and the session notes.

The app API is unofficial: it uses the client credentials shipped in the
APK and is not supported by Honda. This endpoint is behind the query
token like every other read endpoint, and the pitstop stack is LAN-only,
but the password does travel the LAN in plaintext to this backend — the
same trust model as the MQTT password already in Settings.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

import httpx
from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..auth import require_ingest_token

router = APIRouter(prefix="/hondalink", tags=["hondalink"])

IDENTITY_BASE = "https://identity.services.honda.com"
API_BASE = "https://wsc.hondaweb.com"
# App-level credentials shipped in the HondaLink Android APK (published in
# several open-source clients). Not per-user secrets; Honda can rotate
# them, which would break this test.
CLIENT_ID = "HondaLinkAndroidApp0074"
CLIENT_SECRET = "rETFrZcLyUycsSblksCP"
USER_AGENT = "HondaLink/5.0.51 (Android)"
BUSINESS_ID = "HONDALINK CONNECT"
SYSTEM_ID = "com.honda.hondalink.cv_android"
# Progressively smaller filter sets — Honda rejects an over-broad request
# with an "invalid scope" error, so fall back to fewer filters.
FILTER_SETS: tuple[list[str], ...] = (
    ["DigitalTwin", "VEHICLE RANGE", "odometer", "TIRE PRESSURE"],
    ["DigitalTwin"],
    [],
)
DASHBOARD_KEYS = ("fuelLevel", "odometer", "oilLife", "tireStatus", "gpsData")
TIMEOUT = httpx.Timeout(30.0)


class HondaLinkTestRequest(BaseModel):
    email: str = Field(min_length=3)
    password: str = Field(min_length=1)


def _utc() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _redact_vin(vin: str | None) -> str:
    return f"...{vin[-4:]}" if vin and len(vin) >= 4 else "?"


def _clip(value: Any, limit: int = 400) -> str:
    text = value if isinstance(value, str) else str(value)
    return text if len(text) <= limit else text[:limit] + "…"


class _Probe:
    """One login-and-read chain. Holds tokens for the chain's lifetime
    only; the caller discards the instance immediately after."""

    def __init__(self, client: httpx.AsyncClient, email: str, password: str):
        self.client = client
        self.email = email
        self.password = password
        self.client_reg_key: str | None = None
        self.access_token: str | None = None
        self.hidas_ident: str | None = None
        self.country = "US"
        self.language = "en"
        self.device_id = str(uuid.uuid4())
        self.session_id = str(uuid.uuid4())
        self.steps: list[dict[str, Any]] = []

    def _step(self, name: str, ok: bool, detail: str) -> None:
        self.steps.append({"step": name, "ok": ok, "detail": detail})

    def _api_headers(self) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self.access_token}",
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": USER_AGENT,
            "hondaHeaderType.version": "1.0",
            "hondaHeaderType.messageId": str(uuid.uuid4()),
            "hondaHeaderType.siteId": self.client_reg_key or "",
            "hondaHeaderType.businessId": BUSINESS_ID,
            "hondaHeaderType.systemId": SYSTEM_ID,
            "hondaHeaderType.collectedTimestamp": _utc(),
            "hondaHeaderType.collectedTimeStamp": _utc(),
            "hondaHeaderType.clientType": "Mobile",
            "hondaHeaderType.deviceID": self.device_id,
            "hondaHeaderType.sessionID": self.session_id,
            "hondaHeaderType.country_code": self.country,
            "hondaHeaderType.language_code": self.language,
        }
        if self.hidas_ident:
            headers["hondaHeaderType.userId"] = self.hidas_ident
            headers["hondaHeaderType.hidasId"] = self.hidas_ident
        return headers

    @staticmethod
    def _json(resp: httpx.Response) -> Any:
        try:
            return resp.json()
        except ValueError:
            return {"_raw": _clip(resp.text)}

    async def _api(self, method: str, path: str,
                   body: dict | None = None) -> tuple[int, Any]:
        resp = await self.client.request(
            method, API_BASE + path, headers=self._api_headers(), json=body,
        )
        return resp.status_code, self._json(resp)

    async def login(self) -> bool:
        reg = await self.client.post(
            f"{IDENTITY_BASE}/hidas/rs/client/register",
            data={"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET},
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
        )
        data = self._json(reg)
        try:
            self.client_reg_key = data["clientregistrationkey"]["client_reg_key"]
        except (KeyError, TypeError):
            self._step("register", False,
                       f"HTTP {reg.status_code}: {_clip(data)}")
            return False
        self._step("register", True, "app client registered")

        tok = await self.client.post(
            f"{IDENTITY_BASE}/hidas/rs/token/generate",
            data={
                "username": self.email,
                "password": self.password,
                "description": "Android",
                "client_reg_key": self.client_reg_key,
            },
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
        )
        data = self._json(tok)
        if data.get("request_status") != "success":
            # Do not echo the payload verbatim — it can carry account
            # details. Report the coarse reason only.
            reason = data.get("request_status") or f"HTTP {tok.status_code}"
            self._step("login", False, f"login rejected ({reason})")
            return False
        token = data.get("token") or {}
        user = data.get("user") or {}
        self.access_token = token.get("access_token")
        self.hidas_ident = user.get("hidas_ident")
        self.country = user.get("country_code") or self.country
        self.language = user.get("language_code") or self.language
        if not self.access_token:
            self._step("login", False, "no access token returned")
            return False
        self._step("login", True, f"logged in (country {self.country})")
        return True

    async def dashboard(self, vin: str) -> dict[str, Any] | None:
        status, data = await self._api(
            "POST", f"/REST/NGT/CIG/dbd/latest/{vin}",
            {"fromDate": "", "toDate": ""},
        )
        body = data.get("responseBody") if isinstance(data, dict) else None
        fields = self._extract(body if isinstance(body, dict) else data)
        if status < 400 and str((data or {}).get("status", "")).lower() == "success":
            self._step("dashboard", True, "dbd/latest returned a dashboard")
            return fields
        err = ""
        if isinstance(data, dict):
            err = str(data.get("errorCode")
                      or (data.get("Header") or {}).get("ErrorCode") or "")
        self._step("dashboard", False,
                   f"dbd/latest HTTP {status}"
                   + (f" errorCode {err}" if err else ""))

        # Fall back to the async request-a-fresh-read path.
        for filters in FILTER_SETS:
            req_body: dict[str, Any] = {"device": vin}
            if filters:
                req_body["filters"] = filters
            status, data = await self._api(
                "POST", "/REST/NGT/CIG/dbd/async", req_body)
            rid = None
            if isinstance(data, dict):
                rid = (data.get("responseBody") or {}).get("cigServiceRequestId")
            if rid:
                self._step("dashboard_async", True,
                           f"async accepted (filters={filters or 'none'})")
                return self._extract(data)
            if status < 400:
                break
        self._step("dashboard_async", False,
                   "backend did not serve a dashboard for this vehicle "
                   "(the pre-MY21 case)")
        return None

    @staticmethod
    def _extract(body: Any) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if not isinstance(body, dict):
            return out
        for key in DASHBOARD_KEYS:
            if key in body:
                out[key] = body[key]
        return out


@router.post("/test", dependencies=[Depends(require_ingest_token)])
async def test_hondalink(req: HondaLinkTestRequest) -> dict[str, Any]:
    """Run one read-only HondaLink probe. Returns a step-by-step trace, the
    vehicles found (VIN redacted), and any dashboard fields Honda returned.
    Stores nothing; the credentials live only for this request."""
    async with httpx.AsyncClient(timeout=TIMEOUT, follow_redirects=True) as client:
        probe = _Probe(client, req.email, req.password)
        try:
            if not await probe.login():
                return {"ok": False, "steps": probe.steps, "vehicles": [],
                        "dashboard": None}

            status, data = await probe._api("GET", "/REST/NGT/MyVehicle/1.0")
            raw = data if isinstance(data, list) else (
                (data.get("vehicles") or data.get("data") or [])
                if isinstance(data, dict) else []
            )
            if isinstance(raw, dict):
                raw = [raw]
            vehicles = [
                {
                    "model_year": v.get("ModelYear"),
                    "model": v.get("ModelGroupNameFriendly") or v.get("ModelCode"),
                    "vin_last4": _redact_vin(v.get("VIN") or v.get("vin")),
                    "vin": v.get("VIN") or v.get("vin"),  # used server-side only
                }
                for v in raw if isinstance(v, dict)
            ]
            if not vehicles:
                probe._step("vehicles", False,
                            f"no vehicles returned (HTTP {status})")
                return {"ok": False, "steps": probe.steps, "vehicles": [],
                        "dashboard": None}
            probe._step("vehicles", True, f"{len(vehicles)} vehicle(s) on the account")

            vin = vehicles[0].pop("vin", None)
            for other in vehicles[1:]:
                other.pop("vin", None)
            dashboard = await probe.dashboard(vin) if vin else None
            return {
                "ok": dashboard is not None,
                "steps": probe.steps,
                "vehicles": vehicles,
                "dashboard": dashboard,
            }
        except httpx.HTTPError as exc:
            probe._step("network", False, f"network error: {_clip(str(exc))}")
            return {"ok": False, "steps": probe.steps, "vehicles": [],
                    "dashboard": None}
