"""Unit tests for the trip-detail sample allowlist + bucket sizing.

Pure-function / pure-constant — no DB. Guards two things that have bitten
before: the metric list drifting between the Timescale query and its
fallback, and the per-metric sample budget silently overshooting once the
list got longer.
"""

from __future__ import annotations

import re
from pathlib import Path

from pitstop.api.trips import (
    _TRIP_SAMPLE_EXCLUDED,
    _TRIP_SAMPLE_METRICS,
    _TRIP_SAMPLE_TARGET_POINTS,
    _bucket_seconds,
)

_TRIPS_SRC = Path(__file__).resolve().parents[1] / "src/pitstop/api/trips.py"


def test_core_drive_metrics_still_present() -> None:
    """The original ten — dropping one silently blanks a chart series."""
    for metric in (
        "vehicle_speed",
        "engine_rpm",
        "coolant_temp",
        "throttle_position",
        "maf_air_flow",
        "manifold_pressure",
        "engine_load",
        "control_module_voltage",
        "fuel_level",
        "intake_air_temp",
    ):
        assert metric in _TRIP_SAMPLE_METRICS


def test_fuel_and_exhaust_metrics_added() -> None:
    """Metrics recovered by the direct-capture work (custom PIDs 0x9D/0x9E)
    plus the Mode 01 emissions block now aliased to canonical names."""
    for metric in (
        "engine_fuel_rate",
        "engine_exhaust_flow",
        "catalyst_temp_b1",
        "catalyst_temp_b2",
        "commanded_afr_ratio",
        "o2_s1_lambda",
        "fuel_rail_pressure",
    ):
        assert metric in _TRIP_SAMPLE_METRICS


def test_odometer_is_charted_and_offset_corrected() -> None:
    """Odometer is the one charted metric whose raw value contradicts the
    car: this PCM reads a fixed distance above the dash cluster.

    Charting it therefore only makes sense together with the correction, so
    this asserts both halves — the metric being on the list AND get_trip
    subtracting `odometer_offset_km` before returning it. Shipping the first
    without the second puts the timeline ~50 km off both the dash and the
    fillup form (which already corrects, in FillupModal.vue).
    """
    assert "odometer" in _TRIP_SAMPLE_METRICS
    src = _TRIPS_SRC.read_text()
    assert "odometer_offset_km" in src, "offset never read from vehicles"
    assert "_apply_odo_offset" in src, "samples not offset-corrected"
    # The boundary stats are the same quantity as the chart line and must
    # not disagree with it.
    assert "float(odo_start) - odo_offset" in src
    assert "float(odo_end) - odo_offset" in src


def test_low_signal_metrics_stay_excluded() -> None:
    """Constants, decoder-dead fields, and near-duplicates of a series
    already on the chart. Each one costs a full metric's worth of payload
    to draw something the user cannot read anything off."""
    for metric in (
        # Never lands under this name (see wican_aliases.py) and the
        # underlying field is a flat 2.000 V.
        "o2_s1_voltage",
        # Constant 0.00 on this PCM.
        "commanded_egr",
        # The dash odometer duplicates the offset-corrected `odometer`; run
        # time is a slope-1 line that the x axis already draws.
        "odometer_dash",
        "run_time_since_start",
        # Post-cat O2 block; o2_s2_stft is the 99 % "not used" sentinel.
        "o2_s2_voltage",
        "o2_s2_stft",
        "o2_s6_voltage",
        "o2_s6_stft",
        "friction_torque_pct",
        # Same pedal as throttle_position, three more times.
        "throttle_pos_b",
        "throttle_pos_d",
        "throttle_pos_e",
    ):
        assert metric not in _TRIP_SAMPLE_METRICS
        assert metric in _TRIP_SAMPLE_EXCLUDED


def test_allowlist_and_denylist_are_disjoint_and_unique() -> None:
    assert len(set(_TRIP_SAMPLE_METRICS)) == len(_TRIP_SAMPLE_METRICS)
    assert len(set(_TRIP_SAMPLE_EXCLUDED)) == len(_TRIP_SAMPLE_EXCLUDED)
    assert not set(_TRIP_SAMPLE_METRICS) & set(_TRIP_SAMPLE_EXCLUDED)


def test_metric_list_is_not_inlined_in_sql() -> None:
    """The allowlist lives in exactly one place.

    Both sample queries (Timescale `time_bucket` and the vanilla-Postgres
    fallback) must bind the constant via `metric = ANY(...)`; an inline
    `metric IN ('...')` in either arm is how the two drifted apart before.
    """
    src = _TRIPS_SRC.read_text()
    assert "metric IN (" not in src
    assert src.count("metric = ANY($5::text[])") == 2


def test_bucket_seconds_holds_the_per_metric_budget() -> None:
    """Every duration must yield <= _TRIP_SAMPLE_TARGET_POINTS buckets.

    The old `duration_s // 500` under-rounded: 999 s fell into 1 s buckets
    and produced 999 points per metric.
    """
    for duration_s in (1, 2, 60, 499, 500, 501, 999, 1000, 1200, 3600, 86_400):
        bucket_s = _bucket_seconds(duration_s)
        assert bucket_s >= 1
        points = -(-duration_s // bucket_s)  # ceil
        assert points <= _TRIP_SAMPLE_TARGET_POINTS, (duration_s, bucket_s, points)


def test_bucket_seconds_specific_values() -> None:
    # Short trips keep 1 s resolution.
    assert _bucket_seconds(1) == 1
    assert _bucket_seconds(500) == 1
    # The regression case: 999 s used to bucket at 1 s (999 points).
    assert _bucket_seconds(999) == 2
    assert _bucket_seconds(1000) == 2
    # A 20 minute trip.
    assert _bucket_seconds(1200) == 3
    # An hour.
    assert _bucket_seconds(3600) == 8


def test_bucket_seconds_degenerate_inputs() -> None:
    """A zero/negative duration (ended_at == started_at, or clock skew on a
    phone-sourced trip) must not divide by zero or return 0."""
    assert _bucket_seconds(0) == 1
    assert _bucket_seconds(-30) == 1
    assert _bucket_seconds(600, target_points=0) == 1


def test_tire_and_brake_metrics_charted() -> None:
    """TPMS (all four corners, pressure + temp) and brake pressure."""
    for corner in ("fl", "fr", "rl", "rr"):
        assert f"tire_pressure_{corner}" in _TRIP_SAMPLE_METRICS
        assert f"tire_temp_{corner}" in _TRIP_SAMPLE_METRICS
    assert "brake_pressure" in _TRIP_SAMPLE_METRICS


def test_sparse_tire_readings_get_their_own_bucket() -> None:
    """Phone-polled tires arrive every ~30 s. As long as the bucket is no wider than
    that, each reading survives as its own sample row (and a bucket with no
    reading emits no row, so dropouts stay gaps). Holds for trips up to
    30 s * the point budget (~4 h)."""
    assert _bucket_seconds(30 * _TRIP_SAMPLE_TARGET_POINTS) <= 30
    assert _bucket_seconds(2 * 3600) <= 30


def test_payload_ceiling_is_bounded() -> None:
    """Worst-case row count for the whole response, stated as a test so a
    future list extension has to look at the number it is moving.

    Raised from 10 000 to 15 000 for the eight TPMS series + brake pressure.
    Phone-polled TPMS (~30 s) only reaches the per-metric budget on drives
    over ~4 h, but the WiCAN publishes it every ~2.5 s, so a WiCAN-covered
    trip can spend the full budget on all nine new series.

    Raised to 22 000 for the sixteen Mode 22 / trim / EVAP series (ATF,
    gear, cylinders, six misfire counters, dash fuel litres, ambient, four
    trims, EVAP purge). The cluster DIDs (fuel litres, ambient) poll every
    30 s and so stay far under budget; it is the 1-2 s ones (gear, trims,
    cylinders) that can actually spend it."""
    ceiling = len(_TRIP_SAMPLE_METRICS) * _TRIP_SAMPLE_TARGET_POINTS
    assert ceiling <= 22_000


def test_sql_bucket_expressions_use_the_same_width_param() -> None:
    """Both arms must bucket at $4 so the fallback returns the same shape
    and cardinality as the Timescale path."""
    src = _TRIPS_SRC.read_text()
    assert "make_interval(secs => $4)" in src
    assert re.search(r"floor\(extract\(epoch FROM time\) / \$4::int\)", src)


def test_brake_pressure_bucketed_by_peak_in_both_arms() -> None:
    """A 1-2 s brake press averaged into a wider bucket loses its height;
    both sample queries must take max() for the peak metrics."""
    from pitstop.api.trips import _TRIP_SAMPLE_PEAK_METRICS

    assert "brake_pressure" in _TRIP_SAMPLE_PEAK_METRICS
    assert set(_TRIP_SAMPLE_PEAK_METRICS) <= set(_TRIP_SAMPLE_METRICS)
    src = _TRIPS_SRC.read_text()
    assert src.count("CASE WHEN metric = ANY($6::text[])") == 2
    assert src.count("list(_TRIP_SAMPLE_PEAK_METRICS)") == 2


def test_new_drivetrain_and_trim_metrics_charted() -> None:
    for metric in (
        "atf_temp",
        "gear_position",
        "active_cylinders",
        *(f"misfire_count_{n}" for n in range(1, 7)),
        "fuel_level_l",
        "ambient_air_temp_f",
        "stft_b1",
        "stft_b2",
        "ltft_b1",
        "ltft_b2",
        "commanded_evap_purge",
    ):
        assert metric in _TRIP_SAMPLE_METRICS, metric


def test_discrete_metrics_bucketed_by_last_value_in_both_arms() -> None:
    """Gear / cylinder count / misfire counters are discrete: AVG invents
    gear 4.5, MAX turns a bucket that touched Reverse (15) into R. Both
    sample queries must take the bucket's last value for them."""
    from pitstop.api.trips import _TRIP_SAMPLE_LAST_METRICS, _TRIP_SAMPLE_PEAK_METRICS

    assert {"gear_position", "active_cylinders"} <= set(_TRIP_SAMPLE_LAST_METRICS)
    for n in range(1, 7):
        assert f"misfire_count_{n}" in _TRIP_SAMPLE_LAST_METRICS
    assert set(_TRIP_SAMPLE_LAST_METRICS) <= set(_TRIP_SAMPLE_METRICS)
    assert not set(_TRIP_SAMPLE_LAST_METRICS) & set(_TRIP_SAMPLE_PEAK_METRICS)
    src = _TRIPS_SRC.read_text()
    assert src.count("WHEN metric = ANY($7::text[])") == 2
    assert src.count("(array_agg(value_num ORDER BY time DESC))[1]") == 2
    assert src.count("list(_TRIP_SAMPLE_LAST_METRICS)") == 2
