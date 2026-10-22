"""GET /dashboard/summary — one flat, already-converted car summary.

Built for the outside dashboards (Home Assistant REST sensors, the homelab
inventory page): they poll once a minute and want every number on the car's
own dash in one small response, in US units, with the time each was read,
rather than walking ``/vehicles``' full ``latest`` map and re-deriving the
fuel, tire and MPG rules. Those rules stay here, so each client shows what
the app shows:

- fuel: ``fuel_display_l`` from the vehicles API (cluster litres when
  fresh, else the estimate, ADR-025);
- tires: the spread rule (``tires.low_tire_corners``) and the placard rules;
- MPG: the recomputed full-to-full chain (``fillups.compute_recomputed_mpg``).

Every value is ``None`` when it is unknown, never 0.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_query_token
from ..db.deps import get_pool
from ..tires import (
    PSI_PER_KPA,
    TIRE_CORNERS,
    low_tire_corners,
    placard_verdict,
)
from . import maintenance
from .fillups import compute_recomputed_mpg
from .vehicles import _VEHICLE_SELECT, _attach_fuel_display, _row_to_vehicle

router = APIRouter(prefix="/dashboard", tags=["dashboard"])

L_PER_GAL = 3.785411784
KM_PER_MI = 1.609344

# The engine counts as running only while an 'on' event is the newest and
# readings are still arriving: a lost 'off' event must not leave the car
# "running" overnight.
RUNNING_FRESH_S = 300
# Fill-ups averaged for the MPG figure and the range estimate.
MPG_FILLUPS = 5

# metric -> (summary key, converter). Temperatures arrive in °C except the
# cluster's outside temperature, which is °F already.
_TEMPS: dict[str, tuple[str, str]] = {
    "coolant_temp": ("coolant_f", "c"),
    "atf_temp": ("atf_f", "c"),
    "intake_air_temp": ("intake_f", "c"),
    "ambient_air_temp_f": ("outside_f", "f"),
}


def _c_to_f(c: float) -> float:
    return c * 9 / 5 + 32


def _num(latest: dict[str, Any], metric: str) -> tuple[float | None, Any]:
    e = latest.get(metric)
    if not isinstance(e, dict) or e.get("value_num") is None:
        return None, None
    return float(e["value_num"]), e.get("time")


def _money(v: Any) -> float | None:
    return None if v is None else float(v)


def _r(v: float | None, nd: int = 1) -> float | None:
    return None if v is None else round(v, nd)


def build_summary(
    *,
    vehicle: dict[str, Any],
    engine_event: dict[str, Any] | None,
    trip: dict[str, Any] | None,
    fillups: list[dict[str, Any]],
    dtcs: list[dict[str, Any]],
    reminders: dict[str, Any],
    now: datetime,
) -> dict[str, Any]:
    """Shape the response. Pure: every input is already fetched.

    ``fillups`` is newest first and already carries the recomputed ``mpg``.
    """
    latest = vehicle.get("latest") or {}

    # ── engine ─────────────────────────────────────────────────────────
    last_seen = vehicle.get("last_seen_at")
    running: bool | None = None
    if engine_event is not None:
        fresh = last_seen is not None and (now - last_seen).total_seconds() <= RUNNING_FRESH_S
        running = engine_event["state"] == "on" and fresh

    # ── fuel ───────────────────────────────────────────────────────────
    fuel_l = vehicle.get("fuel_display_l")
    tank_l = vehicle.get("tank_capacity_l")
    mpgs = [f["mpg"] for f in fillups if f.get("mpg")][:MPG_FILLUPS]
    avg_mpg = sum(mpgs) / len(mpgs) if mpgs else None
    gal = fuel_l / L_PER_GAL if fuel_l is not None else None
    fuel = {
        "gallons": _r(gal),
        "litres": _r(fuel_l),
        "pct": _r(fuel_l / tank_l * 100, 0) if fuel_l is not None and tank_l else None,
        "tank_gallons": _r(tank_l / L_PER_GAL) if tank_l else None,
        "range_mi": _r(gal * avg_mpg, 0) if gal is not None and avg_mpg else None,
        "source": vehicle.get("fuel_display_source"),
        "at": vehicle.get("fuel_display_at"),
    }

    # ── tires ──────────────────────────────────────────────────────────
    front = vehicle.get("tire_placard_front_kpa")
    rear = vehicle.get("tire_placard_rear_kpa")
    kpa: dict[str, float | None] = {}
    corners: dict[str, Any] = {}
    tire_at = None
    for c in TIRE_CORNERS:
        p, p_at = _num(latest, f"tire_pressure_{c}")
        t, _ = _num(latest, f"tire_temp_{c}")
        kpa[c] = p
        if p_at is not None and (tire_at is None or str(p_at) > str(tire_at)):
            tire_at = p_at
        verdict = placard_verdict(c, p, front, rear)
        corners[c] = {
            "psi": _r(p * PSI_PER_KPA) if p is not None else None,
            "temp_f": _r(_c_to_f(t), 0) if t is not None else None,
            "under_inflated": verdict["under_inflated"],
            "below_spec": verdict["below_spec"],
        }
    low = low_tire_corners(kpa)
    tires = {
        **corners,
        "low": low,
        "warning": bool(low) or any(corners[c]["under_inflated"] for c in TIRE_CORNERS),
        "placard_front_psi": _r(front * PSI_PER_KPA, 0) if front else None,
        "placard_rear_psi": _r(rear * PSI_PER_KPA, 0) if rear else None,
        "at": tire_at,
    }

    # ── temperatures, battery, odometer ───────────────────────────────
    temps: dict[str, Any] = {}
    for metric, (key, unit) in _TEMPS.items():
        v, at = _num(latest, metric)
        temps[key] = _r(v if unit == "f" or v is None else _c_to_f(v), 0)
        temps[key + "_at"] = at
    volts, volts_at = _num(latest, "control_module_voltage")
    odo, odo_at = _num(latest, "odometer_dash")
    if odo is None and vehicle.get("latest_odo_km") is not None:
        odo, odo_at = vehicle["latest_odo_km"] / KM_PER_MI, vehicle.get("latest_odo_at")

    # ── last trip / fill-up / problems ────────────────────────────────
    last_trip = None
    if trip is not None:
        km = trip.get("distance_km")
        fuel_used = trip.get("fuel_used_l")
        mi = km / KM_PER_MI if km is not None else None
        last_trip = {
            "started_at": trip["started_at"],
            "ended_at": trip.get("ended_at"),
            "miles": _r(mi),
            "minutes": _r(trip["duration_s"] / 60, 0) if trip.get("duration_s") else None,
            "mpg": (
                _r(mi / (fuel_used / L_PER_GAL))
                if mi and fuel_used and fuel_used > 0.05 else None
            ),
            "avg_mph": (
                _r(trip["avg_speed_kph"] / KM_PER_MI, 0) if trip.get("avg_speed_kph") else None
            ),
        }
    last_fillup = None
    if fillups:
        f = fillups[0]
        last_fillup = {
            "date": f["fillup_date"],
            "gallons": _r(f.get("fuel_volume"), 2),
            "price_total": _money(f.get("price_total")),
            "price_per_gal": _money(f.get("price_per_unit")),
            "mpg": f.get("mpg"),
            "days_ago": (now - f["fillup_date"]).days,
        }
    overdue = reminders.get("overdue") or []
    upcoming = reminders.get("upcoming") or []
    nxt = (overdue or upcoming or [None])[0]

    return {
        "vehicle": {"id": vehicle["id"], "slug": vehicle["slug"], "name": vehicle["name"]},
        "generated_at": now,
        "last_seen_at": last_seen,
        "running": running,
        "fuel": fuel,
        "mpg_avg": _r(avg_mpg),
        "mpg_fillups": len(mpgs),
        "tires": tires,
        "temps": temps,
        "battery_v": _r(volts, 2),
        "battery_at": volts_at,
        "odometer_mi": _r(odo, 0),
        "odometer_at": odo_at,
        "last_trip": last_trip,
        "last_fillup": last_fillup,
        "dtcs": [{"code": d["code"], "description": d["description"]} for d in dtcs],
        "service": {
            "overdue": len(overdue),
            "upcoming": len(upcoming),
            "next": nxt and {
                "title": nxt.get("title"),
                "overdue": bool(overdue),
                "miles": nxt.get("miles_over") or nxt.get("miles_until"),
                "days": nxt.get("days_over") or nxt.get("days_until"),
            },
        },
    }


async def _resolve_vehicle(conn: asyncpg.Connection, vehicle: str | None) -> dict[str, Any]:
    if vehicle is None:
        row = await conn.fetchrow(
            _VEHICLE_SELECT + " WHERE v.active ORDER BY s.last_seen_at DESC NULLS LAST LIMIT 1"
        )
    else:
        try:
            vid: UUID | None = UUID(vehicle)
        except ValueError:
            vid = None
        row = await conn.fetchrow(
            _VEHICLE_SELECT + (" WHERE v.id = $1" if vid else " WHERE v.slug = $1"),
            vid or vehicle,
        )
    if row is None:
        raise HTTPException(status_code=404, detail="vehicle not found")
    out = _row_to_vehicle(row)
    await _attach_fuel_display(conn, [out])
    return out


@router.get("/summary", dependencies=[Depends(require_query_token)])
async def summary(
    vehicle: str | None = Query(
        default=None, description="Slug or id; default = the active vehicle seen last"
    ),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    now = datetime.now(UTC)
    async with pool.acquire() as conn:
        v = await _resolve_vehicle(conn, vehicle)
        vid = v["id"]
        ev = await conn.fetchrow(
            "SELECT time, state FROM engine_events WHERE vehicle_id = $1 "
            "AND time > $2 ORDER BY time DESC LIMIT 1",
            vid, now - timedelta(days=30),
        )
        trip = await conn.fetchrow(
            "SELECT started_at, ended_at, duration_s, distance_km, fuel_used_l, "
            "avg_speed_kph FROM trips WHERE vehicle_id = $1 "
            "ORDER BY started_at DESC LIMIT 1",
            vid,
        )
        chain = await conn.fetch(
            "SELECT id, fillup_date, odo, fuel_volume, is_full, is_missed, "
            "price_total, price_per_unit FROM fillups WHERE vehicle_id = $1 "
            "ORDER BY fillup_date ASC",
            vid,
        )
        dtcs = await conn.fetch(
            "SELECT code, description FROM dtc_events WHERE vehicle_id = $1 "
            "AND cleared_at IS NULL ORDER BY seen_at DESC LIMIT 20",
            vid,
        )
    rows = [dict(r) for r in chain]
    mpg = compute_recomputed_mpg(rows)
    for r in rows:
        r["mpg"] = mpg.get(r["id"])
    rem = await maintenance.reminders(vehicle_id=vid, pool=pool)
    return build_summary(
        vehicle=v,
        engine_event=dict(ev) if ev else None,
        trip=dict(trip) if trip else None,
        fillups=rows[::-1],
        dtcs=[dict(d) for d in dtcs],
        reminders=rem,
        now=now,
    )
