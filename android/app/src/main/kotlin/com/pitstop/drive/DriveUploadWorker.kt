package com.pitstop.drive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pitstop.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager-driven periodic backstop. Calls into the same
 * [DriveUploader] that the immediate-after-seal kick from
 * [DriveSealer] uses. The 4h cadence is set up in
 * [scheduleDriveUploads]; this is purely the safety net for cases
 * where the immediate kick missed (e.g. process death between seal
 * and drain).
 */
@HiltWorker
class DriveUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploader: DriveUploader,
    private val logs: LogBuffer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
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
