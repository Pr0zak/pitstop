"""Pure-maths tests for /analytics/tires (pitstop.tires). No DB."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest

from pitstop.tires import (
    KPA_PER_PSI,
    TIRE_COLD_MAX_ABOVE_AMBIENT_C,
    TIRE_SLOW_LEAK_PSI_PER_WEEK,
    assemble_tire_analytics,
    is_cold_reading,
    median,
    slope_psi_per_week,
    valid_tire_value,
)

D0 = date(2026, 10, 1)
T0 = datetime(2026, 10, 1, 12, tzinfo=UTC)


def _days(n: int) -> list[date]:
    return [D0 + timedelta(days=i) for i in range(n)]


# ── slope ────────────────────────────────────────────────────────────────


def test_slope_needs_four_samples() -> None:
    pts = [(T0 + timedelta(days=i), 210.0) for i in range(3)]
    assert slope_psi_per_week(pts) == (None, 3)


def test_slope_flat_is_zero() -> None:
    pts = [(T0 + timedelta(days=i), 210.0) for i in range(5)]
    slope, n = slope_psi_per_week(pts)
    assert n == 5
    assert slope == pytest.approx(0.0)


def test_slope_recovers_a_known_leak() -> None:
    # Lose exactly 1 psi per week, sampled daily for three weeks.
    per_day_kpa = KPA_PER_PSI / 7
    pts = [(T0 + timedelta(days=i), 220.0 - per_day_kpa * i) for i in range(21)]
    slope, n = slope_psi_per_week(pts)
    assert n == 21
    assert slope == pytest.approx(-1.0, abs=1e-6)


def test_slope_least_squares_not_endpoints() -> None:
    # Endpoints flat, middle points low: OLS slope is 0 by symmetry.
    pts = [
        (T0, 210.0),
        (T0 + timedelta(days=7), 200.0),
        (T0 + timedelta(days=14), 200.0),
        (T0 + timedelta(days=21), 210.0),
    ]
    slope, _ = slope_psi_per_week(pts)
    assert slope == pytest.approx(0.0, abs=1e-9)


def test_slope_same_timestamp_is_none() -> None:
    assert slope_psi_per_week([(T0, 200.0)] * 4) == (None, 4)


# ── cold detection helpers ───────────────────────────────────────────────


def test_heat_guard() -> None:
    # 50 °F outside = 10 °C. A tire at 15 °C is cold, 25 °C is still hot.
    assert is_cold_reading(15.0, 50.0)
    assert is_cold_reading(10.0 + TIRE_COLD_MAX_ABOVE_AMBIENT_C, 50.0)
    assert not is_cold_reading(25.0, 50.0)
    # Nothing to compare against -> the 3 h gap alone decides.
    assert is_cold_reading(None, 50.0)
    assert is_cold_reading(40.0, None)


def test_sentinels() -> None:
    assert not valid_tire_value("tire_pressure_fl", 0.0)
    assert valid_tire_value("tire_pressure_fl", 200.0)
    # Signed byte, no offset: -40 °C is a real (cold) reading; the byte's
    # extremes are not.
    assert valid_tire_value("tire_temp_fl", -40.0)
    assert not valid_tire_value("tire_temp_fl", -128.0)
    assert valid_tire_value("tire_temp_fl", 0.0)
    assert not valid_tire_value("tire_temp_fl", None)


def test_median() -> None:
    assert median([]) is None
    assert median([3.0, 1.0, 2.0]) == 2.0
    assert median([1.0, 2.0, 3.0, 4.0]) == 2.5


# ── assembly ─────────────────────────────────────────────────────────────


def test_empty_window_has_no_data_and_no_zeros() -> None:
    out = assemble_tire_analytics(_days(3), {}, [])
    assert out["has_data"] is False
    assert out["days"] == ["2026-10-01", "2026-10-02", "2026-10-03"]
    assert [a["ambient_mean_f"] for a in out["ambient"]] == [None, None, None]
    assert [c["corner"] for c in out["corners"]] == ["fl", "fr", "rl", "rr"]
    for c in out["corners"]:
        assert len(c["daily"]) == 3
        for d in c["daily"]:
            assert all(v is None for k, v in d.items() if k != "day")
        assert c["current_cold_kpa"] is None
        assert c["slope_psi_per_week"] is None
        assert c["slope_samples"] == 0
        assert c["possible_slow_leak"] is False
        assert c["warmup_median_psi"] is None
    assert out["low_corners"] == []


def test_assembly_cold_warmup_low_and_leak() -> None:
    days = _days(5)
    daily = {}
    cold = []
    for i, d in enumerate(days):
        # FL leaks 1 psi/day; FR holds steady at 210 kPa.
        fl_cold = 220.0 - KPA_PER_PSI * i
        daily[(d, "tire_pressure_fl")] = (fl_cold + 10, fl_cold, fl_cold + 20)
        daily[(d, "tire_temp_fl")] = (25.0, 15.0, 35.0)
        daily[(d, "ambient_air_temp_f")] = (60.0, 55.0, 65.0)
        t = datetime(d.year, d.month, d.day, 13, tzinfo=UTC)
        cold.append((t, "fl", fl_cold, 15.0, 60.0))
        cold.append((t, "fr", 210.0, 15.0, 60.0))
        # A hot "first reading after a gap" (arriving home): rejected.
        cold.append((t + timedelta(hours=6), "fr", 230.0, 40.0, 60.0))
    # Day 2 has no FL data at all -> nulls on that day, not zeros.
    del daily[(days[2], "tire_pressure_fl")]
    del daily[(days[2], "tire_temp_fl")]

    out = assemble_tire_analytics(days, daily, cold)
    assert out["has_data"] is True
    fl, fr, rl, _rr = out["corners"]

    assert fl["daily"][2]["pressure_mean_kpa"] is None
    assert fl["daily"][2]["warmup_psi"] is None
    assert fl["daily"][0]["cold_pressure_kpa"] == pytest.approx(220.0)
    # Warm-up = day max - day cold = 20 kPa ≈ 2.9 psi.
    assert fl["warmup_median_psi"] == pytest.approx(20 * 0.145038, abs=0.01)
    assert fl["slope_samples"] == 5
    assert fl["slope_psi_per_week"] == pytest.approx(-7.0, abs=0.01)
    assert fl["possible_slow_leak"] is True
    assert fl["slope_psi_per_week"] <= TIRE_SLOW_LEAK_PSI_PER_WEEK

    # Hot readings were dropped: FR keeps only its 210 kPa cold readings.
    assert [c["pressure_kpa"] for c in fr["cold_readings"]] == [210.0] * 5
    assert fr["slope_psi_per_week"] == pytest.approx(0.0)
    assert fr["possible_slow_leak"] is False

    assert rl["current_cold_kpa"] is None
    # Latest cold: FL 220 - 4 psi ≈ 192.4 kPa vs FR 210 (2.5 psi) -> FL low.
    assert out["low_corners"] == ["fl"]
    assert out["ambient"][0]["ambient_mean_f"] == 60.0


def test_first_cold_reading_of_the_day_wins() -> None:
    d = D0
    cold = [
        (datetime(2026, 10, 1, 20, tzinfo=UTC), "rr", 205.0, None, None),
        (datetime(2026, 10, 1, 8, tzinfo=UTC), "rr", 200.0, None, None),
    ]
    out = assemble_tire_analytics([d], {(d, "tire_pressure_rr"): (210.0, 200.0, 215.0)}, cold)
    rr = out["corners"][3]
    assert rr["daily"][0]["cold_pressure_kpa"] == 200.0
    assert rr["current_cold_kpa"] == 205.0  # latest cold reading overall


def test_placard_verdict_rides_on_current_cold_pressure() -> None:
    placard = 35 * KPA_PER_PSI
    cold = [
        (T0, "fl", 35 * KPA_PER_PSI, None, None),
        (T0, "fr", 31 * KPA_PER_PSI, None, None),  # 4 psi under: below spec
        (T0, "rl", 26 * KPA_PER_PSI, None, None),  # >= 25 % under
    ]
    out = assemble_tire_analytics(
        [D0], {}, cold, placard_front_kpa=placard, placard_rear_kpa=placard
    )
    fl, fr, rl, rr = out["corners"]
    assert out["placard_front_kpa"] == placard
    assert out["placard_rear_kpa"] == placard
    assert fl["below_placard_psi"] == 0.0 and fl["below_spec"] is False
    assert fr["below_placard_psi"] == 4.0 and fr["below_spec"] is True
    assert fr["under_inflated"] is False
    assert rl["under_inflated"] is True
    assert rr["below_placard_psi"] is None and rr["under_inflated"] is False


def test_no_placard_no_verdicts() -> None:
    out = assemble_tire_analytics([D0], {}, [(T0, "fl", 150.0, None, None)])
    assert out["placard_front_kpa"] is None
    fl = out["corners"][0]
    assert fl["placard_kpa"] is None and fl["below_placard_psi"] is None
    assert fl["under_inflated"] is False and fl["below_spec"] is False


# ── air added ────────────────────────────────────────────────────────────


def test_fill_while_running_estimates_cold_and_restarts_trend() -> None:
    days = _days(4)
    psi = KPA_PER_PSI
    cold = [
        (datetime(d.year, d.month, d.day, 11, tzinfo=UTC), "fr", 29.0 * psi, 15.0, 59.0)
        for d in days
    ]
    fill = datetime(days[3].year, days[3].month, days[3].day, 19, tzinfo=UTC)
    # Newest reading: 38 psi hot (40 °C) with 68 °F outside, after the fill.
    latest = {"fr": (fill + timedelta(hours=3), 38.0 * psi, 40.0, 68.0)}
    daily = {(days[3], "tire_pressure_fr"): (34.0 * psi, 29.0 * psi, 38.0 * psi)}
    out = assemble_tire_analytics(days, daily, cold, air_ups={"fr": [fill]}, latest=latest)
    fr = out["corners"][1]
    assert fr["aired_up_at"] == fill.isoformat()
    assert fr["current_cold_estimated"] is True
    # 38 psi at 40 °C corrected to 20 °C ≈ 34.6 psi: not the pre-fill 29.
    assert fr["current_cold_kpa"] / psi == pytest.approx(34.6, abs=0.05)
    assert fr["current_cold_at"] == latest["fr"][0].isoformat()
    # Every cold reading predates the fill: no trend yet.
    assert fr["slope_psi_per_week"] is None
    assert fr["slope_samples"] == 0
    # The fill day's max is the new pressure, not warm-up.
    assert fr["daily"][3]["warmup_psi"] is None


def test_fill_without_temperatures_leaves_cold_unknown() -> None:
    d = D0
    fill = datetime(2026, 10, 1, 19, tzinfo=UTC)
    cold = [(datetime(2026, 10, 1, 11, tzinfo=UTC), "rr", 200.0, None, None)]
    out = assemble_tire_analytics(
        [d], {}, cold, air_ups={"rr": [fill]},
        latest={"rr": (fill + timedelta(hours=1), 260.0, None, None)},
    )
    rr = out["corners"][3]
    assert rr["current_cold_kpa"] is None
    assert rr["current_cold_estimated"] is False


def test_fill_with_the_car_off_is_found_from_cold_readings() -> None:
    days = _days(6)
    psi = KPA_PER_PSI
    cold = []
    for i, d in enumerate(days):
        # 30 psi cold, then 35 psi from day 3 on (air added while parked).
        p = 30.0 if i < 3 else 35.0
        cold.append((datetime(d.year, d.month, d.day, 11, tzinfo=UTC), "rl", p * psi, 20.0, 68.0))
    out = assemble_tire_analytics(days, {}, cold)
    rl = out["corners"][2]
    assert rl["aired_up_at"] == cold[3][0].isoformat()
    assert rl["current_cold_estimated"] is False
    assert rl["current_cold_kpa"] / psi == pytest.approx(35.0, abs=0.05)
    # Trend only over the 3 readings since the fill: not a +5 psi rise.
    assert rl["slope_psi_per_week"] is None
    assert rl["slope_samples"] == 3


def test_cold_morning_after_warm_afternoon_is_not_a_fill() -> None:
    # 31 psi at 23 °C, then 32.2 psi at 12 °C: corrected, that is the same
    # air (warm-to-cold), and the 30.5 -> 32.2 raw rise is under 2 psi anyway.
    psi = KPA_PER_PSI
    cold = [
        (datetime(2026, 10, 1, 20, tzinfo=UTC), "fl", 31.0 * psi, 23.0, 75.0),
        (datetime(2026, 10, 2, 11, tzinfo=UTC), "fl", 30.5 * psi, 12.0, 52.0),
    ]
    out = assemble_tire_analytics(_days(2), {}, cold)
    assert out["corners"][0]["aired_up_at"] is None


def test_same_reading_on_a_colder_morning_is_not_a_fill() -> None:
    # 2026-10-23 RR: 30 psi at 24 °C, then 30 psi at 10 °C. Corrected that is
    # a 2.2 psi "rise", but nothing went in.
    psi = KPA_PER_PSI
    cold = [
        (datetime(2026, 10, 1, 20, tzinfo=UTC), "rr", 30.0 * psi, 24.0, 75.0),
        (datetime(2026, 10, 2, 11, tzinfo=UTC), "rr", 30.0 * psi, 10.0, 50.0),
    ]
    out = assemble_tire_analytics(_days(2), {}, cold)
    assert out["corners"][3]["aired_up_at"] is None
