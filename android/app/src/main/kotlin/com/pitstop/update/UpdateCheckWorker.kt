package com.pitstop.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pitstop.PitstopApp
import com.pitstop.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic update check. Calls [UpdateChecker.check] and, when a newer
 * release is available, posts a system notification linking to the
 * GitHub release page. The user can tap to download in-browser; we
 * deliberately don't try to side-load the APK in-process (REQUEST_INSTALL_PACKAGES
 * permissions + OEM install-from-unknown-sources flow are flaky enough
 * that a browser hand-off is the friendlier path for a sideloaded app).
 *
 * Scheduled by [scheduleUpdateChecks] at app start, fires every 6 hours
 * with WorkManager's standard backoff. Network is required (KEEP policy
 * keeps the periodic job alive across reboots).
 *
 * Notification channel `pitstop_updates` is created in [PitstopApp.onCreate].
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateChecker: UpdateChecker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val info = updateChecker.check() ?: return Result.success()
        if (!info.isNewer) return Result.success()

        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return Result.success()

        // Don't re-notify for the same version on every fire — the
        // OS dedupes by id but we keep the same id so a fresh release
        // updates the existing notification text rather than stacking.
        // Play, never the GitHub release page — see PlayStore's KDoc.
        val tapIntent = PlayStore.intent(ctx)
        val pi = PendingIntent.getActivity(
            ctx,
            UPDATE_NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(ctx, PitstopApp.UPDATES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pitstop ${info.latestVersion} available")
            .setContentText("Update in Google Play. You're on ${info.currentVersion}.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Tap to open Google Play and update. " +
                        "You're on v${info.currentVersion}; " +
                        "v${info.latestVersion} is now available.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(UPDATE_NOTIFICATION_ID, n)
        return Result.success()
    }

    companion object {
        const val UPDATE_NOTIFICATION_ID = 4242
    }
}
