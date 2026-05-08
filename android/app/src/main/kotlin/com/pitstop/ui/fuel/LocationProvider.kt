package com.pitstop.ui.fuel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
)

@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun fix(): GpsFix? {
        if (!hasPermission()) return null
        return try {
            val loc = client
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .await()
                ?: return null
            GpsFix(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyMeters = loc.accuracy,
            )
        } catch (_: Exception) {
            null
        }
    }
}
