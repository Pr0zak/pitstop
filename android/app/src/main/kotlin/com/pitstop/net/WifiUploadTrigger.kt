package com.pitstop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.pitstop.data.SettingsRepository
import com.pitstop.drive.DriveUploader
import com.pitstop.drive.PendingDriveDao
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the queued-drive backlog the moment the phone joins a WiFi
 * network the user nominated for uploads.
 *
 * This is the live half of the feature and only works while the process
 * is alive — a `NetworkCallback` does not wake a dead app. The durable
 * half is [com.pitstop.drive.enqueueWifiDriveUpload], a WorkManager
 * request with an unmetered-network constraint that [com.pitstop.drive.DriveSealer]
 * arms whenever it parks a drive it wasn't allowed to upload; the OS
 * starts the process for that even after it was killed. Both paths run
 * the same [WifiUploadGate], and [DriveUploader]'s drain mutex means a
 * double-fire costs one skipped pass, not a double upload.
 *
 * Registered only while the setting is on, and re-evaluated on every
 * settings emission so turning the toggle on while already sitting on
 * the target network uploads immediately instead of waiting for the next
 * time the user leaves and comes home.
 */
@Singleton
class WifiUploadTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val gate: WifiUploadGate,
    private val uploader: DriveUploader,
    private val dao: PendingDriveDao,
    private val logs: LogBuffer,
) {

    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evalMutex = Mutex()

    private var settingsJob: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    @Volatile private var enabled = false

    /** Epoch-ms of the last drain this trigger requested. Capability
     *  callbacks fire in bursts while a network settles, and a failing
     *  server would otherwise have us re-request on every one of them. */
    @Volatile private var lastRequestedAtMs = 0L

    /** Start observing. Idempotent; called from [com.pitstop.PitstopApp.onCreate]. */
    fun start() {
        if (started) return
        started = true
        settingsJob = ownScope.launch {
            settings.settings.collect { s ->
                val was = enabled
                enabled = s.uploadOnWifi
                if (enabled && !was) {
                    registerCallback()
                    logs.info("wifi-upload: enabled")
                } else if (!enabled && was) {
                    unregisterCallback()
                    logs.info("wifi-upload: disabled")
                }
                // Re-evaluate on every emission, not just on the edge: the
                // SSID list can change while the toggle stays on, and the
                // phone may already be sitting on the newly-listed network.
                if (enabled) maybeDrain("settings changed")
            }
        }
    }

    /** Tear down. Used by tests; the singleton lives for the process. */
    fun stop() {
        started = false
        settingsJob?.cancel()
        settingsJob = null
        unregisterCallback()
    }

    private fun registerCallback() {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                logs.warn("wifi-upload: ConnectivityManager unavailable")
                return
            }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A fresh network is a fresh opportunity — clear the
                // cooldown so arriving home always gets one attempt even
                // if a pass was requested minutes ago on another network.
                lastRequestedAtMs = 0L
                maybeDrain("wifi available")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                // This is where VALIDATED usually shows up: onAvailable
                // fires before the platform has confirmed the network
                // actually reaches the internet.
                maybeDrain("wifi capabilities changed")
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, cb)
            callback = cb
        }.onFailure {
            logs.warn(
                "wifi-upload: registerNetworkCallback failed",
                mapOf("err" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    private fun unregisterCallback() {
        val cb = callback ?: return
        callback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    /**
     * Evaluate the gate and, if it passes and there is anything queued,
     * ask the uploader for a pass. Never blocks a callback thread — the
     * whole body runs on [ownScope].
     */
    fun maybeDrain(reason: String) {
        if (!enabled) return
        ownScope.launch {
            evalMutex.withLock {
                // Cheapest discriminator first: capability callbacks fire in
                // bursts, and with an empty queue there is nothing to say.
                val queued = runCatching { dao.unackedCount() }.getOrDefault(0)
                if (queued == 0) return@withLock
                val now = System.currentTimeMillis()
                if (now - lastRequestedAtMs < COOLDOWN_MS) {
                    logs.debug(
                        "wifi-upload: within cooldown",
                        mapOf("trigger" to reason, "since_ms" to (now - lastRequestedAtMs)),
                    )
                    return@withLock
                }
                val verdict = gate.evaluate()
                if (verdict !is WifiUploadGate.Verdict.Allowed) {
                    logs.debug(
                        "wifi-upload: not draining",
                        mapOf("trigger" to reason, "verdict" to verdict.reason()),
                    )
                    return@withLock
                }
                lastRequestedAtMs = now
                logs.info(
                    "wifi-upload: draining queue",
                    mapOf(
                        "trigger" to reason,
                        "ssid" to (verdict.ssid ?: "unnamed"),
                        "queued" to queued,
                    ),
                )
                uploader.requestDrain("wifi-auto")
            }
        }
    }

    companion object {
        /** Minimum gap between two drains this trigger requests. The
         *  uploader already ignores a request while a pass is running;
         *  this covers the case where the pass finished by failing and
         *  the network keeps emitting capability changes. */
        private const val COOLDOWN_MS = 60_000L
    }
}
