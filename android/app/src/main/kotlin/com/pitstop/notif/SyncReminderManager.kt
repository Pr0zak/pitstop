package com.pitstop.notif

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pitstop.MainActivity
import com.pitstop.PitstopApp
import com.pitstop.R
import com.pitstop.drive.PendingDriveDao
import com.pitstop.drive.UploadPhase
import com.pitstop.drive.UploadProgress
import com.pitstop.drive.UploadProgressBus
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the unacked-drive queue and posts a persistent reminder
 * notification once the backlog crosses [THRESHOLD]. Used by users in
 * manual-sync mode (drives accumulate until manually synced) but the
 * threshold is universal — even with auto-sync on, a queue ≥5 means
 * something's stuck (no network, server down, auth broken) and the
 * user should know.
 *
 * Lifecycle: started from [com.pitstop.PitstopApp.onCreate]. The
 * collection runs on a singleton-owned scope, so the observation
 * survives across MainActivity recreates / process priority changes —
 * the only way it stops is the process dying, in which case
 * PitstopApp.onCreate fires again on next launch and we resync from
 * the current DAO count.
 *
 * Threshold semantics:
 *   - 0 → 4 : no notification
 *   - 5+    : ongoing notification with current count
 *   - drops to 4 or below: notification cancelled
 *
 * The notification carries two actions:
 *   tap body → MainActivity with ACTION_SYNC_DRIVES (lands on History)
 *   "Sync now" action button → [SyncReminderReceiver] (fires drain in bg)
 */
@Singleton
class SyncReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingDao: PendingDriveDao,
    private val progressBus: UploadProgressBus,
    private val logs: LogBuffer,
) {

    /**
     * Singleton-owned scope. Tying this to the app lifecycle (not the
     * bridge service) means the manager keeps observing the queue even
     * while the bridge is stopped — sealed drives from a prior session
     * still trigger the notification when they cross the threshold.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    @Volatile private var lastPostedCount: Int = -1

    /** Tracks whether the progress row is currently posted, so the
     *  cancel only fires once per pass instead of on every Idle tick. */
    @Volatile private var progressShowing = false
    @Volatile private var lastProgressPhase: UploadPhase? = null
    @Volatile private var lastProgressIndex = -1
    @Volatile private var lastProgressPercent = -1
    @Volatile private var lastProgressPostAt = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch {
            pendingDao.observeUnackedCount()
                .distinctUntilChanged()
                .collect { count ->
                    handleCountChange(count)
                }
        }
        scope.launch {
            progressBus.state.collect { handleProgress(it) }
        }
        logs.info("SyncReminderManager: started")
    }

    /**
     * Mirrors the in-app upload card into the notification shade.
     *
     * This is the only feedback that exists for the common case: the
     * phone seals a drive when the engine goes off and uploads it while
     * the app is closed and the user is walking away from the car.
     * Silent and low-priority (the channel is IMPORTANCE_LOW and the
     * notification sets onlyAlertOnce) so it informs without nagging,
     * and it clears the moment the pass ends.
     */
    private fun handleProgress(progress: UploadProgress) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        when (progress) {
            is UploadProgress.Running -> {
                // The byte counter emits every 64 KiB — ~80 updates for a
                // 5 MB payload. Android throttles a notification updated
                // that hard, so only re-post on a phase change, a whole
                // percentage point, or once a second.
                val now = System.currentTimeMillis()
                val pct = percent(progress.bytesSent, progress.payloadBytes)
                val worthPosting = !progressShowing ||
                    progress.phase != lastProgressPhase ||
                    progress.driveIndex != lastProgressIndex ||
                    pct != lastProgressPercent ||
                    now - lastProgressPostAt >= PROGRESS_MIN_INTERVAL_MS
                if (worthPosting) {
                    nm.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(progress))
                    lastProgressPhase = progress.phase
                    lastProgressIndex = progress.driveIndex
                    lastProgressPercent = pct
                    lastProgressPostAt = now
                    progressShowing = true
                }
            }
            else -> {
                if (progressShowing) {
                    nm.cancel(PROGRESS_NOTIFICATION_ID)
                    progressShowing = false
                }
            }
        }
    }

    private fun buildProgressNotification(p: UploadProgress.Running): android.app.Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SYNC_DRIVES
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Byte counts while the body streams; "waiting for the server"
        // once it's all out. An indeterminate bar covers the window
        // before the payload size is known.
        val total = p.payloadBytes
        val determinate = total > 0L && p.phase != UploadPhase.Reading
        val text = when (p.phase) {
            UploadPhase.Reading -> "Preparing…"
            UploadPhase.Sending ->
                if (total > 0L) "${percent(p.bytesSent, total)}% sent" else "Sending…"
            UploadPhase.AwaitingServer -> "Waiting for the server…"
        }
        return NotificationCompat.Builder(context, PitstopApp.SYNC_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Uploading drive ${p.driveIndex} of ${p.driveTotal}")
            .setContentText(text)
            .setProgress(
                100,
                if (determinate) percent(p.bytesSent, total) else 0,
                !determinate || p.phase == UploadPhase.AwaitingServer,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .build()
    }

    private fun percent(sent: Long, total: Long): Int =
        if (total <= 0L) 0 else ((sent * 100L) / total).coerceIn(0L, 100L).toInt()

    private fun handleCountChange(count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        when {
            count >= THRESHOLD -> {
                // Either first crossing or count update while above
                // threshold — both want a notify() with the current
                // count. NotificationManager dedupes on ID so the
                // update path doesn't re-post a new system row.
                nm.notify(NOTIFICATION_ID, buildNotification(count))
                if (lastPostedCount < THRESHOLD) {
                    logs.info(
                        "SyncReminderManager: queue crossed threshold; posting reminder",
                        mapOf("count" to count, "threshold" to THRESHOLD),
                    )
                }
                lastPostedCount = count
            }
            else -> {
                if (lastPostedCount >= THRESHOLD) {
                    nm.cancel(NOTIFICATION_ID)
                    logs.info(
                        "SyncReminderManager: queue back below threshold; cancelling reminder",
                        mapOf("count" to count),
                    )
                }
                lastPostedCount = count
            }
        }
    }

    private fun buildNotification(count: Int): android.app.Notification {
        // Tap intent → MainActivity with ACTION_SYNC_DRIVES so the
        // pager lands on the History tab. CLEAR_TOP + SINGLE_TOP
        // matches the launch-mode (singleTask) on the activity so an
        // already-running app doesn't relaunch the stack.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SYNC_DRIVES
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // "Sync now" action → SyncReminderReceiver which calls
        // DriveUploader.drain on its own scope. Stays a broadcast (vs
        // a service) so the tap fires immediately without launching
        // any UI; the user can hit it from the lock screen.
        val syncIntent = Intent(context, SyncReminderReceiver::class.java).apply {
            action = SyncReminderReceiver.ACTION_SYNC_NOW
        }
        val syncPi = PendingIntent.getBroadcast(
            context,
            REQUEST_SYNC,
            syncIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, PitstopApp.SYNC_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Drives waiting to sync")
            .setContentText("$count drives haven't uploaded · tap to sync")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$count drives are queued on this phone. Tap to open History, " +
                        "or use \"Sync now\" to upload in the background.",
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(0, "Sync now", syncPi)
            .build()
    }

    companion object {
        const val THRESHOLD = 5
        const val NOTIFICATION_ID = 2002

        /** Separate row from the backlog reminder: the two say different
         *  things and can legitimately be up at the same time (a big
         *  queue draining one drive at a time). */
        const val PROGRESS_NOTIFICATION_ID = 2003
        private const val PROGRESS_MIN_INTERVAL_MS = 1_000L
        private const val REQUEST_OPEN = 30
        private const val REQUEST_SYNC = 31
    }
}
