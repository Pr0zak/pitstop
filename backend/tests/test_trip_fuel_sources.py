"""Unit tests for the trip fuel-used integration (workers/trip_stats.py).

Pure functions, no DB needed — the DB only ever hands these helpers a bare
``SUM(value_num * dt)`` integral in grams, and every unit conversion lives
here. ``compute_trip_stats`` itself is DB-backed and covered (skipped
without Postgres) by ``test_trip_stats.py``.

The thing under test is the fuel-vs-air distinction: OBD PID 0x9D
(``engine_fuel_rate``) already reports grams of FUEL per second, while MAF
reports grams of AIR per second. Applying the 14.7:1 stoichiometric ratio to
the wrong one is a 14.7x error in every MPG figure on the site.
"""

from __future__ import annotations

import pytest

from pitstop.workers.trip_stats import (
    FUEL_SOURCES,
    GASOLINE_DENSITY_G_PER_L,
    MIN_COVERAGE_FRACTION,
    MIN_CREDIBLE_FUEL_L,
    STOICH_AIR_PER_FUEL_G_PER_G,
    FlowSpecies,
    MassFlowFuelSource,
    liters_from_flow_integral,
    resolve_fuel_used_l,
)
from pitstop.workers.wican_aliases import normalise

FUEL_RATE = MassFlowFuelSource("engine_fuel_rate", FlowSpecies.FUEL)
AIRFLOW = MassFlowFuelSource("maf_air_flow", FlowSpecies.AIR)


# --- unit conventions -----------------------------------------------------


def test_fuel_species_does_not_divide_by_stoich() -> None:
    # One tank-worth of grams of FUEL is exactly density-many liters.
    assert liters_from_flow_integral(GASOLINE_DENSITY_G_PER_L, FUEL_RATE) == 1.0
    # And explicitly: no 14.7 anywhere in the path.
    assert liters_from_flow_integral(749.9, FUEL_RATE) != pytest.approx(
        1.0 / STOICH_AIR_PER_FUEL_G_PER_G
    )


def test_air_species_does_divide_by_stoich() -> None:
    # The same grams of AIR only burned 1/14.7 as much fuel.
    assert liters_from_flow_integral(
        GASOLINE_DENSITY_G_PER_L, AIRFLOW
    ) == pytest.approx(1.0 / STOICH_AIR_PER_FUEL_G_PER_G)


def test_air_and_fuel_species_differ_by_exactly_stoich() -> None:
    grams = 12_345.6
    fuel = liters_from_flow_integral(grams, FUEL_RATE)
    air = liters_from_flow_integral(grams, AIRFLOW)
    assert fuel / air == pytest.approx(STOICH_AIR_PER_FUEL_G_PER_G)


def test_conversion_factors_are_the_measured_ones() -> None:
    assert GASOLINE_DENSITY_G_PER_L == 749.9
    assert STOICH_AIR_PER_FUEL_G_PER_G == 14.7
    assert FUEL_RATE.fuel_grams_per_flow_gram == 1.0
    assert AIRFLOW.fuel_grams_per_flow_gram == pytest.approx(1 / 14.7)


def test_idle_fuel_rate_matches_hand_arithmetic() -> None:
    # Live-probed idle: PID 0x9D ≈ 0.40 g/s of fuel. Ten minutes of idling
    # is 240 g -> ~0.32 L. (Cross-checked against the simultaneous MAF
    # reading of 5.59 g/s air, which is 0.380 g/s of fuel at 14.7:1.)
    assert liters_from_flow_integral(0.40 * 600, FUEL_RATE) == pytest.approx(
        0.32, abs=0.01
    )
    assert liters_from_flow_integral(5.59 * 600, AIRFLOW) == pytest.approx(
        0.304, abs=0.01
    )


def test_missing_integral_is_zero_liters() -> None:
    assert liters_from_flow_integral(None, FUEL_RATE) == 0.0
    assert liters_from_flow_integral(None, AIRFLOW) == 0.0
    assert liters_from_flow_integral(0, FUEL_RATE) == 0.0


# --- source preference order ---------------------------------------------


def test_source_order_prefers_direct_fuel_rate_then_maf() -> None:
    assert [s.metric for s in FUEL_SOURCES] == [
        "engine_fuel_rate",
        "maf_air_flow",
        "maf_sensor_a",
    ]


def test_only_the_fuel_rate_source_is_fuel_species() -> None:
    by_metric = {s.metric: s.species for s in FUEL_SOURCES}
    assert by_metric["engine_fuel_rate"] is FlowSpecies.FUEL
    assert by_metric["maf_air_flow"] is FlowSpecies.AIR
    assert by_metric["maf_sensor_a"] is FlowSpecies.AIR


# Every trip in these tests is a nominal 1000 s window, and by default each
# source is assumed to have sampled the whole of it — coverage is varied
# explicitly only in the coverage tests below.
TRIP_S = 1000.0


def _recorder(
    integrals: dict[str, float | None],
    coverage_s: dict[str, float] | None = None,
):
    """Fake ``integrate_grams`` that records which metrics were queried.

    Returns ``(grams, covered_s)`` per the real contract; ``coverage_s``
    defaults to full coverage of [TRIP_S] for any metric with an integral.
    """
    queried: list[str] = []

    async def integrate(metric: str) -> tuple[float | None, float]:
        queried.append(metric)
        covered = (coverage_s or {}).get(metric, TRIP_S)
        return integrals.get(metric), covered

    return integrate, queried


async def test_fuel_rate_wins_and_short_circuits_the_maf_queries() -> None:
    # All three sources present. The direct fuel rate must win, and the MAF
    # sources must never even be queried (never summed together).
    integrate, queried = _recorder(
        {
            "engine_fuel_rate": 749.9,  # -> 1.0 L
            "maf_air_flow": 749.9 * 14.7,  # would also be 1.0 L
            "maf_sensor_a": 749.9 * 14.7,
        }
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.0)
    assert queried == ["engine_fuel_rate"]


async def test_falls_through_to_maf_air_flow_when_fuel_rate_silent() -> None:
    integrate, queried = _recorder(
        {"engine_fuel_rate": None, "maf_air_flow": 749.9 * 14.7}
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.0)
    assert queried == ["engine_fuel_rate", "maf_air_flow"]


async def test_falls_through_to_maf_sensor_a_last() -> None:
    integrate, queried = _recorder(
        {"engine_fuel_rate": 0, "maf_air_flow": 0, "maf_sensor_a": 749.9 * 14.7}
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.0)
    assert queried == ["engine_fuel_rate", "maf_air_flow", "maf_sensor_a"]


async def test_trivial_fuel_rate_does_not_block_a_real_maf_reading() -> None:
    # A dribble of 0x9D samples that integrates below the credibility floor
    # must not win over a MAF source that has real data.
    below_floor_grams = (MIN_CREDIBLE_FUEL_L / 2) * GASOLINE_DENSITY_G_PER_L
    integrate, queried = _recorder(
        {
            "engine_fuel_rate": below_floor_grams,
            "maf_air_flow": 749.9 * 14.7,
        }
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.0)
    assert queried == ["engine_fuel_rate", "maf_air_flow"]


async def test_no_source_answers_returns_none() -> None:
    integrate, queried = _recorder({})
    assert await resolve_fuel_used_l(integrate, TRIP_S) is None
    assert queried == [s.metric for s in FUEL_SOURCES]


async def test_credibility_floor_is_where_it_says_it_is() -> None:
    # Just over the floor is kept; just under it falls through to the next
    # source (and here there is none, so None).
    at_floor_grams = MIN_CREDIBLE_FUEL_L * GASOLINE_DENSITY_G_PER_L
    just_over, _ = _recorder({"engine_fuel_rate": at_floor_grams * 1.001})
    assert await resolve_fuel_used_l(just_over, TRIP_S) == pytest.approx(
        MIN_CREDIBLE_FUEL_L, rel=1e-2
    )
    just_under, _ = _recorder({"engine_fuel_rate": at_floor_grams * 0.999})
    assert await resolve_fuel_used_l(just_under, TRIP_S) is None


# --- trip-window coverage -------------------------------------------------


async def test_source_covering_a_sliver_of_the_trip_does_not_win() -> None:
    # The live failure this guards: the WiCAN is WiFi-only, so it publishes
    # engine_fuel_rate for the ~120 s the car is still in the driveway and
    # then goes silent, while the phone's BLE stream covers the whole trip.
    # The sliver integrates to a small POSITIVE number that clears the
    # magnitude floor, so without a coverage gate it wins and reports ~0.06 L
    # for a 23-minute drive (~530 MPG).
    driveway_grams = 0.35 * 120  # 42 g -> 0.056 L, over MIN_CREDIBLE_FUEL_L
    assert liters_from_flow_integral(driveway_grams, FUEL_RATE) > MIN_CREDIBLE_FUEL_L
    integrate, queried = _recorder(
        {"engine_fuel_rate": driveway_grams, "maf_sensor_a": 749.9 * 14.7},
        coverage_s={"engine_fuel_rate": 120.0, "maf_sensor_a": 1397.0},
    )
    assert await resolve_fuel_used_l(integrate, 1397.0) == pytest.approx(1.0)
    assert queried == ["engine_fuel_rate", "maf_air_flow", "maf_sensor_a"]


async def test_no_well_covered_source_defers_to_the_fuel_level_fallback() -> None:
    # Nothing covered the trip => None, which is what hands the trip to the
    # fuel-level-delta fallback. Returning the sliver instead would suppress
    # that fallback (it only runs below MIN_CREDIBLE_FUEL_L).
    integrate, _ = _recorder(
        {"engine_fuel_rate": 749.9},
        coverage_s={"engine_fuel_rate": 10.0},
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) is None


async def test_coverage_gate_is_where_it_says_it_is() -> None:
    grams = {"engine_fuel_rate": 749.9}
    covered = MIN_COVERAGE_FRACTION * TRIP_S * 1.01
    just_over, _ = _recorder(grams, {"engine_fuel_rate": covered})
    # Accepted — and extrapolated across the seconds it missed, so the
    # figure is the raw 1.0 L scaled by the window it actually covered.
    assert await resolve_fuel_used_l(just_over, TRIP_S) == pytest.approx(
        1.0 * TRIP_S / covered
    )
    just_under, _ = _recorder(
        grams, {"engine_fuel_rate": MIN_COVERAGE_FRACTION * TRIP_S * 0.99}
    )
    assert await resolve_fuel_used_l(just_under, TRIP_S) is None


async def test_coverage_is_judged_against_activity_not_wall_clock() -> None:
    # The denominator `compute_trip_stats` passes is `obd_active_seconds`, NOT
    # ended_at - started_at. A drive sealed by the phone's BLE-lost watchdog
    # carries a ~3-minute silent tail inside the trip window; a 150 s drive
    # then has a 330 s wall clock but only ~150 s of OBD activity. Judged on
    # wall clock the fuel stream scores 45% and is thrown away even though it
    # covered every second the car was reporting.
    drive_s = 150.0
    integrate, _ = _recorder(
        {"engine_fuel_rate": 749.9}, {"engine_fuel_rate": drive_s}
    )
    assert await resolve_fuel_used_l(integrate, drive_s) == pytest.approx(1.0)
    # Same numbers against the inflated wall clock: rejected. This is the
    # comparison the caller must not make.
    wall_clock_s = drive_s + 180.0
    integrate_wall, _ = _recorder(
        {"engine_fuel_rate": 749.9}, {"engine_fuel_rate": drive_s}
    )
    assert await resolve_fuel_used_l(integrate_wall, wall_clock_s) is None


async def test_zero_length_window_falls_back_to_the_magnitude_gate() -> None:
    # A degenerate trip window can't be judged on coverage; don't reject
    # everything just because the fraction is undefined.
    integrate, _ = _recorder({"engine_fuel_rate": 749.9}, {"engine_fuel_rate": 0.0})
    assert await resolve_fuel_used_l(integrate, 0.0) == pytest.approx(1.0)


# --- ingest wiring --------------------------------------------------------


def test_wican_std_decoder_name_is_not_aliased_onto_the_preferred_source() -> None:
    # PID 0x9D IS supported by this PCM, but the WiCAN's STD-PID decoder
    # publishes a constant 0 for it over MQTT (11,586 rows, min = max = 0,
    # 2026-05-08..2026-05-23) — the same decoder-bug class as 0x68 IAT.
    # Aliasing that name onto `engine_fuel_rate` would interleave 1 Hz of
    # zeros with the working stream and roughly halve every fuel integral,
    # while still clearing both gates. It must stay quarantined under its
    # own hex-prefixed name.
    assert normalise("9D-EngineFuelRate") == "9D-EngineFuelRate"
    assert normalise("9D-EngineFuelRate") != FUEL_SOURCES[0].metric
    # 0x68 IAT is quarantined for the identical reason — same rule.
    assert normalise("68-IntakeAirTempSens1") == "68-IntakeAirTempSens1"
    # The working path is a WiCAN *custom* PID published under the canonical
    # name directly, which needs no alias and must survive normalise().
    assert normalise("engine_fuel_rate") == FUEL_SOURCES[0].metric


# --- coverage extrapolation ----------------------------------------------
#
# The integral drops every gap of 60 s or more, so seconds a source did not
# sample contribute exactly zero fuel — even though the engine was running,
# which is precisely what coverage_window_s (OBD-active seconds, unioned
# across metrics) establishes. Scaling by the shortfall replaces a value
# known to be wrong with the trip's own average rate.


async def test_partial_coverage_is_extrapolated_to_the_active_window() -> None:
    # Sampled 800 of 1000 OBD-active seconds: the 1.0 L measured stands for
    # 80 % of the burn, so the trip used ~1.25 L.
    integrate, _ = _recorder(
        {"engine_fuel_rate": 749.9}, {"engine_fuel_rate": 800.0}
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.25)


async def test_full_coverage_is_left_alone() -> None:
    integrate, _ = _recorder(
        {"engine_fuel_rate": 749.9}, {"engine_fuel_rate": TRIP_S}
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.0)


async def test_extrapolation_never_scales_down() -> None:
    """A source sampling denser than the unioned window is not evidence of
    less fuel, so the factor floors at 1.0 rather than shrinking the
    integral."""
    integrate, _ = _recorder(
        {"engine_fuel_rate": 749.9}, {"engine_fuel_rate": TRIP_S * 1.5}
    )
    assert await resolve_fuel_used_l(integrate, TRIP_S) == pytest.approx(1.0)


async def test_extrapolation_is_bounded_by_the_coverage_gate() -> None:
    """MIN_COVERAGE_FRACTION is what stops this running away: anything below
    it is rejected outright, so the scale factor can never exceed 1/that."""
    barely_covered = MIN_COVERAGE_FRACTION * TRIP_S * 1.001
    integrate, _ = _recorder({"engine_fuel_rate": 749.9}, {"engine_fuel_rate": barely_covered})
    worst_case = await resolve_fuel_used_l(integrate, TRIP_S)
    assert worst_case is not None
    assert worst_case <= 1.0 / MIN_COVERAGE_FRACTION


async def test_unknown_window_leaves_the_integral_untouched() -> None:
    """A zero-length window can't be judged on coverage — the magnitude gate
    stands alone there, and nothing is extrapolated."""
    integrate, _ = _recorder({"engine_fuel_rate": 749.9}, {"engine_fuel_rate": 10.0})
    assert await resolve_fuel_used_l(integrate, 0.0) == pytest.approx(1.0)
