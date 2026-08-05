"""Unit tests for the ingest-side garbage filters (no DB required).

Covers the WiCAN alias drop-list (constant capability/monitor PIDs) and the
per-metric plausibility guard that keeps decoder artifacts out of
pid_readings + the continuous aggregates.
"""

from __future__ import annotations

from pitstop.workers.ingest import _implausible
from pitstop.workers.wican_aliases import normalise


def test_normalise_maps_and_passes_through() -> None:
    assert normalise("0C-EngineRPM") == "engine_rpm"
    assert normalise("A6-Odometer") == "odometer"
    assert normalise("5C-EngineOilTemp") == "engine_oil_temp"
    # PID 0x9D's WiCAN std-PID decoder is broken (constant 0), so its
    # hex-prefixed name is deliberately NOT aliased onto the canonical
    # `engine_fuel_rate` that trip_stats trusts first — see
    # test_trip_fuel_sources.py.
    assert normalise("9D-EngineFuelRate") == "9D-EngineFuelRate"
    # Unmapped Honda-specific metric passes through unchanged.
    assert normalise("XX-SomeUnknownHondaPid") == "XX-SomeUnknownHondaPid"


def test_normalise_maps_emissions_and_o2_pids() -> None:
    """The Mode 01 emissions/O2 block measured live on the Pilot — previously
    stored under raw hex names and therefore invisible to both UIs."""
    expected = {
        "3C-CatTempBank1Sens1": "catalyst_temp_b1",
        "3D-CatTempBank2Sens1": "catalyst_temp_b2",
        "44-FuelAirCmdEquiv": "commanded_afr_ratio",
        "24-OxySensor1_FAER": "o2_s1_lambda",
        "15-OxySensor2_Volt": "o2_s2_voltage",
        "15-OxySensor2_STFT": "o2_s2_stft",
        "19-OxySensor6_Volt": "o2_s6_voltage",
        "19-OxySensor6_STFT": "o2_s6_stft",
        "23-FuelRailGaug": "fuel_rail_pressure",
        "2C-CmdEGR": "commanded_egr",
        "2E-CmdEvapPurge": "commanded_evap_purge",
        "32-EvapSysVaporPres": "evap_vapor_pressure",
        "8E-EngineFrictionPercentTorque": "friction_torque_pct",
        "47-AbsThrottlePosB": "throttle_pos_b",
        "49-AbsThrottlePosD": "throttle_pos_d",
        "4A-AbsThrottlePosE": "throttle_pos_e",
    }
    for wican_name, canonical in expected.items():
        assert normalise(wican_name) == canonical, wican_name
    # Canonical names must be unique — a collision would silently merge two
    # distinct sensors into one series.
    assert len(set(expected.values())) == len(expected)


def test_normalise_leaves_broken_and_counter_pids_unaliased() -> None:
    # PID 0x6C's WiCAN std-PID decoder is broken the same way 0x9D's is
    # (constant 0 while the raw frame carries data), so it must keep its hex
    # name rather than surface a permanently-zero canonical metric.
    assert normalise("6C-CmdThrottleActRel") == "6C-CmdThrottleActRel"
    # A since-code-clear counter, not telemetry — nothing curated consumes it.
    assert normalise("30-WarmUpsSinceCodeClear") == "30-WarmUpsSinceCodeClear"
    # Wide-range O2 sensor 1's VOLTAGE field is invariant in the stored data
    # (2 distinct values in 29,103 rows, flat 2.000 for the last 45 days), so
    # it stays quarantined under its hex name. Its LAMBDA field is real and is
    # aliased.
    assert normalise("24-OxySensor1_Volt") == "24-OxySensor1_Volt"
    assert normalise("24-OxySensor1_FAER") == "o2_s1_lambda"


def test_normalise_drops_synthetic_and_cruft() -> None:
    assert normalise("timestamp") is None
    # Constant capability / monitor PIDs → dropped.
    for junk in (
        "20-PIDsSupported_21_40",
        "A0-PIDsSupported_A1_C0",
        "01-MonitorStatus",
        "41-MonStatusDriveCycle",
        "03-FuelSystemStatus",
        "1C-OBDStandard",
        "13-OxySensorsPresent_2Banks",
        "_hdr_reset",
    ):
        assert normalise(junk) is None, junk
    # Per-sensor O2 metrics carry signal and must NOT be dropped by the
    # "OxySensorsPresent" substring (distinct string) — they alias instead,
    # or (24-OxySensor1_Volt) pass through under their own name.
    assert normalise("15-OxySensor2_STFT") == "o2_s2_stft"
    assert normalise("24-OxySensor1_Volt") == "24-OxySensor1_Volt"


def test_implausible_rejects_decoder_garbage() -> None:
    # Poisoned CAN odometer frame.
    assert _implausible("odometer", 10_496_563.0) is True
    assert _implausible("odometer", 0.0) is True
    assert _implausible("odometer", 124_944.0) is False
    # -37C coolant byte-0x03 artifact; real operating coolant survives.
    assert _implausible("coolant_temp", -37.0) is True
    assert _implausible("coolant_temp", 92.0) is False
    assert _implausible("coolant_temp", -5.0) is False
    # -97.66% fuel-trim sentinel; real trims survive.
    assert _implausible("stft_b1", -97.66) is True
    assert _implausible("ltft_b2", 5.0) is False
    assert _implausible("stft_sec_b1", 61.0) is True
    assert _implausible("stft_sec_b1", 12.0) is False
    # Unlisted metric always passes (targeted filter, not a general clamp).
    assert _implausible("engine_rpm", 99_999.0) is False
    assert _implausible("vehicle_speed", 250.0) is False
    assert _implausible("odometer", None) is False
