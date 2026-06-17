package com.pitstop.companion

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.service.PitstopBridgeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The OS binds this service while we're observing the WiCAN's BLE presence
 * (set up by [WicanCompanionManager.ensureObserving]). It is the reliable,
 * OS-sanctioned auto-start path: when the WiCAN advertises in BLE range the
 * system WAKES our process and calls [onDeviceAppeared]; because the WiCAN is
 * an associated companion device the app holds the
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` exemption, so
 * the bridge FGS start that previously failed with
 * `ForegroundServiceStartNotAllowedException` now succeeds from the background.
 *
 * Both override shapes are implemented and dispatched by API level:
 *   - **API 31–32**: the system calls [onDeviceAppeared]`(String)`.
 *   - **API 33+**: the system calls [onDeviceAppeared]`(AssociationInfo)`.
 *
 * This does NOT replace [com.pitstop.presence.InCarDetector] — CDM is the
 * robust PRIMARY trigger; the InCarDetector AA/WiFi/AR paths remain best-effort
 * fallbacks. The bridge's onStartCommand is idempotent (guards on an active
 * poll job), so a CDM start racing an InCarDetector start is harmless.
 */
@AndroidEntryPoint
class WicanCompanionService : CompanionDeviceService() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var logBuffer: LogBuffer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingStopJob: Job? = null

    // ── API 33+ (AssociationInfo) ──────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val mac = runCatching { associationInfo.deviceMacAddress?.toString() }.getOrNull()
        onAppeared(mac, associationInfo.id)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        onDisappeared()
    }

    // ── API 31–32 (String address) — deprecated but still the call shape
    //     the OS uses below 33. ────────────────────────────────────────

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        // On API 33+ the AssociationInfo overload is authoritative — skip the
        // string one to avoid a double start.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        onAppeared(address, associationId = null)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        onDisappeared()
    }

    // ── shared handling ────────────────────────────────────────────────

    private fun onAppeared(mac: String?, associationId: Int?) {
        logBuffer.info(
            "companion: device appeared",
            mapOf(
                "mac" to (mac ?: "?"),
                "association_id" to (associationId ?: -1),
            ),
        )
        pendingStopJob?.cancel()
        pendingStopJob = null
        scope.launch {
            runCatching {
                // Respect the user's auto-start toggle. If they turned
                // "Auto-start in car" off, the CDM wake should NOT force the
                // bridge up — they've opted into manual Start/Stop only.
                val auto = settings.settings.first().bridgeAutoTrigger
                if (!auto) {
                    logBuffer.info("companion: bridge start skipped — auto-start off")
                    return@launch
                }
                logBuffer.info("companion: bridge start")
                ContextCompat.startForegroundService(
                    this@WicanCompanionService,
                    PitstopBridgeService.startIntent(this@WicanCompanionService),
                )
            }.onFailure {
                logBuffer.warn(
                    "companion: bridge start failed",
                    mapOf("err" to (it.message ?: it::class.java.simpleName)),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDisappeared() {
        logBuffer.info("companion: device disappeared")
        // Mirror InCarDetector's grace window so a brief BLE drop in the
        // driveway doesn't immediately tear the bridge down. The bridge's
        // own engine-state + BLE-lost watchdogs (ADR-017) seal the open
        // drive independently; this stop is the "we're parked + the WiCAN is
        // gone" backstop.
        pendingStopJob?.cancel()
        pendingStopJob = scope.launch {
            delay(STOP_GRACE_MS)
            runCatching {
                logBuffer.info("companion: bridge stop")
                startService(PitstopBridgeService.stopIntent(this@WicanCompanionService))
            }.onFailure {
                logBuffer.warn(
                    "companion: bridge stop failed",
                    mapOf("err" to (it.message ?: it::class.java.simpleName)),
                )
            }
        }
    }

    companion object {
        /** Matches [com.pitstop.presence.InCarDetector.STOP_GRACE_MS] intent —
         *  hold the bridge through a momentary BLE drop near the car. */
        private const val STOP_GRACE_MS: Long = 120_000L
    }
}
