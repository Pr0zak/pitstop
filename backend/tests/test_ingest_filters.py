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
    # Unmapped Honda-specific metric passes through unchanged.
    assert normalise("9D-EngineFuelRate") == "fuel_rate" or normalise(
        "5C-EngineOilTemp"
    ) == "engine_oil_temp"
    assert normalise("XX-SomeUnknownHondaPid") == "XX-SomeUnknownHondaPid"


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
    # O2-sensor STFT metrics carry signal and must NOT be dropped by the
    # "OxySensorsPresent" substring (distinct string).
    assert normalise("15-OxySensor2_STFT") == "15-OxySensor2_STFT"


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
