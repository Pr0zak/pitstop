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
private const val KM_TO_MI = 0.621371
private const val MI_TO_KM = 1.609344
private const val USGAL_TO_L = 3.785411784

/** 1 mpg (US) = 235.215 / (L/100 km). The conversion is an inversion,
 *  not a scale — so is the direction of "better". */
private const val MPG_L100_CONSTANT = 235.214583

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
        SpeedKph("km/h", "mph", toImperial = { it * KM_TO_MI }),

        /**
         * GPS speed arrives from the fused location provider in m/s,
         * which NEITHER unit system displays — metric users want km/h.
         * So this one converts on both branches.
         */
        SpeedMps("km/h", "mph", toMetric = { it * 3.6 }, toImperial = { it * 2.23694 }),

        DistanceKm("km", "mi", toImperial = { it * KM_TO_MI }),

        /**
         * A distance stored in MILES — fillup odometers, which are typed off
         * a US dash and stored in the vehicle's dist_unit (miles here).
         */
        DistanceMi("km", "mi", toMetric = { it * MI_TO_KM }),

        /** Fuel volume — tank size, gas used on a trip, fillup amount. */
        VolumeL("L", "gal", toImperial = { it * L_TO_USGAL }),

        /** Fuel volume stored in US gallons — fillup amounts. */
        VolumeGal("L", "gal", toMetric = { it * USGAL_TO_L }),

        /** A duration already in minutes — trip time, idle time. */
        Minutes("min", "min"),

        /**
         * Fuel economy stored in US mpg. Metric inverts to L/100 km, so the
         * direction of "better" flips — see [economyHigherIsBetter].
         */
        EconomyMpg("L/100km", "mpg", toMetric = { MPG_L100_CONSTANT / it }),

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

    // ── Fuel economy, distance, volume, price ─────────────────────────
    //
    // Fillup and analytics payloads arrive in US units (mpg, gal, $/gal,
    // $/mi — the Fuelio data is imperial); trips arrive in km / L. These
    // helpers are the ONE place that knows how each renders per system, so
    // no screen multiplies by 0.621371 or glues "mpg" onto a number again.

    /** "12.3 mi" / "19.8 km" from kilometres. */
    fun distanceKm(km: Double?, system: String, digits: Int = 1): String =
        Quantity.DistanceKm.format(km, system, digits)

    /** "76,304 mi" / "122,800 km" from MILES, grouped — for odometers. */
    fun odometerMi(mi: Double?, system: String): String {
        if (mi == null || mi.isNaN()) return "—"
        val v = Quantity.DistanceMi.convert(mi, system)
        return "%,.0f %s".format(v, Quantity.DistanceMi.unit(system))
    }

    /** "12.40 gal" / "46.94 L" from US gallons. */
    fun volumeGal(gal: Double?, system: String, digits: Int = 2): String =
        Quantity.VolumeGal.format(gal, system, digits)

    /** "0.50 gal" / "1.90 L" from litres. */
    fun volumeL(l: Double?, system: String, digits: Int = 2): String =
        Quantity.VolumeL.format(l, system, digits)

    /** Unit label for fuel economy: "mpg" / "L/100km". */
    fun economyUnit(system: String): String =
        if (system == "imperial") "mpg" else "L/100km"

    /**
     * Whether a larger economy number is better. mpg: yes. L/100 km: no —
     * any trend arrow / delta colour must flip with the unit system, or a
     * worse tank reads green for a metric user.
     */
    fun economyHigherIsBetter(system: String): Boolean = system == "imperial"

    /** mpg → display value (mpg or L/100km). Null / non-positive → null. */
    fun economyValue(mpg: Double?, system: String): Double? {
        if (mpg == null || mpg.isNaN() || mpg <= 0.0) return null
        return if (system == "imperial") mpg else MPG_L100_CONSTANT / mpg
    }

    /** Bare economy number: "20.4" (mpg) / "11.5" (L/100km). */
    fun economyNumber(mpg: Double?, system: String, digits: Int = 1): String =
        economyValue(mpg, system)?.let { "%.${digits}f".format(it) } ?: "—"

    /** "20.4 mpg" / "11.5 L/100km". */
    fun economy(mpg: Double?, system: String, digits: Int = 1): String {
        val n = economyNumber(mpg, system, digits)
        return if (n == "—") n else "$n ${economyUnit(system)}"
    }

    /** mpg from a distance in km and fuel in litres; null when unusable. */
    fun mpgFrom(distanceKm: Double?, fuelL: Double?): Double? {
        if (distanceKm == null || fuelL == null || fuelL <= 0.0 || distanceKm <= 0.0) return null
        return (distanceKm * KM_TO_MI) / (fuelL * L_TO_USGAL)
    }

    /**
     * Instantaneous economy from the ECU fuel rate (g/s, PID 0x9D) and road
     * speed (km/h). Null below walking pace — at a standstill the ratio
     * divides by ~0 and "0.3 mpg" is noise, not information.
     */
    fun instantMpg(fuelRateGramsPerSec: Double?, speedKph: Double?): Double? {
        if (fuelRateGramsPerSec == null || speedKph == null) return null
        if (speedKph < 3.0 || fuelRateGramsPerSec <= 0.0) return null
        val litresPerHour = fuelRateGramsPerSec * 3600.0 / GASOLINE_G_PER_L
        return mpgFrom(speedKph, litresPerHour)
    }

    /** Price-per-volume unit label: "/gal" / "/L". */
    fun perVolumeUnit(system: String): String = if (system == "imperial") "/gal" else "/L"

    /** $/gal → display value ($/gal or $/L). */
    fun pricePerVolumeValue(perGal: Double?, system: String): Double? {
        if (perGal == null || perGal.isNaN()) return null
        return if (system == "imperial") perGal else perGal / USGAL_TO_L
    }

    /** "$3.290/gal" / "$0.869/L". */
    fun pricePerVolume(
        perGal: Double?,
        system: String,
        digits: Int = 3,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String {
        val v = pricePerVolumeValue(perGal, system) ?: return "—"
        return money(v, digits, locale) + perVolumeUnit(system)
    }

    /** Distance-cost unit label: "/mi" / "/km". */
    fun perDistanceUnit(system: String): String = if (system == "imperial") "/mi" else "/km"

    /** $/mi → display value ($/mi or $/km). */
    fun costPerDistanceValue(perMi: Double?, system: String): Double? {
        if (perMi == null || perMi.isNaN()) return null
        return if (system == "imperial") perMi else perMi / MI_TO_KM
    }

    /** "$0.152/mi" / "$0.094/km". */
    fun costPerDistance(
        perMi: Double?,
        system: String,
        digits: Int = 3,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String {
        val v = costPerDistanceValue(perMi, system) ?: return "—"
        return money(v, digits, locale) + perDistanceUnit(system)
    }

    /**
     * Locale-aware currency. The amounts are whatever the user typed at the
     * pump, in their own currency, so the symbol and grouping come from the
     * device locale rather than a hard-coded "$".
     */
    fun money(
        v: Double?,
        digits: Int = 2,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String {
        if (v == null || v.isNaN()) return "—"
        // A language-only locale ("en") has no currency and would print the
        // generic "¤"; the data is US-sourced, so fall back to US dollars.
        val loc = if (locale.country.isNullOrBlank()) java.util.Locale.US else locale
        val nf = java.text.NumberFormat.getCurrencyInstance(loc)
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        return nf.format(v)
    }

    /** Locale-grouped integer: "18,402" (en) / "18.402" (de). */
    fun count(
        n: Long,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String = java.text.NumberFormat.getIntegerInstance(locale).format(n)
}
