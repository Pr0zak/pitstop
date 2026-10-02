package com.pitstop.domain

import com.pitstop.http.EiaWeekDto
import com.pitstop.http.FillupDto
import com.pitstop.http.MpgPointDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Maths behind three charts, shared in spirit with the web's versions
 * (same formulas, same headline numbers):
 *
 *  - A: [mpgOverTime] — monthly MPG over a span, 3-month rolling median.
 *  - B: [marketCompare] — each fillup's $/gal against the EIA US weekly average.
 *  - D: [spendYoy] — cumulative fuel spend by day of year, this year vs last.
 *
 * X values are in DAYS: epoch days for A and B (a date sits at its noon,
 * so `epochDay + 0.5`), days since Jan 1 for D. Every price is $/gal and
 * every economy number mpg — the server's units; screens convert.
 */
object FuelCharts {
    /** Average month length the span cut-off uses (365.25 / 12, rounded). */

    // ── Shared ───────────────────────────────────────────────────────

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of nothing" }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * Gridline values between [lo] and [hi] at a step of 1, 2 or 5 × 10ⁿ
     * (never 2.5, so a label can't round to the wrong value), at most
     * [n] steps across the span.
     */
    fun niceTicks(lo: Double, hi: Double, n: Int = 4): List<Double> {
        val step = niceStep(lo, hi, n)
        val out = mutableListOf<Double>()
        var v = ceil(lo / step) * step
        var guard = 0
        while (v <= hi + 1e-9 && guard++ < 100) {
            // Round away float drift (3.8000000000000003), like toFixed(6).
            out += Math.round(v * 1e6) / 1e6
            v += step
        }
        return out
    }

    fun niceStep(lo: Double, hi: Double, n: Int = 4): Double {
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val mag = 10.0.pow(floor(log10(span / n)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.firstOrNull { span / it <= n } ?: (10 * mag)
    }

    /** Decimals a tick label needs so every gridline prints its true value. */
    fun tickDecimals(step: Double): Int = max(0, -floor(log10(step) + 1e-9).toInt())

    /** [lo]..[hi] widened by [f] of the span on both sides. */
    fun pad(lo: Double, hi: Double, f: Double = 0.08): Pair<Double, Double> {
        val s = (hi - lo).takeIf { it > 0 } ?: 1.0
        return (lo - s * f) to (hi + s * f)
    }

    /** Noon of [d] as fractional epoch days. */
    fun dayX(d: LocalDate): Double = d.toEpochDay() + 0.5

    fun dateOf(x: Double): LocalDate = LocalDate.ofEpochDay(floor(x).toLong())

    /** A fillup's local calendar date. Null when the timestamp can't be read. */
    fun localDate(iso: String, zone: ZoneId): LocalDate? =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate() }
                .getOrNull()
            ?: runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()

    private fun monthName(m: Int) = java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.US)

    /** Jan 1 of each year after [x0] up to [x1], labelled "2024". */
    fun yearTicks(x0: Double, x1: Double): List<Pair<Double, String>> {
        val out = mutableListOf<Pair<Double, String>>()
        for (y in dateOf(x0).year + 1..dateOf(x1).year) {
            out += LocalDate.of(y, 1, 1).toEpochDay().toDouble() to y.toString()
        }
        return out
    }

    /** First of every [every]-th month inside the range ("Mar", "Jan ’26"). */
    fun monthTicks(x0: Double, x1: Double, every: Int): List<Pair<Double, String>> {
        val out = mutableListOf<Pair<Double, String>>()
        var d = dateOf(x0).withDayOfMonth(1).plusMonths(1)
        while (d.toEpochDay() <= x1) {
            if ((d.monthValue - 1) % every == 0) {
                val label = monthName(d.monthValue) +
                    if (d.monthValue == 1) " ’" + (d.year % 100).toString().padStart(2, '0') else ""
                out += d.toEpochDay().toDouble() to label
            }
            d = d.plusMonths(1)
        }
        return out
    }

    // ── A: MPG over time ─────────────────────────────────────────────

    enum class MpgSpan(val months: Int?, val label: String) {
        Year(12, "12 mo"),
        ThreeYears(36, "3 yr"),
        All(null, "All"),
    }

    data class MpgMonth(
        val period: YearMonth,
        /** The 15th at noon, in epoch days. */
        val x: Double,
        val mpg: Double,
        val fills: Int,
        /** Median of this month and up to two earlier ones in the span. */
        val median3: Double,
    )

    data class MpgOverTime(
        val span: MpgSpan,
        val months: List<MpgMonth>,
        /** Fillup-count-weighted mean mpg over [months]. */
        val avgMpg: Double,
    ) {
        val xRange: Pair<Double, Double> get() = (months.first().x - 15) to (months.last().x + 15)

        /** "the last 3 years" — the tail of "average over …". */
        val spanPhrase: String
            get() = when (span) {
                MpgSpan.Year -> "the last 12 months"
                MpgSpan.ThreeYears -> "the last 3 years"
                MpgSpan.All -> "all ${months.size} months"
            }
    }

    /**
     * Monthly points within [span] of the newest month, nulls dropped: exactly
     * `span.months` calendar months ending with the newest one, the same rule
     * as the web chart, so "12 mo" is 12 months on both clients.
     */
    fun mpgOverTime(points: List<MpgPointDto>, span: MpgSpan): MpgOverTime? {
        val all = points.mapNotNull { p ->
            val mpg = p.mpg?.takeIf { it > 0 && !it.isNaN() } ?: return@mapNotNull null
            val ym = runCatching { YearMonth.parse(p.period.take(7)) }.getOrNull() ?: return@mapNotNull null
            Triple(ym, mpg, p.fillupCount ?: 1)
        }.sortedBy { it.first }
        if (all.isEmpty()) return null
        val xs = all.map { dayX(it.first.atDay(15)) }
        val firstMonth = span.months?.let { all.last().first.minusMonths((it - 1).toLong()) }
        val kept = all.indices.filter { firstMonth == null || all[it].first >= firstMonth }
        if (kept.isEmpty()) return null
        val ys = kept.map { all[it].second }
        val months = kept.mapIndexed { i, idx ->
            val (ym, mpg, n) = all[idx]
            MpgMonth(
                period = ym,
                x = xs[idx],
                mpg = mpg,
                fills = n,
                median3 = median(ys.subList(max(0, i - 2), i + 1)),
            )
        }
        val weight = months.sumOf { it.fills }
        val avg = if (weight > 0) {
            months.sumOf { it.mpg * it.fills } / weight
        } else {
            months.sumOf { it.mpg } / months.size
        }
        return MpgOverTime(span, months, avg)
    }

    // ── B: what you paid vs the market ───────────────────────────────

    data class MarketWeek(val weekOf: LocalDate, val x: Double, val price: Double)

    data class PaidFill(
        val fillupId: String,
        val date: LocalDate,
        val x: Double,
        val pricePerGal: Double,
        val gallons: Double?,
        /** EIA price for the week containing (or the latest before) [date]. */
        val marketPrice: Double,
    ) {
        val diff: Double get() = pricePerGal - marketPrice
    }

    data class MarketCompare(
        /** Oldest first. */
        val weeks: List<MarketWeek>,
        /** Oldest first. */
        val fills: List<PaidFill>,
        /** Mean of fillup $/gal − market $/gal; negative = paid less. */
        val meanDiff: Double,
        val gallons: Double,
    ) {
        /** |mean diff| × gallons — what the gap came to in money. */
        val dollars: Double get() = kotlin.math.abs(meanDiff * gallons)
        val below: Boolean get() = meanDiff < 0
        val xRange: Pair<Double, Double>
            get() = (weeks.first().x - 3) to (max(weeks.last().x, fills.last().x) + 3)
    }

    /**
     * Each fillup inside the EIA window (from 3 days before its first week)
     * matched to the EIA week on or before its local date. Null when EIA has
     * no data or no priced fillup falls in the window — the card hides
     * rather than showing zeros.
     */
    fun marketCompare(
        fillups: List<FillupDto>,
        eia: List<EiaWeekDto>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MarketCompare? {
        val weeks = marketWeeks(eia)
        if (weeks.isEmpty()) return null
        val t0 = weeks.first().x - 3
        val fills = fillups.mapNotNull { f ->
            val ppg = f.pricePerUnit?.takeIf { it > 0 } ?: return@mapNotNull null
            val d = localDate(f.fillupDate, zone) ?: return@mapNotNull null
            val x = dayX(d)
            if (x < t0) return@mapNotNull null
            PaidFill(f.id, d, x, ppg, f.fuelVolume, marketAt(weeks, x))
        }.sortedBy { it.x }
        if (fills.isEmpty()) return null
        val meanDiff = fills.sumOf { it.diff } / fills.size
        val gallons = fills.sumOf { it.gallons ?: 0.0 }
        return MarketCompare(weeks, fills, meanDiff, gallons)
    }

    /** EIA rows → weeks, oldest first, unusable rows dropped. */
    fun marketWeeks(eia: List<EiaWeekDto>): List<MarketWeek> = eia.mapNotNull { w ->
        val price = w.price?.takeIf { it > 0 } ?: return@mapNotNull null
        val d = runCatching { LocalDate.parse(w.weekOf.take(10)) }.getOrNull() ?: return@mapNotNull null
        MarketWeek(d, dayX(d), price)
    }.sortedBy { it.x }

    /**
     * The EIA week for a fillup row's "vs US avg" pill: the latest week
     * starting on or before [date]; the first week for a fill up to 3 days
     * before it. Null for anything earlier (same rule as the web).
     */
    fun weekFor(weeks: List<MarketWeek>, date: LocalDate): MarketWeek? {
        val first = weeks.firstOrNull() ?: return null
        if (date.isBefore(first.weekOf.minusDays(3))) return null
        return weeks.lastOrNull { !it.weekOf.isAfter(date) } ?: first
    }

    /** The newest [n] weeks of [weeks] (oldest first in, oldest first out). */
    fun lastWeeks(weeks: List<EiaWeekDto>, n: Int): List<EiaWeekDto> =
        weeks.sortedByDescending { it.weekOf }.take(n)

    /** Price of the latest week starting on or before [x]; the first week if none. */
    fun marketAt(weeks: List<MarketWeek>, x: Double): Double {
        var best = weeks.first()
        for (w in weeks) if (w.x <= x) best = w
        return best.price
    }

    // ── Fillup list grouped by calendar month ────────────────────────

    data class FillupMonth(
        val month: YearMonth,
        /** Newest first. */
        val fillups: List<FillupDto>,
    ) {
        val gallons: Double get() = fillups.sumOf { it.fuelVolume ?: 0.0 }
        val total: Double get() = fillups.sumOf { it.priceTotal ?: 0.0 }
    }

    /** Newest month first, each month newest fillup first. */
    fun fillupMonths(fillups: List<FillupDto>, zone: ZoneId = ZoneId.systemDefault()): List<FillupMonth> =
        fillups.mapNotNull { f -> localDate(f.fillupDate, zone)?.let { YearMonth.from(it) to f } }
            .groupBy({ it.first }, { it.second })
            .map { (ym, fs) -> FillupMonth(ym, fs.sortedByDescending { it.fillupDate }) }
            .sortedByDescending { it.month }

    // ── MPG per tank ─────────────────────────────────────────────────

    data class Tank(
        val fillupId: String,
        val date: LocalDate,
        val x: Double,
        val mpg: Double,
        /** Mean of this tank and up to four before it. */
        val roll5: Double,
    )

    data class TankSeries(val tanks: List<Tank>) {
        val last: Tank get() = tanks.last()
        val avg: Double get() = tanks.sumOf { it.mpg } / tanks.size
        val xRange: Pair<Double, Double> get() = (tanks.first().x - 10) to (last.x + 10)
    }

    /**
     * The last [n] full, non-missed tanks with an mpg, oldest first, each
     * with a 5-tank rolling mean. Null with fewer than two tanks.
     */
    fun tankSeries(fillups: List<FillupDto>, n: Int = 20, zone: ZoneId = ZoneId.systemDefault()): TankSeries? {
        val full = fillups.mapNotNull { f ->
            val mpg = f.mpg?.takeIf { it > 0 && f.isFull && !f.isMissed } ?: return@mapNotNull null
            val d = localDate(f.fillupDate, zone) ?: return@mapNotNull null
            Triple(f, d, mpg)
        }.sortedBy { it.first.fillupDate }.takeLast(n)
        if (full.size < 2) return null
        val tanks = full.mapIndexed { i, (f, d, mpg) ->
            val w = full.subList(max(0, i - 4), i + 1)
            Tank(f.id, d, dayX(d), mpg, w.sumOf { it.third } / w.size)
        }
        return TankSeries(tanks)
    }

    // ── Trip rows: 30-day economy ────────────────────────────────────

    /**
     * Total distance ÷ total fuel over trips that started in the 30 days
     * before [nowMs] and measured both; null when none did. The trip-row
     * bar's tick (the bar scale is 1.25 × this, so the tick sits at 80 %).
     */
    fun thirtyDayMpg(trips: List<com.pitstop.http.TripDto>, nowMs: Long): Double? {
        val since = nowMs - 30L * 86_400_000L
        var km = 0.0
        var l = 0.0
        for (t in trips) {
            val start = runCatching { OffsetDateTime.parse(t.startedAt).toInstant().toEpochMilli() }.getOrNull() ?: continue
            if (start < since) continue
            val d = t.distanceKm?.takeIf { it > 0 } ?: continue
            val f = t.fuelUsedL?.takeIf { it > 0 } ?: continue
            km += d
            l += f
        }
        return com.pitstop.util.UnitFormat.mpgFrom(km, l)
    }

    // ── D: fuel spend, this year vs last ─────────────────────────────

    data class CumPoint(
        /** Days since Jan 1 (a fillup sits at its date's noon). */
        val x: Double,
        val total: Double,
        /** The fillup that moved the total; null for the Jan 1 origin. */
        val date: LocalDate? = null,
    )

    data class SpendYoy(
        val year: Int,
        /** Starts at (0, 0); oldest first. */
        val thisYear: List<CumPoint>,
        val lastYear: List<CumPoint>,
    ) {
        val total: Double get() = thisYear.last().total
        val lastYearHasData: Boolean get() = lastYear.size > 1

        /** Last year's running total on this year's latest fillup day. */
        val lastYearSameDate: Double get() = lastYearAt(thisYear.last().x)
        val delta: Double get() = total - lastYearSameDate

        fun lastYearAt(x: Double): Double = lastYear.lastOrNull { it.x <= x }?.total ?: 0.0
    }

    /** Running price_total by day of year for [today]'s year and the one before. Null when this year has no costed fillup. */
    fun spendYoy(
        fillups: List<FillupDto>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SpendYoy? {
        val dated = fillups.mapNotNull { f ->
            val cost = f.priceTotal ?: return@mapNotNull null
            val d = localDate(f.fillupDate, zone) ?: return@mapNotNull null
            d to cost
        }.sortedBy { it.first }
        fun cum(year: Int): List<CumPoint> {
            var s = 0.0
            val out = mutableListOf(CumPoint(0.0, 0.0))
            for ((d, cost) in dated) {
                if (d.year != year) continue
                s += cost
                out += CumPoint((d.dayOfYear - 1) + 0.5, s, d)
            }
            return out
        }
        val now = cum(today.year)
        if (now.size < 2) return null
        return SpendYoy(today.year, now, cum(today.year - 1))
    }

    /** Step-after expansion: hold each total flat until the next change. */
    fun stepped(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        points.flatMapIndexed { i, p -> if (i == 0) listOf(p) else listOf(p.first to points[i - 1].second, p) }

    /** Jan / Apr / Jul / Oct at their day-of-year offsets in [year]. */
    fun quarterTicks(year: Int): List<Pair<Double, String>> = listOf(1, 4, 7, 10).map { m ->
        (LocalDate.of(year, m, 1).dayOfYear - 1).toDouble() to monthName(m)
    }
}
