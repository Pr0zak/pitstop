package com.pitstop.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

/** Unique name for the unmetered-network one-shot armed after a seal. */
const val DRIVE_UPLOAD_WIFI_WORK_NAME = "pitstop-drive-upload-on-wifi"

/**
 * Arm a one-shot upload that the OS runs the next time the phone is on an
 * unmetered network — the durable half of "auto-upload on WiFi".
 *
 * [com.pitstop.net.WifiUploadTrigger] covers the case where the app is
 * still alive when the network arrives, but a `NetworkCallback` cannot
 * wake a killed process, and the gap between parking the car and getting
 * home is exactly when Android reclaims the app. WorkManager's constraint
 * survives process death and reboot, so this is what makes the feature
 * work on the drive that matters.
 *
 * The constraint can only express "unmetered", not "this SSID" — so the
 * worker re-runs the full [com.pitstop.net.WifiUploadGate] when it wakes
 * and exits quietly if the network isn't one the user nominated.
 *
 * KEEP: a request already waiting on the constraint covers every drive
 * queued behind it, since the drain empties the whole queue.
 */
fun enqueueWifiDriveUpload(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    val req = OneTimeWorkRequestBuilder<DriveUploadWorker>()
        .setConstraints(constraints)
        .addTag(DriveUploadWorker.TAG)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        DRIVE_UPLOAD_WIFI_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        req,
    )
}
