"""Google Places API (New) client for live nearby fuel prices.

One Nearby Search call returns up to 20 gas stations around a point,
each with ``fuelOptions.fuelPrices[]`` — the last known price per grade
and when it was reported. Stations that don't report prices come back
without ``fuelOptions``; callers render those as "—", never 0.

Cost control, because ``fuelOptions`` bills as "Nearby Search
Enterprise + Atmosphere" (1,000 free calls a month, then $40 per 1,000):

- Results are cached in memory for ``CACHE_TTL`` per ~1 km grid cell.
  The search is always centred on the cell centre (with the radius
  padded by ``_CELL_PAD_M``) so every point in a cell shares one call;
  distances are then recomputed from the caller's real position.
- ``places_api_usage`` counts calls per UTC month. A slot is reserved
  with a conditional upsert *before* calling Google, and the reservation
  fails at ``MONTHLY_CAP`` — below the free 1,000 so a miscount can't
  tip into billing.

Prices are never written to the DB — Google's terms restrict storing
Places content, so the in-memory cache is the only copy.
"""

from __future__ import annotations

import logging
import math
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

import asyncpg
import httpx

log = logging.getLogger(__name__)

_SEARCH_URL = "https://places.googleapis.com/v1/places:searchNearby"
# Only what the card shows. fuelOptions already puts the call on the
# top SKU, so the address/name fields don't raise the price further.
_FIELD_MASK = ",".join(
    (
        "places.id",
        "places.displayName",
        "places.shortFormattedAddress",
        "places.location",
        "places.fuelOptions",
    )
)

MONTHLY_CAP = 900
CACHE_TTL = 30 * 60  # seconds
MAX_RADIUS_M = 25_000
# 2-dp cells are ~1.1 km on a side; the farthest point from a cell
# centre is ~0.8 km, so pad the search by 1 km to still cover the
# caller's full radius.
_CELL_PAD_M = 1_000

# Places FuelType values the clients offer as grades. The API has more
# (SP95, E85, LPG, …); they still come back in `other_prices`.
GRADES = ("REGULAR_UNLEADED", "MIDGRADE", "PREMIUM", "DIESEL")


class FuelPriceError(Exception):
    """Google rejected or failed the call. `detail` is safe to show."""

    def __init__(self, detail: str, status_code: int | None = None) -> None:
        super().__init__(detail)
        self.detail = detail
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class FuelPrice:
    grade: str
    price: float
    currency: str | None
    updated_at: datetime | None


@dataclass(frozen=True, slots=True)
class PlaceStation:
    place_id: str
    name: str | None
    address: str | None
    lat: float
    lon: float
    prices: tuple[FuelPrice, ...] = field(default_factory=tuple)

    def price_for(self, grade: str) -> FuelPrice | None:
        for p in self.prices:
            if p.grade == grade:
                return p
        return None


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _money(m: dict[str, Any] | None) -> tuple[float, str | None] | None:
    """Google `Money` → (amount, currency). `units` arrives as a string
    (int64 in JSON); `nanos` is billionths and shares the sign of units."""
    if not m:
        return None
    try:
        units = int(m.get("units") or 0)
        nanos = int(m.get("nanos") or 0)
    except (TypeError, ValueError):
        return None
    amount = units + nanos / 1e9
    if amount <= 0:
        return None
    return round(amount, 3), m.get("currencyCode")


def _ts(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def parse_places(body: dict[str, Any]) -> list[PlaceStation]:
    out: list[PlaceStation] = []
    for p in body.get("places") or []:
        loc = p.get("location") or {}
        lat, lon = loc.get("latitude"), loc.get("longitude")
        pid = p.get("id")
        if pid is None or lat is None or lon is None:
            continue
        prices: list[FuelPrice] = []
        for fp in (p.get("fuelOptions") or {}).get("fuelPrices") or []:
            money = _money(fp.get("price"))
            grade = fp.get("type")
            if money is None or not grade:
                continue
            prices.append(
                FuelPrice(
                    grade=grade,
                    price=money[0],
                    currency=money[1],
                    updated_at=_ts(fp.get("updateTime")),
                )
            )
        out.append(
            PlaceStation(
                place_id=pid,
                name=(p.get("displayName") or {}).get("text"),
                address=p.get("shortFormattedAddress"),
                lat=float(lat),
                lon=float(lon),
                prices=tuple(prices),
            )
        )
    return out


def cell_center(lat: float, lon: float) -> tuple[float, float]:
    return round(lat, 2), round(lon, 2)


class _TtlCache:
    """Cell-keyed cache of Places results. Capacity is small — one user,
    a handful of places they refuel."""

    def __init__(self, capacity: int = 256, ttl: float = CACHE_TTL) -> None:
        self._capacity = capacity
        self._ttl = ttl
        self._data: OrderedDict[str, tuple[float, datetime, list[PlaceStation]]] = (
            OrderedDict()
        )

    @staticmethod
    def _key(lat: float, lon: float, radius_m: int) -> str:
        clat, clon = cell_center(lat, lon)
        return f"{clat:.2f},{clon:.2f},{radius_m}"

    def get(
        self, lat: float, lon: float, radius_m: int
    ) -> tuple[datetime, list[PlaceStation]] | None:
        k = self._key(lat, lon, radius_m)
        hit = self._data.get(k)
        if hit is None:
            return None
        stored, fetched_at, stations = hit
        if time.monotonic() - stored > self._ttl:
            del self._data[k]
            return None
        self._data.move_to_end(k)
        return fetched_at, stations

    def put(
        self, lat: float, lon: float, radius_m: int, stations: list[PlaceStation]
    ) -> datetime:
        k = self._key(lat, lon, radius_m)
        fetched_at = datetime.now(UTC)
        self._data[k] = (time.monotonic(), fetched_at, stations)
        self._data.move_to_end(k)
        while len(self._data) > self._capacity:
            self._data.popitem(last=False)
        return fetched_at

    def clear(self) -> None:
        self._data.clear()


cache = _TtlCache()


def _month(today: date | None = None) -> date:
    d = today or datetime.now(UTC).date()
    return d.replace(day=1)


async def reserve_call(conn: asyncpg.Connection, cap: int = MONTHLY_CAP) -> int | None:
    """Claim one Places call for this month. Returns the new count, or
    None when the month is already at `cap` (nothing is incremented)."""
    return await conn.fetchval(
        """
        INSERT INTO places_api_usage (month, calls) VALUES ($1, 1)
        ON CONFLICT (month) DO UPDATE
           SET calls = places_api_usage.calls + 1, updated_at = now()
         WHERE places_api_usage.calls < $2
        RETURNING calls
        """,
        _month(),
        cap,
    )


async def month_calls(conn: asyncpg.Connection) -> int:
    v = await conn.fetchval(
        "SELECT calls FROM places_api_usage WHERE month = $1", _month()
    )
    return int(v or 0)


async def search_nearby(
    api_key: str, lat: float, lon: float, radius_m: int
) -> list[PlaceStation]:
    """One Nearby Search call. Raises FuelPriceError on any failure."""
    body = {
        "includedTypes": ["gas_station"],
        "maxResultCount": 20,
        "rankPreference": "DISTANCE",
        "locationRestriction": {
            "circle": {
                "center": {"latitude": lat, "longitude": lon},
                "radius": float(min(radius_m, 50_000)),
            }
        },
    }
    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": api_key,
        "X-Goog-FieldMask": _FIELD_MASK,
    }
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(_SEARCH_URL, json=body, headers=headers)
    except httpx.HTTPError as exc:
        log.warning("places nearby search failed: %s", exc)
        raise FuelPriceError(f"request failed: {type(exc).__name__}") from exc
    if resp.status_code >= 400:
        # Google's error body names the cause ("API key not valid",
        # "Places API (New) has not been used in project …") without
        # echoing the key — safe to surface in Settings.
        try:
            msg = resp.json().get("error", {}).get("message") or resp.text
        except ValueError:
            msg = resp.text
        log.warning("places nearby search HTTP %s: %s", resp.status_code, msg[:300])
        raise FuelPriceError(msg[:300], status_code=resp.status_code)
    try:
        return parse_places(resp.json())
    except ValueError as exc:
        raise FuelPriceError("unreadable response from Google") from exc


async def nearby(
    pool: asyncpg.Pool, api_key: str, lat: float, lon: float, radius_m: int
) -> tuple[str, datetime | None, list[PlaceStation], bool]:
    """Cached nearby lookup.

    Returns ``(status, fetched_at, stations, cached)`` where status is
    ``ok`` or ``quota_reached``. Upstream failures raise FuelPriceError.
    """
    hit = cache.get(lat, lon, radius_m)
    if hit is not None:
        return "ok", hit[0], hit[1], True
    async with pool.acquire() as conn:
        slot = await reserve_call(conn)
    if slot is None:
        return "quota_reached", None, [], False
    clat, clon = cell_center(lat, lon)
    stations = await search_nearby(api_key, clat, clon, radius_m + _CELL_PAD_M)
    fetched_at = cache.put(lat, lon, radius_m, stations)
    log.info(
        "places nearby: %d stations, %d with prices (call %d/%d this month)",
        len(stations),
        sum(1 for s in stations if s.prices),
        slot,
        MONTHLY_CAP,
    )
    return "ok", fetched_at, stations, False
