package com.pitstop.drive

import android.content.Context
import com.pitstop.domain.Parked
import com.pitstop.domain.ParkedSpot
import com.pitstop.http.RoutePointDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last parking spot the phone itself recorded, saved the moment a
 * drive is sealed. Home's Parked card used to read only the server's
 * newest trip, so a drive still waiting in the upload queue (manual-sync
 * mode, no signal, WiFi-only upload) left the pin on the PREVIOUS spot —
 * exactly when the user needs it to find the car. This store is a
 * local cache only; the server trip stays the source of truth and wins
 * whenever it is at least as new.
 */
@Singleton
class ParkedStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    /** A locally recorded spot and the vehicle it belongs to. */
    data class Local(val vehicleId: String, val spot: ParkedSpot)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _latest = MutableStateFlow(load())
    val latest: StateFlow<Local?> = _latest.asStateFlow()

    /**
     * Record where a just-sealed drive ended. No-op when the drive has
     * no usable fix, so a GPS-less drive never replaces a good pin.
     */
    fun record(drive: DriveUploadDto, endedAtMs: Long) {
        val route = drive.gpsPoints.map {
            RoutePointDto(t = it.t, lat = it.lat, lon = it.lon, accuracyM = it.accuracyM)
        }
        val p = Parked.pickPoint(route) ?: return
        val local = Local(
            vehicleId = drive.vehicleId,
            spot = ParkedSpot(
                tripId = drive.clientDriveUuid,
                lat = p.lat,
                lon = p.lon,
                sinceMs = endedAtMs,
            ),
        )
        prefs.edit()
            .putString(K_VEHICLE, local.vehicleId)
            .putString(K_ID, local.spot.tripId)
            .putString(K_LAT, local.spot.lat.toString())
            .putString(K_LON, local.spot.lon.toString())
            .putLong(K_SINCE, endedAtMs)
            .apply()
        _latest.value = local
    }

    private fun load(): Local? {
        val vehicle = prefs.getString(K_VEHICLE, null) ?: return null
        val lat = prefs.getString(K_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(K_LON, null)?.toDoubleOrNull() ?: return null
        val since = prefs.getLong(K_SINCE, -1L).takeIf { it > 0 }
        return Local(
            vehicleId = vehicle,
            spot = ParkedSpot(
                tripId = prefs.getString(K_ID, null) ?: "",
                lat = lat,
                lon = lon,
                sinceMs = since,
            ),
        )
    }

    private companion object {
        const val PREFS = "parked_spot"
        const val K_VEHICLE = "vehicle_id"
        const val K_ID = "drive_uuid"
        const val K_LAT = "lat"
        const val K_LON = "lon"
        const val K_SINCE = "since_ms"
    }
}
