"""Insight builders in pitstop.insights. Pure, no DB. All data is synthetic."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from pitstop import insights as ins
from pitstop.insight_features import day_features, trip_features

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
    feats = trip_features(samples, {})
    bins = {int(k): v for k, v in feats["speed_bins"].items()}
    card = ins.best_speed(bins)
    sixty = next(b for b in card["chart"]["bars"] if b["label"] == "60–65")
    assert sixty["value"] == pytest.approx(47.9, abs=0.6)
    trips = [_trip(i, minutes=20, idle_s=600) for i in range(3)]
    idle = ins.idle_cost(trips, 0.5, 4.0)
    # 3 x 10 min = 0.5 h idle x 0.634 gal/h = 0.32 gal = $1.27
    assert idle["value"] == 1
    assert "0.63 gal an hour" in idle["detail"]


def test_hard_events_debounced() -> None:
    # 0 -> 60 kph in 4 s (15 kph/s) then a hard stop, sampled every 2 s.
    speeds = [0, 30, 60, 60, 60, 30, 0]
    ss = [ins.Sample(t=i * 2.0, spd=v) for i, v in enumerate(speeds)]
    assert ins.hard_events(ss) == (1, 1)


def test_compensated_psi_removes_temperature() -> None:
    # A tire set to 35 psi at 68 °F reads ~37.4 psi at 104 °F (40 °C); both correct to 35.
    cold_kpa = 35 / ins.PSI_PER_KPA
    hot_abs = (cold_kpa + ins.ATM_KPA) * (40 + 273.15) / (20 + 273.15)
    assert ins.compensated_psi(cold_kpa, 20) == pytest.approx(35, abs=0.01)
    assert ins.compensated_psi(hot_abs - ins.ATM_KPA, 40) == pytest.approx(35, abs=0.01)


def _hourly(psi_by_hour: list[tuple[float, float]]) -> list[tuple[float, float]]:
    return [(T0 + h * 3600, p) for h, p in psi_by_hour]


def test_tire_leak_detects_loss_before_air_up() -> None:
    # FR loses 4 psi over 24 h, then is aired back up; the others hold.
    fr = _hourly([(0, 30), (8, 28.7), (16, 27.3), (24, 26), (25, 33), (26, 33)])
    steady = _hourly([(0, 33), (12, 33), (24, 33), (26, 33)])
    card = ins.tire_leak({"fl": steady, "fr": fr, "rl": steady, "rr": steady}, 241.3, -0.5)
    assert card["status"] == "bad"
    assert card["headline"].startswith("Front right was losing 4.0 psi a day")
    assert "before air was added" in card["headline"]
    assert card["value_unit"] == "psi/day"


def test_tire_leak_learning_then_good() -> None:
    steady = _hourly([(h, 33) for h in range(0, 48, 6)])
    assert ins.tire_leak({"fl": steady}, None, -0.5)["status"] == "learning"
    week = _hourly([(h, 33) for h in range(0, 24 * 9, 12)])
    assert ins.tire_leak({"fl": week, "fr": week}, None, -0.5)["status"] == "good"


def test_day_features_correct_tire_pressure_hourly() -> None:
    # Same air, read cold (20 °C) and hot (40 °C) in different hours: both 35 psi corrected.
    cold_kpa = 35 / ins.PSI_PER_KPA
    hot_kpa = (cold_kpa + ins.ATM_KPA) * (40 + 273.15) / (20 + 273.15) - ins.ATM_KPA
    f = day_features([], rest_v=[12.5, 14.1], run_v=[14.2], misfire_max={1: 0.0}, atf_c=[50.0, 105.0],
                     tires={"fr": [(T0, cold_kpa, 20.0), (T0 + 7200, hot_kpa, 40.0)]}, gps_pairs=None)
    assert [p[1] for p in f["tires"]["fr"]] == [35.0, 35.0]
    assert f["rest_v"] == [12.5, 1]  # 14.1 V is a running reading, not rest
    assert f["atf"] == {"max": 221.0, "bins": [1, 0, 0, 0, 1]}


def test_trip_features_warmup_and_hard_events() -> None:
    ss = [ins.Sample(t=i * 2.0, spd=0, ect=20 + i) for i in range(70)]  # reaches 80 °C at 120 s
    f = trip_features(ss, {"fl": [240.0, 242.0]})
    assert f["warmup_min"] == 2.0
    assert f["tire_kpa"] == {"fl": [241.0, 2]}
    assert f["hard_acc"] == 0 and f["n"] == 70


def test_fuel_trim_split_combines_days() -> None:
    day = {"trim": {"1idle": [9.0, 40], "1cruise": [1.0, 40], "2idle": [8.0, 40], "2cruise": [0.5, 40]}}
    card = ins.fuel_trim_split([day, day])
    assert card["status"] == "watch"
    assert "air (vacuum) leak" in card["headline"]
    assert ins.fuel_trim_split([day])["status"] == "learning"


def test_price_vs_market_and_fill_day() -> None:
    fills = [ins.Fill(t=T0 + i * 7 * DAY, gallons=15, price=3.50, market=3.70) for i in range(5)]
    card = ins.price_vs_market(fills)
    assert card["status"] == "good"
    assert card["value"] == -20
    assert "$15 saved" in card["headline"]
    assert ins.fill_day(fills, UTC)["status"] == "learning"


def test_battery_judges_last_14_days() -> None:
    per_day = {f"2026-06-{d:02d}": 12.6 for d in range(1, 21)}
    per_day.update({f"2026-07-{d:02d}": 12.1 for d in range(1, 15)})
    card = ins.battery(per_day, 14.2)
    assert card["value"] == 12.1
    assert card["status"] == "watch"


def test_order_cards_attention_worst_first() -> None:
    cards = [{"id": "a", "status": "watch"}, {"id": "b", "status": "good"}, {"id": "c", "status": "bad"}]
    out = ins.order_cards(cards)  # type: ignore[arg-type]
    assert out["attention"] == ["c", "a"]
    assert all("window" in c for c in out["insights"])
    assert [g["id"] for g in out["groups"]] == ["fuel", "engine", "tires", "cost"]
