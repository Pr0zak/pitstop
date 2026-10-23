"""GET /analytics/insights — correlation cards for the Insights screens.

Reads only small tables: trips, fill-ups, the pid_daily aggregate and the
per-trip / per-day feature documents that workers/insight_features.py
computes once (migration 0026). No raw readings are scanned per request, so
the cost stays flat as history grows. Each card picks its own window
(insights.WINDOWS). The result is cached briefly per vehicle.
"""

from __future__ import annotations

import json
import logging
import time
from collections import defaultdict
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query

from .. import insights as ins
from ..auth import require_query_token
from ..db.deps import get_pool
from ..tires import TIRE_SLOW_LEAK_PSI_PER_WEEK, placard_kpa

log = logging.getLogger(__name__)

router = APIRouter(prefix="/analytics", tags=["analytics"])

CACHE_TTL_S = 120

_cache: dict[tuple[UUID, str], tuple[float, dict[str, Any]]] = {}


async def _trips(
    conn: asyncpg.Connection, vid: UUID
) -> tuple[list[ins.Trip], list[dict[str, Any] | None]]:
    """Every trip, oldest first, with its stored feature document (None
    until the worker has summarised it)."""
    rows = await conn.fetch(
        """
        SELECT extract(epoch FROM t.started_at)::float8 AS s,
               extract(epoch FROM t.ended_at)::float8 AS e,
               t.duration_s, t.distance_km, t.fuel_used_l, t.avg_speed_kph, t.idle_s,
               t.weather_temp_c, t.weather_wind_kph, t.weather_precip_mm,
               coalesce(t.is_towing, false) AS towing, f.features
          FROM trips t
          LEFT JOIN insight_trip_features f ON f.trip_id = t.id
         WHERE t.vehicle_id = $1
           AND t.ended_at IS NOT NULL AND t.duration_s > 0
           AND NOT coalesce(t.gps_only, false)
         ORDER BY t.started_at
        """,
        vid,
    )
    trips = [
        ins.Trip(
            start=r["s"],
            end=r["e"],
            duration_s=float(r["duration_s"]),
            distance_km=r["distance_km"],
            fuel_l=r["fuel_used_l"],
            avg_kph=r["avg_speed_kph"],
            idle_s=r["idle_s"],
            temp_c=r["weather_temp_c"],
            wind_kph=r["weather_wind_kph"],
            precip_mm=r["weather_precip_mm"],
            towing=r["towing"],
        )
        for r in rows
    ]
    feats = [json.loads(r["features"]) if r["features"] else None for r in rows]
    return trips, feats


async def _fills(conn: asyncpg.Connection, vid: UUID) -> list[ins.Fill]:
    rows = await conn.fetch(
        """
        SELECT extract(epoch FROM f.fillup_date)::float8 AS t,
               f.fuel_volume::float8 AS gal, f.price_per_unit::float8 AS price,
               (SELECT m.price_usd_per_gal::float8 FROM fuel_market_weekly m
                 WHERE m.grade = 'regular' AND m.week_of <= f.fillup_date::date
                 ORDER BY m.week_of DESC LIMIT 1) AS market
          FROM fillups f
         WHERE f.vehicle_id = $1
           AND f.price_per_unit > 0 AND f.fuel_volume > 0
         ORDER BY f.fillup_date
        """,
        vid,
    )
    return [
        ins.Fill(t=r["t"], gallons=r["gal"], price=r["price"], market=r["market"]) for r in rows
    ]


async def _daily(
    conn: asyncpg.Connection, vid: UUID, since: datetime, metrics: list[str]
) -> dict[str, dict[str, float]]:
    try:
        rows = await conn.fetch(
            """
            SELECT to_char(bucket, 'YYYY-MM-DD') AS d, metric, avg_value AS v
              FROM pid_daily
             WHERE vehicle_id = $1 AND bucket >= $2 AND metric = ANY($3)
            """,
            vid,
            since,
            metrics,
        )
    except asyncpg.UndefinedTableError:
        rows = await conn.fetch(
            """
            SELECT to_char(date_trunc('day', time), 'YYYY-MM-DD') AS d, metric, avg(value_num) AS v
              FROM pid_readings
             WHERE vehicle_id = $1 AND time >= $2 AND metric = ANY($3)
             GROUP BY 1, 2
            """,
            vid,
            since,
            metrics,
        )
    out: dict[str, dict[str, float]] = defaultdict(dict)
    for r in rows:
        if r["v"] is not None:
            out[r["metric"]][r["d"]] = float(r["v"])
    return out


async def _days(conn: asyncpg.Connection, vid: UUID) -> list[tuple[str, dict[str, Any]]]:
    rows = await conn.fetch(
        "SELECT to_char(day, 'YYYY-MM-DD') AS d, features FROM insight_day_features "
        "WHERE vehicle_id = $1 ORDER BY day",
        vid,
    )
    return [(r["d"], json.loads(r["features"])) for r in rows]


def _day_median(days: list[tuple[str, dict[str, Any]]], key: str, min_n: int) -> dict[str, float]:
    """day -> stored median of ``key`` where it rests on >= min_n readings."""
    return {d: f[key][0] for d, f in days if f.get(key) and f[key][1] >= min_n}


def _since_day(now: datetime, days: int) -> str:
    return (now - timedelta(days=days)).strftime("%Y-%m-%d")


async def build(pool: asyncpg.Pool, vid: UUID, tz_name: str) -> dict[str, Any]:
    try:
        tz: Any = ZoneInfo(tz_name)
    except (ZoneInfoNotFoundError, ValueError):
        tz = UTC
    now = datetime.now(UTC)
    t12m = (now - timedelta(days=365)).timestamp()
    t30d = (now - timedelta(days=30)).timestamp()
    async with pool.acquire() as conn:
        veh = await conn.fetchrow(
            "SELECT tire_placard_front_kpa, tire_placard_rear_kpa, latest_odo_km "
            "FROM vehicles WHERE id = $1",
            vid,
        )
        if veh is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        trips, feats = await _trips(conn, vid)
        fills = await _fills(conn, vid)
        days = await _days(conn, vid)
        pid_daily = await _daily(
            conn,
            vid,
            datetime(2000, 1, 1, tzinfo=UTC),
            ["ltft_b1", "ltft_b2", "catalyst_temp_b1", "catalyst_temp_b2"],
        )
        brake = await conn.fetchrow(
            """
            SELECT expense_date, odo FROM expenses
             WHERE vehicle_id = $1 AND NOT coalesce(is_template, false)
               AND (title ILIKE '%brake%' OR notes ILIKE '%brake%')
             ORDER BY expense_date DESC LIMIT 1
            """,
            vid,
        )

    price = fills[-1].price if fills else None
    front, rear = veh["tire_placard_front_kpa"], veh["tire_placard_rear_kpa"]
    d30 = _since_day(now, 30)
    recent_days = [f for d, f in days if d >= d30]

    # Speed bands and cylinder share: exact sums over every trip.
    bins: dict[int, list[float]] = defaultdict(lambda: [0.0, 0.0, 0])
    for f in feats:
        for k, (mph, gs, n) in ((f or {}).get("speed_bins") or {}).items():
            b = bins[int(k)]
            b[0] += mph
            b[1] += gs
            b[2] += n

    # Tire pressure vs MPG: each trip's mean pressure under the placard.
    psi_below: dict[int, float] = {}
    if front is not None or rear is not None:
        for i, f in enumerate(feats):
            below = []
            for c, (kpa, n) in ((f or {}).get("tire_kpa") or {}).items():
                plc = placard_kpa(c, front, rear)
                if plc and n >= 2:
                    below.append((plc - kpa) * ins.PSI_PER_KPA)
            if below:
                psi_below[i] = sum(below) / len(below)

    idle = ins._combine(f.get("idle_gs") for d, f in days if d >= _since_day(now, 90))
    run_v = ins._combine(f.get("run_v") for f in recent_days)

    hourly: dict[str, list[list[float]]] = defaultdict(list)
    for f in recent_days:
        for c, pts in (f.get("tires") or {}).items():
            hourly[c].extend(pts)

    misfire: dict[int, dict[str, float]] = defaultdict(dict)
    for d, f in days:
        if d >= d30:
            for c, v in (f.get("misfire") or {}).items():
                misfire[int(c)][d] = v

    atf_bins = [0] * len(ins._ATF_BINS)
    atf_peak: float | None = None
    for _, f in days:
        a = f.get("atf")
        if a:
            atf_bins = [x + y for x, y in zip(atf_bins, a["bins"], strict=True)]
            atf_peak = a["max"] if atf_peak is None else max(atf_peak, a["max"])

    gps_month: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0, 0])
    for d, f in days:
        g = f.get("gps")
        if g:
            m = gps_month[d[:7]]
            m[0] += g[0]
            m[1] += g[1]
            m[2] += g[2]
    ratio = {m: (v[0] / v[1], int(v[2])) for m, v in gps_month.items() if v[1] > 0}

    # Bank balance judges the last 14 days of pid_daily but charts it all.
    odo_mi = veh["latest_odo_km"] / ins.KM_PER_MI if veh["latest_odo_km"] else None
    last_brake = None
    if brake is not None:
        last_brake = (brake["expense_date"].strftime("%b %-d, %Y"), float(brake["odo"] or 0))

    trips_12m = [t for t in trips if t.start >= t12m]
    builders = [
        lambda: ins.cold_weather(trips),
        lambda: ins.short_trips(trips),
        lambda: ins.idle_cost(trips_12m, idle[0] if idle else None, price),
        lambda: ins.best_speed(bins),
        lambda: ins.driving_style(trips, feats),
        lambda: ins.cylinder_mode(trips, feats),
        lambda: ins.wind_rain(trips),
        lambda: ins.tire_pressure_mpg(trips, psi_below),
        lambda: ins.towing(trips),
        lambda: ins.fuel_trim_split(recent_days),
        lambda: ins.bank_balance(pid_daily),
        lambda: ins.airflow(_day_median(days, "ve", 10)),
        lambda: ins.fuel_pressure(_day_median(days, "frp_psi", 5)),
        lambda: ins.battery(_day_median(days, "rest_v", 1), run_v[0] if run_v else None),
        lambda: ins.warmup(trips, feats, t30d),
        lambda: ins.misfires(misfire),
        lambda: ins.transmission(atf_peak, atf_bins),
        lambda: ins.tire_leak(hourly, front, TIRE_SLOW_LEAK_PSI_PER_WEEK),
        lambda: ins.speedometer(ratio),
        lambda: ins.braking(trips, feats, last_brake, odo_mi),
        lambda: ins.price_vs_market([f for f in fills if f.t >= t12m]),
        lambda: ins.fill_day(fills, tz),
        lambda: ins.cost_per_mile(trips, price),
    ]
    cards = []
    for b in builders:
        try:
            cards.append(b())
        except Exception:  # one broken card must not take the page down
            log.exception("insight builder failed")
    out = ins.order_cards(cards)
    out["generated_at"] = now.isoformat()
    pending = sum(1 for f in feats if f is None)
    # Trips the worker hasn't summarised yet (first run after an upgrade, or
    # a drive that just ended). Clients may show "still analysing N trips".
    out["pending_trips"] = pending
    return out


@router.get("/insights", dependencies=[Depends(require_query_token)])
async def insights(
    vehicle_id: UUID = Query(...),
    tz: str = Query(default="UTC", max_length=64),
    refresh: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Plain-language correlation cards (fuel, engine, tires, cost).

    Each card chooses its own window and says so in ``window``. ``tz`` is the
    viewer's IANA zone, used only to file fill-ups by weekday.
    """
    key = (vehicle_id, tz)
    hit = _cache.get(key)
    if hit and not refresh and time.monotonic() - hit[0] < CACHE_TTL_S:
        return hit[1]
    t0 = time.monotonic()
    out = await build(pool, vehicle_id, tz)
    log.info("insights built in %.2fs (%d cards)", time.monotonic() - t0, len(out["insights"]))
    _cache[key] = (time.monotonic(), out)
    return out
