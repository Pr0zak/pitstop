package com.pitstop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pitstop.companion.WicanCompanionManager
import com.pitstop.drive.scheduleDriveUploads
import com.pitstop.log.LogBuffer
import com.pitstop.notif.SyncReminderManager
import com.pitstop.presence.InCarDetector
import com.pitstop.update.scheduleUpdateChecks
import com.pitstop.widget.scheduleFuelWidgetRefresh
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PitstopApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logBuffer: LogBuffer
    @Inject lateinit var syncReminderManager: SyncReminderManager
    @Inject lateinit var inCarDetector: InCarDetector
    @Inject lateinit var companionManager: WicanCompanionManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // Ship any crash persisted by the prior run's uncaught handler. The
        // in-memory LogBuffer dies with the process, so a crash only reaches
        // the depot if we re-enqueue it on the next launch.
        replayPersistedCrash()
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
        // App-lifetime in-car detector. Listens for paired-car WiFi
        // SSID + AA + paired-car HFP and auto-starts/stops the bridge
        // foreground service when bridgeAutoTrigger is on. Replaces
        // the leave-it-on-all-day pattern that was burning 5-10 %/h
        // when the phone sat in the driveway. The detector itself is
        // ~free at idle (one WiFi NetworkCallback + the existing AA +
        // HFP observers from PresenceTracker); the bridge service no
        // longer runs unless one of the signals fires.
        inCarDetector.start()
        // Re-assert CompanionDeviceManager presence observation for an
        // already-associated WiCAN. This is the reliable background
        // auto-start path: it keeps the OS binding WicanCompanionService
        // across reboots / process death so the bridge FGS can start from
        // the background (via the companion FGS-from-background exemption)
        // the moment the WiCAN is in BLE range. No-op if not yet paired or
        // below API 31. The InCarDetector triggers above remain as
        // best-effort fallbacks.
        companionManager.ensureObserving()
    }

    /**
     * Capture uncaught exceptions to the log buffer before letting the
     * default handler kill the process. Without this, foreground-service
     * crashes leave no breadcrumb on the server because the log shipper
     * never runs its periodic flush. The first line of the stack trace
     * is the most useful — strip the rest to keep client_logs concise.
     */
    private fun crashFile() = java.io.File(filesDir, "last-crash.json")

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = throwable.stackTraceToString().lineSequence()
                    .take(12)
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
                // Persist synchronously to disk. The in-memory LogBuffer dies
                // with the process and the async shipper rarely flushes a
                // startup crash in time (the Thread.sleep below is racy), so
                // crashes were never reaching the depot. replayPersistedCrash()
                // ships this on the next launch.
                runCatching {
                    val json = org.json.JSONObject()
                        .put("thread", thread.name)
                        .put("type", throwable::class.java.simpleName)
                        .put("msg", throwable.message ?: "")
                        .put("stack", stack)
                    crashFile().writeText(json.toString())
                }
                // Best-effort fast-path flush when the network is already up.
                Thread.sleep(500)
            } catch (_: Throwable) {
                /* don't recurse */
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Re-enqueue a crash persisted by the prior run's uncaught handler so it
     *  actually reaches the log depot (the in-memory buffer didn't survive the
     *  crash). Deletes the marker after shipping it. */
    private fun replayPersistedCrash() {
        val f = crashFile()
        if (!f.exists()) return
        runCatching {
            val o = org.json.JSONObject(f.readText())
            logBuffer.error(
                "recovered crash (previous run)",
                mapOf(
                    "thread" to o.optString("thread"),
                    "type" to o.optString("type"),
                    "msg" to o.optString("msg"),
                    "stack" to o.optString("stack"),
                ),
            )
        }
        runCatching { f.delete() }
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
