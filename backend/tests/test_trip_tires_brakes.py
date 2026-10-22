"""Pure-function tests for the trip-detail TPMS summary and braking stats.

No DB: get_trip() feeds raw (time, metric, value) rows into _tire_summary()
and raw (time, bar) rows into _brake_summary(); these pin the maths.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from pitstop.api.trips import _HARD_BRAKE_BAR, _brake_summary, _tire_summary
from pitstop.tires import TPMS_LOW_DELTA_PSI
from pitstop.tires import low_tire_corners as _low_tire_corners

T0 = datetime(2026, 10, 21, 22, 41, tzinfo=UTC)


def _t(s: float) -> datetime:
    return T0 + timedelta(seconds=s)


# ── Tires ────────────────────────────────────────────────────────────────


def test_low_rule_matches_the_dash_on_2026_10_21() -> None:
    """FR 196 kPa (28 psi) vs FL 213 kPa (31 psi): the dash lit TPMS for FR."""
    assert _low_tire_corners({"fl": 213, "fr": 196, "rl": 209, "rr": 205}) == ["fr"]


def test_low_rule_threshold_is_two_psi_inclusive() -> None:
    kpa_per_psi = 1 / 0.145038
    hi = 220.0
    exactly = hi - TPMS_LOW_DELTA_PSI * kpa_per_psi
    assert _low_tire_corners({"fl": hi, "fr": exactly}) == ["fr"]
    assert _low_tire_corners({"fl": hi, "fr": exactly + 0.5}) == []


def test_low_rule_needs_two_tires() -> None:
    assert _low_tire_corners({"fl": 150, "fr": None, "rl": None, "rr": None}) == []
    assert _low_tire_corners({}) == []


def test_tire_summary_none_without_data() -> None:
    assert _tire_summary([]) is None
    # Only unrelated metrics / sentinels -> still nothing to show.
    assert _tire_summary([(_t(0), "engine_rpm", 800.0)]) is None
    assert _tire_summary([(_t(0), "tire_pressure_fl", 0.0)]) is None
    assert _tire_summary([(_t(0), "tire_temp_fl", -128.0)]) is None


def test_tire_summary_start_end_min_max_and_low() -> None:
    rows = [
        (_t(0), "tire_pressure_fl", 210.0),
        (_t(30), "tire_pressure_fl", 216.0),
        (_t(60), "tire_pressure_fl", 214.0),
        (_t(0), "tire_pressure_fr", 194.0),
        (_t(60), "tire_pressure_fr", 198.0),
        (_t(0), "tire_temp_fl", 18.0),
        (_t(60), "tire_temp_fl", 29.0),
        (_t(60), "tire_pressure_rl", 0.0),  # sentinel, dropped
        (_t(0), "engine_rpm", 900.0),  # not a tire metric
    ]
    # Out of order on purpose: start/end are by time, not row order.
    out = _tire_summary(list(reversed(rows)))
    assert out is not None
    by = {c["corner"]: c for c in out["corners"]}
    assert [c["corner"] for c in out["corners"]] == ["fl", "fr", "rl", "rr"]
    assert by["fl"]["pressure_start_kpa"] == 210.0
    assert by["fl"]["pressure_end_kpa"] == 214.0
    assert by["fl"]["pressure_min_kpa"] == 210.0
    assert by["fl"]["pressure_max_kpa"] == 216.0
    assert by["fl"]["temp_start_c"] == 18.0
    assert by["fl"]["temp_end_c"] == 29.0
    assert by["fr"]["temp_start_c"] is None
    assert by["rl"]["pressure_end_kpa"] is None
    assert by["rr"] == {
        "corner": "rr",
        "pressure_start_kpa": None,
        "pressure_end_kpa": None,
        "pressure_min_kpa": None,
        "pressure_max_kpa": None,
        "temp_start_c": None,
        "temp_end_c": None,
    }
    # End pressures 214 vs 198 kPa = 2.3 psi spread -> FR low.
    assert out["low_corners"] == ["fr"]
    assert out["low_delta_psi"] == TPMS_LOW_DELTA_PSI


# ── Braking ──────────────────────────────────────────────────────────────


def _series(values: list[float], start: float = 0.0, step: float = 1.0):
    return [(_t(start + i * step), v) for i, v in enumerate(values)]


def test_brake_summary_null_not_zero_without_samples() -> None:
    assert _brake_summary([]) == {
        "brake_events": None,
        "hard_brake_events": None,
        "max_brake_bar": None,
        "braking_s": None,
    }


def test_brake_summary_never_braked_is_zero() -> None:
    out = _brake_summary(_series([0, 0, 1, 0]))
    assert out == {
        "brake_events": 0,
        "hard_brake_events": 0,
        "max_brake_bar": 1.0,
        "braking_s": 0.0,
    }


def test_brake_summary_counts_presses_and_hard_brakes() -> None:
    # Light press (3 s, peak 25), released 4 s, firm press (2 s, peak 90).
    vals = [0, 20, 25, 22, 0, 0, 0, 0, 70, 90, 0, 0]
    out = _brake_summary(_series(vals))
    assert out["brake_events"] == 2
    assert out["hard_brake_events"] == 1
    assert out["max_brake_bar"] == 90.0
    # Time above 5 bar: samples 1,2,3 (3 s) + 8,9 (2 s).
    assert out["braking_s"] == 5.0


def test_brake_summary_debounces_a_pumped_pedal() -> None:
    # A dip under threshold for < 1 s between two stabs = one press.
    pts = [
        (_t(0.0), 0.0),
        (_t(1.0), 30.0),
        (_t(1.5), 2.0),  # released...
        (_t(2.0), 65.0),  # ...re-pressed 0.5 s later: same press
        (_t(3.0), 0.0),
        (_t(4.5), 0.0),
    ]
    out = _brake_summary(pts)
    assert out["brake_events"] == 1
    assert out["hard_brake_events"] == 1  # one press, peak 65


def test_brake_summary_release_of_one_second_splits() -> None:
    vals = [0, 30, 0, 30, 0]  # 1 Hz: each release lasts a full second
    out = _brake_summary(_series(vals))
    assert out["brake_events"] == 2
    assert out["hard_brake_events"] == 0


def test_brake_summary_dropout_is_not_a_held_pedal() -> None:
    # Pressed, then 10 minutes of no samples, then pressed again.
    pts = [(_t(0), 40.0), (_t(1), 40.0), (_t(601), 40.0), (_t(602), 0.0)]
    out = _brake_summary(pts)
    assert out["brake_events"] == 2
    assert out["braking_s"] == 2.0  # 1 s + 1 s, nothing across the gap


def test_hard_brake_threshold_is_provisional_constant() -> None:
    assert _HARD_BRAKE_BAR == 60.0
    out = _brake_summary(_series([0, _HARD_BRAKE_BAR, 0]))
    assert out["hard_brake_events"] == 1
