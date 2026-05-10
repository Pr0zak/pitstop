package com.pitstop.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedule [DriveUploadWorker] as a 4-hour periodic backstop (#117).
 * Used in addition to the per-drive immediate kick by [DriveSealer]
 * and the user-driven Sync-now in the History tab. Catches edge
 * cases where the immediate kick missed (Doze restrictions,
 * process death between seal and worker schedule).
 *
 * Constraints: any network. Cellular vs Wi-Fi gating happens in
 * [DriveUploadWorker] based on payload size + user preference.
 * Without NetworkType.CONNECTED we'd burn battery retrying on
 * airplane-mode flights.
 *
 * KEEP policy ensures the periodic chain survives reboots and
 * process restarts; subsequent app launches don't reschedule.
 */
const val DRIVE_UPLOAD_WORK_NAME = "pitstop-drive-upload-periodic"

fun scheduleDriveUploads(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val req = PeriodicWorkRequestBuilder<DriveUploadWorker>(
        repeatInterval = 4,
        repeatIntervalTimeUnit = TimeUnit.HOURS,
        flexTimeInterval = 30,
        flexTimeIntervalUnit = TimeUnit.MINUTES,
    )
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        DRIVE_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        req,
    )
}
