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
    /**
     * Show a trend arrow. Only for SLOW metrics (temperatures, fuel level,
     * battery, long-term trims): over a 30 s window their slope means
     * something. On RPM / speed / throttle the arrow just flickered with
     * every blip of the pedal — noise that pulls the eye while driving.
     */
    val trend: Boolean = false,
    /** Out-of-range bounds in CANONICAL units (°C, V, …); null = none. */
    val warnHigh: Double? = null,
    val warnLow: Double? = null,
    /** Word appended past a bound — colour alone is not an accessible cue. */
    val warnHighWord: String = "HIGH",
    val warnLowWord: String = "LOW",
    /** Severe = RED (stop soon); otherwise YELLOW (keep an eye on it). */
    val warnSevere: Boolean = false,
) {
    fun unit(system: String): String = quantity.unit(system)
}

object CarTileCatalog {
    val ALL: List<CarTileSpec> = listOf(
        // ── Engine ────────────────────────────────────────────────
        CarTileSpec("engine_rpm", "RPM", UnitFormat.Quantity.None, 0, accent = true, icon = R.drawable.ic_metric_tach),
        CarTileSpec("vehicle_speed", "Speed", UnitFormat.Quantity.SpeedKph, 0, icon = R.drawable.ic_metric_speed),
        // 105 °C: past the normal thermostat band on any modern engine even
        // under load; a coolant reading there is worth a word, not a hue.
        CarTileSpec(
            "coolant_temp", "Coolant", UnitFormat.Quantity.TempC, 0, icon = R.drawable.ic_metric_temp,
            trend = true, warnHigh = 105.0, warnHighWord = "HOT", warnSevere = true,
        ),
        CarTileSpec("intake_air_temp", "Intake", UnitFormat.Quantity.TempC, 0, icon = R.drawable.ic_metric_temp, trend = true),
        CarTileSpec("engine_load", "Eng load", UnitFormat.Quantity.Percent, 0, icon = R.drawable.ic_metric_load),
        CarTileSpec("throttle_position", "Throttle", UnitFormat.Quantity.Percent, 0, icon = R.drawable.ic_metric_load),
        CarTileSpec("maf_air_flow", "MAF", UnitFormat.Quantity.MassFlowGramsPerSec, 1, icon = R.drawable.ic_metric_air),
        CarTileSpec("manifold_pressure", "MAP", UnitFormat.Quantity.PressureKpa, 0, icon = R.drawable.ic_metric_pressure),
        CarTileSpec("run_time_since_start", "Run time", UnitFormat.Quantity.Seconds, 0, icon = R.drawable.ic_metric_clock),
        // ── Fuel system ───────────────────────────────────────────
        CarTileSpec("fuel_level", "Fuel", UnitFormat.Quantity.Percent, 0, icon = R.drawable.ic_metric_fuel, trend = true),
        CarTileSpec("stft_b1", "STFT B1", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim),
        CarTileSpec("ltft_b1", "LTFT B1", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim, trend = true),
        CarTileSpec("stft_b2", "STFT B2", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim),
        CarTileSpec("ltft_b2", "LTFT B2", UnitFormat.Quantity.Percent, 1, icon = R.drawable.ic_metric_trim, trend = true),
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
            icon = R.drawable.ic_metric_temp, trend = true,
        ),
        CarTileSpec(
            "catalyst_temp_b2", "Cat B2", UnitFormat.Quantity.TempC, 0,
            icon = R.drawable.ic_metric_temp, trend = true,
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
        // Charging system: under 12.0 V with the engine running means the
        // alternator isn't keeping up; over 15.0 V is overcharging.
        CarTileSpec(
            "control_module_voltage", "Battery", UnitFormat.Quantity.Volt, 1,
            icon = R.drawable.ic_metric_battery, trend = true,
            warnHigh = 15.0, warnLow = 12.0,
        ),
        // ── GPS / IMU (from phone bridge) ─────────────────────────
        // m/s on the wire; SpeedMps renders mph or km/h, never the raw
        // SI value. 0 decimals now that it's a human-scale number.
        CarTileSpec("gps_speed", "GPS spd", UnitFormat.Quantity.SpeedMps, 0, icon = R.drawable.ic_metric_speed),
        CarTileSpec("gps_alt", "Altitude", UnitFormat.Quantity.AltitudeM, 0, icon = R.drawable.ic_metric_altitude, trend = true),
    )

    /**
     * Six per screen, read down to the host's real grid limit at render time.
     *
     * This was three for a while, to stop the head unit resetting scroll
     * position mid-read. That reset was self-inflicted: the live value sat in
     * GridItem.setTitle(), and the host's refresh predicate compares item
     * titles — so every tick was a template REPLACEMENT rather than a
     * refresh, which both reset scroll and burned the five-per-task quota
     * that closes the app when exhausted.
     *
     * With the value moved to setText(), updates are genuine in-place
     * refreshes: scroll survives, the quota is untouched, and the tile count
     * is a free UX choice again. The picker in Settings caps at MAX_TILES.
     *
     * No speed tile: the car's own cluster (and the nav app beside us)
     * already shows it; the slot goes to intake temperature instead.
     */
    val DEFAULT_HOME: List<String> = listOf(
        "engine_rpm", "fuel_level", "coolant_temp",
        "intake_air_temp", "engine_load", "control_module_voltage",
    )

    /**
     * Hard cap. Matches the host's grid content limit — exceeding it throws
     * rather than truncating, and takes the car app down with it.
     */
    const val MAX_TILES = 6

    /** Throttle, run time and the four fuel trims — the tuning view. */
    val DEFAULT_DIAG: List<String> = listOf(
        "throttle_position", "run_time_since_start", "stft_b1",
        "ltft_b1", "stft_b2", "ltft_b2",
    )

    val DEFAULT_ENGINE: List<String> = listOf(
        "coolant_temp", "intake_air_temp", "engine_load",
        "throttle_position", "maf_air_flow", "manifold_pressure",
    )

    val DEFAULT_FUEL: List<String> = listOf(
        "fuel_level", "engine_fuel_rate", "engine_exhaust_flow",
        "commanded_afr_ratio", "o2_s1_lambda", "fuel_rail_pressure",
    )

    /**
     * Everything that can occupy a head-unit tab.
     *
     * TabTemplate takes at most four tabs, but there are more screens worth
     * having than that — so the SET is the user's choice rather than a fixed
     * layout. Metric screens render a GridTemplate of tiles; analytics
     * screens render a PaneTemplate fed from the server.
     *
     * `tiles` is null for analytics screens: they have no tile list to
     * configure, which is also how the Settings picker knows not to offer
     * one for them.
     */
    enum class CarScreenKind(
        val id: String,
        val title: String,
        val defaults: List<String>?,
        @DrawableRes val icon: Int,
    ) {
        Drive("drive", "Drive", DEFAULT_HOME, R.drawable.ic_metric_speed),
        Engine("engine", "Engine", DEFAULT_ENGINE, R.drawable.ic_metric_tach),
        Fuel("fuel", "Fuel", DEFAULT_FUEL, R.drawable.ic_metric_fuel),
        Diagnostics("diag", "Diag", DEFAULT_DIAG, R.drawable.ic_metric_trim),

        /** Live bridge/session state from the in-process bus. No network.
         *  A PaneTemplate: OBD link, engine, upload, device — plus a
         *  permanent Reconnect action. */
        Session("session", "Status", null, R.drawable.ic_metric_clock),
        ;

        // Economy / Fill-ups / Costs were here and were removed deliberately.
        //
        // The app declares androidx.car.app.category.IOT — the only viable
        // category, since there is no telemetry one. IOT's rule permits
        // viewing CURRENT STATE and simple one-touch actions while driving.
        // Monthly spend, cost-per-mile and fill-up history are retrospective
        // analytics: they are what the phone and the web dashboard are for,
        // and on the car surface they read as out-of-category.
        //
        // It is also the ADR-013 split holding: the car is a glance, the
        // phone is where you read. resolveTabs() drops unknown stored ids, so
        // anyone who had selected these falls back to the defaults with no
        // migration.

        val isMetricGrid: Boolean get() = defaults != null

        companion object {
            fun byId(id: String): CarScreenKind? = entries.firstOrNull { it.id == id }

            /** TabTemplate's documented maximum. There is no constant in
             *  ConstraintManager to read it from, so it is validated on a real
             *  host rather than asserted. */
            const val MAX_TABS = 4

            /**
             * Status in, Diag out: "is it connected and uploading?" is what a
             * driver actually checks mid-trip; fuel trims are for the phone.
             * Diag remains one Settings tap away.
             */
            val DEFAULT_TABS: List<String> = listOf("drive", "engine", "fuel", "session")

            /**
             * The tabs to render. Falls back to the defaults when the stored
             * list is empty or resolves to nothing, and truncates to
             * MAX_TABS — the host rejects more, and a rejected template
             * takes the car app down rather than degrading.
             */
            fun resolveTabs(stored: List<String>): List<CarScreenKind> {
                val picked = stored.mapNotNull { byId(it) }.distinct().take(MAX_TABS)
                return picked.ifEmpty { DEFAULT_TABS.mapNotNull { byId(it) } }
            }
        }
    }

    fun byKey(key: String): CarTileSpec? = ALL.firstOrNull { it.key == key }

    /** Resolve a stored config into a list of specs, falling back to the default. */
    fun resolveHome(stored: List<String>): List<CarTileSpec> = resolve(stored, DEFAULT_HOME)

    fun resolveDiag(stored: List<String>): List<CarTileSpec> = resolve(stored, DEFAULT_DIAG)

    /** Tiles for one tab, honouring the user's stored order for it. */
    fun resolveTab(kind: CarScreenKind, stored: List<String>): List<CarTileSpec> =
        resolve(stored, kind.defaults ?: emptyList())

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

// ── Tile rendering (pure — unit-tested in CarTileRenderTest) ─────────

/** How loud a tile is. */
enum class TileWarn { None, Caution, Severe }

/** One tile's text plus the state that drives its icon tint. */
data class CarTileRender(val text: String, val warn: TileWarn, val stale: Boolean)

/** Seconds of OBD silence after which a tile shows its age. */
const val TILE_AGE_AFTER_S = 10L

/** Seconds after which the age is replaced by the word "stale". */
const val TILE_STALE_AFTER_S = 60L

/**
 * Round an age down to 5 s buckets. The bucket is what the tile shows AND
 * what the repaint signature hashes, so a quiet tile repaints every 5 s
 * rather than every tick — still an in-place refresh, just a calmer one.
 */
fun ageBucketS(ageS: Long?): Long? = ageS?.let { (it / 5L) * 5L }

/**
 * Everything a car tile says, derived in one place so the template and the
 * repaint signature can never disagree:
 *
 *   "86 °C ▲"          fresh, slow metric rising
 *   "112 °C HOT"       past a bound (the word, not just a colour)
 *   "86 °C · 25s"      no new sample for 25 s
 *   "86 °C · stale"    a minute or more
 *   "Engine off"       no value at all — [emptyReason] explains why
 */
fun renderCarTile(
    value: Double?,
    spec: CarTileSpec,
    system: String,
    trend: TrendDir,
    ageS: Long?,
    emptyReason: String? = null,
): CarTileRender {
    val num = spec.quantity.number(value, system, spec.digits)
    if (value == null || num == "—") return CarTileRender(emptyReason ?: "—", TileWarn.None, stale = false)
    val unit = spec.unit(system)
    val warn = when {
        spec.warnHigh != null && value > spec.warnHigh -> spec.warnHighWord
        spec.warnLow != null && value < spec.warnLow -> spec.warnLowWord
        else -> null
    }
    val level = when {
        warn == null -> TileWarn.None
        spec.warnSevere -> TileWarn.Severe
        else -> TileWarn.Caution
    }
    val bucket = ageBucketS(ageS)
    val stale = bucket != null && bucket >= TILE_AGE_AFTER_S
    val text = buildString {
        append(if (unit.isBlank()) num else "$num $unit")
        if (warn != null) append(" ").append(warn)
        when {
            bucket != null && bucket >= TILE_STALE_AFTER_S -> append(" · stale")
            stale -> append(" · ${bucket}s")
            // Arrows only on fresh, slow metrics — see CarTileSpec.trend.
            spec.trend && trend == TrendDir.Up -> append(" ▲")
            spec.trend && trend == TrendDir.Down -> append(" ▼")
        }
    }
    return CarTileRender(text, level, stale)
}
