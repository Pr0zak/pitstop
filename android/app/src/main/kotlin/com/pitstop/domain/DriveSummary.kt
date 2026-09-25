package com.pitstop.domain

import com.pitstop.http.TripBaselineDto
import com.pitstop.http.TripDto
import com.pitstop.util.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the drive-summary notifier should post for one upload pass. */
sealed interface DriveSummaryPlan {
    data class Single(val trip: TripDto) : DriveSummaryPlan
    data class Batch(val count: Int, val totalKm: Double) : DriveSummaryPlan
}

object DriveSummary {
    /** More than this many drives in one pass = a backlog drain → one summary. */
    const val BULK_THRESHOLD = 3

    /** Driveway shuffles aren't worth a notification. */
    const val MIN_MILES = 0.3

    private const val KM_TO_MI = 0.621371

    /**
     * [uploadedCount] is how many drives the pass acked; [trips] the rows it
     * could resolve (a trip the server couldn't return is simply absent).
     */
    fun plan(uploadedCount: Int, trips: List<TripDto>): List<DriveSummaryPlan> {
        if (uploadedCount <= 0 || trips.isEmpty()) return emptyList()
        if (uploadedCount > BULK_THRESHOLD) {
            return listOf(DriveSummaryPlan.Batch(uploadedCount, trips.sumOf { it.distanceKm ?: 0.0 }))
        }
        return trips
            .filter { (it.distanceKm ?: 0.0) * KM_TO_MI >= MIN_MILES }
            .sortedBy { it.startedAt }
            .map { DriveSummaryPlan.Single(it) }
    }

    /** "Drive: 17.0 mi · 24.8 mpg" */
    fun title(trip: TripDto, system: String): String {
        val parts = mutableListOf(UnitFormat.distanceKm(trip.distanceKm, system))
        UnitFormat.mpgFrom(trip.distanceKm, trip.fuelUsedL)?.let { parts += UnitFormat.economy(it, system) }
        return "Drive: " + parts.joinToString(" · ")
    }

    /** "31 min · +17% vs your usual for 10–30 mi" — the comparison only with a real baseline. */
    fun body(trip: TripDto, baseline: TripBaselineDto?): String {
        val parts = mutableListOf<String>()
        trip.durationS?.let { parts += durationLabel(it) }
        val delta = durationDeltaPct(trip, baseline)
        if (delta != null && baseline?.bucketLabel != null) {
            parts += "${signedPct(delta)} vs your usual for ${baseline.bucketLabel}"
        }
        return parts.joinToString(" · ").ifEmpty { "Uploaded" }
    }

    fun batchTitle(p: DriveSummaryPlan.Batch, system: String): String =
        "${p.count} drives synced · ${UnitFormat.distanceKm(p.totalKm, system, 0)}"

    /** Duration vs the same-distance-bucket average, in percent. */
    fun durationDeltaPct(trip: TripDto, baseline: TripBaselineDto?): Double? {
        val avg = baseline?.takeIf { it.sufficient }?.avgDurationS ?: return null
        val d = trip.durationS ?: return null
        if (avg <= 0) return null
        return (d - avg) / avg * 100.0
    }

    /** Economy vs the bucket average, in percent (positive = better mpg). */
    fun mpgDeltaPct(trip: TripDto, baseline: TripBaselineDto?): Double? {
        val avg = baseline?.takeIf { it.sufficient }?.avgMpg ?: return null
        val mpg = UnitFormat.mpgFrom(trip.distanceKm, trip.fuelUsedL) ?: return null
        if (avg <= 0) return null
        return (mpg - avg) / avg * 100.0
    }

    fun signedPct(p: Double): String {
        val r = p.roundToInt()
        return when {
            r > 0 -> "+$r%"
            r < 0 -> "−${abs(r)}%"
            else -> "±0%"
        }
    }

    fun durationLabel(s: Int): String = when {
        s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}
