package com.pitstop.data

import com.pitstop.domain.RangeBasis
import com.pitstop.domain.RangeBasisSource
import com.pitstop.domain.RangeMath
import com.pitstop.http.FillupDto
import com.pitstop.http.PitstopApi
import com.pitstop.http.TripDto
import com.pitstop.http.VehicleDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Range inputs for one vehicle, kept in memory for the car tile. */
data class RangeInputs(
    val slug: String,
    val tankUsGallons: Double?,
    val basis: RangeBasis?,
)

/**
 * Owns the mpg basis behind every range-to-empty figure. Home computes it
 * from data it already loaded and publishes it here ([publish]); the widget
 * and the car tile read the cached copy so they never fetch 30 days of trips
 * on a 30-second refresh tick. [ensureBasis] fetches only when the cache is
 * missing or older than [MAX_AGE_MS].
 */
@Singleton
class RangeRepository @Inject constructor(
    private val api: PitstopApi,
    private val prefs: AppPrefs,
) {
    private val _inputs = MutableStateFlow<RangeInputs?>(null)

    /** Latest inputs for the vehicle on screen; null until something loaded. */
    val inputs: StateFlow<RangeInputs?> = _inputs.asStateFlow()

    suspend fun publish(vehicle: VehicleDto, basis: RangeBasis?) {
        val tank = RangeMath.tankUsGallons(vehicle.tank1Capacity, vehicle.fuelUnit, vehicle.tankCapacityL)
        _inputs.value = RangeInputs(vehicle.slug, tank, basis)
        if (basis != null) {
            prefs.setRangeBasis(
                CachedRangeBasis(vehicle.slug, basis.mpg, basis.source.name, tank, System.currentTimeMillis()),
            )
        }
    }

    /** Cached basis, fetching trips (and fillups as a fallback) when stale. */
    suspend fun ensureBasis(vehicle: VehicleDto): RangeBasis? {
        val cached = prefs.rangeBasis(vehicle.slug)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.atMs < MAX_AGE_MS) {
            val basis = RangeBasis(cached.mpg, runCatching { RangeBasisSource.valueOf(cached.source) }
                .getOrDefault(RangeBasisSource.RecentTrips))
            if (_inputs.value?.slug != vehicle.slug) {
                _inputs.value = RangeInputs(vehicle.slug, cached.tankUsGallons, basis)
            }
            return basis
        }
        val trips = runCatching { fetchBasisTrips(vehicle.id) }.getOrNull()
        val fillups = if (trips == null || RangeMath.tripBasisMpg(trips, now) == null) {
            runCatching { api.getFillups(vehicle.id, limit = 10) }.getOrNull()
        } else null
        val basis = RangeMath.basis(trips, fillups, now)
        publish(vehicle, basis)
        return basis ?: cached?.let {
            RangeBasis(it.mpg, runCatching { RangeBasisSource.valueOf(it.source) }.getOrDefault(RangeBasisSource.RecentTrips))
        }
    }

    /** The trips page the basis is computed from — shared with Home's refresh. */
    suspend fun fetchBasisTrips(vehicleId: String, cacheControl: String? = null): List<TripDto> =
        api.getTrips(
            vehicleId,
            limit = 500,
            cacheControl = cacheControl,
            from = Instant.now().minus(RangeMath.BASIS_WINDOW_DAYS, ChronoUnit.DAYS).toString(),
        )

    fun basisFrom(trips: List<TripDto>?, fillups: List<FillupDto>?): RangeBasis? =
        RangeMath.basis(trips, fillups, System.currentTimeMillis())

    private companion object {
        const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}
