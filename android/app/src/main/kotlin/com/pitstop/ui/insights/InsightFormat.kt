package com.pitstop.ui.insights

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Presentation-only helpers for the Insights screen. The server has already
 * computed and rounded every number (ADR-013); these only lay it out: trim
 * zeros, glue a leading symbol onto the number, pick axis ticks.
 */
internal object InsightFormat {

    private val symbols = DecimalFormatSymbols(Locale.US)

    /** Up to [maxDecimals] decimals, trailing zeros dropped, grouped: 1,345 / 12.28 / -4.6. */
    fun number(v: Double, maxDecimals: Int = 2): String {
        val pattern = if (maxDecimals <= 0) "#,##0" else "#,##0." + "#".repeat(maxDecimals)
        val s = DecimalFormat(pattern, symbols).format(v)
        return if (s == "-0") "0" else s.replace("-", "−")
    }

    /** The big number of a card split for display. */
    data class ValueParts(val big: String, val caption: String?)

    /**
     * Joins the unit's leading symbol to the number so "10.0" + "% swing"
     * reads "10%" over "swing", "65" + "$" reads "$65", "153" + "°F peak"
     * reads "153°F" over "peak". Units with no symbol stay a caption.
     */
    fun valueParts(value: Double, unit: String?): ValueParts {
        val n = number(value)
        val u = unit?.trim().orEmpty()
        if (u.isEmpty()) return ValueParts(n, null)
        if (u.startsWith("$")) {
            val rest = u.removePrefix("$").trim().ifEmpty { null }
            val sign = if (n.startsWith("−")) "−" else ""
            return ValueParts("$sign$" + n.removePrefix("−"), rest)
        }
        for (sym in listOf("°F", "°C", "%", "¢")) {
            if (u.startsWith(sym)) {
                val rest = u.removePrefix(sym).trim().ifEmpty { null }
                return ValueParts(n + sym, rest)
            }
        }
        return ValueParts(n, u)
    }

    /** "2 of 7 days". */
    fun progress(have: Double, need: Double, unit: String?): String =
        "${number(have, 0)} of ${number(need, 0)}" + (unit?.let { " $it" } ?: "")

    /**
     * About [count] round tick values covering [lo]..[hi]; the returned
     * first and last ticks bound the data, so they double as the axis range.
     */
    fun niceTicks(lo: Double, hi: Double, count: Int = 3): List<Double> {
        var a = lo
        var b = hi
        if (!a.isFinite() || !b.isFinite()) return listOf(0.0, 1.0)
        if (b - a < 1e-9) {
            val pad = if (abs(a) > 1e-9) abs(a) * 0.05 else 1.0
            a -= pad
            b += pad
        }
        val raw = (b - a) / (count - 1).coerceAtLeast(1)
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-12 }
        val start = floor(a / step) * step
        val end = ceil(b / step) * step
        val out = mutableListOf<Double>()
        var t = start
        while (t <= end + step * 1e-6 && out.size < 12) {
            out += if (abs(t) < step * 1e-6) 0.0 else t
            t += step
        }
        return out
    }

    /** Decimals a tick label needs for the step between [ticks]. */
    fun tickDecimals(ticks: List<Double>): Int {
        if (ticks.size < 2) return 0
        val step = abs(ticks[1] - ticks[0])
        return when {
            step >= 1.0 -> 0
            step >= 0.1 -> 1
            else -> 2
        }
    }

    /** Date label for an epoch-ms tick, shorter when the span is short. */
    fun dateTick(ms: Double, spanMs: Double, zone: ZoneId = ZoneId.systemDefault()): String {
        val pattern = if (spanMs > 200.0 * 86_400_000) "MMM ''yy" else "MMM d"
        return DateTimeFormatter.ofPattern(pattern, Locale.US)
            .format(Instant.ofEpochMilli(ms.toLong()).atZone(zone))
    }

    /** Every how-many-th of [n] labels to draw so at most [max] show. */
    fun labelStep(n: Int, max: Int): Int = if (n <= max || max <= 0) 1 else ceil(n / max.toDouble()).toInt()

    /**
     * Which of [n] bar labels to draw: every [step]-th, plus each highlighted
     * bar, dropping the regular labels too close to a highlighted one.
     */
    fun shownLabels(n: Int, step: Int, highlighted: List<Int>): Set<Int> {
        if (step <= 1) return (0 until n).toSet()
        val out = highlighted.filter { it in 0 until n }.toMutableSet()
        for (i in 0 until n step step) {
            if (highlighted.none { h -> kotlin.math.abs(h - i) < step }) out += i
        }
        return out
    }
}
