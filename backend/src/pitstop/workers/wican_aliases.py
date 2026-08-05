"""Aliases for WiCAN's auto-named Mode 01 / Honda PIDs → pitstop canonical names.

When the user runs WiCAN's Standard PIDs scan, the device auto-names each PID
``<HEX>-<Description>`` (e.g. ``0C-EngineRPM``, ``05-EngineCoolantTemp``).
Pitstop's frontend gauges, profile JSON, and analytics queries look for the
shorter canonical names (e.g. ``engine_rpm``, ``coolant_temp``).

Renaming each PID in the WiCAN UI works but is tedious and is also lost on a
factory reset. Translating at ingest is the cheaper path: the user can leave
the WiCAN's Standard PIDs untouched, and we map them on the way into the DB.

Anything not in this map passes through unchanged so Honda-specific metrics
(``223083-ATFTemp`` …) are still recorded — they just stay under WiCAN's
hex-prefixed names until or unless we add a UI for them.

Four PIDs (``68-IntakeAirTempSens1``, ``6C-CmdThrottleActRel``,
``9D-EngineFuelRate``, ``24-OxySensor1_Volt``) are deliberately left unaliased
because their published values carry no signal — a known-broken std-PID
decoder on the MQTT publish path for the first three, an invariant field for
the fourth; see the inline comments. Aliasing a value-free stream onto a
canonical name silently poisons whatever consumes that name.
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
    "23-FuelRailGaug": "fuel_rail_pressure",
    "2C-CmdEGR": "commanded_egr",
    "2E-CmdEvapPurge": "commanded_evap_purge",
    "2F-FuelTankLevel": "fuel_level",
    # 30-WarmUpsSinceCodeClear is DELIBERATELY NOT aliased: it decodes fine,
    # but it is a monotonic since-code-clear counter rather than telemetry.
    # Nothing on the gauge/analytics side consumes a warm-up count, and giving
    # it a canonical name would only publish a staircase series into the
    # curated metric space. Left passing through under its hex name.
    "31-DistanceSinceCodeClear": "distance_since_code_clear",
    "32-EvapSysVaporPres": "evap_vapor_pressure",
    "33-AbsBaroPres": "barometric_pressure",
    "3C-CatTempBank1Sens1": "catalyst_temp_b1",
    "3D-CatTempBank2Sens1": "catalyst_temp_b2",
    "42-ControlModuleVolt": "control_module_voltage",
    "43-AbsLoadValue": "absolute_load",
    "44-FuelAirCmdEquiv": "commanded_afr_ratio",
    "45-RelThrottlePos": "relative_throttle_position",
    "47-AbsThrottlePosB": "throttle_pos_b",
    "49-AbsThrottlePosD": "throttle_pos_d",
    "4A-AbsThrottlePosE": "throttle_pos_e",
    "5C-EngineOilTemp": "engine_oil_temp",
    "62-ActualEngTorqPct": "engine_torque_pct",
    "63-EngRefTorq": "engine_reference_torque",
    # 6C-CmdThrottleActRel is DELIBERATELY NOT aliased to
    # `commanded_throttle_actuator` — same class of firmware defect as
    # 9D-EngineFuelRate below and 68-IntakeAirTempSens1 further down. The PCM
    # answers Mode 01 PID 0x6C and the raw frame carries a live actuator
    # position, but the WiCAN's std-PID decoder on the MQTT publish path
    # reports a constant 0. A canonical name here would surface a
    # permanently-flat gauge that reads as a closed throttle rather than as a
    # broken decode, and would poison any future throttle-vs-load analytics.
    # If the value is ever wanted, read it the way `intake_air_temp` is read:
    # a WiCAN *custom* Mode 01 PID with an explicit byte expression, published
    # directly under the canonical name. Leaving the std-decoder name passing
    # through keeps the artifact stored, diagnosable and quarantined under its
    # hex name.
    "8E-EngineFrictionPercentTorque": "friction_torque_pct",
    # 9D-EngineFuelRate is DELIBERATELY NOT aliased to `engine_fuel_rate` —
    # same reasoning as 68-IntakeAirTempSens1 below. Mode 01 PID 0x9D IS
    # supported by this PCM and carries live data in the raw frame, but the
    # WiCAN's std-PID decoder on the MQTT publish path emits a constant 0:
    # 11,586 rows between 2026-05-08 and 2026-05-23 with min = max = 0.
    # `workers/trip_stats.FUEL_SOURCES` now tries `engine_fuel_rate` FIRST,
    # so aliasing the broken decoder into that name would interleave a 1 Hz
    # stream of zeros with the working sources and roughly halve every trip's
    # fuel integral (≈2x inflated MPG) while still clearing the credibility
    # floor. The working path is a WiCAN *custom* PID 0x9D with an explicit
    # expression, published under the name `engine_fuel_rate` directly (live
    # since 2026-08-04) — it needs no alias. Leave the std-decoder name
    # passing through unchanged so the artifact stays diagnosable and
    # quarantined.
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
    # O2 sensors — Mode 01 exposes two parallel eight-slot blocks: 0x14-0x1B
    # (narrow-band: voltage + the short fuel trim derived from it) and
    # 0x24-0x2B (wide-range: fuel-air equivalence ratio, i.e. lambda, plus its
    # own voltage). This PCM answers on three of the sixteen. The digit in the
    # canonical name is the sensor slot within its block, matching WiCAN's own
    # label (0x15 = sensor 2, 0x19 = sensor 6, 0x24 = sensor 1 wide-range).
    "24-OxySensor1_FAER": "o2_s1_lambda",
    # 24-OxySensor1_Volt is DELIBERATELY NOT aliased to `o2_s1_voltage` — same
    # rule as 6C/9D/68 above. Measured over the whole stored history: 29,103
    # rows carrying exactly TWO distinct values (1.14 and 2.0), and a flat
    # 2.000 for every one of the 6,216 rows in the last 45 days, across cold
    # starts, idle, WOT and DFCO. A wide-range sensor's voltage cannot be
    # invariant through that, so the field is either a fixed reference the PCM
    # reports for a current-type sensor or a broken decode — either way it
    # carries no signal. Aliasing it would put a frozen number on a gauge that
    # reads as a live measurement, and would occupy the canonical name that a
    # WiCAN *custom* PID would need if the value is ever wanted for real.
    # `24-OxySensor1_FAER` (lambda) above is the wide-range sensor's real
    # output and IS aliased.
    "15-OxySensor2_Volt": "o2_s2_voltage",
    "15-OxySensor2_STFT": "o2_s2_stft",
    "19-OxySensor6_Volt": "o2_s6_voltage",
    "19-OxySensor6_STFT": "o2_s6_stft",
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
# only. Matched as substrings against the unmapped WiCAN hex-prefixed name.
#
# "OxySensorsPresent" is a capability bitmap and is DISTINCT from the
# per-sensor O2 metrics, which must survive. Two different mechanisms keep them
# alive, and the difference matters when adding a substring here:
#   * "15-OxySensor2_STFT" and friends are aliased in the map above, and the
#     map is consulted first — so they never reach this list at all.
#   * "24-OxySensor1_Volt" is deliberately NOT aliased (it is decoder-dead;
#     see its comment above), so it DOES fall through to the substring match.
#     It survives only because no entry here is a substring of it.
# Any new substring must therefore be checked against the unaliased hex names
# too, not just the aliased ones.
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
