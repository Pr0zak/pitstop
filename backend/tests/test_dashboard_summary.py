"""build_summary() — the shape the outside dashboards read. Pure, no DB.
All data is synthetic."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

from pitstop.api.dashboard import build_summary

NOW = datetime(2026, 10, 22, 12, 0, tzinfo=UTC)


def _reading(v, mins_ago=1):
    at = (NOW - timedelta(minutes=mins_ago)).isoformat()
    return {"value_num": v, "time": at, "source": "bridge"}


def _vehicle(**kw):
    v = {
        "id": uuid4(), "slug": "car", "name": "Car",
        "last_seen_at": NOW - timedelta(minutes=1),
        "latest": {
            "tire_pressure_fl": _reading(241.0), "tire_pressure_fr": _reading(224.0),
            "tire_pressure_rl": _reading(241.0), "tire_pressure_rr": _reading(240.0),
            "tire_temp_fl": _reading(20.0), "coolant_temp": _reading(90.0),
            "ambient_air_temp_f": _reading(60.0), "control_module_voltage": _reading(14.123),
            "odometer_dash": _reading(80545.0),
        },
        "fuel_display_l": 37.85411784, "fuel_display_source": "cluster",
        "fuel_display_at": NOW, "tank_capacity_l": 75.70823568,
        "tire_placard_front_kpa": 241.3, "tire_placard_rear_kpa": 241.3,
        "latest_odo_km": None, "latest_odo_at": None,
    }
    v.update(kw)
    return v


def _build(**kw):
    args = dict(vehicle=_vehicle(), engine_event={"state": "on", "time": NOW},
                trip=None, fillups=[], dtcs=[], reminders={"overdue": [], "upcoming": []}, now=NOW)
    args.update(kw)
    return build_summary(**args)


def test_units_and_fuel() -> None:
    s = _build(fillups=[{"fillup_date": NOW - timedelta(days=3), "fuel_volume": 15.0,
                         "price_total": "60.00", "price_per_unit": "4.00", "mpg": 20.0},
                        {"fillup_date": NOW - timedelta(days=10), "mpg": 22.0}])
    assert s["fuel"]["gallons"] == 10.0
    assert s["fuel"]["pct"] == 50
    assert s["mpg_avg"] == 21.0
    assert s["fuel"]["range_mi"] == 210
    assert s["temps"]["coolant_f"] == 194
    assert s["temps"]["outside_f"] == 60
    assert s["battery_v"] == 14.12
    assert s["odometer_mi"] == 80545
    assert s["last_fillup"]["days_ago"] == 3


def test_tires_low_and_absent_is_none() -> None:
    s = _build()
    t = s["tires"]
    assert t["fl"]["psi"] == 35.0 and t["fl"]["temp_f"] == 68
    assert t["fr"]["temp_f"] is None  # unknown, never 0
    assert t["low"] == ["fr"]  # 2.5 psi under the highest
    assert t["warning"] is True
    assert t["placard_front_psi"] == 35


def test_running_needs_fresh_readings() -> None:
    assert _build()["running"] is True
    stale = _vehicle(last_seen_at=NOW - timedelta(hours=2))
    assert _build(vehicle=stale)["running"] is False
    assert _build(engine_event={"state": "off", "time": NOW})["running"] is False
    assert _build(engine_event=None)["running"] is None


def test_unknowns_stay_none() -> None:
    v = _vehicle(latest={}, fuel_display_l=None)
    s = _build(vehicle=v)
    assert s["fuel"]["gallons"] is None and s["fuel"]["range_mi"] is None
    assert s["odometer_mi"] is None and s["battery_v"] is None
    assert s["last_trip"] is None and s["last_fillup"] is None
    assert s["service"]["next"] is None


def test_trip_and_service() -> None:
    trip = {"started_at": NOW - timedelta(hours=1), "ended_at": NOW - timedelta(minutes=30),
            "duration_s": 1800, "distance_km": 32.18688, "fuel_used_l": 3.785411784,
            "avg_speed_kph": 64.37376}
    rem = {"overdue": [{"title": "Oil", "miles_over": 120.0, "days_over": None}], "upcoming": []}
    s = _build(trip=trip, reminders=rem, dtcs=[{"code": "P0300", "description": "Misfire"}])
    want = {"miles": 20.0, "mpg": 20.0, "minutes": 30, "avg_mph": 40}
    assert s["last_trip"] == {**s["last_trip"], **want}
    assert s["service"]["next"] == {"title": "Oil", "overdue": True, "miles": 120.0, "days": None}
    assert s["dtcs"][0]["code"] == "P0300"
