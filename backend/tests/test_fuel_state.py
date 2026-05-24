"""Unit tests for the hybrid fuel-level state helpers (services/fuel_state.py).

Pure functions, no DB needed. ``persist_estimate`` is the one DB-touching
helper and is exercised by integration tests later.
"""

from __future__ import annotations

from datetime import datetime, timezone

from pitstop.services.fuel_state import (
    decrement_on_trip,
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
    # Estimate is within snap threshold — don't churn the state
    u = snap_to_sensor(
        sensor_pct=50.0,
        tank_capacity_l=80.0,
        calibration_pct=100.0,
        current_estimate_l=42.0,  # 2L delta, under threshold
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
