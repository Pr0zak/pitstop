"""Regression cover for the 2026-08-20 double-charge and the dead-band
that hid it.

Two faults that only produce a wrong fuel gauge when they combine:

*   ``snap_pass`` used to write the new estimate and nothing else. The
    tank sensor falls the moment fuel is burned, but a trip's
    ``fuel_used_l`` decrement can arrive an hour later — the phone has to
    upload the drive and ``trip_deriver`` has to build the row. A snap
    landing in that gap charged the same drive twice.

*   The snap dead-band was a flat 5.0 L, which is 6.8 % of the Pilot's
    73.8 L tank. Any drift smaller than 1.3 gallons was therefore
    uncorrectable, so the double charge parked itself permanently in the
    blind spot instead of being cleaned up on the next park.

The live numbers below are the real ones from that evening.
"""

from __future__ import annotations

import os
import sys
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from pitstop.services.fuel_state import (  # noqa: E402
    SNAP_THRESHOLD_MIN_L,
    decrement_on_trip,
    snap_absorbs_trip,
    snap_threshold_for_tank,
    snap_to_sensor,
)

from .conftest import _dsn, _has_pg_env  # noqa: E402

# The Pilot, as configured in production.
TANK_L = 73.8
CAL_PCT = 91.373
EMPTY_PCT = 5.671

# 2026-08-20, the evening this was found.
TRIP_ENDED = datetime(2026, 8, 20, 20, 55, 34, tzinfo=UTC)
SENSOR_AT = datetime(2026, 8, 20, 21, 4, 16, tzinfo=UTC)
SENSOR_PCT = 23.5           # median of the trailing samples at that moment
SNAPPED_TO_L = 15.38        # what snap_pass wrote
TRIP_FUEL_L = 3.13          # the pending decrement for the trip above
LATER_TRIP_FUEL_L = 1.42    # a trip that ended AFTER the sensor sample

# A drive's last six minutes as the phone captures them: thirteen readings
# 30 s apart with the slosh spread a live float sender produces, every
# other one duplicated 2 ms later by the bridge path. Their P75 is
# SENSOR_PCT, so the snap still lands on SNAPPED_TO_L. (Since ADR-025 the
# snap reads a de-duplicated drive window, not the last five rows, and a
# single repeated value is rejected as a replayed frame.)
DRIVE_TAIL_RAW = (
    22.0, 23.9, 19.6, 23.5, 17.3, 24.3, 21.2, 18.8, 23.1, 25.1, 20.4, 22.7, 18.0,
)


async def _seed_drive_tail(conn, vehicle_id, anchor) -> None:
    n = len(DRIVE_TAIL_RAW)
    for i, raw in enumerate(DRIVE_TAIL_RAW):
        at = anchor - timedelta(seconds=30 * (n - 1 - i))
        await conn.execute(
            """
            INSERT INTO pid_readings (time, vehicle_id, metric, value_num, source)
            VALUES ($1, $2, 'fuel_level', $3, 'phone_batch')
            """,
            at, vehicle_id, raw,
        )
        if i % 2 == 0:
            await conn.execute(
                """
                INSERT INTO pid_readings (time, vehicle_id, metric, value_num, source)
                VALUES ($1, $2, 'fuel_level', $3, 'bridge')
                """,
                at + timedelta(milliseconds=2), vehicle_id, raw,
            )


# ── The ordering rule itself ────────────────────────────────────────

def test_snap_absorbs_a_trip_that_ended_before_the_sample():
    assert snap_absorbs_trip(
        trip_ended_at=TRIP_ENDED, sensor_sample_at=SENSOR_AT
    ) is True


def test_snap_does_not_absorb_a_trip_that_ended_after_the_sample():
    """A drive finished after the reading is not in it, so its decrement
    must still run — otherwise a snap silently credits back real fuel."""
    assert snap_absorbs_trip(
        trip_ended_at=SENSOR_AT + timedelta(seconds=1),
        sensor_sample_at=SENSOR_AT,
    ) is False


def test_snap_absorption_boundary_is_inclusive():
    """Equal timestamps count as absorbed. The worker's SQL predicate is
    ``ended_at <= sensor_time``; if the two ever disagree, one drive per
    coincidence gets charged twice."""
    assert snap_absorbs_trip(
        trip_ended_at=SENSOR_AT, sensor_sample_at=SENSOR_AT
    ) is True


# ── The arithmetic the ordering bug produced ────────────────────────

def test_double_charge_is_what_put_the_estimate_3L_low():
    """Pins the defect itself, in the numbers it actually produced.

    Applying the absorbed trip's decrement on top of the snap lands at
    12.25 L. Production went on to 10.83 L after the next (legitimate)
    trip, against a sensor still reading 15.7 L.
    """
    double_charged = decrement_on_trip(
        fuel_used_l=TRIP_FUEL_L,
        current_estimate_l=SNAPPED_TO_L,
        when=TRIP_ENDED,
    )
    assert double_charged is not None
    assert double_charged.liters == pytest.approx(12.25, abs=0.01)


def test_absorbed_trip_must_not_decrement_after_the_snap():
    """The corrected sequence: the snap stands, and only the trip that
    ended after the sensor sample is allowed to move it."""
    estimate = SNAPPED_TO_L
    for ended_at, fuel_l in (
        (TRIP_ENDED, TRIP_FUEL_L),                             # absorbed
        (SENSOR_AT + timedelta(minutes=59), LATER_TRIP_FUEL_L),  # not absorbed
    ):
        if snap_absorbs_trip(trip_ended_at=ended_at, sensor_sample_at=SENSOR_AT):
            continue
        update = decrement_on_trip(
            fuel_used_l=fuel_l, current_estimate_l=estimate, when=ended_at
        )
        assert update is not None
        estimate = update.liters
    assert estimate == pytest.approx(13.96, abs=0.01)


# ── The dead-band that stopped it self-correcting ───────────────────

def test_dead_band_is_proportional_to_the_tank():
    assert snap_threshold_for_tank(TANK_L) == pytest.approx(1.476, abs=0.001)
    # A flat 5.0 L would be 6.8 % of this tank.
    assert snap_threshold_for_tank(TANK_L) < 5.0


def test_small_tanks_keep_an_absolute_floor():
    """2 % of a 20 L tank is 0.4 L — tight enough to chase the float
    sensor's own quantisation, so the floor wins there."""
    assert snap_threshold_for_tank(20.0) == SNAP_THRESHOLD_MIN_L


def test_the_stuck_estimate_now_snaps():
    """The exact reading that sat wrong for hours: 4.89 L of drift, which
    the old flat 5.0 L band declined to correct by 0.11 L."""
    update = snap_to_sensor(
        sensor_pct=23.922,
        tank_capacity_l=TANK_L,
        calibration_pct=CAL_PCT,
        empty_pct=EMPTY_PCT,
        current_estimate_l=10.8304,
        when=SENSOR_AT,
    )
    assert update is not None
    assert update.liters == pytest.approx(15.72, abs=0.01)


def test_explicit_threshold_override_still_wins():
    """The old flat value stays reachable, so this can be pinned per-call."""
    assert snap_to_sensor(
        sensor_pct=23.922,
        tank_capacity_l=TANK_L,
        calibration_pct=CAL_PCT,
        empty_pct=EMPTY_PCT,
        current_estimate_l=10.8304,
        when=SENSOR_AT,
        snap_threshold_l=5.0,
    ) is None


# ── End-to-end through the real worker ──────────────────────────────

pytestmark_pg = pytest.mark.skipif(
    not _has_pg_env(), reason="no test Postgres configured"
)


@pytestmark_pg
async def test_snap_pass_retires_the_trips_it_absorbed():
    """Drive the real snap_pass + decrement_pass against Postgres in the
    order that caused the bug: trip ends, sensor sample lands after it,
    snap fires, and only THEN does the decrement get a chance to run.

    Without the stamping in snap_pass this ends at 12.25 L.
    """
    import asyncpg

    from pitstop.workers.fuel_state_worker import decrement_pass, snap_pass

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-snaporder-{uuid4().hex[:8]}"
    try:
        async with pool.acquire() as conn:
            vehicle_id = await conn.fetchval(
                """
                INSERT INTO vehicles (slug, name, tank_capacity_l,
                                      fuel_level_calibration_pct,
                                      fuel_level_empty_pct,
                                      fuel_level_estimate_l)
                VALUES ($1, 'snap-order fixture', $2, $3, $4, $5)
                RETURNING id
                """,
                slug, TANK_L, CAL_PCT, EMPTY_PCT, 22.13,
            )
            # A trip that has ended but whose decrement is still pending,
            # exactly as it is while the phone has yet to upload.
            trip_id = await conn.fetchval(
                """
                INSERT INTO trips (vehicle_id, started_at, ended_at,
                                   distance_km, fuel_used_l, source)
                VALUES ($1, $2, $3, 27.05, $4, 'phone_batch')
                RETURNING id
                """,
                vehicle_id,
                TRIP_ENDED - timedelta(minutes=32),
                TRIP_ENDED,
                TRIP_FUEL_L,
            )
            # Sensor samples taken AFTER that trip ended, and long enough
            # ago to clear the parked-quiet gate.
            sample_at = datetime.now(UTC) - timedelta(minutes=30)
            await _seed_drive_tail(conn, vehicle_id, sample_at)

        assert await snap_pass(pool) >= 1

        async with pool.acquire() as conn:
            snapped = await conn.fetchval(
                "SELECT fuel_level_estimate_l FROM vehicles WHERE id = $1",
                vehicle_id,
            )
            applied = await conn.fetchval(
                "SELECT fuel_applied_at FROM trips WHERE id = $1", trip_id
            )
        assert float(snapped) == pytest.approx(SNAPPED_TO_L, abs=0.05)
        assert applied is not None, "snap must retire the trip it absorbed"

        # The decrement pass now has nothing to charge for this vehicle.
        await decrement_pass(pool)
        async with pool.acquire() as conn:
            after = await conn.fetchval(
                "SELECT fuel_level_estimate_l FROM vehicles WHERE id = $1",
                vehicle_id,
            )
        assert float(after) == pytest.approx(SNAPPED_TO_L, abs=0.05), (
            "decrement_pass re-charged a trip the snap had already absorbed"
        )
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()


@pytestmark_pg
async def test_a_snap_must_not_wipe_a_decrement_it_did_not_cover():
    """The mirror of the double-charge, and the reason the cycle order was
    swapped to snap-then-decrement.

    A trip that ended AFTER the sensor sample is not reflected in that
    reading, so its fuel must still come off the estimate. Under the old
    decrement-then-snap order the decrement landed first and the snap
    overwrote it in the same second — on 2026-08-22, 3.6 L of real
    post-fillup driving was discarded exactly this way.
    """
    import asyncpg

    from pitstop.workers.fuel_state_worker import run_cycle

    pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=2)
    slug = f"apitest-snapwipe-{uuid4().hex[:8]}"
    try:
        sample_at = datetime.now(UTC) - timedelta(minutes=30)
        async with pool.acquire() as conn:
            vehicle_id = await conn.fetchval(
                """
                INSERT INTO vehicles (slug, name, tank_capacity_l,
                                      fuel_level_calibration_pct,
                                      fuel_level_empty_pct,
                                      fuel_level_estimate_l)
                VALUES ($1, 'snap-wipe fixture', $2, $3, $4, $5)
                RETURNING id
                """,
                slug, TANK_L, CAL_PCT, EMPTY_PCT, 22.13,
            )
            await _seed_drive_tail(conn, vehicle_id, sample_at)
            # Drive finished a minute AFTER that reading — the sensor
            # cannot know about it.
            await conn.execute(
                """
                INSERT INTO trips (vehicle_id, started_at, ended_at,
                                   distance_km, fuel_used_l, source)
                VALUES ($1, $2, $3, 12.0, $4, 'phone_batch')
                """,
                vehicle_id,
                sample_at + timedelta(seconds=30),
                sample_at + timedelta(minutes=1),
                LATER_TRIP_FUEL_L,
            )

        # Drive a real worker cycle, so the ORDER is what is under test.
        # Reversed, the decrement lands first and the snap erases it.
        decremented, snapped = await run_cycle(pool)
        assert snapped >= 1
        assert decremented >= 1

        async with pool.acquire() as conn:
            final = float(await conn.fetchval(
                "SELECT fuel_level_estimate_l FROM vehicles WHERE id = $1",
                vehicle_id,
            ))
        assert final == pytest.approx(SNAPPED_TO_L - LATER_TRIP_FUEL_L, abs=0.05), (
            "the post-sample trip's fuel was lost"
        )

        # And the window must not come back for a second bite: the trip's
        # decrement wrote the estimate AFTER this window's instant, so the
        # once-per-anchor guard (ADR-025) leaves it alone from here on.
        assert await run_cycle(pool) == (0, 0)
        async with pool.acquire() as conn:
            again = float(await conn.fetchval(
                "SELECT fuel_level_estimate_l FROM vehicles WHERE id = $1",
                vehicle_id,
            ))
        assert again == pytest.approx(final, abs=1e-6), (
            "a stale window re-snapped over a later trip's decrement"
        )
    finally:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM vehicles WHERE slug = $1", slug)
        await pool.close()
