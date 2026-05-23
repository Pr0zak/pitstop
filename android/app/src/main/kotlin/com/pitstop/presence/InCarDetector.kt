package com.pitstop.presence

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.service.PitstopBridgeService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-singleton "user is in the car" detector. OR-combines three
 * independent signals into a single [inCar] StateFlow:
 *
 *   1. **Paired-car WiFi SSID** — phone associates with one of the
 *      SSIDs in [com.pitstop.data.Settings.bridgeAutoTriggerSsids].
 *      Owned here via [ConnectivityManager.registerNetworkCallback];
 *      `getSSID()` requires `ACCESS_FINE_LOCATION` on Android Q+ (we
 *      already declare it for GPS).
 *   2. **Android Auto active** — reuses [PresenceTracker]'s existing
 *      AA StateFlow.
 *   3. **Paired-car HFP Bluetooth** — reuses [PresenceTracker]'s
 *      existing HFP signal.
 *
 * The detector also owns the auto-start side effect: whenever the
 * combined `inCar` flips true and [com.pitstop.data.Settings.bridgeAutoTrigger]
 * is on, it calls [ContextCompat.startForegroundService] on
 * [PitstopBridgeService]. When the combined signal goes false (and
 * stays false for [STOP_GRACE_MS]) it sends the stop intent.
 *
 * Manual-vs-auto policy:
 *   - When `bridgeAutoTrigger` is true, the auto-stop applies regardless
 *     of how the service was started (manual Start in Settings will
 *     run as long as one of the three signals is true, then get
 *     auto-stopped on the next debounce). If the user wants the
 *     bridge to stay on while parked, they disable auto-trigger and
 *     drive it manually (the v0.1.173 contract).
 *   - When `bridgeAutoTrigger` is false the detector is inert — no
 *     WiFi callback registered, no service start/stop side effects.
 *     Manual Start/Stop is the only path.
 *
 * Lifecycle: started from [com.pitstop.PitstopApp.onCreate]; lives as
 * long as the app process. The `bridgeAutoTrigger` flag is observed
 * off the settings flow so flipping the toggle in Settings hot-swaps
 * the detector on / off without restarting anything.
 */
@Singleton
class InCarDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val presence: PresenceTracker,
    private val logBuffer: LogBuffer,
) {

    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Whether the current associated WiFi SSID matches one of the
    // configured car SSIDs. Updated by the NetworkCallback below.
    private val _ssidMatched = MutableStateFlow(false)
    val ssidMatched: StateFlow<Boolean> = _ssidMatched.asStateFlow()

    /** True when ANY of the three signals is active. */
    val inCar: StateFlow<Boolean> by lazy {
        combine(_ssidMatched, presence.inCar) { ssid, presenceInCar ->
            ssid || presenceInCar
        }
            .distinctUntilChanged()
            .debounceFalse(DEBOUNCE_MS)
            .stateIn(ownScope, SharingStarted.Eagerly, false)
    }

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var observerJob: Job? = null
    private var settingsJob: Job? = null
    private var pendingStopJob: Job? = null
    private var started = false

    @Volatile private var enabled: Boolean = false
    @Volatile private var configuredSsids: Set<String> = setOf("MobileChicken")

    /** Cache of the most recent SSID we saw associated, so a settings
     *  change (eg. user adds a new SSID) can re-evaluate without a
     *  WiFi roam to force a fresh capabilities callback. */
    @Volatile private var lastSeenSsid: String? = null

    /** Start the detector. Idempotent. */
    fun start() {
        if (started) return
        started = true

        // Watch the toggle + SSID list. Apply changes immediately.
        settingsJob = ownScope.launch {
            settings.settings.collect { s ->
                val priorEnabled = enabled
                enabled = s.bridgeAutoTrigger
                configuredSsids = s.bridgeAutoTriggerSsids.toSet()
                // SSID list could have changed: re-evaluate against the
                // last seen SSID so users get instant feedback after
                // editing the allowlist.
                recomputeSsidMatched()
                if (priorEnabled != enabled) {
                    logBuffer.info(
                        "in-car detector toggled",
                        mapOf("enabled" to enabled),
                    )
                    if (enabled) {
                        // Need PresenceTracker's AA + HFP streams; start
                        // is idempotent so it's safe to call twice (the
                        // bridge service also calls it on its own).
                        presence.start()
                        ensureWifiCallback()
                    } else {
                        // Detector turned off: tear down our WiFi
                        // callback so we don't keep ConnectivityManager
                        // dispatching to us. Don't stop PresenceTracker
                        // — the bridge service may still be using it.
                        clearWifiCallback()
                        _ssidMatched.value = false
                        pendingStopJob?.cancel()
                        pendingStopJob = null
                    }
                }
            }
        }

        // Auto-start / auto-stop the bridge based on the combined signal.
        observerJob = ownScope.launch {
            inCar.collect { v ->
                if (!enabled) return@collect
                if (v) {
                    pendingStopJob?.cancel()
                    pendingStopJob = null
                    startBridge("in-car signal up")
                } else {
                    // Already debounced for DEBOUNCE_MS by the inCar
                    // flow itself. Add a grace window on top so a
                    // momentary all-false window in the driveway
                    // doesn't immediately tear down the service.
                    pendingStopJob?.cancel()
                    pendingStopJob = ownScope.launch {
                        delay(STOP_GRACE_MS)
                        stopBridge("in-car signal down")
                    }
                }
            }
        }
    }

    /** Stop the detector. Used in tests; not called in production
     *  because the singleton lives for the app's lifetime. */
    fun stop() {
        started = false
        settingsJob?.cancel()
        settingsJob = null
        observerJob?.cancel()
        observerJob = null
        pendingStopJob?.cancel()
        pendingStopJob = null
        clearWifiCallback()
        _ssidMatched.value = false
    }

    private fun startBridge(reason: String) {
        logBuffer.info("auto-start bridge", mapOf("reason" to reason))
        runCatching {
            ContextCompat.startForegroundService(
                context,
                PitstopBridgeService.startIntent(context),
            )
        }.onFailure {
            logBuffer.warn(
                "auto-start failed",
                mapOf("err" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    private fun stopBridge(reason: String) {
        logBuffer.info("auto-stop bridge", mapOf("reason" to reason))
        runCatching {
            context.startService(PitstopBridgeService.stopIntent(context))
        }.onFailure {
            logBuffer.warn(
                "auto-stop failed",
                mapOf("err" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    private fun ensureWifiCallback() {
        if (connectivityCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                logBuffer.warn("in-car: ConnectivityManager unavailable")
                return
            }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Reads via getSSID() are the safe path on most Android
                // versions; the WifiInfo path through onCapabilitiesChanged
                // arrives shortly after with the same answer.
                readAndUpdateSsid(network, cm)
            }
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                // Android 12+ exposes WifiInfo directly off capabilities
                // (no extra perm beyond what we have). Use that when
                // available; otherwise fall back to the WifiManager path.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val info = capabilities.transportInfo as? WifiInfo
                    val ssid = info?.ssid?.let { stripQuotes(it) }
                    if (ssid != null) {
                        lastSeenSsid = ssid
                        recomputeSsidMatched()
                        return
                    }
                }
                readAndUpdateSsid(network, cm)
            }
            override fun onLost(network: Network) {
                // WiFi went away → SSID signal is no longer true.
                lastSeenSsid = null
                _ssidMatched.value = false
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, cb)
            connectivityCallback = cb
            logBuffer.info("in-car: wifi callback registered")
        }.onFailure {
            logBuffer.warn(
                "in-car: registerNetworkCallback failed",
                mapOf("err" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    private fun clearWifiCallback() {
        val cb = connectivityCallback ?: return
        connectivityCallback = null
        lastSeenSsid = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    @SuppressLint("MissingPermission")
    private fun readAndUpdateSsid(network: Network, cm: ConnectivityManager) {
        // Pre-Android-12 path: WifiManager.connectionInfo (deprecated
        // but still works). Requires ACCESS_FINE_LOCATION on Q+, which
        // we already declare. If the perm isn't granted we leave the
        // SSID signal at its current value rather than flapping false
        // — the other two signals can still fire.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // No perm → can't read SSID at all. Log once-style by
            // gating on lastSeenSsid being unset.
            if (lastSeenSsid == null) {
                logBuffer.warn(
                    "in-car: SSID read skipped — ACCESS_FINE_LOCATION not granted",
                )
            }
            return
        }
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        @Suppress("DEPRECATION")
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return
        val ssid = stripQuotes(info.ssid)
        if (ssid.isBlank() || ssid == "<unknown ssid>") {
            // SDK returns this when perms are wrong or no WiFi
            // is associated — treat as "no SSID match".
            lastSeenSsid = null
            _ssidMatched.value = false
            return
        }
        lastSeenSsid = ssid
        recomputeSsidMatched()
    }

    private fun recomputeSsidMatched() {
        val seen = lastSeenSsid ?: run {
            _ssidMatched.value = false
            return
        }
        val matched = configuredSsids.any { it.equals(seen, ignoreCase = true) }
        if (_ssidMatched.value != matched) {
            logBuffer.info(
                "in-car ssid match changed",
                mapOf("matched" to matched, "configured_count" to configuredSsids.size),
            )
            _ssidMatched.value = matched
        }
    }

    companion object {
        /** Debounce window for the combined signal flipping false.
         *  Smoothes momentary BT disconnects + WiFi blips. */
        private const val DEBOUNCE_MS: Long = 30_000L

        /** Additional grace after the debounced false before we send
         *  the stop intent. Keeps the bridge alive through a brief
         *  in-driveway lull (eg. user shuts off the hotspot, runs back
         *  inside, comes out, turns it back on). */
        private const val STOP_GRACE_MS: Long = 120_000L

        private fun stripQuotes(s: String): String =
            s.trim().let { v ->
                if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
                    v.substring(1, v.length - 1)
                } else v
            }

        private fun Flow<Boolean>.debounceFalse(ms: Long): Flow<Boolean> =
            transformLatest { v ->
                if (v) emit(true) else {
                    delay(ms)
                    emit(false)
                }
            }
    }
}
