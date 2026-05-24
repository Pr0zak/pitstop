package com.pitstop.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives [ActivityTransitionResult]s from
 * [com.google.android.gms.location.ActivityRecognitionClient.requestActivityTransitionUpdates].
 *
 * Subscribed to ENTER + EXIT transitions for [DetectedActivity.IN_VEHICLE]
 * only (walking / cycling / running / still are ignored — we don't care
 * about them for "user is in the car" detection). False positives on
 * buses, trains, and subways are accepted in exchange for the ~5–15 s
 * detection latency improvement over the WiFi-SSID signal — at worst the
 * bridge briefly starts, finds no BLE device, and the foreground service's
 * own stop-when-quiet logic tears it down.
 *
 * The receiver writes to two places:
 *   1. [ActivityRecognitionBus] — in-process StateFlow consumed by
 *      [InCarDetector] on the next collect tick.
 *   2. [SettingsRepository.writeInVehicleState] — DataStore-persisted
 *      flag so the value survives process death. AR transitions fire via
 *      PendingIntent and can wake the process after it's been killed; on
 *      the next process boot, [InCarDetector] re-seeds the bus from this
 *      flag before any further transitions arrive.
 *
 * Hilt note: `BroadcastReceiver` works with `@AndroidEntryPoint` but the
 * extra wiring isn't worth it for a 15-line receiver — we pull
 * dependencies via `EntryPointAccessors.fromApplication`, matching the
 * pattern in [com.pitstop.widget.FuelWidgetProvider].
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ARReceiverEntryPoint {
        fun bus(): ActivityRecognitionBus
        fun settings(): SettingsRepository
        fun logBuffer(): LogBuffer
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent) &&
            !ActivityTransitionResult.hasResult(intent)
        ) {
            return
        }
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext, ARReceiverEntryPoint::class.java,
        )
        val log = entry.logBuffer()

        // The transitions list arrives in chronological order — we want
        // the latest IN_VEHICLE transition so the most recent state wins.
        val latest = result.transitionEvents.lastOrNull {
            it.activityType == DetectedActivity.IN_VEHICLE
        } ?: return

        val now = System.currentTimeMillis()
        val inVehicle = latest.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER

        log.info(
            "AR transition",
            mapOf(
                "in_vehicle" to inVehicle,
                "elapsed_ns_offset" to latest.elapsedRealTimeNanos,
            ),
        )

        // Emit synchronously to the in-process bus so InCarDetector sees
        // the change immediately (no awaiting an async coroutine for the
        // hot-path signal).
        entry.bus().setInVehicle(inVehicle)

        // Best-effort persist for process-death survival. Failure here
        // doesn't break the live path — only the next process boot would
        // miss the carryover.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching { entry.settings().writeInVehicleState(inVehicle, now) }
                .onFailure {
                    log.warn(
                        "AR state persist failed",
                        mapOf("err" to (it.message ?: it::class.java.simpleName)),
                    )
                }
        }
    }
}
