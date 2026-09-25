package com.pitstop.ui.history

import com.pitstop.http.TripDto
import com.pitstop.util.UnitFormat

/** Trips shorter than this (0.3 mi) are "short hops" — a driveway shuffle,
 *  a move across the car park — and fold together in the list. */
const val SHORT_HOP_KM = 0.48

/** Below ~0.1 mi a distance / fuel ratio is sensor noise, not economy. */
const val MIN_MPG_DISTANCE_KM = 0.161

/** One row of the Trips list: a trip, or a run of short hops folded into one. */
sealed interface TripRow {
    val key: String

    data class Single(val trip: TripDto) : TripRow {
        override val key: String get() = trip.id
    }

    /** Two or more consecutive short hops. Keyed by the first trip's id so
     *  the expanded state survives a refresh that adds trips above it. */
    data class ShortHops(val trips: List<TripDto>) : TripRow {
        override val key: String get() = "hops-${trips.first().id}"
        val distanceKm: Double get() = trips.sumOf { it.distanceKm ?: 0.0 }
    }
}

fun isShortHop(trip: TripDto, thresholdKm: Double = SHORT_HOP_KM): Boolean =
    trip.distanceKm != null && trip.distanceKm < thresholdKm

/**
 * Folds every run of two or more consecutive short hops into one
 * [TripRow.ShortHops]. A lone short trip stays a normal row — folding one
 * trip into "1 short hop" only adds a tap. Order is preserved, so this is
 * only meaningful on a chronological list.
 */
fun foldShortHops(trips: List<TripDto>, thresholdKm: Double = SHORT_HOP_KM): List<TripRow> {
    val out = mutableListOf<TripRow>()
    val run = mutableListOf<TripDto>()
    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> out += TripRow.Single(run.single())
            else -> out += TripRow.ShortHops(run.toList())
        }
        run.clear()
    }
    for (t in trips) {
        if (isShortHop(t, thresholdKm)) {
            run += t
        } else {
            flush()
            out += TripRow.Single(t)
        }
    }
    flush()
    return out
}

/** Trip economy in mpg from distance / fuel used; null (never 0) when the
 *  fuel figure is absent or zero, or the trip is too short to mean anything. */
fun tripMpg(trip: TripDto): Double? {
    val km = trip.distanceKm ?: return null
    if (km < MIN_MPG_DISTANCE_KM) return null
    return UnitFormat.mpgFrom(km, trip.fuelUsedL)
}

/** Totals for a date-group header, from the rows actually in that group. */
data class TripGroupTotals(val count: Int, val distanceKm: Double, val fuelL: Double)

fun tripGroupTotals(trips: List<TripDto>): TripGroupTotals = TripGroupTotals(
    count = trips.size,
    distanceKm = trips.sumOf { it.distanceKm ?: 0.0 },
    fuelL = trips.sumOf { it.fuelUsedL?.takeIf { l -> l > 0 } ?: 0.0 },
)

/** "3 trips · 42.1 mi · 1.9 gal" — the fuel part only when some was measured. */
fun tripGroupSummary(t: TripGroupTotals, system: String): String = buildList {
    add("${t.count} trip${if (t.count == 1) "" else "s"}")
    add(UnitFormat.distanceKm(t.distanceKm, system))
    if (t.fuelL > 0.0) add(UnitFormat.volumeL(t.fuelL, system, 1))
}.joinToString(" · ")
