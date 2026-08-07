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
        // g/s on the wire -> L/h or gph via the Quantity, matching the Live
        // tile and the trip chart. It was already named in DEFAULT_FUEL but
        // missing here, so resolve() silently dropped it and the Fuel tab
        // would have rendered two tiles instead of three.
        CarTileSpec(
            "engine_fuel_rate", "Fuel rate", UnitFormat.Quantity.FuelRateGramsPerSec, 2,
            icon = R.drawable.ic_metric_fuel,
        ),
        // ── Emissions / fuel control ──────────────────────────────
        // Added when the phone learned to poll these directly (0.1.221).
        // They were pollable and visible on the Live screen but absent from
        // this catalogue, so they could not be chosen for a head-unit tab —
        // the tab picker only offers what ALL contains.
        CarTileSpec(
            "engine_exhaust_flow", "Exhaust", UnitFormat.Quantity.MassFlowKgPerHour, 1,
            icon = R.drawable.ic_metric_air,
        ),
        CarTileSpec(
            "catalyst_temp_b1", "Cat B1", UnitFormat.Quantity.TempC, 0,
            icon = R.drawable.ic_metric_temp,
        ),
        CarTileSpec(
            "catalyst_temp_b2", "Cat B2", UnitFormat.Quantity.TempC, 0,
            icon = R.drawable.ic_metric_temp,
        ),
        // 3 decimals: the interesting range is 0.98-1.02 and 2 quantises the
        // signal away, same reason the trip chart uses 3.
        CarTileSpec(
            "commanded_afr_ratio", "Cmd AFR", UnitFormat.Quantity.Lambda, 3,
            icon = R.drawable.ic_metric_trim,
        ),
        CarTileSpec(
            "o2_s1_lambda", "O2 S1", UnitFormat.Quantity.Lambda, 3,
            icon = R.drawable.ic_metric_trim,
        ),
        CarTileSpec(
            "fuel_rail_pressure", "Fuel rail", UnitFormat.Quantity.PressureKpa, 0,
            icon = R.drawable.ic_metric_pressure,
        ),
        CarTileSpec(
            "commanded_egr", "Cmd EGR", UnitFormat.Quantity.Percent, 0,
            icon = R.drawable.ic_metric_load,
        ),
        CarTileSpec(
            "commanded_evap_purge", "Evap", UnitFormat.Quantity.Percent, 0,
            icon = R.drawable.ic_metric_load,
        ),
        // ── Electrical ────────────────────────────────────────────
        CarTileSpec("control_module_voltage", "Battery", UnitFormat.Quantity.Volt, 1, icon = R.drawable.ic_metric_battery),
        // ── GPS / IMU (from phone bridge) ─────────────────────────
        // m/s on the wire; SpeedMps renders mph or km/h, never the raw
        // SI value. 0 decimals now that it's a human-scale number.
        CarTileSpec("gps_speed", "GPS spd", UnitFormat.Quantity.SpeedMps, 0, icon = R.drawable.ic_metric_speed),
        CarTileSpec("gps_alt", "Altitude", UnitFormat.Quantity.AltitudeM, 0, icon = R.drawable.ic_metric_altitude),
    )

    /**
     * Three, not six — one head-unit row, so the grid never scrolls.
     *
     * The car host replaces (rather than refreshes) a template whenever any
     * item's text changes, and it resets scroll position on replacement. A
     * live dashboard changes text by definition, so with a scrollable grid
     * the user was thrown back to the top every couple of seconds while
     * reading the lower tiles. Throttling only made it rarer; the only way
     * to make it impossible is to have nothing to scroll.
     *
     * MAX_TILES stays 6 for anyone whose head unit shows two full rows —
     * the picker in Settings lets them add more, and the trade-off is theirs
     * to make because it depends on the screen in their car.
     */
    val DEFAULT_HOME: List<String> = listOf(
        "fuel_level", "coolant_temp", "engine_rpm",
    )

    /**
     * Hard cap. Matches the host's grid content limit — exceeding it throws
     * rather than truncating, and takes the car app down with it.
     */
    const val MAX_TILES = 6

    /** Same single-row reasoning as DEFAULT_HOME. */
    val DEFAULT_DIAG: List<String> = listOf(
        "throttle_position", "control_module_voltage", "run_time_since_start",
    )

    val DEFAULT_ENGINE: List<String> = listOf(
        "coolant_temp", "engine_load", "intake_air_temp",
    )

    val DEFAULT_FUEL: List<String> = listOf(
        "fuel_level", "engine_fuel_rate", "maf_air_flow",
    )

    /**
     * The head-unit tabs. Four is the documented TabTemplate maximum and
     * there is no constant in ConstraintManager to read it from, so this is
     * validated on a real host rather than asserted.
     *
     * Tabs exist as much for behaviour as for structure: the car host resets
     * a grid's scroll position every time the template is replaced, and a
     * changing value always counts as a replacement. Three tiles per tab
     * means no grid ever scrolls, so the reset has nothing to undo — while
     * still putting 12 metrics one tap away instead of 6 behind a scroll.
     */
    enum class CarTab(
        val id: String,
        val title: String,
        val defaults: List<String>,
        @DrawableRes val icon: Int,
    ) {
        Drive("drive", "Drive", DEFAULT_HOME, R.drawable.ic_metric_speed),
        Engine("engine", "Engine", DEFAULT_ENGINE, R.drawable.ic_metric_tach),
        Fuel("fuel", "Fuel", DEFAULT_FUEL, R.drawable.ic_metric_fuel),
        Diagnostics("diag", "Diag", DEFAULT_DIAG, R.drawable.ic_metric_trim),
    }

    fun byKey(key: String): CarTileSpec? = ALL.firstOrNull { it.key == key }

    /** Resolve a stored config into a list of specs, falling back to the default. */
    fun resolveHome(stored: List<String>): List<CarTileSpec> = resolve(stored, DEFAULT_HOME)

    fun resolveDiag(stored: List<String>): List<CarTileSpec> = resolve(stored, DEFAULT_DIAG)

    /** Tiles for one tab, honouring the user's stored order for it. */
    fun resolveTab(tab: CarTab, stored: List<String>): List<CarTileSpec> =
        resolve(stored, tab.defaults)

    private fun resolve(stored: List<String>, default: List<String>): List<CarTileSpec> {
        val source = stored.ifEmpty { default }
        // Fall back again if the stored keys resolve to nothing — a config
        // written by an older build could name metrics that no longer exist,
        // and a GridTemplate with an empty list is a blank car screen with no
        // way back. take(6) matches the host's grid content limit.
        val specs = source.mapNotNull { byKey(it) }.take(MAX_TILES)
        return specs.ifEmpty { default.mapNotNull { byKey(it) }.take(MAX_TILES) }
    }
}
