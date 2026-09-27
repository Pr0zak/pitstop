"""Live nearby fuel prices — Google Places API (New) via services.fuel_prices.

``GET /fuel-prices/nearby`` answers "what does gas cost around here right
now?" and joins each station to the user's own fillup history ("you
paid $3.19 there on Sep 12").

Every outcome is a 200 with a ``status`` the clients render directly,
so the phone and web cards never have to decode an HTTP error:

- ``ok``              stations (possibly empty) in ``stations``
- ``no_key``          no Places API key in Settings
- ``no_location``     no lat/lon given and no GPS fix / home to fall back on
- ``quota_reached``   this month's call cap is spent (cached cells still work)
- ``upstream_error``  Google refused or failed; ``detail`` says why

Where the search is centred, in order: the caller's ``lat``/``lon``
(the phone's own fix), else the vehicle's latest ``gps_points`` row
(where the car is parked — the web UI is plain http on the LAN, so the
browser won't hand it a location), else Settings → home.
"""

from __future__ import annotations

import logging
import math
from datetime import datetime
from typing import Any, Literal
from urllib.parse import quote
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_query_token
from ..db.deps import get_pool
from ..services import fuel_prices as fp

log = logging.getLogger(__name__)

router = APIRouter(prefix="/fuel-prices", tags=["fuel-prices"])

Grade = Literal["REGULAR_UNLEADED", "MIDGRADE", "PREMIUM", "DIESEL"]

# A fillup within this distance of a Places station is "that station".
# Fuelio's own fillup coordinates are the phone's GPS at the pump, so
# they land on the forecourt, not the street address.
_HISTORY_MATCH_M = 150.0


def _iso(dt: datetime | None) -> str | None:
    return dt.isoformat() if dt is not None else None


def _maps_url(s: fp.PlaceStation) -> str:
    return (
        "https://www.google.com/maps/search/?api=1"
        f"&query={s.lat:.6f},{s.lon:.6f}&query_place_id={quote(s.place_id)}"
    )


async def _resolve_origin(
    conn: asyncpg.Connection,
    vehicle_id: UUID | None,
    lat: float | None,
    lon: float | None,
) -> dict[str, Any] | None:
    if lat is not None and lon is not None:
        return {"lat": lat, "lon": lon, "source": "device", "as_of": None}
    if vehicle_id is not None:
        row = await conn.fetchrow(
            "SELECT lat, lon, time FROM gps_points WHERE vehicle_id = $1 "
            "ORDER BY time DESC LIMIT 1",
            vehicle_id,
        )
        if row is not None:
            return {
                "lat": float(row["lat"]),
                "lon": float(row["lon"]),
                "source": "vehicle",
                "as_of": row["time"].isoformat(),
            }
    home = await conn.fetchrow("SELECT home_lat, home_lon FROM settings WHERE id = 1")
    if home is not None and home["home_lat"] is not None and home["home_lon"] is not None:
        return {
            "lat": float(home["home_lat"]),
            "lon": float(home["home_lon"]),
            "source": "home",
            "as_of": None,
        }
    return None


async def _history_near(
    conn: asyncpg.Connection,
    vehicle_id: UUID | None,
    lat: float,
    lon: float,
    radius_m: int,
) -> list[asyncpg.Record]:
    """Priced fillups inside a bounding box around the search area,
    newest first. The box is a cheap pre-filter; exact matching is done
    per station in Python."""
    pad_m = radius_m + 2_000
    dlat = pad_m / 111_000.0
    # Longitude degrees shrink with latitude; clamp to avoid div-by-~0
    # near the poles.
    dlon = pad_m / (111_000.0 * max(0.1, math.cos(math.radians(lat))))
    where = [
        "price_per_unit IS NOT NULL",
        "lat BETWEEN $1 AND $2",
        "lon BETWEEN $3 AND $4",
    ]
    args: list[Any] = [lat - dlat, lat + dlat, lon - dlon, lon + dlon]
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")
    return await conn.fetch(
        f"""
        SELECT lat, lon, price_per_unit, fillup_date
          FROM fillups
         WHERE {' AND '.join(where)}
         ORDER BY fillup_date DESC
        """,
        *args,
    )


def build_station_rows(
    stations: list[fp.PlaceStation],
    origin_lat: float,
    origin_lon: float,
    radius_m: int,
    grade: str,
    history: list[Any],
) -> list[dict[str, Any]]:
    """Shape, filter and sort stations for the clients.

    Priced stations come first, cheapest first (distance breaks ties);
    stations with no price for `grade` follow, nearest first.
    """
    in_range = [
        (st, fp.haversine_m(origin_lat, origin_lon, st.lat, st.lon))
        for st in stations
    ]
    in_range = [(st, d) for st, d in in_range if d <= radius_m]

    # Each fillup belongs to its single nearest station, and only if
    # that one is within _HISTORY_MATCH_M — two forecourts facing each
    # other across an intersection must not both claim it.
    mine_by_id: dict[str, list[Any]] = {st.place_id: [] for st, _ in in_range}
    for h in history:  # newest first
        hlat, hlon = float(h["lat"]), float(h["lon"])
        best: tuple[float, str] | None = None
        for st, _ in in_range:
            d = fp.haversine_m(st.lat, st.lon, hlat, hlon)
            if d <= _HISTORY_MATCH_M and (best is None or d < best[0]):
                best = (d, st.place_id)
        if best is not None:
            mine_by_id[best[1]].append(h)

    rows: list[dict[str, Any]] = []
    for s, dist in in_range:
        mine = mine_by_id[s.place_id]
        # `history` is newest-first, so mine[0] is the last visit.
        last = mine[0] if mine else None
        price = s.price_for(grade)
        rows.append(
            {
                "place_id": s.place_id,
                "name": s.name,
                "address": s.address,
                "lat": s.lat,
                "lon": s.lon,
                "distance_m": round(dist),
                "price": price.price if price else None,
                "currency": price.currency if price else None,
                "price_updated_at": _iso(price.updated_at) if price else None,
                "other_prices": [
                    {
                        "grade": p.grade,
                        "price": p.price,
                        "currency": p.currency,
                        "updated_at": _iso(p.updated_at),
                    }
                    for p in s.prices
                    if p.grade != grade
                ],
                "my_last_price": float(last["price_per_unit"]) if last else None,
                "my_last_date": last["fillup_date"].isoformat() if last else None,
                "my_fillup_count": len(mine),
                "maps_url": _maps_url(s),
            }
        )
    rows.sort(
        key=lambda r: (
            r["price"] is None,
            r["price"] if r["price"] is not None else 0.0,
            r["distance_m"],
        )
    )
    return rows


@router.get("/nearby", dependencies=[Depends(require_query_token)])
async def nearby(
    vehicle_id: UUID | None = Query(default=None),
    lat: float | None = Query(default=None, ge=-90, le=90),
    lon: float | None = Query(default=None, ge=-180, le=180),
    radius_m: int = Query(default=5_000, ge=500, le=fp.MAX_RADIUS_M),
    grade: Grade = Query(default="REGULAR_UNLEADED"),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    if (lat is None) != (lon is None):
        raise HTTPException(status_code=422, detail="lat and lon go together")

    async with pool.acquire() as conn:
        key = await conn.fetchval("SELECT places_api_key FROM settings WHERE id = 1")
        origin = await _resolve_origin(conn, vehicle_id, lat, lon)
        calls = await fp.month_calls(conn)

    result: dict[str, Any] = {
        "status": "ok",
        "detail": None,
        "grade": grade,
        "radius_m": radius_m,
        "origin": origin,
        "fetched_at": None,
        "cached": False,
        "usage": {"month_calls": calls, "monthly_cap": fp.MONTHLY_CAP},
        "stations": [],
    }
    if not key:
        result["status"] = "no_key"
        return result
    if origin is None:
        result["status"] = "no_location"
        return result

    try:
        status, fetched_at, stations, cached = await fp.nearby(
            pool, key, origin["lat"], origin["lon"], radius_m
        )
    except fp.FuelPriceError as exc:
        result["status"] = "upstream_error"
        result["detail"] = exc.detail
        return result

    async with pool.acquire() as conn:
        history = await _history_near(
            conn, vehicle_id, origin["lat"], origin["lon"], radius_m
        )
        result["usage"]["month_calls"] = await fp.month_calls(conn)

    result["status"] = status
    result["fetched_at"] = _iso(fetched_at)
    result["cached"] = cached
    result["stations"] = build_station_rows(
        stations, origin["lat"], origin["lon"], radius_m, grade, history
    )
    return result
