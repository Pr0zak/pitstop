package com.pitstop.domain

import kotlin.math.cos
import kotlin.math.max

/**
 * A route's outline, ready to draw: points in a box of [w] × [h] (the
 * longer side is 1), north up, plus each point's speed in mph when the
 * route carried one. Built off the main thread — a long drive is
 * thousands of fixes.
 */
class RouteShape(
    val xs: FloatArray,
    val ys: FloatArray,
    /** mph per point; null when the route had no speeds. */
    val mph: FloatArray?,
    val w: Float,
    val h: Float,
) {
    val size: Int get() = xs.size

    companion object {
        /**
         * Equirectangular projection (longitude shrinks with cos(lat)),
         * downsampled to at most [max] points (the last one always kept).
         * Null for fewer than two points.
         */
        fun of(latLon: List<Pair<Double, Double>>, speedsMps: List<Double?>? = null, max: Int = 160): RouteShape? {
            if (latLon.size < 2) return null
            val idx = if (latLon.size <= max) {
                latLon.indices.toList()
            } else {
                val step = latLon.size.toDouble() / max
                (0 until max).map { (it * step).toInt() } + latLon.lastIndex
            }
            val lats = idx.map { latLon[it].first }
            val lons = idx.map { latLon[it].second }
            val midLat = Math.toRadians((lats.min() + lats.max()) / 2)
            val xsD = lons.map { it * cos(midLat) }
            val minX = xsD.min()
            val maxX = xsD.max()
            val minY = lats.min()
            val maxY = lats.max()
            val span = max(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1e-9
            val xs = FloatArray(idx.size) { ((xsD[it] - minX) / span).toFloat() }
            val ys = FloatArray(idx.size) { ((maxY - lats[it]) / span).toFloat() }
            val mph = speedsMps?.takeIf { s -> s.size == latLon.size && s.any { it != null } }?.let { s ->
                FloatArray(idx.size) { ((s[idx[it]] ?: 0.0) * MPS_TO_MPH).toFloat() }
            }
            return RouteShape(xs, ys, mph, ((maxX - minX) / span).toFloat(), ((maxY - minY) / span).toFloat())
        }

        private const val MPS_TO_MPH = 2.236936
    }
}

/**
 * The shared speed buckets (55-mph highway cutoff, same as web and
 * `/analytics/mpg-by-speed-class`): stopped < 2, city 2–22, suburban
 * 22–55, highway ≥ 55 mph.
 */
enum class SpeedBand(val label: String) {
    Stopped("Stopped"),
    City("City"),
    Suburban("Suburban"),
    Highway("Highway"),
    ;

    companion object {
        fun of(mph: Double): SpeedBand = when {
            mph < 2 -> Stopped
            mph < 22 -> City
            mph < 55 -> Suburban
            else -> Highway
        }
    }
}

/** Resting battery voltage, read from the WiCAN's parked wake-ups. */
object BatteryHealth {
    enum class Status(val label: String) { Good("Good"), Fair("Fair"), Low("Low") }

    const val GOOD_V = 12.4
    const val LOW_V = 12.0

    fun status(v: Double): Status = when {
        v >= GOOD_V -> Status.Good
        v >= LOW_V -> Status.Fair
        else -> Status.Low
    }

    /** One plain sentence, from where most of the days sat. */
    fun guidance(days: List<Double>): String {
        if (days.isEmpty()) return ""
        val by = days.groupingBy { status(it) }.eachCount()
        val most = by.maxByOrNull { it.value }!!.key
        val lead = when (most) {
            Status.Good -> "Mostly in the good band."
            Status.Fair -> "Mostly in the fair band."
            Status.Low -> "Mostly in the low band — have the battery tested."
        }
        return "$lead A healthy resting battery sits at 12.4 V or more; " +
            "a steady slide under 12.0 V usually means it's near the end."
    }
}
