package com.pitstop.util

/**
 * Unit-aware formatters for telemetry values. The bridge service stores
 * canonical metric units on the wire (°C, km/h, kPa, g/s, m, m/s) —
 * these helpers convert at render time based on the user's "imperial" /
 * "metric" preference in SettingsRepository.
 *
 * Every screen that renders a raw metric value MUST go through
 * [UnitFormat.Quantity] rather than hardcoding a unit string. A
 * hardcoded "L/h" / "kph" / "°C" is a bug for an imperial user and a
 * hardcoded "mph" / "°F" is a bug for a metric one; the enum is the
 * single place that knows which is which.
 */
private const val GASOLINE_G_PER_L = 749.9
private const val L_TO_USGAL = 0.264172

object UnitFormat {

    /**
     * A canonical telemetry quantity plus how it renders in each unit
     * system. [convert] maps the on-the-wire value into the display
     * unit, [unit] names that unit, [number] renders the bare number
     * and [format] renders "number unit".
     *
     * Dimensionless quantities (%, V, rpm, λ, degrees) are listed too
     * so a call site never has to decide between "use the enum" and
     * "pass a raw string" — everything is a Quantity.
     */
    enum class Quantity(
        private val metricUnit: String,
        private val imperialUnit: String,
        private val toMetric: (Double) -> Double = { it },
        private val toImperial: (Double) -> Double = { it },
    ) {
        // ── Dimensionless: identical in both systems ──────────────────
        None("", ""),
        Percent("%", "%"),
        Volt("V", "V"),
        Rpm("rpm", "rpm"),
        Seconds("s", "s"),
        Degrees("°", "°"),

        /**
         * Equivalence ratio / lambda. 1.000 = stoichiometric. There is
         * no imperial variant — a ratio is a ratio — so both systems
         * see λ.
         */
        Lambda("λ", "λ"),

        // ── Converted ────────────────────────────────────────────────
        TempC("°C", "°F", toImperial = { it * 9.0 / 5.0 + 32.0 }),
        SpeedKph("km/h", "mph", toImperial = { it * 0.621371 }),

        /**
         * GPS speed arrives from the fused location provider in m/s,
         * which NEITHER unit system displays — metric users want km/h.
         * So this one converts on both branches.
         */
        SpeedMps("km/h", "mph", toMetric = { it * 3.6 }, toImperial = { it * 2.23694 }),

        DistanceKm("km", "mi", toImperial = { it * 0.621371 }),
        AltitudeM("m", "ft", toImperial = { it * 3.28084 }),
        PressureKpa("kPa", "psi", toImperial = { it * 0.145038 }),

        /** MAF air mass flow, PID 0x10 / 0x66. */
        MassFlowGramsPerSec("g/s", "lb/min", toImperial = { it * 0.132277 }),

        /**
         * Exhaust mass flow (PID 0x9E). Deliberately kg/h in BOTH
         * systems: unlike MAF there is no imperial convention for
         * exhaust flow that a driver would recognise — every scan tool
         * on the market shows kg/h — so "converting" it to lb/h would
         * make the number less readable, not more. Modelled as its own
         * Quantity anyway so the decision is documented in one place
         * instead of being an unexplained literal at a call site.
         */
        MassFlowKgPerHour("kg/h", "kg/h"),

        /**
         * ECU-computed fuel rate (custom PID 0x9D) arrives as a MASS
         * rate in g/s, which means nothing at a glance. Both systems
         * therefore convert to a volume rate at gasoline's 749.9 g/L:
         *   metric   g/s × 3600 / 749.9            = L/h
         *   imperial (that) × 0.264172             = US gal/h
         * Matches the web's fmtFuelRateLh().
         */
        FuelRateGramsPerSec(
            "L/h",
            "gph",
            toMetric = { it * 3600.0 / GASOLINE_G_PER_L },
            toImperial = { it * 3600.0 / GASOLINE_G_PER_L * L_TO_USGAL },
        ),
        ;

        fun unit(system: String): String =
            if (system == "imperial") imperialUnit else metricUnit

        fun convert(value: Double, system: String): Double =
            if (system == "imperial") toImperial(value) else toMetric(value)

        /** Bare number in the display unit; "—" for null / NaN. */
        fun number(value: Double?, system: String, digits: Int): String {
            if (value == null || value.isNaN() || value.isInfinite()) return "—"
            return "%.${digits}f".format(convert(value, system))
        }

        /** "number unit" in the display unit; "—" for null / NaN. */
        fun format(value: Double?, system: String, digits: Int): String {
            val n = number(value, system, digits)
            val u = unit(system)
            return if (n == "—" || u.isEmpty()) n else "$n $u"
        }
    }

    // ── Legacy call-site shims ────────────────────────────────────────
    // Thin wrappers kept so existing callers keep compiling; new code
    // should use Quantity directly.

    fun temp(c: Double?, system: String, digits: Int = 0): String =
        Quantity.TempC.format(c, system, digits)

    fun speed(kph: Double?, system: String, digits: Int = 0): String =
        Quantity.SpeedKph.format(kph, system, digits)

    /** OBD MAF returns g/s; converts to lb/min for imperial. */
    fun mafGramsPerSec(gps: Double?, system: String, digits: Int = 1): String =
        Quantity.MassFlowGramsPerSec.format(gps, system, digits)

    /** MAP kPa → psi for imperial. */
    fun pressureKpa(kpa: Double?, system: String, digits: Int = 0): String =
        Quantity.PressureKpa.format(kpa, system, digits)

    /** Altitude m → ft for imperial. */
    fun altitudeM(m: Double?, system: String, digits: Int = 0): String =
        Quantity.AltitudeM.format(m, system, digits)

    /** GPS speed m/s → mph for imperial, km/h for metric. */
    fun gpsSpeedMs(ms: Double?, system: String, digits: Int = 0): String =
        Quantity.SpeedMps.format(ms, system, digits)

    /** ECU fuel rate g/s → gph for imperial, L/h for metric. */
    fun fuelRateGramsPerSec(gps: Double?, system: String, digits: Int = 2): String =
        Quantity.FuelRateGramsPerSec.format(gps, system, digits)
}
