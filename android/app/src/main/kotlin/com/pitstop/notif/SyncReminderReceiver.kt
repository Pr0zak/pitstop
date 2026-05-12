package com.pitstop.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pitstop.drive.DriveUploader
import com.pitstop.log.LogBuffer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Sync now" PendingIntent action embedded in the
 * sync-reminder notification (see [SyncReminderManager]).
 *
 * The receiver fires on the main thread; we hand off immediately to
 * a worker scope so the broadcast finishes quickly. `goAsync` keeps
 * the receiver alive while the drain runs — without it Android can
 * tear down the process between the dispatch and the network call.
 *
 * Exposed only to our own app: `exported="false"` in the manifest +
 * a fully-qualified intent action. No external app can fire this.
 */
@AndroidEntryPoint
class SyncReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var uploader: DriveUploader
    @Inject lateinit var logs: LogBuffer

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_NOW) return
        // goAsync gives us a PendingResult to finish() once the
        // coroutine resolves. Android allows ~10s before forcibly
        // killing — the drain pass is bounded by the network calls
        // it makes; if a single drive's upload takes longer the
        // remaining drives wait for the next periodic / sealer kick.
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                logs.info("SyncReminderReceiver: drain requested by notification action")
                uploader.drain("notification-action")
            } catch (t: Throwable) {
                logs.warn(
                    "SyncReminderReceiver: drain threw",
                    mapOf("err" to (t.message ?: t::class.java.simpleName)),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "com.pitstop.notif.SYNC_NOW"
    }
}
