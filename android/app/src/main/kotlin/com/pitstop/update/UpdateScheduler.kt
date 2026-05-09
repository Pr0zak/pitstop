package com.pitstop.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedule [UpdateCheckWorker] on a 6-hour repeating cadence. The
 * KEEP policy means once an install enqueues this work, subsequent
 * app launches don't reschedule — the periodic chain survives reboots
 * and process restarts via WorkManager's persistence layer.
 *
 * NetworkType.CONNECTED so the worker doesn't burn battery polling
 * GitHub on cellular when off-network. WorkManager batches against
 * other low-priority jobs, so the actual fire time can drift up to
 * a few minutes — that's fine for "is there a new APK?".
 */
const val UPDATE_WORK_NAME = "pitstop-update-check"

fun scheduleUpdateChecks(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
        repeatInterval = 6,
        repeatIntervalTimeUnit = TimeUnit.HOURS,
        flexTimeInterval = 30,
        flexTimeIntervalUnit = TimeUnit.MINUTES,
    )
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UPDATE_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        req,
    )
}
