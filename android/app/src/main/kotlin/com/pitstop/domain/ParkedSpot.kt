package com.pitstop.domain

import com.pitstop.http.RoutePointDto
import com.pitstop.http.TripDto
import java.time.OffsetDateTime

/** Where the car was left: the end of the most recent trip's GPS route. */
data class ParkedSpot(
    val tripId: String,
    val lat: Double,
    val lon: Double,
    /** When it was parked (trip end, else the last fix's time). */
    val sinceMs: Long?,
)

object Parked {
    /** A fix worse than this is only used when nothing better is near the end. */
    const val GOOD_ACCURACY_M = 50.0

    /** How far back from the end to look for a good fix. */
    private const val TAIL = 10

    /**
     * The trip the car was parked after: the newest by start time. Only that
     * one — an older trip's end is not where the car is now.
     */
    fun latestTrip(trips: List<TripDto>): TripDto? = trips.maxByOrNull { it.startedAt }

    /**
     * The parking point from a route: the last fix with a usable position,
     * preferring one within [GOOD_ACCURACY_M] among the final few (the very
     * last fix is often a coarse one taken as the engine switched off).
     */
    fun pickPoint(route: List<RoutePointDto>): RoutePointDto? {
        val valid = route.filter { p ->
            p.lat in -90.0..90.0 && p.lon in -180.0..180.0 &&
                !(kotlin.math.abs(p.lat) < 0.01 && kotlin.math.abs(p.lon) < 0.01)
        }
        if (valid.isEmpty()) return null
        val tail = valid.takeLast(TAIL)
        return tail.lastOrNull { (it.accuracyM ?: Double.MAX_VALUE) <= GOOD_ACCURACY_M } ?: valid.last()
    }

    /**
     * Null when a drive is in progress (the car isn't parked) or the last
     * trip has no usable GPS.
     */
    fun spot(trip: TripDto?, route: List<RoutePointDto>, driveActive: Boolean): ParkedSpot? {
        if (driveActive || trip == null) return null
        val p = pickPoint(route) ?: return null
        val since = trip.endedAt?.let(::isoToMs) ?: isoToMs(p.t)
        return ParkedSpot(tripId = trip.id, lat = p.lat, lon = p.lon, sinceMs = since)
    }

    /** `geo:` URI with a labelled pin — what maps apps expect for "navigate here". */
    fun geoUri(s: ParkedSpot, label: String = "Car"): String =
        "geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}($label)"

    private fun isoToMs(iso: String): Long? =
        runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
