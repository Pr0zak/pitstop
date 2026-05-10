"""Backfill weather columns on historical fillups + trips.

Hourly cycle, paged 100 rows at a time, throttled ~1 req/sec to stay
well under Open-Meteo's 600/min rate limit. Walks oldest-first by
`weather_temp_c IS NULL` so a freshly-imported batch of historical
fillups gets covered first.

Realtime hooks (trip-close, fillup-save) populate weather inline so
new records don't depend on this worker. The backfiller exists for
the historical 510-fillup CSV import + any record where the realtime
hook missed (broker offline, fetch error, etc).
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime

import asyncpg

from ..services import weather

log = logging.getLogger(__name__)


CYCLE_INTERVAL_S = 3_600   # 1 hour
PAGE_SIZE = 100
THROTTLE_S = 1.0


async def _backfill_table(
    pool: asyncpg.Pool, table: str, time_col: str, lat_col: str, lon_col: str
) -> int:
    rows = await pool.fetch(
        f"""
        SELECT id, {time_col} AS ts, {lat_col} AS lat, {lon_col} AS lon
          FROM {table}
         WHERE weather_temp_c IS NULL
           AND {lat_col} IS NOT NULL
           AND {lon_col} IS NOT NULL
         ORDER BY {time_col} DESC
         LIMIT $1
        """,
        PAGE_SIZE,
    )
    touched = 0
    for r in rows:
        when: datetime = r["ts"]
        if when.tzinfo is None:
            when = when.replace(tzinfo=UTC)
        obs = await weather.fetch(float(r["lat"]), float(r["lon"]), when)
        await pool.execute(
            f"""
            UPDATE {table} SET
                weather_temp_c       = $2,
                weather_humidity_pct = $3,
                weather_precip_mm    = $4,
                weather_wind_kph     = $5,
                weather_code         = $6
             WHERE id = $1
            """,
            r["id"],
            obs.temp_c,
            obs.humidity_pct,
            obs.precip_mm,
            obs.wind_kph,
            obs.weather_code,
        )
        touched += 1
        await asyncio.sleep(THROTTLE_S)
    return touched


async def run(pool: asyncpg.Pool) -> None:
    log.info("weather backfiller started (cycle every %s s)", CYCLE_INTERVAL_S)
    while True:
        try:
            f = await _backfill_table(pool, "fillups", "fillup_date", "lat", "lon")
            t = await _backfill_table(
                pool, "trips", "started_at",
                # Trips don't have a single lat/lon column. Resolve to
                # the first GPS point for the trip if available.
                lat_col="(SELECT lat FROM gps_points "
                        "WHERE vehicle_id = trips.vehicle_id "
                        "AND time >= trips.started_at "
                        "AND time <= COALESCE(trips.ended_at, trips.started_at + interval '12 hours') "
                        "ORDER BY time ASC LIMIT 1)",
                lon_col="(SELECT lon FROM gps_points "
                        "WHERE vehicle_id = trips.vehicle_id "
                        "AND time >= trips.started_at "
                        "AND time <= COALESCE(trips.ended_at, trips.started_at + interval '12 hours') "
                        "ORDER BY time ASC LIMIT 1)",
            )
            if f or t:
                log.info("weather backfill: %s fillups, %s trips touched", f, t)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("weather backfiller cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_S)
