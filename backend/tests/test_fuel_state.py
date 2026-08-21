"""Unit tests for the hybrid fuel-level state helpers (services/fuel_state.py).

Pure functions, no DB needed. ``persist_estimate`` is the one DB-touching
helper and is exercised by integration tests later.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from pitstop.services.fuel_state import (
    decrement_on_trip,
    derive_empty_pct,
    reset_on_fillup,
    sensor_pct_to_liters,
    snap_to_sensor,
)


NOW = datetime(2026, 5, 24, 12, 0, 0, tzinfo=timezone.utc)


def test_sensor_pct_to_liters_full_calibration():
    # 50% sensor on an 80L tank with 100% calibration -> 40L
    assert sensor_pct_to_liters(50.0, 80.0, 100.0) == 40.0


def test_sensor_pct_to_liters_with_honda_cap():
    # 85% sensor with 85% calibration = real 100% = full tank
    assert sensor_pct_to_liters(85.0, 80.0, 85.0) == 80.0
    # 42.5% sensor with 85% cal = real 50% = 40L
    assert sensor_pct_to_liters(42.5, 80.0, 85.0) == 40.0


def test_sensor_pct_to_liters_clamps_high():
    # Sensor jitters above its calibration ceiling — clamp to 100%
    assert sensor_pct_to_liters(90.0, 80.0, 85.0) == 80.0


def test_reset_on_fillup_is_full_snaps_to_tank():
    u = reset_on_fillup(
        is_full=True,
        pumped_liters=45.0,  # ignored when is_full
        current_estimate_l=20.0,
        tank_capacity_l=80.0,
        when=NOW,
    )
    assert u.liters == 80.0
    assert u.confidence == "HIGH"
    assert u.reason == "fillup_is_full"


def test_reset_on_fillup_partial_adds():
    u = reset_on_fillup(
        is_full=False,
        pumped_liters=15.0,
        current_estimate_l=20.0,
        tank_capacity_l=80.0,
        when=NOW,
    )
    assert u.liters == 35.0
    assert u.confidence == "HIGH"


def test_reset_on_fillup_partial_capped_at_tank():
    # Pumped more than capacity allows — cap at tank size (defensive)
    u = reset_on_fillup(
        is_full=False,
        pumped_liters=70.0,
        current_estimate_l=20.0,
        tank_capacity_l=80.0,
        when=NOW,
    )
    assert u.liters == 80.0


def test_reset_on_fillup_partial_no_prior_returns_low_confidence():
    u = reset_on_fillup(
        is_full=False,
        pumped_liters=15.0,
        current_estimate_l=None,
        tank_capacity_l=80.0,
        when=NOW,
    )
    assert u.confidence == "LOW"
    assert u.reason == "fillup_partial_no_prior"


def test_reset_on_fillup_skips_with_no_liters():
    u = reset_on_fillup(
        is_full=False,
        pumped_liters=None,
        current_estimate_l=42.0,
        tank_capacity_l=80.0,
        when=NOW,
    )
    assert u.confidence == "LOW"
    assert u.reason == "fillup_skipped_no_liters"
    assert u.liters == 42.0  # unchanged


def test_decrement_on_trip_subtracts():
    u = decrement_on_trip(fuel_used_l=5.5, current_estimate_l=42.0, when=NOW)
    assert u is not None
    assert u.liters == 36.5
    assert u.confidence == "MEDIUM"


def test_decrement_on_trip_floors_at_zero():
    # Trip claims more than estimate had — floor at 0, don't go negative
    u = decrement_on_trip(fuel_used_l=50.0, current_estimate_l=10.0, when=NOW)
    assert u is not None
    assert u.liters == 0.0


def test_decrement_on_trip_skips_with_no_prior():
    # Can't decrement what we don't have
    assert decrement_on_trip(fuel_used_l=5.0, current_estimate_l=None, when=NOW) is None


def test_decrement_on_trip_skips_zero_used():
    assert decrement_on_trip(fuel_used_l=0.0, current_estimate_l=42.0, when=NOW) is None


def test_snap_to_sensor_corrects_drift():
    # Estimate drifted to 35L but sensor reads 50%/80L = 40L; >5L delta triggers snap
    u = snap_to_sensor(
        sensor_pct=50.0,
        tank_capacity_l=80.0,
        calibration_pct=100.0,
        current_estimate_l=30.0,
        when=NOW,
    )
    assert u is not None
    assert u.liters == 40.0
    assert u.confidence == "HIGH"


def test_snap_to_sensor_no_op_when_close():
    # Estimate is within the dead-band — don't churn the state.
    # 80 L tank -> 1.6 L band, so 1.0 L of drift is left alone.
    u = snap_to_sensor(
        sensor_pct=50.0,
        tank_capacity_l=80.0,
        calibration_pct=100.0,
        current_estimate_l=41.0,
        when=NOW,
    )
    assert u is None


def test_snap_to_sensor_seeds_initial_estimate():
    # No prior estimate — accept sensor reading as initial truth
    u = snap_to_sensor(
        sensor_pct=75.0,
        tank_capacity_l=80.0,
        calibration_pct=100.0,
        current_estimate_l=None,
        when=NOW,
    )
    assert u is not None
    assert u.liters == 60.0
    assert u.reason == "snap_initial_from_sensor"


# ── Two-point calibration (migration 0019) ────────────────────────────
#
# Regression cover for the 2026-08-03 incident: the one-point map read
# 3.18 gal while the tank actually held 2.10 gal (51% high) and the
# driver was on the low-fuel light. Numbers below are that vehicle's
# real measurements — Pilot, 73.8 L tank, sender full at 91.373%.

PILOT_TANK_L = 73.8
PILOT_CAL_PCT = 91.373
PILOT_EMPTY_PCT = 5.7


def test_empty_pct_none_preserves_one_point_behaviour():
    """Vehicles that never calibrated the low end must render unchanged."""
    assert sensor_pct_to_liters(45.6865, 73.8, 91.373, None) == pytest.approx(
        sensor_pct_to_liters(45.6865, 73.8, 91.373), rel=1e-9
    )


def test_two_point_map_fixes_the_low_end_over_read():
    """The measured failure: raw 14.902% on the Pilot."""
    one_point = sensor_pct_to_liters(14.902, PILOT_TANK_L, PILOT_CAL_PCT)
    two_point = sensor_pct_to_liters(
        14.902, PILOT_TANK_L, PILOT_CAL_PCT, PILOT_EMPTY_PCT
    )
    # One-point said ~3.2 gal; the tank actually held 2.10 gal.
    assert one_point * 0.264172 == pytest.approx(3.18, abs=0.05)
    # Two-point lands on the measured truth.
    assert two_point * 0.264172 == pytest.approx(2.10, abs=0.15)
    assert two_point < one_point


def test_two_point_still_reads_full_at_the_calibration_ceiling():
    assert sensor_pct_to_liters(
        PILOT_CAL_PCT, PILOT_TANK_L, PILOT_CAL_PCT, PILOT_EMPTY_PCT
    ) == pytest.approx(PILOT_TANK_L)


def test_two_point_reads_zero_at_and_below_the_empty_point():
    assert sensor_pct_to_liters(
        PILOT_EMPTY_PCT, PILOT_TANK_L, PILOT_CAL_PCT, PILOT_EMPTY_PCT
    ) == pytest.approx(0.0)
    # Below the dry reading must clamp, not go negative.
    assert sensor_pct_to_liters(
        1.0, PILOT_TANK_L, PILOT_CAL_PCT, PILOT_EMPTY_PCT
    ) == pytest.approx(0.0)


def test_bogus_empty_points_fall_back_to_one_point():
    one_point = sensor_pct_to_liters(50.0, PILOT_TANK_L, PILOT_CAL_PCT)
    for bogus in (-5.0, PILOT_CAL_PCT, PILOT_CAL_PCT + 10.0):
        assert sensor_pct_to_liters(
            50.0, PILOT_TANK_L, PILOT_CAL_PCT, bogus
        ) == pytest.approx(one_point)


def test_derive_empty_pct_recovers_the_measured_calibration():
    """The Aug 3 fill: 17.396 gal into a 19.5 gal tank, sender at 14.902%."""
    remaining_l = PILOT_TANK_L - 17.396 * 3.78541
    empty = derive_empty_pct(
        sensor_pct_before=14.902,
        liters_remaining_before=remaining_l,
        tank_capacity_l=PILOT_TANK_L,
        calibration_pct=PILOT_CAL_PCT,
    )
    assert empty == pytest.approx(5.7, abs=0.3)


def test_derive_empty_pct_rejects_nonsense_observations():
    # Sensor already at/above the full point tells us nothing.
    assert derive_empty_pct(
        sensor_pct_before=95.0,
        liters_remaining_before=5.0,
        tank_capacity_l=PILOT_TANK_L,
        calibration_pct=PILOT_CAL_PCT,
    ) is None
    # More fuel remaining than the tank holds.
    assert derive_empty_pct(
        sensor_pct_before=14.9,
        liters_remaining_before=PILOT_TANK_L * 1.2,
        tank_capacity_l=PILOT_TANK_L,
        calibration_pct=PILOT_CAL_PCT,
    ) is None
    # An implausibly high dry reading is a bad observation, not a cal.
    assert derive_empty_pct(
        sensor_pct_before=60.0,
        liters_remaining_before=1.0,
        tank_capacity_l=PILOT_TANK_L,
        calibration_pct=PILOT_CAL_PCT,
    ) is None


def test_snap_to_sensor_uses_the_empty_point():
    """Snap must correct DOWN once the low end is calibrated — this is the
    behaviour that would have caught the drift without a manual fix."""
    when = datetime(2026, 8, 3, 22, 0, tzinfo=timezone.utc)
    update = snap_to_sensor(
        sensor_pct=14.902,
        tank_capacity_l=PILOT_TANK_L,
        calibration_pct=PILOT_CAL_PCT,
        empty_pct=PILOT_EMPTY_PCT,
        current_estimate_l=14.76,   # what the estimate had drifted to
        when=when,
    )
    assert update is not None
    assert update.liters * 0.264172 == pytest.approx(2.10, abs=0.15)
