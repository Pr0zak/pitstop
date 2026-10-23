"""GET /analytics/insights — correlation cards for the Insights screens.

Fetches the rows and hands them to the pure builders in ``pitstop.insights``.
The PID stream is read once, pivoted into 2-second buckets, and shared by
every card that needs it. The result is cached per vehicle for
``CACHE_TTL_S``: it is a slow-moving summary and the pivot costs ~1-2 s.
"""

from __future__ import annotations

import bisect
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
from ..tires import TIRE_CORNERS, TIRE_SLOW_LEAK_PSI_PER_WEEK, placard_kpa

log = logging.getLogger(__name__)

router = APIRouter(prefix="/analytics", tags=["analytics"])

CACHE_TTL_S = 600
# The PID-stream cards look back at most this far; trips and fill-ups use the
# full requested window.
MAX_SAMPLE_DAYS = 180

_cache: dict[tuple[UUID, int, str], tuple[float, dict[str, Any]]] = {}

# Sample field -> pid_readings metric.
_PIVOT: dict[str, str] = {
    "spd": "vehicle_speed",
    "fr": "engine_fuel_rate",
    "rpm": "engine_rpm",
    "maf": "maf_air_flow",
    "map": "manifold_pressure",
    "iat": "intake_air_temp",
    "load": "engine_load",
    "ect": "coolant_temp",
    "ltft1": "ltft_b1",
    "ltft2": "ltft_b2",
    "stft1": "stft_b1",
    "stft2": "stft_b2",
    "cyl": "active_cylinders",
    "frp": "fuel_rail_pressure",
}


async def _samples(conn: asyncpg.Connection, vid: UUID, since: datetime) -> list[ins.Sample]:
    cols = ",\n".join(
        f"avg(value_num) FILTER (WHERE metric = '{m}') AS {f}" for f, m in _PIVOT.items()
    )
    rows = await conn.fetch(
        f"""
        SELECT extract(epoch FROM time_bucket('2 seconds', time))::float8 AS t,
               {cols}
          FROM pid_readings
         WHERE vehicle_id = $1 AND time >= $2 AND metric = ANY($3)
           AND value_num IS NOT NULL
         GROUP BY 1
         ORDER BY 1
        """,
        vid,
        since,
        list(_PIVOT.values()),
    )
    return [ins.Sample(t=r["t"], **{f: r[f] for f in _PIVOT}) for r in rows]


async def _trips(conn: asyncpg.Connection, vid: UUID, since: datetime) -> list[ins.Trip]:
    rows = await conn.fetch(
        """
        SELECT extract(epoch FROM started_at)::float8 AS s,
               extract(epoch FROM ended_at)::float8 AS e,
               duration_s, distance_km, fuel_used_l, avg_speed_kph, idle_s,
               weather_temp_c, weather_wind_kph, weather_precip_mm,
               coalesce(is_towing, false) AS towing
          FROM trips
         WHERE vehicle_id = $1 AND started_at >= $2
           AND ended_at IS NOT NULL AND duration_s > 0
           AND NOT coalesce(gps_only, false)
         ORDER BY started_at
        """,
        vid,
        since,
    )
    return [
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


async def _fills(conn: asyncpg.Connection, vid: UUID, since: datetime) -> list[ins.Fill]:
    rows = await conn.fetch(
        """
        SELECT extract(epoch FROM f.fillup_date)::float8 AS t,
               f.fuel_volume::float8 AS gal, f.price_per_unit::float8 AS price,
               (SELECT m.price_usd_per_gal::float8 FROM fuel_market_weekly m
                 WHERE m.grade = 'regular' AND m.week_of <= f.fillup_date::date
                 ORDER BY m.week_of DESC LIMIT 1) AS market
          FROM fillups f
         WHERE f.vehicle_id = $1 AND f.fillup_date >= $2
           AND f.price_per_unit > 0 AND f.fuel_volume > 0
         ORDER BY f.fillup_date
        """,
        vid,
        since,
    )
    return [
        ins.Fill(t=r["t"], gallons=r["gal"], price=r["price"], market=r["market"]) for r in rows
    ]


async def _metric_rows(
    conn: asyncpg.Connection, vid: UUID, since: datetime, metrics: list[str]
) -> list[asyncpg.Record]:
    return await conn.fetch(
        """
        SELECT extract(epoch FROM time)::float8 AS t, metric, value_num AS v
          FROM pid_readings
         WHERE vehicle_id = $1 AND time >= $2 AND metric = ANY($3) AND value_num IS NOT NULL
         ORDER BY time
        """,
        vid,
        since,
        metrics,
    )


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


async def _speed_ratio(
    conn: asyncpg.Connection, vid: UUID, since: datetime
) -> dict[str, tuple[float, int]]:
    rows = await conn.fetch(
        """
        WITH g AS (
            SELECT time_bucket('2 seconds', time) AS b, avg(speed_mps) * 3.6 AS gk
              FROM gps_points
             WHERE vehicle_id = $1 AND time >= $2 AND speed_mps > 13.4
             GROUP BY 1),
        o AS (
            SELECT time_bucket('2 seconds', time) AS b, avg(value_num) AS ok
              FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'vehicle_speed' AND time >= $2 AND value_num > 48
             GROUP BY 1)
        SELECT to_char(date_trunc('month', g.b), 'YYYY-MM') AS m,
               sum(o.ok) / sum(g.gk) AS r, count(*) AS n
          FROM g JOIN o USING (b)
         WHERE o.ok / g.gk BETWEEN 0.85 AND 1.15
         GROUP BY 1
        """,
        vid,
        since,
    )
    return {r["m"]: (float(r["r"]), int(r["n"])) for r in rows}


def _trip_psi_below(
    trips: list[ins.Trip], tire_rows: list[asyncpg.Record], front: float | None, rear: float | None
) -> dict[int, float]:
    """Trip index -> mean psi under placard across corners during the trip."""
    if front is None and rear is None:
        return {}
    starts = [t.start for t in trips]
    acc: dict[int, list[float]] = defaultdict(list)
    for r in tire_rows:
        if not r["metric"].startswith("tire_pressure_"):
            continue
        k = bisect.bisect_right(starts, r["t"]) - 1
        if k < 0 or r["t"] > trips[k].end:
            continue
        plc = placard_kpa(r["metric"][-2:], front, rear)
        if plc:
            acc[k].append((plc - r["v"]) * ins.PSI_PER_KPA)
    return {k: sum(v) / len(v) for k, v in acc.items() if len(v) >= 8}


async def build(pool: asyncpg.Pool, vid: UUID, days: int, tz_name: str) -> dict[str, Any]:
    try:
        tz = ZoneInfo(tz_name)
    except (ZoneInfoNotFoundError, ValueError):
        tz = UTC  # type: ignore[assignment]
    now = datetime.now(UTC)
    since = now - timedelta(days=days)
    s_since = now - timedelta(days=min(days, MAX_SAMPLE_DAYS))
    async with pool.acquire() as conn:
        veh = await conn.fetchrow(
            "SELECT tire_placard_front_kpa, tire_placard_rear_kpa, latest_odo_km FROM vehicles WHERE id = $1",
            vid,
        )
        if veh is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        trips = await _trips(conn, vid, since)
        samples = await _samples(conn, vid, s_since)
        fills = await _fills(conn, vid, since)
        tire_metrics = [f"tire_pressure_{c}" for c in TIRE_CORNERS] + [
            f"tire_temp_{c}" for c in TIRE_CORNERS
        ]
        tire_rows = await _metric_rows(conn, vid, s_since, tire_metrics)
        misc = await _metric_rows(
            conn,
            vid,
            s_since,
            ["battery_voltage", "atf_temp"] + [f"misfire_count_{i}" for i in range(1, 7)],
        )
        daily = await _daily(
            conn, vid, s_since, ["ltft_b1", "ltft_b2", "catalyst_temp_b1", "catalyst_temp_b2"]
        )
        ratio = await _speed_ratio(conn, vid, since)
        brake = await conn.fetchrow(
            """
            SELECT expense_date, odo FROM expenses
             WHERE vehicle_id = $1 AND NOT coalesce(is_template, false)
               AND (title ILIKE '%brake%' OR notes ILIKE '%brake%')
             ORDER BY expense_date DESC LIMIT 1
            """,
            vid,
        )
        cmv = await conn.fetchval(
            """
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY value_num)
              FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'control_module_voltage'
               AND source IN ('bridge', 'phone_batch') AND time >= $2 AND value_num > 11
            """,
            vid,
            now - timedelta(days=30),
        )

    by_trip = ins.samples_by_trip(trips, samples)
    price = fills[-1].price if fills else None
    front, rear = veh["tire_placard_front_kpa"], veh["tire_placard_rear_kpa"]

    # Tires: corner -> [(t, kPa, °C)], pairing each pressure with the nearest
    # temperature reading of the same corner (they arrive in the same frame).
    tires: dict[str, list[tuple[float, float, float]]] = {}
    last_temp: dict[str, float] = {}
    for r in tire_rows:
        c = r["metric"][-2:]
        if r["metric"].startswith("tire_temp_"):
            last_temp[c] = r["v"]
        elif c in last_temp:
            tires.setdefault(c, []).append((r["t"], r["v"], last_temp[c]))

    rest = [(r["t"], r["v"]) for r in misc if r["metric"] == "battery_voltage" and r["v"] < 13.0]
    atf = [r["v"] for r in misc if r["metric"] == "atf_temp"]
    mis: dict[int, dict[str, float]] = defaultdict(dict)
    for r in misc:
        if r["metric"].startswith("misfire_count_"):
            cyl, d = int(r["metric"][-1]), ins._day(r["t"])
            mis[cyl][d] = max(mis[cyl].get(d, 0.0), r["v"])

    odo_mi = veh["latest_odo_km"] / ins.KM_PER_MI if veh["latest_odo_km"] else None
    last_brake = None
    if brake is not None:
        last_brake = (brake["expense_date"].strftime("%b %-d, %Y"), float(brake["odo"] or 0))

    builders = [
        lambda: ins.cold_weather(trips),
        lambda: ins.short_trips(trips),
        lambda: ins.idle_cost(trips, samples, price),
        lambda: ins.best_speed(samples),
        lambda: ins.driving_style(trips, by_trip),
        lambda: ins.cylinder_mode(trips, by_trip),
        lambda: ins.wind_rain(trips),
        lambda: ins.tire_pressure_mpg(trips, _trip_psi_below(trips, tire_rows, front, rear)),
        lambda: ins.towing(trips),
        lambda: ins.fuel_trim_split(
            [s for s in samples if s.t >= (now - timedelta(days=30)).timestamp()]
        ),
        lambda: ins.bank_balance(daily),
        lambda: ins.airflow(samples),
        lambda: ins.fuel_pressure(samples),
        lambda: ins.battery(rest, samples, [cmv] if cmv else []),
        lambda: ins.warmup(trips, by_trip),
        lambda: ins.misfires(mis),
        lambda: ins.transmission(atf),
        lambda: ins.tire_leak(tires, front, TIRE_SLOW_LEAK_PSI_PER_WEEK),
        lambda: ins.speedometer(ratio),
        lambda: ins.braking(trips, by_trip, last_brake, odo_mi),
        lambda: ins.price_vs_market(fills),
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
    out["window_days"] = days
    return out


@router.get("/insights", dependencies=[Depends(require_query_token)])
async def insights(
    vehicle_id: UUID = Query(...),
    days: int = Query(default=365, ge=30, le=3650),
    tz: str = Query(default="UTC", max_length=64),
    refresh: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Plain-language correlation cards (fuel, engine, tires, cost).

    ``tz`` is the viewer's IANA zone, used only to file fill-ups by weekday.
    Cached for 10 minutes per (vehicle, days, tz); ``refresh=true`` rebuilds.
    """
    key = (vehicle_id, days, tz)
    hit = _cache.get(key)
    if hit and not refresh and time.monotonic() - hit[0] < CACHE_TTL_S:
        return hit[1]
    t0 = time.monotonic()
    out = await build(pool, vehicle_id, days, tz)
    log.info("insights built in %.1fs (%d cards)", time.monotonic() - t0, len(out["insights"]))
    _cache[key] = (time.monotonic(), out)
    return out
