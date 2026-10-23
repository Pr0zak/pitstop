"""Insight builders in pitstop.insights. Pure, no DB. All data is synthetic."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from pitstop import insights as ins

T0 = datetime(2026, 6, 1, 12, 0, tzinfo=UTC).timestamp()
DAY = 86400.0


def _trip(i: int, *, minutes: float = 20, miles: float = 10, mpg: float = 20, temp_c: float = 20,
          **kw) -> ins.Trip:
    gal = miles / mpg
    start = T0 + i * DAY
    return ins.Trip(start=start, end=start + minutes * 60, duration_s=minutes * 60,
                    distance_km=miles * ins.KM_PER_MI, fuel_l=gal * ins.L_PER_GAL,
                    avg_kph=miles * ins.KM_PER_MI / (minutes / 60), temp_c=temp_c, **kw)


def test_trip_mpg_rejects_tiny_and_implausible() -> None:
    assert _trip(0, mpg=20).mpg == pytest.approx(20)
    assert ins.Trip(start=0, end=60, duration_s=60, distance_km=0.5, fuel_l=0.2, avg_kph=30).mpg is None
    assert _trip(0, mpg=90).mpg is None


def test_cold_weather_needs_enough_trips_then_compares_bins() -> None:
    few = [_trip(i) for i in range(5)]
    assert ins.cold_weather(few)["status"] == "learning"
    trips = [_trip(i, temp_c=-5, mpg=15) for i in range(10)] + [_trip(100 + i, temp_c=20, mpg=20) for i in range(10)]
    card = ins.cold_weather(trips)
    assert card["status"] == "info"
    assert card["value"] == 25
    labels = [b["label"] for b in card["chart"]["bars"]]
    assert labels == ["Below 32°F", "50–70°F"]
    assert "on days below 32 °F (15.0)" in card["headline"]


def test_short_trips_compares_under_10_with_over_20() -> None:
    trips = [_trip(i, minutes=6, miles=2, mpg=12) for i in range(10)]
    trips += [_trip(50 + i, minutes=30, miles=20, mpg=24) for i in range(10)]
    card = ins.short_trips(trips)
    assert card["value"] == 50
    assert "50% of all your trips" in card["detail"]


def test_fuel_rate_is_grams_per_second() -> None:
    # 1.0 g/s at a steady 60 mph: 1 * 3600 / 749.9 = 4.80 L/h = 1.268 gal/h -> 47.3 mpg.
    samples = []
    for mph in (30, 45, 60, 75):
        for k in range(60):
            samples.append(ins.Sample(t=k, spd=mph * ins.KM_PER_MI + 1, fr=1.0))
    card = ins.best_speed(samples)
    sixty = next(b for b in card["chart"]["bars"] if b["label"] == "60–65")
    assert sixty["value"] == pytest.approx(47.9, abs=0.6)
    idle = [ins.Sample(t=k, spd=0, fr=0.5, rpm=650) for k in range(40)]
    assert ins.idle_rate_lph(idle) == pytest.approx(0.5 * 3600 / 749.9)


def test_hard_events_debounced() -> None:
    # 0 -> 60 kph in 4 s (15 kph/s) then a hard stop, sampled every 2 s.
    speeds = [0, 30, 60, 60, 60, 30, 0]
    ss = [ins.Sample(t=i * 2.0, spd=v) for i, v in enumerate(speeds)]
    assert ins.hard_events(ss) == (1, 1)


def test_fuel_trim_split_flags_idle_only_lean_as_air_leak() -> None:
    ss = []
    for k in range(60):
        ss.append(ins.Sample(t=k, spd=0, rpm=700, ect=90, ltft1=8, stft1=4, ltft2=7, stft2=3))
        ss.append(ins.Sample(t=k, spd=80, load=50, ect=90, ltft1=1, stft1=0, ltft2=0, stft2=1))
    card = ins.fuel_trim_split(ss)
    assert card["status"] == "bad"
    assert "air (vacuum) leak" in card["headline"]
    healthy = [ins.Sample(t=s.t, spd=s.spd, rpm=s.rpm, load=s.load, ect=90, ltft1=1, stft1=0,
                          ltft2=-1, stft2=1) for s in ss]
    assert ins.fuel_trim_split(healthy)["status"] == "good"


def test_compensated_psi_removes_temperature() -> None:
    # A tire set to 35 psi at 68 °F reads ~37.4 psi at 104 °F (40 °C); both correct to 35.
    cold_kpa = 35 / ins.PSI_PER_KPA
    hot_abs = (cold_kpa + ins.ATM_KPA) * (40 + 273.15) / (20 + 273.15)
    assert ins.compensated_psi(cold_kpa, 20) == pytest.approx(35, abs=0.01)
    assert ins.compensated_psi(hot_abs - ins.ATM_KPA, 40) == pytest.approx(35, abs=0.01)


def _tire_rows(psi_by_hour: list[tuple[float, float]], temp_c: float = 20):
    return [(T0 + h * 3600, p / ins.PSI_PER_KPA, temp_c) for h, p in psi_by_hour]


def test_tire_leak_detects_loss_before_air_up() -> None:
    # FR loses 4 psi over 24 h, then is aired back up; the others hold.
    fr = _tire_rows([(0, 30), (8, 28.7), (16, 27.3), (24, 26), (25, 33), (26, 33)])
    steady = _tire_rows([(0, 33), (12, 33), (24, 33), (26, 33)])
    card = ins.tire_leak({"fl": steady, "fr": fr, "rl": steady, "rr": steady}, 241.3, -0.5)
    assert card["status"] == "bad"
    assert card["headline"].startswith("Front right was losing 4.0 psi a day")
    assert "before air was added" in card["headline"]
    assert card["value_unit"] == "psi/day"


def test_tire_leak_learning_then_good() -> None:
    steady = _tire_rows([(h, 33) for h in range(0, 48, 6)])
    assert ins.tire_leak({"fl": steady}, None, -0.5)["status"] == "learning"
    week = _tire_rows([(h, 33) for h in range(0, 24 * 9, 12)])
    assert ins.tire_leak({"fl": week, "fr": week}, None, -0.5)["status"] == "good"


def test_price_vs_market_and_fill_day() -> None:
    fills = [ins.Fill(t=T0 + i * 7 * DAY, gallons=15, price=3.50, market=3.70) for i in range(5)]
    card = ins.price_vs_market(fills)
    assert card["status"] == "good"
    assert card["value"] == -20
    assert "$15 saved" in card["headline"]
    assert ins.fill_day(fills, UTC)["status"] == "learning"


def test_battery_reads_daily_median() -> None:
    rest = [(T0 + d * DAY + k * 60, v) for d in range(6) for k, v in enumerate((12.5, 12.6, 11.0))]
    card = ins.battery(rest, [], [14.2])
    assert card["value"] == 12.5
    assert card["status"] == "good"


def test_order_cards_attention_worst_first() -> None:
    cards = [{"id": "a", "status": "watch"}, {"id": "b", "status": "good"}, {"id": "c", "status": "bad"}]
    out = ins.order_cards(cards)  # type: ignore[arg-type]
    assert out["attention"] == ["c", "a"]
    assert [g["id"] for g in out["groups"]] == ["fuel", "engine", "tires", "cost"]
