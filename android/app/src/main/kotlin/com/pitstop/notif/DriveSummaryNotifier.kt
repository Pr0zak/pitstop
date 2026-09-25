package com.pitstop.notif

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.pitstop.R
import com.pitstop.data.AppPrefs
import com.pitstop.data.SettingsRepository
import com.pitstop.domain.DriveSummary
import com.pitstop.domain.DriveSummaryPlan
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Drive: 17.0 mi · 24.8 mpg" after a drive finishes uploading.
 *
 * Fed by [com.pitstop.drive.DriveUploader] at the end of a pass with the
 * trip ids the server acked (duplicates excluded). Runs on its own
 * process-lifetime scope — the uploader's pass must not wait on it, and it
 * must not die with whichever screen happened to start the pass.
 *
 * Rules ([DriveSummary.plan]): nothing under 0.3 mi; more than three drives
 * in one pass is a backlog drain and gets one "N drives synced" summary.
 */
@Singleton
class DriveSummaryNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val prefs: AppPrefs,
    private val logs: LogBuffer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onPassFinished(tripIds: List<String>) {
        if (tripIds.isEmpty()) return
        scope.launch {
            runCatching { post(tripIds) }.onFailure {
                logs.warn("drive summary failed", mapOf("err" to (it.message ?: it::class.java.simpleName)))
            }
        }
    }

    private suspend fun post(tripIds: List<String>) {
        if (!prefs.driveSummaryNotif.first() || !AppNotifications.canPost(context)) return
        val s = settings.current().settings
        val system = s.unitSystem
        val vehicleId = api.getVehicles().firstOrNull { it.slug == s.vehicleSlug.trim() }?.id ?: return
        // The server derives the trip row on ingest, but give a just-acked
        // drive a moment before reading the list back.
        delay(1_500)
        val page = api.getTrips(vehicleId, limit = (tripIds.size + 10).coerceAtMost(200), cacheControl = "no-cache")
        val wanted = tripIds.toSet()
        val trips = page.filter { it.id in wanted }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (plan in DriveSummary.plan(tripIds.size, trips)) {
            when (plan) {
                is DriveSummaryPlan.Batch -> nm.notify(
                    AppNotifications.DRIVE_BATCH_ID,
                    NotificationCompat.Builder(context, AppNotifications.DRIVE_SUMMARY_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_car)
                        .setContentTitle(DriveSummary.batchTitle(plan, system))
                        .setContentText("Tap to see them in Trips")
                        .setAutoCancel(true)
                        .setContentIntent(AppNotifications.openTrips(context, AppNotifications.DRIVE_BATCH_ID))
                        .build(),
                )
                is DriveSummaryPlan.Single -> {
                    val trip = plan.trip
                    val baseline = trip.distanceKm?.takeIf { it > 0 }?.let { km ->
                        runCatching { api.getTripBaseline(vehicleId, km) }.getOrNull()
                    }
                    val id = AppNotifications.driveNotificationId(trip.id)
                    nm.notify(
                        id,
                        NotificationCompat.Builder(context, AppNotifications.DRIVE_SUMMARY_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_stat_car)
                            .setContentTitle(DriveSummary.title(trip, system))
                            .setContentText(DriveSummary.body(trip, baseline))
                            .setAutoCancel(true)
                            .setContentIntent(AppNotifications.openTrip(context, trip.id, edit = false, requestCode = id))
                            .addAction(
                                0,
                                "Tag as…",
                                AppNotifications.openTrip(context, trip.id, edit = true, requestCode = id + 1),
                            )
                            .build(),
                    )
                }
            }
        }
    }
}
