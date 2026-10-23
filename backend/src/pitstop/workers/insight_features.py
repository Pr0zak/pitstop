"""Fill insight_trip_features / insight_day_features (migration 0026).

Each cycle summarises, newest first:

* trips with no row, an old FEATURE_VERSION, or a different ended_at than
  when they were summarised (the deriver extends an in-progress trip in
  place);
* UTC days that have readings (per the pid_daily aggregate) but no row or an
  old version, plus the last three days again (pid_daily lags, and late
  phone uploads land there).

A trip or day costs one small query over its own readings, so a cycle is
bounded by TRIPS_PER_CYCLE / DAYS_PER_CYCLE; a first run on years of history
catches up over a few cycles. /analytics/insights never reads raw readings.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections import defaultdict
from datetime import UTC, date, datetime, timedelta
from typing import Any
from uuid import UUID

import asyncpg

from ..insight_features import FEATURE_VERSION, day_features, trip_features
from ..insights import Sample
from ..tires import TIRE_CORNERS

log = logging.getLogger(__name__)

CYCLE_INTERVAL_S = 300
STARTUP_DELAY_S = 30
TRIPS_PER_CYCLE = 300
DAYS_PER_CYCLE = 90

# Sample field -> pid_readings metric (the 2-second pivot).
PIVOT: dict[str, str] = {
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
_TIRE_P = [f"tire_pressure_{c}" for c in TIRE_CORNERS]
_TIRE_T = [f"tire_temp_{c}" for c in TIRE_CORNERS]
_MISFIRE = [f"misfire_count_{i}" for i in range(1, 7)]


async def _pivot(conn: asyncpg.Connection, vid: UUID, t0: datetime, t1: datetime) -> list[Sample]:
    cols = ",\n".join(
        f"avg(value_num) FILTER (WHERE metric = '{m}') AS {f}" for f, m in PIVOT.items()
    )
    rows = await conn.fetch(
        f"""
        SELECT extract(epoch FROM time_bucket('2 seconds', time))::float8 AS t, {cols}
          FROM pid_readings
         WHERE vehicle_id = $1 AND time >= $2 AND time < $3
           AND metric = ANY($4) AND value_num IS NOT NULL
         GROUP BY 1 ORDER BY 1
        """,
        vid,
        t0,
        t1,
        list(PIVOT.values()),
    )
    return [Sample(t=r["t"], **{f: r[f] for f in PIVOT}) for r in rows]


async def _raw(
    conn: asyncpg.Connection,
    vid: UUID,
    t0: datetime,
    t1: datetime,
    metrics: list[str],
    sources: list[str] | None = None,
) -> list[asyncpg.Record]:
    return await conn.fetch(
        """
        SELECT extract(epoch FROM time)::float8 AS t, metric, value_num AS v
          FROM pid_readings
         WHERE vehicle_id = $1 AND time >= $2 AND time < $3 AND metric = ANY($4)
           AND value_num IS NOT NULL AND ($5::text[] IS NULL OR source = ANY($5))
         ORDER BY time
        """,
        vid,
        t0,
        t1,
        metrics,
        sources,
    )


async def compute_trip(
    conn: asyncpg.Connection, vid: UUID, start: datetime, end: datetime
) -> dict[str, Any]:
    samples = await _pivot(conn, vid, start, end)
    tires: dict[str, list[float]] = defaultdict(list)
    for r in await _raw(conn, vid, start, end, _TIRE_P):
        tires[r["metric"][-2:]].append(r["v"])
    return trip_features(samples, tires)


async def compute_day(conn: asyncpg.Connection, vid: UUID, day: date) -> dict[str, Any]:
    t0 = datetime(day.year, day.month, day.day, tzinfo=UTC)
    t1 = t0 + timedelta(days=1)
    samples = await _pivot(conn, vid, t0, t1)
    raw = await _raw(
        conn, vid, t0, t1, ["battery_voltage", "atf_temp", *_MISFIRE, *_TIRE_P, *_TIRE_T]
    )
    # Running voltage from the phone only: the WiCAN replays a frozen frame
    # after key-off (ADR-025), which would read as "running" at 14 V.
    run = await _raw(conn, vid, t0, t1, ["control_module_voltage"], ["bridge", "phone_batch"])
    misfire: dict[int, float] = {}
    tires: dict[str, list[tuple[float, float, float]]] = defaultdict(list)
    last_temp: dict[str, float] = {}
    rest, atf = [], []
    for r in raw:
        m = r["metric"]
        if m == "battery_voltage":
            if r["v"] < 13.0:
                rest.append(r["v"])
        elif m == "atf_temp":
            atf.append(r["v"])
        elif m.startswith("misfire_count_"):
            c = int(m[-1])
            misfire[c] = max(misfire.get(c, 0.0), r["v"])
        elif m.startswith("tire_temp_"):
            last_temp[m[-2:]] = r["v"]
        elif m.startswith("tire_pressure_") and m[-2:] in last_temp:
            tires[m[-2:]].append((r["t"], r["v"], last_temp[m[-2:]]))
    gps = await conn.fetchrow(
        """
        WITH g AS (
            SELECT time_bucket('2 seconds', time) AS b, avg(speed_mps) * 3.6 AS gk
              FROM gps_points
             WHERE vehicle_id = $1 AND time >= $2 AND time < $3 AND speed_mps > 13.4
             GROUP BY 1),
        o AS (
            SELECT time_bucket('2 seconds', time) AS b, avg(value_num) AS ok
              FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'vehicle_speed' AND time >= $2 AND time < $3
               AND value_num > 48
             GROUP BY 1)
        SELECT sum(o.ok) AS so, sum(g.gk) AS sg, count(*) AS n
          FROM g JOIN o USING (b)
         WHERE o.ok / g.gk BETWEEN 0.85 AND 1.15
        """,
        vid,
        t0,
        t1,
    )
    pairs = (float(gps["so"]), float(gps["sg"]), int(gps["n"])) if gps and gps["n"] else None
    feats = day_features(
        samples,
        rest_v=rest,
        run_v=[r["v"] for r in run],
        misfire_max=misfire,
        atf_c=atf,
        tires=tires,
        gps_pairs=pairs,
    )
    # The pid_daily reading count this summary saw; _days_due redoes the day
    # when the aggregate later counts a different number (late upload).
    # Taken from pid_daily itself so the two always agree when nothing new
    # arrived. NULL while the aggregate hasn't materialised the day yet.
    try:
        feats["src_n"] = await conn.fetchval(
            "SELECT sum(n)::bigint FROM pid_daily WHERE vehicle_id = $1 "
            "AND (bucket AT TIME ZONE 'UTC')::date = $2",
            vid,
            day,
        )
    except asyncpg.UndefinedTableError:
        feats["src_n"] = None
    return feats


async def _trips_due(conn: asyncpg.Connection) -> list[asyncpg.Record]:
    return await conn.fetch(
        """
        SELECT t.id, t.vehicle_id, t.started_at, t.ended_at
          FROM trips t
          LEFT JOIN insight_trip_features f ON f.trip_id = t.id
         WHERE t.ended_at IS NOT NULL AND NOT coalesce(t.gps_only, false)
           AND (f.trip_id IS NULL OR f.version < $1 OR f.trip_ended_at IS DISTINCT FROM t.ended_at)
         ORDER BY t.started_at DESC
         LIMIT $2
        """,
        FEATURE_VERSION,
        TRIPS_PER_CYCLE,
    )


async def _days_due(conn: asyncpg.Connection) -> list[asyncpg.Record]:
    try:
        return await conn.fetch(
            """
            -- pid_daily lags real time (it materialises with an end offset),
            -- so the last three UTC days are always candidates too.
            -- pid_daily lags real time (it materialises with an end offset),
            -- so the last three UTC days are always candidates too. A day is
            -- redone when pid_daily counts a different number of readings
            -- than the summary was built from (a late phone upload).
            WITH d AS (
                SELECT vehicle_id, (bucket AT TIME ZONE 'UTC')::date AS day, sum(n) AS n
                  FROM pid_daily
                 GROUP BY 1, 2
                UNION ALL
                SELECT v.id, g.day::date, NULL
                  FROM vehicles v
                 CROSS JOIN generate_series((now() AT TIME ZONE 'UTC')::date - 2,
                                            (now() AT TIME ZONE 'UTC')::date,
                                            interval '1 day') AS g(day))
            SELECT DISTINCT d.vehicle_id, d.day
              FROM d
              LEFT JOIN insight_day_features f ON f.vehicle_id = d.vehicle_id AND f.day = d.day
             WHERE f.day IS NULL OR f.version < $1
                OR (d.n IS NOT NULL AND (f.features->>'src_n')::bigint IS DISTINCT FROM d.n)
                OR (d.day >= (now() AT TIME ZONE 'UTC')::date - 2
                    AND f.computed_at < now() - interval '10 minutes')
             ORDER BY d.day DESC
             LIMIT $2
            """,
            FEATURE_VERSION,
            DAYS_PER_CYCLE,
        )
    except asyncpg.UndefinedTableError:  # no continuous aggregate (test DB)
        return []


async def run_cycle(pool: asyncpg.Pool) -> tuple[int, int]:
    async with pool.acquire() as conn:
        trips = await _trips_due(conn)
        days = await _days_due(conn)
    for t in trips:
        async with pool.acquire() as conn:
            feats = await compute_trip(conn, t["vehicle_id"], t["started_at"], t["ended_at"])
            await conn.execute(
                """
                INSERT INTO insight_trip_features
                    (trip_id, vehicle_id, started_at, trip_ended_at, version, features, computed_at)
                VALUES ($1, $2, $3, $4, $5, $6::jsonb, now())
                ON CONFLICT (trip_id) DO UPDATE SET
                    started_at = EXCLUDED.started_at, trip_ended_at = EXCLUDED.trip_ended_at,
                    version = EXCLUDED.version, features = EXCLUDED.features, computed_at = now()
                """,
                t["id"],
                t["vehicle_id"],
                t["started_at"],
                t["ended_at"],
                FEATURE_VERSION,
                json.dumps(feats),
            )
    for d in days:
        async with pool.acquire() as conn:
            feats = await compute_day(conn, d["vehicle_id"], d["day"])
            await conn.execute(
                """
                INSERT INTO insight_day_features (vehicle_id, day, version, features, computed_at)
                VALUES ($1, $2, $3, $4::jsonb, now())
                ON CONFLICT (vehicle_id, day) DO UPDATE SET
                    version = EXCLUDED.version, features = EXCLUDED.features, computed_at = now()
                """,
                d["vehicle_id"],
                d["day"],
                FEATURE_VERSION,
                json.dumps(feats),
            )
    return len(trips), len(days)


async def run(pool: asyncpg.Pool) -> None:
    log.info("insight features worker started (cycle every %s s)", CYCLE_INTERVAL_S)
    await asyncio.sleep(STARTUP_DELAY_S)
    while True:
        try:
            n_trips, n_days = await run_cycle(pool)
            if n_trips or n_days:
                log.info("insight features: %s trips, %s days summarised", n_trips, n_days)
                # More history waiting: go again soon instead of in 5 min.
                if n_trips >= TRIPS_PER_CYCLE or n_days >= DAYS_PER_CYCLE:
                    await asyncio.sleep(5)
                    continue
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("insight features cycle failed")
        await asyncio.sleep(CYCLE_INTERVAL_S)
