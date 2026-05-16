package com.pitstop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pitstop.drive.scheduleDriveUploads
import com.pitstop.log.LogBuffer
import com.pitstop.notif.SyncReminderManager
import com.pitstop.update.scheduleUpdateChecks
import com.pitstop.widget.scheduleFuelWidgetRefresh
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PitstopApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logBuffer: LogBuffer
    @Inject lateinit var syncReminderManager: SyncReminderManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        createNotificationChannels()
        // Periodic update check (every ~6 h). KEEP policy means a
        // re-launch doesn't reset the cadence — the existing periodic
        // job stays scheduled across reboots via WorkManager's
        // persistence layer.
        scheduleUpdateChecks(this)
        // Fuel-widget refresh backstop (WIDGET-5). 15-min periodic via
        // WorkManager — survives Doze deferral of the AppWidget
        // `updatePeriodMillis` ticker so the widget can't sit stale
        // for hours when the app's closed.
        scheduleFuelWidgetRefresh(this)
        // Drive-upload backstop (#117). Per-drive immediate kicks
        // come from DriveSealer; this 4 h periodic catches edge
        // cases where the immediate kick missed (Doze, process
        // death between seal and worker schedule).
        scheduleDriveUploads(this)
        // Start the persistent sync-reminder observer. Watches the
        // unacked drive queue; posts a sticky notification once it
        // reaches SyncReminderManager.THRESHOLD drives. Especially
        // useful in manual-sync mode where the queue otherwise grows
        // silently.
        syncReminderManager.start()
    }

    /**
     * Capture uncaught exceptions to the log buffer before letting the
     * default handler kill the process. Without this, foreground-service
     * crashes leave no breadcrumb on the server because the log shipper
     * never runs its periodic flush. The first line of the stack trace
     * is the most useful — strip the rest to keep client_logs concise.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = throwable.stackTraceToString().lineSequence()
                    .take(8)
                    .joinToString("\n")
                logBuffer.error(
                    "uncaught exception",
                    mapOf(
                        "thread" to thread.name,
                        "type" to throwable::class.java.simpleName,
                        "msg" to (throwable.message ?: ""),
                        "stack" to stack,
                    ),
                )
                // Best-effort synchronous flush so the log lands before
                // the process dies. The shipper itself is async; this
                // gives it a chance.
                Thread.sleep(500)
            } catch (_: Throwable) {
                /* don't recurse */
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val bridgeChannel = NotificationChannel(
            BRIDGE_CHANNEL_ID,
            "Bridge service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status of the OBD bridge service"
            setShowBadge(false)
        }
        nm.createNotificationChannel(bridgeChannel)
        val updatesChannel = NotificationChannel(
            UPDATES_CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "New pitstop releases on GitHub"
            setShowBadge(true)
        }
        nm.createNotificationChannel(updatesChannel)
        // Drive sync reminder — fires when the unacked drive queue
        // backs up. Low importance + no sound/vibration so it's an
        // ambient nudge, not a disruptive alert; the notification
        // itself is setOngoing so the user can't accidentally swipe
        // it away while drives are still pending.
        val syncReminderChannel = NotificationChannel(
            SYNC_REMINDER_CHANNEL_ID,
            "Drive sync reminders",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Reminds you when local drive recordings are waiting to upload"
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(syncReminderChannel)
    }

    companion object {
        const val BRIDGE_CHANNEL_ID = "pitstop_bridge"
        const val UPDATES_CHANNEL_ID = "pitstop_updates"
        const val SYNC_REMINDER_CHANNEL_ID = "drive_sync_reminder"
    }
}
