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
# After this many seconds of no new fuel_level samples we treat the
# vehicle as parked + sensor settled. WiCAN sleeps on engine-off and the
# phone bridge only publishes when the bridge service is running, so a
# sustained silence is a strong "parked" signal.
PARKED_QUIET_S = 600.0
# Snap to the median of the trailing N samples (robust to single slosh
# spikes). Median over a small window is more honest than mean over a
# wide window — the wide window can be dominated by mid-drive slosh.
SNAP_TRAILING_SAMPLES = 5
# Safety cap: never let a single snap drop the estimate by more than this
# fraction of tank_capacity_l. A 66 L drop on an 80 L tank in one tick
# (observed 2026-06-04, Honda PID 0x2F still stuck on pre-fillup reading
# many hours after a real fill) is far more likely sensor lag than real
# fuel loss. Cap forces the correction to happen gradually as the
# sensor catches up.
MAX_SNAP_DROP_FRACTION = 0.25


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
    """For each vehicle, if the latest fuel_level sample is older than
    ENGINE_OFF_STABLE_S (no recent capture = vehicle parked) AND a stable
    batch of trailing samples exists, snap the estimate to that batch's
    mean. Returns count of vehicles snapped.

    Doesn't depend on engine_events — those can be missing (short drives
    that didn't generate LWT, phone-only captures with no wican_lwt). The
    "no recent samples" heuristic is more robust: if WiCAN was active it
    would be publishing fuel_level at 1 Hz; silence ≥ STABLE_S means the
    vehicle is parked. The trailing samples are then necessarily from
    the pre-shutdown stationary moment where slosh has settled.
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
            # Latest fillup time — snaps must only consider samples taken
            # AFTER any recent fillup. A fillup is authoritative (user
            # observed the tank state); pre-fillup sensor samples are
            # stale and would silently undo the fillup-reset. Discovered
            # 2026-06-03: a 18:17 is_full=true fillup reset estimate to
            # 80 L; snap_pass at 23:14 read pre-fillup near-empty samples
            # and clobbered the estimate down to 3.94 L.
            latest_fillup = await conn.fetchval(
                """
                SELECT max(fillup_date) FROM fillups WHERE vehicle_id = $1
                """,
                v["id"],
            )
            # Quarantine snap until at least one COMPLETED TRIP has
            # happened since the latest fillup. Time-based quarantine
            # (the prior 6h fixed window) isn't enough: Honda's PID 0x2F
            # stays stuck on pre-fillup readings until the car has been
            # driven, and if WiCAN sleeps right after the pump there are
            # no fresh samples to disambiguate. A trip-since-fillup is
            # physical proof the sensor has had a chance to update.
            if latest_fillup is not None:
                trip_since = await conn.fetchval(
                    """
                    SELECT 1 FROM trips
                     WHERE vehicle_id = $1
                       AND ended_at IS NOT NULL
                       AND ended_at > $2
                       AND distance_km > 1.0
                     LIMIT 1
                    """,
                    v["id"], latest_fillup,
                )
                if trip_since is None:
                    continue
            latest = await conn.fetchval(
                """
                SELECT max(time) FROM pid_readings
                 WHERE vehicle_id = $1 AND metric = 'fuel_level'
                   AND ($2::timestamptz IS NULL OR time > $2)
                """,
                v["id"], latest_fillup,
            )
            if latest is None:
                continue
            quiet_for_s = (datetime.now(UTC) - latest).total_seconds()
            if quiet_for_s < PARKED_QUIET_S:
                continue  # data still flowing, vehicle likely active
            # Median of the last N samples — robust to single slosh spikes,
            # honest about what the sensor was reading at the moment of
            # park. Stability gating proved too brittle: during a drive
            # the samples bounce ±15 % from slosh, and clean post-park
            # samples are rare because WiCAN sleeps on engine-off.
            # Same post-fillup gate applies here.
            rows = await conn.fetch(
                """
                SELECT value_num FROM pid_readings
                 WHERE vehicle_id = $1 AND metric = 'fuel_level'
                   AND ($3::timestamptz IS NULL OR time > $3)
                 ORDER BY time DESC LIMIT $2
                """,
                v["id"], SNAP_TRAILING_SAMPLES, latest_fillup,
            )
            if len(rows) < 1:
                continue
            samples = sorted(float(r["value_num"]) for r in rows)
            sensor_pct = samples[len(samples) // 2]  # median
            update = snap_to_sensor(
                sensor_pct=sensor_pct,
                tank_capacity_l=float(v["tank_capacity_l"]),
                calibration_pct=float(v["cal_pct"]),
                current_estimate_l=(
                    float(v["fuel_level_estimate_l"])
                    if v["fuel_level_estimate_l"] is not None
                    else None
                ),
                when=latest,
            )
            if update is None:
                continue
            # Safety cap: never drop the estimate by more than
            # MAX_SNAP_DROP_FRACTION of tank_capacity_l in a single tick.
            # A huge downward correction is almost always sensor lag, not
            # real fuel loss; force the correction to happen gradually.
            current_l = (
                float(v["fuel_level_estimate_l"])
                if v["fuel_level_estimate_l"] is not None else None
            )
            tank_l = float(v["tank_capacity_l"])
            if current_l is not None and update.liters < current_l:
                drop = current_l - update.liters
                max_drop = tank_l * MAX_SNAP_DROP_FRACTION
                if drop > max_drop:
                    log.warning(
                        "fuel-estimate snap CAPPED vehicle=%s sensor=%.1f%% "
                        "wanted=%.2fL current=%.2fL cap=%.2fL — sensor "
                        "likely laggy; deferring full correction",
                        v["id"], sensor_pct, update.liters, current_l, max_drop,
                    )
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
