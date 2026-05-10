package com.pitstop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pitstop.drive.scheduleDriveUploads
import com.pitstop.log.LogBuffer
import com.pitstop.update.scheduleUpdateChecks
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PitstopApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logBuffer: LogBuffer

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
        // Drive-upload backstop (#117). Per-drive immediate kicks
        // come from DriveSealer; this 4 h periodic catches edge
        // cases where the immediate kick missed (Doze, process
        // death between seal and worker schedule).
        scheduleDriveUploads(this)
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
    }

    companion object {
        const val BRIDGE_CHANNEL_ID = "pitstop_bridge"
        const val UPDATES_CHANNEL_ID = "pitstop_updates"
    }
}
