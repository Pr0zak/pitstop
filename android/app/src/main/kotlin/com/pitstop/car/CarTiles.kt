package com.pitstop.car

import com.pitstop.util.UnitFormat

/**
 * Catalog of metrics the Android Auto screens can display, with their
 * presentation hints (label, quantity, decimal places). Single source of
 * truth shared between the CarApp screens and the phone Settings UI
 * that lets the user reorder them.
 *
 * A tile names a [UnitFormat.Quantity], never a literal unit string —
 * the head-unit grid honours the same imperial/metric toggle the phone
 * screens do. This catalog used to hardcode canonical units ("kph",
 * "°C", "m/s"), so an imperial user's dash showed metric numbers with
 * metric labels while the Live screen next to it showed mph and °F.
 */
data class CarTileSpec(
    val key: String,
    val label: String,
    val quantity: UnitFormat.Quantity,
    val digits: Int,
    val accent: Boolean = false,
) {
    fun unit(system: String): String = quantity.unit(system)
}

object CarTileCatalog {
    val ALL: List<CarTileSpec> = listOf(
        // ── Engine ────────────────────────────────────────────────
        CarTileSpec("engine_rpm", "RPM", UnitFormat.Quantity.None, 0, accent = true),
        CarTileSpec("vehicle_speed", "Speed", UnitFormat.Quantity.SpeedKph, 0),
        CarTileSpec("coolant_temp", "Coolant", UnitFormat.Quantity.TempC, 0),
        CarTileSpec("intake_air_temp", "Intake", UnitFormat.Quantity.TempC, 0),
        CarTileSpec("engine_load", "Eng load", UnitFormat.Quantity.Percent, 0),
        CarTileSpec("throttle_position", "Throttle", UnitFormat.Quantity.Percent, 0),
        CarTileSpec("maf_air_flow", "MAF", UnitFormat.Quantity.MassFlowGramsPerSec, 1),
        CarTileSpec("manifold_pressure", "MAP", UnitFormat.Quantity.PressureKpa, 0),
        CarTileSpec("run_time_since_start", "Run time", UnitFormat.Quantity.Seconds, 0),
        // ── Fuel system ───────────────────────────────────────────
        CarTileSpec("fuel_level", "Fuel", UnitFormat.Quantity.Percent, 0),
        CarTileSpec("stft_b1", "STFT B1", UnitFormat.Quantity.Percent, 1),
        CarTileSpec("ltft_b1", "LTFT B1", UnitFormat.Quantity.Percent, 1),
        CarTileSpec("stft_b2", "STFT B2", UnitFormat.Quantity.Percent, 1),
        CarTileSpec("ltft_b2", "LTFT B2", UnitFormat.Quantity.Percent, 1),
        // ── Electrical ────────────────────────────────────────────
        CarTileSpec("control_module_voltage", "Battery", UnitFormat.Quantity.Volt, 1),
        // ── GPS / IMU (from phone bridge) ─────────────────────────
        // m/s on the wire; SpeedMps renders mph or km/h, never the raw
        // SI value. 0 decimals now that it's a human-scale number.
        CarTileSpec("gps_speed", "GPS spd", UnitFormat.Quantity.SpeedMps, 0),
        CarTileSpec("gps_alt", "Altitude", UnitFormat.Quantity.AltitudeM, 0),
    )

    val DEFAULT_HOME: List<String> = listOf(
        "coolant_temp", "fuel_level", "engine_rpm",
        "engine_load", "control_module_voltage", "intake_air_temp",
    )

    val DEFAULT_DIAG: List<String> = listOf(
        "throttle_position", "maf_air_flow",
        "run_time_since_start", "stft_b1", "ltft_b1",
        "stft_b2",
    )

    fun byKey(key: String): CarTileSpec? = ALL.firstOrNull { it.key == key }

    /** Resolve a stored config into a list of specs, falling back to the default. */
    fun resolveHome(stored: List<String>): List<CarTileSpec> = resolve(stored, DEFAULT_HOME)

    fun resolveDiag(stored: List<String>): List<CarTileSpec> = resolve(stored, DEFAULT_DIAG)

    private fun resolve(stored: List<String>, default: List<String>): List<CarTileSpec> {
        val source = stored.ifEmpty { default }
        // Fall back again if the stored keys resolve to nothing — a config
        // written by an older build could name metrics that no longer exist,
        // and a GridTemplate with an empty list is a blank car screen with no
        // way back. take(6) matches the host's grid content limit.
        val specs = source.mapNotNull { byKey(it) }.take(6)
        return specs.ifEmpty { default.mapNotNull { byKey(it) }.take(6) }
    }
}
