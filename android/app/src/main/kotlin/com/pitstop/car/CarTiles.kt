package com.pitstop.car

/**
 * Catalog of metrics the Android Auto screens can display, with their
 * presentation hints (label, unit, decimal places). Single source of
 * truth shared between the CarApp screens and the phone Settings UI
 * that lets the user reorder them.
 */
data class CarTileSpec(
    val key: String,
    val label: String,
    val unit: String,
    val digits: Int,
    val accent: Boolean = false,
)

object CarTileCatalog {
    val ALL: List<CarTileSpec> = listOf(
        // ── Engine ────────────────────────────────────────────────
        CarTileSpec("engine_rpm", "RPM", "", 0, accent = true),
        CarTileSpec("vehicle_speed", "Speed", "kph", 0),
        CarTileSpec("coolant_temp", "Coolant", "°C", 0),
        CarTileSpec("intake_air_temp", "Intake", "°C", 0),
        CarTileSpec("engine_load", "Eng load", "%", 0),
        CarTileSpec("throttle_position", "Throttle", "%", 0),
        CarTileSpec("maf_air_flow", "MAF", "g/s", 1),
        CarTileSpec("manifold_pressure", "MAP", "kPa", 0),
        CarTileSpec("run_time_since_start", "Run time", "s", 0),
        // ── Fuel system ───────────────────────────────────────────
        CarTileSpec("fuel_level", "Fuel", "%", 0),
        CarTileSpec("stft_b1", "STFT B1", "%", 1),
        CarTileSpec("ltft_b1", "LTFT B1", "%", 1),
        CarTileSpec("stft_b2", "STFT B2", "%", 1),
        CarTileSpec("ltft_b2", "LTFT B2", "%", 1),
        // ── Electrical ────────────────────────────────────────────
        CarTileSpec("control_module_voltage", "Battery", "V", 1),
        // ── GPS / IMU (from phone bridge) ─────────────────────────
        CarTileSpec("gps_speed", "GPS spd", "m/s", 1),
        CarTileSpec("gps_alt", "Altitude", "m", 0),
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
        return source.mapNotNull { byKey(it) }.take(6)
    }
}
