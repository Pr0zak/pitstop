package com.pitstop.notif

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pitstop.data.AppPrefs
import com.pitstop.data.SettingsRepository
import com.pitstop.data.effectiveVehicleSlug
import com.pitstop.domain.Maintenance
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily backstop for the service-reminder and check-engine notifications,
 * so they fire even on days the app is never opened. Checks the bridge's
 * vehicle and the one the app is showing (usually the same). Every fetch is
 * best-effort; a failure just waits for tomorrow.
 */
@HiltWorker
class VehicleCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val prefs: AppPrefs,
    private val alerts: VehicleAlerts,
    private val logs: LogBuffer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val secrets = runCatching { settings.current() }.getOrNull() ?: return Result.success()
        if (secrets.queryToken.isBlank() || secrets.settings.apiBaseUrl.isBlank()) return Result.success()
        val slugs = setOf(
            secrets.settings.vehicleSlug.trim(),
            effectiveVehicleSlug(prefs.viewVehicleSlug.first(), secrets.settings.vehicleSlug),
        ).filter { it.isNotEmpty() }
        if (slugs.isEmpty()) return Result.success()
        val vehicles = runCatching { api.getVehicles() }.getOrElse { return Result.retry() }
        for (slug in slugs) {
            val v = vehicles.firstOrNull { it.slug == slug } ?: continue
            runCatching { api.getDtcs(v.id, activeOnly = true) }
                .onSuccess { alerts.onActiveDtcs(v, it) }
            runCatching { api.getReminders(v.id) }
                .onSuccess { alerts.onReminders(v, Maintenance.normalize(it)) }
        }
        logs.info("vehicle check worker ran", mapOf("vehicles" to slugs.size))
        return Result.success()
    }
}

const val VEHICLE_CHECK_WORK_NAME = "pitstop-vehicle-check"

fun scheduleVehicleChecks(context: Context) {
    val req = PeriodicWorkRequestBuilder<VehicleCheckWorker>(
        repeatInterval = 24,
        repeatIntervalTimeUnit = TimeUnit.HOURS,
        flexTimeInterval = 2,
        flexTimeIntervalUnit = TimeUnit.HOURS,
    )
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        VEHICLE_CHECK_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        req,
    )
}
