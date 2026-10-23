package com.pitstop.domain

import com.pitstop.http.InsightChartDto
import com.pitstop.http.InsightRefDto
import com.pitstop.http.InsightSeriesDto
import com.pitstop.http.TireAnalyticsDto
import com.pitstop.http.TireCornerHistoryDto
import com.pitstop.http.TireDayDto
import com.pitstop.util.UnitFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure shaping for the Tire detail screen (GET /analytics/tires). Mirrors the
 * web's lib/tireAnalytics.ts + TireAnalyticsSection.vue: the server computed
 * every number (cold detection, slope, slow-leak flag, placard verdict); this
 * only formats and lays them out. Pressure is always whole psi (dash parity);
 * temperatures follow the unit system.
 */
object TireHistory {

    /** The range chips. The endpoint takes 7..365 days. */
    val RANGES = listOf(7, 30, 90)
    const val DEFAULT_DAYS = 30

    enum class PressureMode(val label: String) {
        /** First reading after the car sat parked a few hours — the comparable one. */
        Cold("Cold"),

        /** The day's average of every reading, warm ones included. */
        Average("Daily average"),
    }

    /** "2026-10-21" (a UTC day) → epoch ms at 12:00 UTC, so it sits mid-day in any US zone. */
    fun dayToMs(day: String): Double =
        LocalDate.parse(day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli().toDouble()

    private fun corner(dto: TireAnalyticsDto, c: VehicleSignals.Corner): TireCornerHistoryDto? =
        dto.corners.firstOrNull { it.corner.equals(c.name, ignoreCase = true) }

    /**
     * Pressure over time, one line per corner (FL, FR, RL, RR — always all
     * four series, in that order, so colours stay fixed), psi to one decimal.
     * Days without a value are skipped and the line joins across them, like
     * the web. The door placard rides as the dashed reference when both axles
     * share it (or only one is set). Null when no corner has a value.
     */
    fun pressureChart(dto: TireAnalyticsDto, mode: PressureMode): InsightChartDto? {
        val pick: (TireDayDto) -> Double? = when (mode) {
            PressureMode.Cold -> { d -> d.coldPressureKpa }
            PressureMode.Average -> { d -> d.pressureMeanKpa }
        }
        val series = VehicleSignals.Corner.entries.map { c ->
            val pts = corner(dto, c)?.daily.orEmpty().mapNotNull { d ->
                val kpa = pick(d)?.takeIf { it > 0 } ?: return@mapNotNull null
                listOf<Double?>(dayToMs(d.day), round1(kpa * VehicleSignals.PSI_PER_KPA))
            }
            InsightSeriesDto(c.label, pts)
        }
        if (series.all { it.points.isEmpty() }) return null
        return InsightChartDto(
            kind = "lines",
            xType = "time",
            yUnit = if (mode == PressureMode.Cold) "psi, cold" else "psi, daily average",
            series = series,
            ref = placardRef(dto),
        )
    }

    /** One placard line, or null when there is none or the axles differ. */
    fun placardRef(dto: TireAnalyticsDto): InsightRefDto? {
        val f = dto.placardFrontKpa?.takeIf { it > 0 }
        val r = dto.placardRearKpa?.takeIf { it > 0 }
        val kpa = when {
            f != null && r != null -> if (abs(f - r) < 0.5) f else return null
            else -> f ?: r ?: return null
        }
        val psi = (kpa * VehicleSignals.PSI_PER_KPA).roundToInt()
        return InsightRefDto(psi.toDouble(), "Door placard $psi psi")
    }

    /** "placard 35 psi" / "placard F 35 / R 38 psi"; null without one. */
    fun placardText(dto: TireAnalyticsDto): String? {
        val f = dto.placardFrontKpa?.takeIf { it > 0 }?.let { (it * VehicleSignals.PSI_PER_KPA).roundToInt() }
        val r = dto.placardRearKpa?.takeIf { it > 0 }?.let { (it * VehicleSignals.PSI_PER_KPA).roundToInt() }
        return when {
            f == null && r == null -> null
            f != null && r != null && f != r -> "Door placard: front $f psi, rear $r psi"
            else -> "Door placard: ${f ?: r} psi"
        }
    }

    /**
     * Daily hottest tire temperature per corner plus the day's average
     * outside temperature (series "Outside"), both in the display unit on one
     * axis — the web's second chart. Null when no corner has a temperature.
     */
    fun tempChart(dto: TireAnalyticsDto, system: String): InsightChartDto? {
        val tempQ = UnitFormat.Quantity.TempC
        val series = VehicleSignals.Corner.entries.map { c ->
            val pts = corner(dto, c)?.daily.orEmpty().mapNotNull { d ->
                val t = d.tempMaxC?.takeIf { VehicleSignals.validTireTempC(it) } ?: return@mapNotNull null
                listOf<Double?>(dayToMs(d.day), round1(tempQ.convert(t, system)))
            }
            InsightSeriesDto(c.label, pts)
        }.toMutableList()
        if (series.all { it.points.isEmpty() }) return null
        val outside = dto.ambient.mapNotNull { a ->
            val f = a.ambientMeanF ?: return@mapNotNull null
            listOf<Double?>(dayToMs(a.day), round1(UnitFormat.Quantity.TempF.convert(f, system)))
        }
        if (outside.isNotEmpty()) series += InsightSeriesDto(OUTSIDE, outside)
        return InsightChartDto(kind = "lines", xType = "time", yUnit = tempQ.unit(system), series = series)
    }

    const val OUTSIDE = "Outside"

    /** One corner's card on the detail screen, already formatted. */
    data class CornerStat(
        val corner: VehicleSignals.Corner,
        /** Current cold pressure, whole psi, or "—". */
        val coldPsi: String,
        /** "cold · Oct 23" / "no cold reading yet". */
        val coldCaption: String,
        /** "−0.4 psi/week", or "needs 4 cold readings (have 2)". */
        val trend: String,
        val trendKnown: Boolean,
        /** "highest" / "−3 psi vs highest" / null with < 2 corners. */
        val spread: String?,
        /** "−4 psi" vs placard; null without a placard or cold reading. */
        val vsPlacard: String?,
        /** "+2.5 psi" median warm-up while driving; null when unknown. */
        val warmup: String?,
        val low: Boolean,
        val slowLeak: Boolean,
        val belowSpec: Boolean,
        val underInflated: Boolean,
    ) {
        /** The worst flag in plain words, or null when the tire looks fine. */
        val flag: String?
            get() = when {
                underInflated -> "Under-inflated"
                slowLeak -> "Possible slow leak"
                belowSpec -> "Below spec"
                low -> "Low"
                else -> null
            }

        /** Red (true) for under-inflated / slow leak, amber otherwise. */
        val flagSevere: Boolean get() = underInflated || slowLeak
    }

    fun cornerStats(dto: TireAnalyticsDto, zone: ZoneId = ZoneId.systemDefault()): List<CornerStat> {
        val cold = VehicleSignals.Corner.entries.associateWith { corner(dto, it)?.currentColdKpa?.takeIf { p -> p > 0 } }
        val tires = VehicleSignals.tpms(cold).associateBy { it.corner }
        val known = cold.values.filterNotNull()
        val minSamples = (dto.slowLeakMinSamples ?: 4.0).roundToInt()
        return VehicleSignals.Corner.entries.map { c ->
            val h = corner(dto, c)
            val p = cold[c]
            val samples = (h?.slopeSamples ?: 0.0).roundToInt()
            val slope = h?.slopePsiPerWeek
            val trendKnown = slope != null && samples >= minSamples
            val spread = if (known.size >= 2 && p != null) {
                val d = ((p - known.max()) * VehicleSignals.PSI_PER_KPA).roundToInt()
                if (d == 0) "highest" else "−${abs(d)} psi vs highest"
            } else {
                null
            }
            CornerStat(
                corner = c,
                coldPsi = VehicleSignals.tirePressure(p),
                coldCaption = run {
                    val label = if (h?.currentColdEstimated == true) "est. cold, air added" else "cold"
                    h?.currentColdAt?.let { at ->
                        runCatching { "$label · " + DAY_FMT.format(OffsetDateTime.parse(at).atZoneSameInstant(zone)) }.getOrNull()
                    } ?: if (p != null) label else "no cold reading yet"
                },
                trend = if (trendKnown) {
                    psiPerWeek(slope)
                } else if (h?.airedUpAt != null) {
                    "needs $minSamples cold readings since air was added (have $samples)"
                } else {
                    "needs $minSamples cold readings (have $samples)"
                },
                trendKnown = trendKnown,
                spread = spread,
                vsPlacard = h?.belowPlacardPsi?.let { placardDelta(it) + " psi" },
                warmup = h?.warmupMedianPsi?.let { "+" + String.format(Locale.US, "%.1f", it) + " psi" },
                low = tires[c]?.low == true,
                slowLeak = h?.possibleSlowLeak == true,
                belowSpec = h?.belowSpec == true,
                underInflated = h?.underInflated == true,
            )
        }
    }

    /**
     * The server's tire insights (slow leak with its temperature-corrected
     * pressure chart, etc.) for the "Leak check" section. The "tires" group
     * also carries brake and speedometer cards, so keep tire_* ids only.
     */
    fun tireInsights(cards: List<com.pitstop.http.InsightCardDto>): List<com.pitstop.http.InsightCardDto> =
        cards.filter { it.group == "tires" && it.id.startsWith("tire") }

    /** "−0.4 psi/week" (true minus sign). */
    fun psiPerWeek(v: Double?): String {
        if (v == null || !v.isFinite()) return "—"
        val r = Math.round(v * 10.0) / 10.0
        val sign = when {
            r > 0 -> "+"
            r < 0 -> "−"
            else -> ""
        }
        return sign + String.format(Locale.US, "%.1f", abs(r)) + " psi/week"
    }

    /** psi UNDER placard → "−4" / "+1" / "±0" (whole psi), as the web does. */
    fun placardDelta(belowPsi: Double): String {
        val d = -Math.round(belowPsi).toInt()
        return when {
            d > 0 -> "+$d"
            d < 0 -> "−${-d}"
            else -> "±0"
        }
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0

    private val DAY_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)
}
