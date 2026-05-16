package com.pitstop.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic widget-refresh fallback (WIDGET-5). The platform's
 * `updatePeriodMillis = 30 min` declared in fuel_widget_info.xml is
 * heavily deferred once the device enters Doze — a user reported
 * the widget going hours without a fetch because the OS ticker
 * never fired.
 *
 * WorkManager is Doze-aware: it fires inside the system's
 * maintenance windows even on dozing devices, so this worker keeps
 * the fuel widget within ~15 minutes of fresh regardless of the
 * platform ticker's drift.
 *
 * Belt-and-suspenders. The on-poll, on-MQTT, on-bridge-start, and
 * on-app-open triggers all still fire when *something* is
 * happening; this worker covers the "nothing happening, app closed
 * for hours" case.
 */
class FuelWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        FuelWidgetProvider.refreshWidgets(applicationContext)
        return Result.success()
    }
}

const val FUEL_WIDGET_WORK_NAME = "pitstop-fuel-widget-refresh"

fun scheduleFuelWidgetRefresh(context: Context) {
    // 15 min is WorkManager's minimum periodic interval. No
    // constraints — the widget fetch needs network but if it's
    // briefly unavailable the next tick handles it (and the existing
    // event-driven triggers cover real driving anyway).
    val req = PeriodicWorkRequestBuilder<FuelWidgetRefreshWorker>(
        repeatInterval = 15,
        repeatIntervalTimeUnit = TimeUnit.MINUTES,
    ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        FUEL_WIDGET_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        req,
    )
}
