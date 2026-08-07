package com.pitstop.car

import androidx.annotation.DrawableRes
import com.pitstop.R
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
    /**
     * Glyph drawn on the head-unit tile. Every tile MUST have one:
     * androidx.car.app rejects a GridItem that has neither an image nor
     * a loading flag, and it throws hard enough to take the car app down.
     *
     * Icons are grouped by what the number MEANS, not one per metric —
     * four fuel trims share a slider glyph, both temperatures share a
     * thermometer. Six tiles are visible at once and distinct shapes are
     * what make them scannable at a glance; a unique-but-similar glyph
     * per metric would read as noise.
     */
    @DrawableRes val icon: Int = R.drawable.ic_metric_tach,
) {
    fun unit(system: String): String = quantity.unit(system)
}

object CarTileCatalog {
    val ALL: List<CarTileSpec> = listOf(
        // ── Engine ────────────────────────────────────────────────
        CarTileSpec("engine_rpm", "RPM", UnitFormat.Quantity.None, 0, accent = true, icon = R.drawable.ic_metric_tach),
        CarTileSpec("vehicle_speed", "Speed", UnitFormat.Quantity.SpeedKph, 0, icon = R.drawable.ic_metric_speed),
        CarTileSpec("coolant_temp", "Coolant", UnitFormat.Quantity.TempC, 0, icon = R.drawable.ic_metric_temp),
        CarTileSpec("intake_air_temp", "Intake", UnitFormat.Quantity.TempC, 0, icon = R.drawable.ic_metric_temp),
        CarTileSpec("engine_load", "Eng load", UnitFormat.Quantity.Percent, 0, icon = R.drawable.ic_metric_load),
        CarTileSpec("throttle_position", "Throttle", UnitFormat.Quantity.Percent, 0, icon = R.drawable.ic_metric_load),
        CarTileSpec("maf_air_flow", "MAF", UnitFormat.Quantity.MassFlowGramsPerSec, 1, icon = R.drawable.ic_metric_air),
        CarTileSpec("manifold_pressure", "MAP", UnitFormat.Quantity.PressureKpa, 0, icon = R.drawable.ic_metric_pressure),
        CarTileSpec("run_time_since_start", "Run time", UnitFormat.Quantity.Seconds, 0, icon = R.drawable.ic_metric_clock),
        // ── Fuel system ───────────────────────────────────────────
        CarTileSpec("fuel_level", "Fuel", UnitFormat.Quantity.Percent, 0, icon = R.drawable.ic_metric_fuel),
        CarTileSpec("stft_b1", "STFT B1", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim),
        CarTileSpec("ltft_b1", "LTFT B1", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim),
        CarTileSpec("stft_b2", "STFT B2", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim),
        CarTileSpec("ltft_b2", "LTFT B2", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim),
        // ── Electrical ────────────────────────────────────────────
        CarTileSpec("control_module_voltage", "Battery", UnitFormat.Quantity.Volt, 1, icon = R.drawable.ic_metric_battery),
        // ── GPS / IMU (from phone bridge) ─────────────────────────
        // m/s on the wire; SpeedMps renders mph or km/h, never the raw
        // SI value. 0 decimals now that it's a human-scale number.
        CarTileSpec("gps_speed", "GPS spd", UnitFormat.Quantity.SpeedMps, 0, icon = R.drawable.ic_metric_speed),
        CarTileSpec("gps_alt", "Altitude", UnitFormat.Quantity.AltitudeM, 0, icon = R.drawable.ic_metric_altitude),
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
