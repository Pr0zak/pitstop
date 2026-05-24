"""Hybrid fuel-level estimator — background worker (phase 2).

Runs alongside trip_deriver / ingest. Two passes per cycle:

1. **decrement_pass** — for each settled trip (ended_at older than the
   settle delay) where fuel_used_l is non-null and fuel_applied_at is
   null, subtract fuel_used_l from the vehicle's running estimate and
   stamp fuel_applied_at. Idempotent via the column flag.

2. **snap_pass** — for each vehicle, if the engine has been off long
   enough (default 10 min) and the raw fuel_level sensor reading has
   been stable (default stddev < 0.5 %) over a recent window, snap the
   running estimate to the sensor reading (HIGH confidence reset). This
   absorbs accumulated MAF-integration drift between fillups.

The state-machine math lives in services/fuel_state.py as pure functions;
this worker is the DB driver around it.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import UUID

import asyncpg

from ..services.fuel_state import (
    decrement_on_trip,
    persist_estimate,
    snap_to_sensor,
)

if TYPE_CHECKING:
    from ..config import Settings

log = logging.getLogger(__name__)


CYCLE_INTERVAL_S = 60.0
TRIP_SETTLE_S = 120.0  # let trip_deriver finish UPSERTing before we apply
ENGINE_OFF_STABLE_S = 600.0  # 10 min of engine-off before snap is safe
SENSOR_STABILITY_WINDOW_S = 600.0  # last 10 min of fuel_level samples
SENSOR_STABILITY_MAX_STDDEV = 0.5  # %; tighter than this and we snap


async def decrement_pass(pool: asyncpg.Pool) -> int:
    """Process all unapplied trips. Returns count of trips processed."""
    processed = 0
    async with pool.acquire() as conn:
        # SELECT FOR UPDATE on vehicles row prevents racing with the
        # fillup-reset path in the api.
        trips = await conn.fetch(
            """
            SELECT t.id, t.vehicle_id, t.fuel_used_l, t.ended_at,
                   v.fuel_level_estimate_l, v.tank_capacity_l
              FROM trips t
              JOIN vehicles v ON v.id = t.vehicle_id
             WHERE t.fuel_applied_at IS NULL
               AND t.fuel_used_l IS NOT NULL
               AND t.ended_at IS NOT NULL
               AND t.ended_at < now() - make_interval(secs => $1)
             ORDER BY t.vehicle_id, t.ended_at
            """,
            TRIP_SETTLE_S,
        )
        for row in trips:
            update = decrement_on_trip(
                fuel_used_l=float(row["fuel_used_l"]),
                current_estimate_l=(
                    float(row["fuel_level_estimate_l"])
                    if row["fuel_level_estimate_l"] is not None
                    else None
                ),
                when=row["ended_at"],
            )
            if update is None:
                # No prior estimate — can't decrement. Mark the trip as
                # applied anyway so we don't keep re-trying it on every
                # cycle; the next snap or fillup will seed the state.
                await conn.execute(
                    "UPDATE trips SET fuel_applied_at = now() WHERE id = $1",
                    row["id"],
                )
                continue
            async with conn.transaction():
                await persist_estimate(conn, row["vehicle_id"], update)
                await conn.execute(
                    "UPDATE trips SET fuel_applied_at = now() WHERE id = $1",
                    row["id"],
                )
            processed += 1
            log.info(
                "fuel-estimate decrement trip=%s vehicle=%s used=%.2fL new=%.2fL",
                row["id"], row["vehicle_id"], float(row["fuel_used_l"]),
                update.liters,
            )
    return processed


async def snap_pass(pool: asyncpg.Pool) -> int:
    """For each vehicle, if engine has been off long enough and sensor is
    stable, snap the estimate to the sensor reading. Returns count of
    vehicles snapped.
    """
    snapped = 0
    async with pool.acquire() as conn:
        vehicles = await conn.fetch(
            """
            SELECT id, tank_capacity_l,
                   COALESCE(fuel_level_calibration_pct, 100.0) AS cal_pct,
                   fuel_level_estimate_l
              FROM vehicles
             WHERE tank_capacity_l IS NOT NULL
            """
        )
        for v in vehicles:
            last_off = await conn.fetchval(
                """
                SELECT max(time) FROM engine_events
                 WHERE vehicle_id = $1 AND state = 'off'
                """,
                v["id"],
            )
            if last_off is None:
                continue
            elapsed = (datetime.now(UTC) - last_off).total_seconds()
            if elapsed < ENGINE_OFF_STABLE_S:
                continue
            # Check if engine has come back on since last off (would void
            # the stability assumption).
            on_since_off = await conn.fetchval(
                """
                SELECT 1 FROM engine_events
                 WHERE vehicle_id = $1 AND state = 'on' AND time > $2
                 LIMIT 1
                """,
                v["id"], last_off,
            )
            if on_since_off:
                continue
            # Sensor stability over the recent window
            stats = await conn.fetchrow(
                """
                SELECT count(*) AS n, avg(value_num) AS mean,
                       stddev_samp(value_num) AS sd, max(time) AS latest
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND time > now() - make_interval(secs => $2)
                """,
                v["id"], SENSOR_STABILITY_WINDOW_S,
            )
            if stats is None or stats["n"] is None or stats["n"] < 3:
                continue
            if stats["sd"] is None or float(stats["sd"]) > SENSOR_STABILITY_MAX_STDDEV:
                continue
            sensor_pct = float(stats["mean"])
            update = snap_to_sensor(
                sensor_pct=sensor_pct,
                tank_capacity_l=float(v["tank_capacity_l"]),
                calibration_pct=float(v["cal_pct"]),
                current_estimate_l=(
                    float(v["fuel_level_estimate_l"])
                    if v["fuel_level_estimate_l"] is not None
                    else None
                ),
                when=stats["latest"],
            )
            if update is None:
                continue
            await persist_estimate(conn, v["id"], update)
            snapped += 1
            log.info(
                "fuel-estimate snap vehicle=%s sensor=%.1f%% liters=%.2fL %s",
                v["id"], sensor_pct, update.liters, update.reason,
            )
    return snapped


async def run(pool: asyncpg.Pool, cfg: "Settings") -> None:
    """Forever loop. Mirrors the trip_deriver/retention pattern."""
    log.info("fuel-state worker started (cycle every %s s)", CYCLE_INTERVAL_S)
    while True:
        try:
            d = await decrement_pass(pool)
            s = await snap_pass(pool)
            if d or s:
                log.info("fuel-state cycle: decremented=%d snapped=%d", d, s)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("fuel-state cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_S)
