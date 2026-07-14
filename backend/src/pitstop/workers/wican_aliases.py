"""Aliases for WiCAN's auto-named Mode 01 / Honda PIDs → pitstop canonical names.

When the user runs WiCAN's Standard PIDs scan, the device auto-names each PID
``<HEX>-<Description>`` (e.g. ``0C-EngineRPM``, ``05-EngineCoolantTemp``).
Pitstop's frontend gauges, profile JSON, and analytics queries look for the
shorter canonical names (e.g. ``engine_rpm``, ``coolant_temp``).

Renaming each PID in the WiCAN UI works but is tedious and is also lost on a
factory reset. Translating at ingest is the cheaper path: the user can leave
the WiCAN's Standard PIDs untouched, and we map them on the way into the DB.

Anything not in this map passes through unchanged so Honda-specific metrics
(``A6-Odometer``, ``9D-EngineFuelRate``, ``67-EngineCoolantTemp1`` …) are
still recorded — they just stay under WiCAN's hex-prefixed names until or
unless we add a UI for them.
"""
from __future__ import annotations

# WiCAN-named → pitstop canonical.
WICAN_TO_CANONICAL: dict[str, str] = {
    # Standard OBD-II Mode 01 PIDs the gauges + analytics expect.
    "04-CalcEngineLoad": "engine_load",
    "05-EngineCoolantTemp": "coolant_temp",
    "0B-IntakeManiAbsPress": "intake_manifold_pressure",
    "0C-EngineRPM": "engine_rpm",
    "0D-VehicleSpeed": "vehicle_speed",
    "0E-TimingAdvance": "timing_advance",
    "0F-IntakeAirTemp": "intake_air_temp",
    "10-MAFAirFlowRate": "maf_air_flow",
    "11-ThrottlePosition": "throttle_position",
    "1F-TimeSinceEngStart": "time_since_engine_start",
    "21-DistanceMILOn": "distance_mil_on",
    "2F-FuelTankLevel": "fuel_level",
    "31-DistanceSinceCodeClear": "distance_since_code_clear",
    "33-AbsBaroPres": "barometric_pressure",
    "42-ControlModuleVolt": "control_module_voltage",
    "43-AbsLoadValue": "absolute_load",
    "45-RelThrottlePos": "relative_throttle_position",
    "5C-EngineOilTemp": "engine_oil_temp",
    "62-ActualEngTorqPct": "engine_torque_pct",
    "63-EngRefTorq": "engine_reference_torque",
    "9D-EngineFuelRate": "fuel_rate",
    "A6-Odometer": "odometer",
    # Fuel trims — paired bank (1) maps to canonical, bank 2 keeps its suffix.
    "06-ShortFuelTrimBank1": "stft_b1",
    "07-LongFuelTrimBank1": "ltft_b1",
    "08-ShortFuelTrimBank2": "stft_b2",
    "09-LongFuelTrimBank2": "ltft_b2",
    "55-ShortSecOxyTrimBank1": "stft_sec_b1",
    "56-LongSecOxyTrimBank1": "ltft_sec_b1",
    "57-ShortSecOxyTrimBank2": "stft_sec_b2",
    "58-LongSecOxyTrimBank2": "ltft_sec_b2",
    # Honda dual-sensor variants — Sensor1/A maps to canonical for coolant
    # and MAF where WiCAN's std-PID decoder is byte-correct.
    #
    # 68-IntakeAirTempSens1 is DELIBERATELY NOT aliased: WiCAN-PRO firmware
    # v4.49 Beta-06 has a confirmed decoder bug on the MQTT publish path for
    # PID 0x68 — it emits the supported-sensors bitmap byte (0x01) instead of
    # the temperature byte, yielding a constant -39°C. The UI Test button
    # reads the right byte (~69°C plausible) but MQTT publishes -39 on every
    # poll. Bisect on 2026-05-12 confirmed the response layout via custom
    # Mode 01 PID 0168 — sensor 1 temp is at byte index B5 (after PCI+length
    # +mode-echo+PID-echo+bitmap). Use a custom PID named `intake_air_temp`
    # with PID 0168 and expression `B5-40` instead.
    "67-EngineCoolantTemp1": "coolant_temp",
    "66-MAFSensorA": "maf_air_flow",
    # WiCAN's reserved synthetic key — drop it; pitstop has its own ts.
    "timestamp": "_drop_",
}


# WiCAN AutoPID "capability"/"monitor" PIDs that read a constant (0, or a
# static support bitmap) on every poll — pure storage + continuous-aggregate
# noise with zero analytical signal (~298k such rows observed). Dropped at
# ingest so they never persist. Prune them from the WiCAN device poll list
# too (wican-config skill) to also reclaim CAN/BLE airtime. `_hdr_reset` is
# the ELM header-reset marker — needed on the DEVICE to keep the std-PID
# cycle broadcasting, but valueless as a stored reading, so drop from storage
# only. Matched as substrings against the unmapped WiCAN hex-prefixed name;
# note "OxySensorsPresent" is distinct from the O2-sensor STFT metrics
# ("15-OxySensor2_STFT") which DO carry signal and are left untouched.
_DROP_SUBSTRINGS: tuple[str, ...] = (
    "PIDsSupported",
    "MonitorStatus",
    "MonStatusDriveCycle",
    "FuelSystemStatus",
    "OBDStandard",
    "OxySensorsPresent",
)
_DROP_EXACT: frozenset[str] = frozenset({"_hdr_reset"})


def normalise(metric: str) -> str | None:
    """Translate a WiCAN-published metric name to pitstop's canonical name.

    Returns the canonical name, the original name (if no mapping exists), or
    ``None`` to drop the metric (synthetic ``timestamp`` keys, and the
    constant capability/monitor cruft in ``_DROP_SUBSTRINGS`` / ``_DROP_EXACT``
    that duplicate no useful signal).
    """
    target = WICAN_TO_CANONICAL.get(metric)
    if target == "_drop_":
        return None
    if target is not None:
        return target
    # Unmapped WiCAN name: drop the constant capability/monitor PIDs, else
    # pass it through unchanged (Honda-specific metrics still get recorded).
    if metric in _DROP_EXACT or any(sub in metric for sub in _DROP_SUBSTRINGS):
        return None
    return metric
