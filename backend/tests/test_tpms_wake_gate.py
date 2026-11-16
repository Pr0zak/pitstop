"""TPMS wake gate. Same vectors as the phone's TpmsWakeGateTest."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from pitstop.tires import TIRE_WAKE_REARM_S, TpmsWakeGate

T0 = datetime(2026, 11, 16, 12, 11, 57, tzinfo=UTC)


def at(s: float) -> datetime:
    return T0 + timedelta(seconds=s)


def test_stored_set_is_held_until_the_sensor_wakes() -> None:
    # 2026-11-16, FR: 218 kPa stored until the sensor woke at 152.
    g = TpmsWakeGate()
    fr = [218.0, 218.0, 218.0, 152.0, 152.0, 155.0]
    got = [v if g.admit("v", "tire_pressure_fr", v, at(i * 32)) else None for i, v in enumerate(fr)]
    assert got == [None, None, None, 152.0, 152.0, 155.0]


def test_short_trip_before_wake_records_nothing() -> None:
    g = TpmsWakeGate()
    assert not g.admit("v", "tire_pressure_fr", 218.0, at(0))
    assert not g.admit("v", "tire_temp_fr", 14.0, at(0))
    assert not g.admit("v", "tire_pressure_fr", 218.0, at(33))
    assert not g.admit("v", "tire_temp_fr", 14.0, at(33))


def test_slow_drift_releases_on_first_change() -> None:
    g = TpmsWakeGate()
    for i in range(8):
        assert not g.admit("v", "tire_pressure_rr", 225.0, at(i * 30))
    assert g.admit("v", "tire_pressure_rr", 228.0, at(244))
    assert g.admit("v", "tire_temp_rr", 12.0, at(244))
    assert g.admit("v", "tire_pressure_rr", 228.0, at(274))


def test_a_park_rearms_the_corner() -> None:
    g = TpmsWakeGate()
    g.admit("v", "tire_pressure_fl", 229.0, at(0))
    assert g.admit("v", "tire_pressure_fl", 230.0, at(30))
    later = 30 + TIRE_WAKE_REARM_S
    assert not g.admit("v", "tire_pressure_fl", 236.0, at(later))
    assert not g.admit("v", "tire_temp_fl", 12.0, at(later))
    assert g.admit("v", "tire_pressure_fl", 235.0, at(later + 30))


def test_corners_and_vehicles_are_independent_other_metrics_pass() -> None:
    g = TpmsWakeGate()
    g.admit("a", "tire_pressure_fl", 229.0, at(0))
    g.admit("a", "tire_pressure_fr", 218.0, at(0))
    g.admit("b", "tire_pressure_fl", 229.0, at(0))
    assert g.admit("a", "tire_pressure_fl", 220.0, at(30))
    assert not g.admit("a", "tire_pressure_fr", 218.0, at(30))
    assert not g.admit("b", "tire_pressure_fl", 229.0, at(30))
    assert g.admit("a", "engine_rpm", 700.0, at(0))
