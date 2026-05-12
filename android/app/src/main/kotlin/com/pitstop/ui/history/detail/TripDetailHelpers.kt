package com.pitstop.ui.history.detail

import androidx.compose.ui.graphics.Color
import com.pitstop.http.TripDetailDto
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Centralised unit conversions — same constants the rest of the app uses.
private const val KM_TO_MI = 0.621371
private const val L_TO_GAL_US = 0.264172
private const val M_PER_S_TO_MPH = 2.23694

internal fun kphToMph(kph: Double): Double = kph * KM_TO_MI

internal fun kmToMi(km: Double): Double = km * KM_TO_MI

internal fun mpsToMph(mps: Double): Double = mps * M_PER_S_TO_MPH

internal fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0

internal fun lToGal(l: Double): Double = l * L_TO_GAL_US

/**
 * WMO weather code → human label (mirrors the web frontend's
 * `WMO_CODE` map, sans emoji). Used in the trip narrative and the
 * weather snapshot card.
 */
internal fun wmoLabel(code: Int?): String? = when (code) {
    0 -> "clear"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51 -> "light drizzle"
    53 -> "drizzle"
    55 -> "heavy drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    71 -> "light snow"
    73 -> "snow"
    75 -> "heavy snow"
    77 -> "snow grains"
    80, 81 -> "rain showers"
    82 -> "violent showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunder + hail"
    else -> null
}

/**
 * Speed → HSL color matching the web frontend's `speedColor`. 0 m/s
 * is red (stopped), 36 m/s ≈ 80 mph is magenta. Anchored at a fixed
 * range so colors mean the same thing across trips.
 */
internal fun speedColor(speedMps: Double?): Int {
    if (speedMps == null || !speedMps.isFinite()) return 0xFF2F81F7.toInt()
    val clamped = speedMps.coerceIn(0.0, HEATMAP_MAX_MPS)
    val t = clamped / HEATMAP_MAX_MPS
    val hue = t * 300.0
    return hslToRgb(hue.toFloat(), 0.8f, 0.5f)
}

private const val HEATMAP_MAX_MPS = 36.0

private fun hslToRgb(h: Float, s: Float, l: Float): Int {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val hh = h / 60f
    val x = c * (1 - kotlin.math.abs(hh % 2f - 1))
    val (r1, g1, b1) = when {
        hh < 1f -> Triple(c, x, 0f)
        hh < 2f -> Triple(x, c, 0f)
        hh < 3f -> Triple(0f, c, x)
        hh < 4f -> Triple(0f, x, c)
        hh < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    val r = ((r1 + m).coerceIn(0f, 1f) * 255f).toInt()
    val g = ((g1 + m).coerceIn(0f, 1f) * 255f).toInt()
    val b = ((b1 + m).coerceIn(0f, 1f) * 255f).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun speedColorCompose(speedMps: Double?): Color = Color(speedColor(speedMps))

/**
 * Haversine distance in km between two lat/lon pairs. Used for
 * route-segment cumulative distance when needed.
 */
internal fun haversineKm(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double,
): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2).let { it * it }
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

private val LOCAL_ZONE: ZoneId = ZoneId.systemDefault()
private val CLOCK_FMT = DateTimeFormatter.ofPattern("h:mma")
private val DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d, h:mma")
private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("MMM d")
private val SHORT_DATETIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mma")

internal fun fmtClockLocal(iso: String?): String =
    iso?.let {
        runCatching {
            OffsetDateTime.parse(it).atZoneSameInstant(LOCAL_ZONE).format(CLOCK_FMT)
        }.getOrNull()
    } ?: "—"

internal fun fmtDateTimeLocal(iso: String?): String =
    iso?.let {
        runCatching {
            OffsetDateTime.parse(it).atZoneSameInstant(LOCAL_ZONE).format(DATE_FMT)
        }.getOrNull()
    } ?: "—"

internal fun fmtShortDateTimeLocal(iso: String?): String =
    iso?.let {
        runCatching {
            OffsetDateTime.parse(it).atZoneSameInstant(LOCAL_ZONE).format(SHORT_DATETIME_FMT)
        }.getOrNull()
    } ?: "—"

internal fun fmtShortDateLocal(iso: String?): String =
    iso?.let {
        runCatching {
            OffsetDateTime.parse(it).atZoneSameInstant(LOCAL_ZONE).format(SHORT_DATE_FMT)
        }.getOrNull()
    } ?: "—"

internal fun fmtDuration(durationS: Int?): String {
    val s = durationS ?: return "—"
    val m = s / 60
    val r = s % 60
    if (m >= 60) {
        val h = m / 60
        val mm = m % 60
        return "${h}h ${mm}m"
    }
    return if (m > 0) "${m}m ${r}s" else "${r}s"
}

internal fun localHourOf(iso: String): Int? = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(LOCAL_ZONE).hour
}.getOrNull()

/**
 * Auto-generated narrative for a trip, mirroring the web
 * `summarySentence` computed property. Each clause is independent —
 * skipped when its inputs are missing — so a stub trip with just a
 * duration still produces a sane line.
 */
internal fun tripNarrative(trip: TripDetailDto): String {
    val parts = mutableListOf<String>()

    // 1) "13-min morning drive" / "Brief drive"
    val minutes = trip.durationS?.let { (it / 60.0).roundToInt() }
    val todLabel = trip.startedAt.let { localHourOf(it) }?.let { h ->
        when {
            h >= 22 || h < 5 -> "late-night"
            h < 11 -> "morning"
            h < 17 -> "afternoon"
            else -> "evening"
        }
    }
    if (minutes != null) {
        if (minutes < 1) parts += "Brief drive"
        else parts += "${minutes}-min ${todLabel?.let { "$it " } ?: ""}drive"
    }

    // 2) Weather. "in 67°F clear conditions"
    if (trip.weatherTempC != null) {
        val f = cToF(trip.weatherTempC).roundToInt()
        val wmo = wmoLabel(trip.weatherCode)
        parts += "in ${f}°F${wmo?.let { " $it" } ?: ""} conditions"
    }

    // 3) Distance.
    if (trip.distanceKm != null && trip.distanceKm > 0) {
        val mi = kmToMi(trip.distanceKm)
        parts += "%.1f mi covered".format(mi)
    }

    // 4) MPG (only when fuel reading is meaningful).
    if (trip.distanceKm != null && trip.fuelUsedL != null && trip.fuelUsedL > 0.4) {
        val mi = kmToMi(trip.distanceKm)
        val gal = lToGal(trip.fuelUsedL)
        if (gal > 0) {
            val mpg = mi / gal
            if (mpg in 1.0..100.0) parts += "averaged %.1f mpg".format(mpg)
        }
    }

    // 5) Top speed.
    if (trip.maxSpeedKph != null && trip.maxSpeedKph > 0) {
        parts += "peaked at ${kphToMph(trip.maxSpeedKph).roundToInt()} mph"
    }

    // 6) Hard IMU events. The web defers this to a side card but on
    // a phone screen it's worth surfacing inline — those events are
    // the most-likely "interesting bit" about an otherwise routine
    // commute and motivate clicking into the detail in the first
    // place.
    val hardEvents = trip.imuEvents.size
    if (hardEvents > 0) {
        parts += "$hardEvents hard event${if (hardEvents == 1) "" else "s"}"
    }

    // 7) DTCs.
    val dtcs = trip.dtcs.size
    if (dtcs > 0) parts += "$dtcs DTC${if (dtcs == 1) "" else "s"} fired"

    if (parts.isEmpty()) return ""
    // Join "in <weather>" clause without a separating comma so it
    // reads as part of the headline.
    val head = parts[0] + if (parts.size > 1 && parts[1].startsWith("in ")) " " + parts[1] else ""
    val rest = parts.drop(if (parts.size > 1 && parts[1].startsWith("in ")) 2 else 1)
    return if (rest.isEmpty()) "$head." else "$head, ${rest.joinToString(", ")}."
}

/**
 * Pivot the sparse per-metric `samples` list into one chronological
 * series per metric. Downsamples to `maxPoints` per series with
 * average-by-bucket so a 12-min trip with 360 samples renders just
 * as cleanly as a 4-hour interstate run.
 */
internal data class MetricSeries(
    val metric: String,
    val points: List<TimedPoint>,
)

internal data class TimedPoint(val tMillis: Long, val value: Double)

internal fun pivotSamples(
    samples: List<com.pitstop.http.TripSampleDto>,
    maxPoints: Int = 500,
    smoothWindow: Int = 1,
): Map<String, MetricSeries> {
    val byMetric = mutableMapOf<String, MutableList<TimedPoint>>()
    for (s in samples) {
        val v = s.valueNum ?: continue
        val tMs = runCatching {
            OffsetDateTime.parse(s.time).toInstant().toEpochMilli()
        }.getOrNull() ?: continue
        byMetric.getOrPut(s.metric) { mutableListOf() }
            .add(TimedPoint(tMs, v))
    }
    return byMetric.mapValues { (metric, pts) ->
        pts.sortBy { it.tMillis }
        // Apply spike-removal first (rolling median) so the
        // averaging downsample doesn't carry outliers forward into
        // its bucket means. Layering order is intentional:
        // median → downsample.
        val smoothed = rollingMedian(pts, smoothWindow)
        MetricSeries(metric, downsample(smoothed, maxPoints))
    }
}

/**
 * Rolling-median spike filter. For each index `i`, replaces its
 * value with the median of values in `[i - W/2, i + W/2]` clipped to
 * the bounds of the input. The point timestamp is preserved as-is so
 * the chart's x-axis still reflects real capture time.
 *
 * Operates on array indices — gaps in time are not bridged. The
 * caller has already sorted by time when this runs from pivotSamples.
 * `window <= 1` returns the input unchanged.
 */
internal fun rollingMedian(points: List<TimedPoint>, window: Int): List<TimedPoint> {
    if (window <= 1 || points.size < 2) return points
    val half = window / 2
    val n = points.size
    val out = ArrayList<TimedPoint>(n)
    // Reusable scratch buffer; we copy slice values and sort to find
    // the median. Window sizes top out at ~15 so this is negligible.
    val scratch = DoubleArray(window)
    for (i in 0 until n) {
        val lo = max(0, i - half)
        val hi = min(n - 1, i + half)
        val len = hi - lo + 1
        for (k in 0 until len) scratch[k] = points[lo + k].value
        // Partial sort: only the first `len` slots matter.
        java.util.Arrays.sort(scratch, 0, len)
        val median = if (len % 2 == 1) {
            scratch[len / 2]
        } else {
            (scratch[len / 2 - 1] + scratch[len / 2]) / 2.0
        }
        out += TimedPoint(points[i].tMillis, median)
    }
    return out
}

private fun downsample(points: List<TimedPoint>, maxPoints: Int): List<TimedPoint> {
    if (points.size <= maxPoints) return points
    val bucketSize = points.size.toDouble() / maxPoints
    val out = mutableListOf<TimedPoint>()
    var i = 0
    while (i < points.size) {
        val end = min(((out.size + 1) * bucketSize).toInt(), points.size)
        if (end <= i) break
        var sum = 0.0
        var n = 0
        var tSum = 0L
        for (j in i until end) {
            sum += points[j].value
            tSum += points[j].tMillis
            n++
        }
        if (n > 0) out += TimedPoint(tSum / n, sum / n)
        i = end
    }
    return out
}

/**
 * Trip-summary speed split mirroring the web's `speedDistribution`.
 * Buckets in m/s: stopped <1, city <10, suburban <20, highway >=20.
 * Returns seconds in each bucket; empty when route is too sparse to
 * tell.
 */
internal data class SpeedBucket(
    val label: String,
    val color: Int,
    val seconds: Double,
)

internal fun speedDistribution(
    points: List<com.pitstop.http.RoutePointDto>,
): List<SpeedBucket> {
    if (points.size < 2) return emptyList()
    val buckets = mutableListOf(
        SpeedBucket("Stopped", 0xFFEF4444.toInt(), 0.0),
        SpeedBucket("City",    0xFFF59E0B.toInt(), 0.0),
        SpeedBucket("Suburban", 0xFF22C55E.toInt(), 0.0),
        SpeedBucket("Highway", 0xFF2F81F7.toInt(), 0.0),
    )
    for (i in 1 until points.size) {
        val ta = runCatching { OffsetDateTime.parse(points[i - 1].t).toInstant().toEpochMilli() }
            .getOrNull() ?: continue
        val tb = runCatching { OffsetDateTime.parse(points[i].t).toInstant().toEpochMilli() }
            .getOrNull() ?: continue
        val dt = (tb - ta) / 1000.0
        if (!dt.isFinite() || dt <= 0 || dt > 60) continue
        val sp = points[i - 1].speedMps ?: 0.0
        val idx = when {
            sp < 1 -> 0
            sp < 10 -> 1
            sp < 20 -> 2
            else -> 3
        }
        buckets[idx] = buckets[idx].copy(seconds = buckets[idx].seconds + dt)
    }
    return buckets
}

internal fun fmtMinutes(seconds: Double): String {
    if (seconds < 60) return "${seconds.roundToInt()}s"
    val m = (seconds / 60).roundToInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** Find min + max in O(n) — used everywhere by the chart. */
internal fun List<TimedPoint>.bounds(): Pair<Double, Double>? {
    if (isEmpty()) return null
    var mn = first().value
    var mx = mn
    for (p in this) {
        if (p.value < mn) mn = p.value
        if (p.value > mx) mx = p.value
    }
    if (mn == mx) {
        // Degenerate — give the chart at least a 1-unit window so the
        // line draws as a horizontal mark rather than an undefined NaN.
        return mn - 0.5 to mx + 0.5
    }
    return mn to mx
}

/** Time bounds across all visible series. */
internal fun List<MetricSeries>.timeBounds(): Pair<Long, Long>? {
    var mn = Long.MAX_VALUE
    var mx = Long.MIN_VALUE
    for (s in this) for (p in s.points) {
        if (p.tMillis < mn) mn = p.tMillis
        if (p.tMillis > mx) mx = p.tMillis
    }
    return if (mn == Long.MAX_VALUE) null else mn to mx
}

/** Convenience — never let max == min so chart math doesn't divide by zero. */
internal fun safeRange(min: Double, max: Double): Pair<Double, Double> =
    if (max == min) (min - 0.5) to (max + 0.5) else min to max

@Suppress("unused")
private fun unusedKeepImport(): Pair<Double, Double> = max(0.0, 0.0) to 0.0
