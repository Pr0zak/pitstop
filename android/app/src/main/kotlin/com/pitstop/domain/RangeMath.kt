package com.pitstop.domain

import com.pitstop.http.FillupDto
import com.pitstop.http.TripDto
import com.pitstop.http.VehicleDto
import java.time.OffsetDateTime

/** Where the mpg a range estimate multiplies by came from. */
enum class RangeBasisSource(val label: String) {
    /** sum(distance) / sum(fuel) over trips in the last 30 days that measured fuel. */
    RecentTrips("last 30 days"),

    /** Mean of the last few full-tank fillup MPGs. */
    RecentFillups("recent fillups"),
}

data class RangeBasis(val mpg: Double, val source: RangeBasisSource)

/**
 * Fuel in the tank, always in US gallons so the range math has one unit.
 * [pct] and [usGallons] are independent: a vehicle with no tank size still
 * has a percentage, and the estimator's litres give gallons without one.
 */
data class FuelSnapshot(
    val pct: Double?,
    val usGallons: Double?,
    val tankUsGallons: Double?,
    /** When the fuel figure was produced (estimate update or sensor sample). */
    val readingAtMs: Long?,
    /** True when it came from the backend's hybrid estimator (ADR-019). */
    val isEstimate: Boolean = false,
)

data class RangeEstimate(
    val fuel: FuelSnapshot,
    val basis: RangeBasis?,
    /** Miles to empty; null when either the fuel or the mpg is unknown. */
    val rangeMi: Double?,
) {
    val low: Boolean get() = rangeMi != null && rangeMi < RangeMath.LOW_RANGE_MI
}

/**
 * Range to empty = fuel left × recent economy. The ONE place the phone does
 * this arithmetic — Home, the Fuel hub, the widget and the car tile all
 * call it, so they can't disagree about how far the tank goes.
 */
object RangeMath {
    const val LOW_RANGE_MI = 50.0
    const val BASIS_WINDOW_DAYS = 30L

    /** Below this much measured driving the trip basis is too noisy to use. */
    const val MIN_BASIS_MILES = 20.0

    /** Fillups averaged for the fallback basis. */
    const val FILLUP_BASIS_COUNT = 3

    private const val L_TO_US_GAL = 0.264172
    private const val UK_GAL_TO_US_GAL = 1.20095
    private const val KM_TO_MI = 0.621371

    /** An mpg outside this band is a data fault, not a basis. */
    private val PLAUSIBLE_MPG = 5.0..80.0

    /**
     * Tank size in US gallons. `tank1_capacity` is what the user typed into
     * Fuelio, in the vehicle's `fuel_unit` (0 = L, 1 = US gal, 2 = UK gal);
     * `tank_capacity_l` is the estimator's litres and the fallback.
     */
    fun tankUsGallons(tank1Capacity: Double?, fuelUnit: Int?, tankCapacityL: Double?): Double? {
        tank1Capacity?.takeIf { it > 0 }?.let { t ->
            return when (fuelUnit) {
                0 -> t * L_TO_US_GAL
                2 -> t * UK_GAL_TO_US_GAL
                else -> t
            }
        }
        return tankCapacityL?.takeIf { it > 0 }?.let { it * L_TO_US_GAL }
    }

    /**
     * Fuel state from a vehicle row: the hybrid estimator when it has been
     * seeded, else the smoothed sensor reading from `latest.fuel_level`.
     */
    fun fuelSnapshot(v: VehicleDto): FuelSnapshot {
        val tankGal = tankUsGallons(v.tank1Capacity, v.fuelUnit, v.tankCapacityL)
        val estimateL = v.fuelLevelEstimateL
        val tankL = v.tankCapacityL?.takeIf { it > 0 }
        if (estimateL != null && tankL != null) {
            return FuelSnapshot(
                pct = (estimateL / tankL * 100.0).coerceIn(0.0, 100.0),
                usGallons = (estimateL * L_TO_US_GAL).coerceAtLeast(0.0),
                tankUsGallons = tankGal,
                readingAtMs = v.fuelLevelEstimateUpdatedAt?.let(::isoToMs),
                isEstimate = true,
            )
        }
        val sensor = v.latest["fuel_level"]
        val pct = sensor?.valueNum?.coerceIn(0.0, 100.0)
        return FuelSnapshot(
            pct = pct,
            usGallons = if (pct != null && tankGal != null) tankGal * pct / 100.0 else null,
            tankUsGallons = tankGal,
            readingAtMs = sensor?.time?.let(::isoToMs),
        )
    }

    /**
     * Overlay a live BLE fuel_level sample. Only on the sensor path and only
     * when it is newer — the estimator is deliberately stable between
     * events and a live slosh reading must not jostle it (Home parity).
     */
    fun withLiveLevel(s: FuelSnapshot, livePct: Double?, liveAtMs: Long?): FuelSnapshot {
        if (s.isEstimate || livePct == null || liveAtMs == null) return s
        if (s.readingAtMs != null && liveAtMs <= s.readingAtMs) return s
        val pct = livePct.coerceIn(0.0, 100.0)
        return s.copy(
            pct = pct,
            usGallons = s.tankUsGallons?.let { it * pct / 100.0 } ?: s.usGallons,
            readingAtMs = liveAtMs,
        )
    }

    /**
     * mpg over the trips in the last [BASIS_WINDOW_DAYS] days that measured
     * fuel: total distance over total fuel, so a long trip weighs what it
     * burned rather than counting the same as a driveway shuffle. Null below
     * [MIN_BASIS_MILES] of measured driving or outside a plausible band.
     */
    fun tripBasisMpg(trips: List<TripDto>, nowMs: Long): Double? {
        val cutoff = nowMs - BASIS_WINDOW_DAYS * 86_400_000L
        var km = 0.0
        var litres = 0.0
        var n = 0
        for (t in trips) {
            val d = t.distanceKm ?: continue
            val f = t.fuelUsedL ?: continue
            if (d <= 0.0 || f <= 0.0) continue
            val at = isoToMs(t.startedAt) ?: continue
            if (at < cutoff || at > nowMs + 60_000L) continue
            km += d
            litres += f
            n++
        }
        if (n < 2 || km * KM_TO_MI < MIN_BASIS_MILES || litres <= 0.0) return null
        val mpg = (km * KM_TO_MI) / (litres * L_TO_US_GAL)
        return mpg.takeIf { it in PLAUSIBLE_MPG }
    }

    /** Mean of the last [FILLUP_BASIS_COUNT] full, unbroken-chain fillup MPGs. */
    fun fillupBasisMpg(fillups: List<FillupDto>): Double? {
        val recent = fillups
            .sortedByDescending { it.fillupDate }
            .filter { it.isFull && !it.isMissed }
            .mapNotNull { f -> (f.mpg ?: f.mpgReported)?.takeIf { it in PLAUSIBLE_MPG } }
            .take(FILLUP_BASIS_COUNT)
        return if (recent.isEmpty()) null else recent.average()
    }

    fun basis(trips: List<TripDto>?, fillups: List<FillupDto>?, nowMs: Long): RangeBasis? {
        trips?.let { tripBasisMpg(it, nowMs) }?.let { return RangeBasis(it, RangeBasisSource.RecentTrips) }
        fillups?.let { fillupBasisMpg(it) }?.let { return RangeBasis(it, RangeBasisSource.RecentFillups) }
        return null
    }

    fun estimate(fuel: FuelSnapshot, basis: RangeBasis?): RangeEstimate {
        val gal = fuel.usGallons
        val range = if (gal != null && basis != null) gal * basis.mpg else null
        return RangeEstimate(fuel = fuel, basis = basis, rangeMi = range)
    }

    /** Range from a raw percentage — for the car tile and the widget, which
     *  only have the live gauge reading and a cached basis. */
    fun rangeFromPct(pct: Double?, tankUsGallons: Double?, mpg: Double?): Double? {
        if (pct == null || tankUsGallons == null || mpg == null) return null
        return tankUsGallons * pct.coerceIn(0.0, 100.0) / 100.0 * mpg
    }

    private fun isoToMs(iso: String): Long? =
        runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
