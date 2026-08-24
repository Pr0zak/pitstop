package com.pitstop.drive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.net.WifiUploadGate
import com.pitstop.net.reason
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager-driven periodic backstop. Calls into the same
 * [DriveUploader] that the immediate-after-seal kick from
 * [DriveSealer] uses. The 4h cadence is set up in
 * [scheduleDriveUploads]; this is purely the safety net for cases
 * where the immediate kick missed (e.g. process death between seal
 * and drain).
 *
 * When manual-sync mode is on the worker still wakes (cheaper than
 * cancelling + rescheduling the periodic chain every time the user
 * toggles the setting) but exits immediately — unless the user has
 * turned on auto-upload-on-WiFi and the phone is currently on a network
 * that qualifies, which is a standing instruction to upload here.
 * Otherwise the only way drives upload in manual-sync mode is an
 * explicit user action: History "Sync now" or the persistent-reminder
 * notification action.
 *
 * This worker also serves the unmetered-network one-shot armed by
 * [enqueueWifiDriveUpload] — same gate, same drain.
 */
@HiltWorker
class DriveUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploader: DriveUploader,
    private val settings: SettingsRepository,
    private val wifiGate: WifiUploadGate,
    private val logs: LogBuffer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manualOnly = runCatching { settings.settings.first().manualSyncOnly }
            .getOrDefault(false)
        if (manualOnly) {
            val verdict = wifiGate.evaluate()
            if (verdict !is WifiUploadGate.Verdict.Allowed) {
                logs.info(
                    "DriveUploadWorker: manual-sync mode on — skipping drain",
                    mapOf("wifi_verdict" to verdict.reason()),
                )
                return Result.success()
            }
            logs.info(
                "DriveUploadWorker: manual-sync mode on but on an upload network",
                mapOf("ssid" to (verdict.ssid ?: "unnamed")),
            )
        }
        logs.info("DriveUploadWorker: starting (periodic backstop)")
        return try {
            uploader.drain("periodic-backstop")
            Result.success()
        } catch (t: Throwable) {
            logs.warn(
                "DriveUploadWorker: drain threw",
                mapOf("err" to (t.message ?: t::class.java.simpleName)),
            )
            Result.retry()
        }
    }

    companion object {
        const val TAG = "drive-upload"
        const val UNIQUE_NAME = "drive-upload-unique"
    }
}
