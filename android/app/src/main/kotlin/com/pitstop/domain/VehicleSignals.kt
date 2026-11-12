package com.pitstop.domain

import kotlin.math.roundToInt

/**
 * Display rules for the Honda Mode 22 signals (WiCAN firmware 6 profile,
 * 2026-10-21): gear, ATF temp, cylinder deactivation, per-cylinder misfires,
 * cluster odometer / fuel litres, ambient temp and four-corner TPMS.
 *
 * Mirrors `frontend/src/lib/vehicleSignals.ts` rule for rule (web/phone
 * parity, ADR-013) — change both together. Pure Kotlin so it is unit-tested
 * and shared by Live, Home and the Android Auto tiles.
 */
object VehicleSignals {

    // ── Metric names (canonical, as ingested) ───────────────────────────
    const val GEAR = "gear_position"
    const val ATF_TEMP = "atf_temp"
    const val ACTIVE_CYLINDERS = "active_cylinders"
    const val ODOMETER_DASH = "odometer_dash"
    const val FUEL_LEVEL_L = "fuel_level_l"
    const val AMBIENT_F = "ambient_air_temp_f"
    const val OIL_LIFE = "oil_life_pct"

    fun misfireMetric(cyl: Int): String = "misfire_count_$cyl"
    val MISFIRE_CYLINDERS: List<Int> = (1..6).toList()

    /**
     * ZF 9HP `gear_position`: 0 = Park OR Neutral (indistinguishable), 1–9 =
     * forward gear, 15 = Reverse. Stopped in Drive reads 2 — the 9HP launches
     * in second — and is shown as-is ("D2"). An undocumented code shows raw.
     */
    fun gearLabel(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "—"
        val g = v.roundToInt()
        return when {
            g == 0 -> "P/N"
            g == 15 -> "R"
            g in 1..9 -> "D$g"
            else -> g.toString()
        }
    }

    /** Anything 1–5 is a Variable Cylinder Management state (3 on the Pilot). */
    fun vcmActive(v: Double?): Boolean {
        if (v == null || v.isNaN() || v.isInfinite() || v <= 0.0) return false
        return v.roundToInt() < 6
    }

    /** "6" or "3 (VCM)". */
    fun cylindersLabel(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "—"
        val n = v.roundToInt()
        return if (vcmActive(v)) "$n (VCM)" else n.toString()
    }

    /** `ambient_air_temp_f` arrives in °F; everything renders from °C. */
    fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    // ── TPMS ────────────────────────────────────────────────────────────

    enum class Corner(val label: String) {
        FL("FL"), FR("FR"), RL("RL"), RR("RR");

        val pressureMetric: String get() = "tire_pressure_${name.lowercase()}"
        val tempMetric: String get() = "tire_temp_${name.lowercase()}"
    }

    /**
     * A tire is low when it reads at least this far below the HIGHEST of the
     * four. Relative, so it needs no placard pressure and can't fire on a
     * uniform cold-morning drop. 2 psi, not 3: on 2026-10-21 the dash lit
     * TPMS for FR at 28.4 psi against a 30.9 psi high (2.5 psi spread). The
     * threshold is in psi whatever the display unit.
     *
     * When the corner's door placard is known, a tire at or above it is never
     * low, whatever the spread: on 2026-11-11 the set read 38/38/37/35 psi on
     * a 35 psi placard and RR showed amber for being 2.8 psi under FR, though
     * every tire was fully inflated. Mirrors backend tires.low_tire_corners.
     */
    const val LOW_DELTA_PSI = 2.0
    const val PSI_PER_KPA = 0.145038

    data class Tire(
        val corner: Corner,
        /** kPa as stored. */
        val pressureKpa: Double?,
        /** °C as stored. */
        val tempC: Double?,
        val low: Boolean,
    )

    /** Four tires (always FL, FR, RL, RR) with the low flag applied. */
    fun tpms(
        pressureKpa: Map<Corner, Double?>,
        tempC: Map<Corner, Double?> = emptyMap(),
        placardFrontKpa: Double? = null,
        placardRearKpa: Double? = null,
    ): List<Tire> {
        val known = Corner.entries.mapNotNull { c -> pressureKpa[c]?.takeIf { it.isFinite() } }
        // One reading can't be "below" itself.
        val max = if (known.size >= 2) known.max() else null
        val threshold = LOW_DELTA_PSI / PSI_PER_KPA
        return Corner.entries.map { c ->
            val p = pressureKpa[c]?.takeIf { it.isFinite() }
            val plc = placardFor(c, placardFrontKpa, placardRearKpa)?.takeIf { it > 0 }
            Tire(
                corner = c,
                pressureKpa = p,
                tempC = tempC[c]?.takeIf { it.isFinite() },
                // 1e-6 slack so exactly 2.0 psi (after the kPa round-trip) counts.
                low = p != null && max != null && max - p >= threshold - 1e-6 &&
                    (plc == null || p < plc),
            )
        }
    }

    /**
     * TPMS from a metric snapshot (`metric -> value`). Null when no corner
     * has ever reported a pressure — the Home card hides only then.
     */
    fun tpmsFrom(values: Map<String, Double>): List<Tire>? {
        val p = Corner.entries.associateWith { values[it.pressureMetric] }
        if (p.values.all { it == null }) return null
        return tpms(p, Corner.entries.associateWith { values[it.tempMetric] })
    }

    /** Newest timestamp among the four pressure readings. */
    fun newestPressureMs(timestamps: Map<String, Long>): Long? =
        Corner.entries.mapNotNull { timestamps[it.pressureMetric] }.maxOrNull()

    /**
     * Tire pressure in whole psi — always, whatever the unit system, because
     * that is what the dash TPMS page shows (user preference 2026-10-21; the
     * web's fmtTirePressure does the same). Temps still follow the units.
     */
    fun tirePressure(kpa: Double?): String {
        if (kpa == null || !kpa.isFinite()) return "—"
        return "${(kpa * PSI_PER_KPA).roundToInt()}"
    }

    const val TIRE_PRESSURE_UNIT = "psi"

    // ── Door placard (cold pressure) — mirrors backend tires.py ─────────

    /** At least this many psi under placard → amber delta ("below spec"). */
    const val BELOW_SPEC_PSI = 3.0

    /** At or below placard × (1 − this) → red "under" (under-inflated). */
    const val UNDER_INFLATED_FRACTION = 0.25

    /** Plausible tire temperatures; outside is a sentinel / noise (tires.py). */
    fun validTireTempC(c: Double?): Boolean = c != null && c >= -50.0 && c <= 120.0

    data class PlacardVerdict(
        /** Whole psi of the placard, e.g. 35. */
        val placardPsi: Int,
        /** psi UNDER placard, one decimal; negative = above. */
        val belowPsi: Double,
        val belowSpec: Boolean,
        val underInflated: Boolean,
    ) {
        /** "−4" / "+1" / "±0" against the placard, whole psi. */
        val deltaLabel: String
            get() {
                val d = -Math.round(belowPsi).toInt()
                return when {
                    d < 0 -> "−${-d}"
                    d > 0 -> "+$d"
                    else -> "±0"
                }
            }
    }

    /** Front placard for FL/FR, rear for RL/RR. */
    fun placardFor(corner: Corner, frontKpa: Double?, rearKpa: Double?): Double? =
        if (corner == Corner.FL || corner == Corner.FR) frontKpa else rearKpa

    /**
     * Client-side placard verdict for a LIVE pressure (Live, Home, widget).
     * Same thresholds as the server's placard_verdict, which the trip card
     * uses as given. Null without a pressure or a placard.
     */
    fun placardVerdict(pressureKpa: Double?, placardKpa: Double?): PlacardVerdict? {
        if (pressureKpa == null || placardKpa == null || pressureKpa <= 0 || placardKpa <= 0) return null
        val below = (placardKpa - pressureKpa) * PSI_PER_KPA
        return PlacardVerdict(
            placardPsi = (placardKpa * PSI_PER_KPA).roundToInt(),
            belowPsi = Math.round(below * 10.0) / 10.0,
            belowSpec = below >= BELOW_SPEC_PSI - 1e-6,
            underInflated = pressureKpa <= placardKpa * (1 - UNDER_INFLATED_FRACTION) + 1e-6,
        )
    }

    /** "live" under 90 s, then "Nm ago" / "Nh ago" / "Nd ago" — web parity. */
    fun ageLabel(tsMs: Long, nowMs: Long): String {
        val s = ((nowMs - tsMs) / 1000L).coerceAtLeast(0L)
        return when {
            s < 90 -> "live"
            s < 3_600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3_600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    // ── Tire status + one-line verdict (TpmsGrid; web tireStatus/tireHeadline) ──

    /**
     * How a tire is drawn. LOW (≥ 2 psi below the highest tire, the dash's
     * own rule) is the one to act on and gets full amber; UNDER_PLACARD (only
     * ≥ 3 psi under the door placard, often just a cold morning) gets a dim
     * amber so the low tire still stands out; UNDER_INFLATED (≥ 25 % under
     * placard, the car's warning light) is red. NONE = no reading.
     */
    enum class TireStatus { NONE, GOOD, UNDER_PLACARD, LOW, UNDER_INFLATED }

    fun tireStatus(tire: Tire?, placardKpa: Double?): TireStatus {
        val p = tire?.pressureKpa ?: return TireStatus.NONE
        val v = placardVerdict(p, placardKpa)
        return when {
            v?.underInflated == true -> TireStatus.UNDER_INFLATED
            tire.low -> TireStatus.LOW
            v?.belowSpec == true -> TireStatus.UNDER_PLACARD
            else -> TireStatus.GOOD
        }
    }

    /** "FL", "FL and FR", "FL, FR and RL". */
    private fun cornerList(cs: List<Corner>): String =
        if (cs.size == 1) cs[0].label
        else cs.dropLast(1).joinToString(", ") { it.label } + " and " + cs.last().label

    /**
     * The whole set in one sentence, worst status first: "FR is under-inflated:
     * add air now", "FR is 3 psi lower than the others", "2 tires under the
     * 35 psi placard", "All four tires look good". Null when no tire reports.
     */
    fun tireHeadline(tires: List<Tire>, placardFrontKpa: Double?, placardRearKpa: Double?): Pair<TireStatus, String>? {
        val st = tires.associate { it.corner to tireStatus(it, placardFor(it.corner, placardFrontKpa, placardRearKpa)) }
        val reporting = st.filterValues { it != TireStatus.NONE }
        if (reporting.isEmpty()) return null
        val worst = reporting.values.maxBy { it.ordinal }
        val hit = Corner.entries.filter { st[it] == worst }
        val text = when (worst) {
            TireStatus.UNDER_INFLATED ->
                "${cornerList(hit)} ${if (hit.size == 1) "is" else "are"} under-inflated: add air now"
            TireStatus.LOW -> if (hit.size == 1) {
                val highest = tires.mapNotNull { it.pressureKpa }.max()
                // Difference of the whole-psi numbers on screen, so "3 psi lower"
                // matches 31 vs 28 even when the raw gap is 2.5.
                val gap = (highest * PSI_PER_KPA).roundToInt() -
                    (tires.first { it.corner == hit[0] }.pressureKpa!! * PSI_PER_KPA).roundToInt()
                "${hit[0].label} is $gap psi lower than the others"
            } else "${cornerList(hit)} are low"
            TireStatus.UNDER_PLACARD -> {
                val f = placardFrontKpa?.let { (it * PSI_PER_KPA).roundToInt() }
                val r = placardRearKpa?.let { (it * PSI_PER_KPA).roundToInt() }
                val which = if (f != null && (r == null || r == f)) "the $f psi placard" else "the door placard"
                "${hit.size} ${if (hit.size == 1) "tire" else "tires"} under $which"
            }
            else -> if (reporting.size == Corner.entries.size) "All four tires look good" else "All reporting tires look good"
        }
        return worst to text
    }

    // ── Oil life (Maintenance Minder; web oilLifeStatus/oilLifeHeadline) ──

    /** Honda's Minder shows "due soon" at 15 % and "due now" at 5 %. */
    const val OIL_DUE_SOON_PCT = 15.0
    const val OIL_DUE_NOW_PCT = 5.0

    enum class OilStatus { NONE, GOOD, DUE_SOON, DUE_NOW }

    fun oilLifeStatus(pct: Double?): OilStatus = when {
        pct == null || !pct.isFinite() -> OilStatus.NONE
        pct <= OIL_DUE_NOW_PCT -> OilStatus.DUE_NOW
        pct <= OIL_DUE_SOON_PCT -> OilStatus.DUE_SOON
        else -> OilStatus.GOOD
    }

    /** One line under the percentage, matching the dash's wording. */
    fun oilLifeHeadline(pct: Double?): String = when (oilLifeStatus(pct)) {
        OilStatus.NONE -> "No reading yet"
        OilStatus.DUE_NOW -> "Oil change due now"
        OilStatus.DUE_SOON -> "Oil change due soon"
        OilStatus.GOOD -> "No service needed yet"
    }
}
