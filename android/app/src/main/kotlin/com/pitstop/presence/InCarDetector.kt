package com.pitstop.presence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.service.BridgeStateBus
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-singleton "user is in the car" detector. OR-combines four
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
 *   4. **Activity Recognition `IN_VEHICLE`** *(opt-in)* — GMS
 *      [ActivityRecognitionClient] transitions, fed in via
 *      [ActivityRecognitionBus] (which the
 *      [ActivityRecognitionReceiver] writes to). Off by default; the
 *      user enables it under the "Auto-start in car" toggle, at which
 *      point the UI requests the `ACTIVITY_RECOGNITION` runtime
 *      permission. Fires within ~5–15 s of vehicle motion — faster
 *      than the WiFi SSID signal which can lag 30–60 s while the
 *      phone clings to home WiFi.
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
    private val activityBus: ActivityRecognitionBus,
    private val stateBus: BridgeStateBus,
    private val logBuffer: LogBuffer,
) {

    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Whether the current associated WiFi SSID matches one of the
    // configured car SSIDs. Updated by the NetworkCallback below.
    private val _ssidMatched = MutableStateFlow(false)
    val ssidMatched: StateFlow<Boolean> = _ssidMatched.asStateFlow()

    /** True when ANY of the four signals is active. */
    val inCar: StateFlow<Boolean> by lazy {
        combine(
            _ssidMatched,
            presence.inCar,
            activityBus.inVehicle,
        ) { ssid, presenceInCar, inVehicle ->
            ssid || presenceInCar || inVehicle
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
    @Volatile private var configuredSsids: Set<String> = emptySet()
    @Volatile private var activitySubscribed: Boolean = false
    @Volatile private var activityToggleOn: Boolean = false

    /** Cache of the most recent SSID we saw associated, so a settings
     *  change (eg. user adds a new SSID) can re-evaluate without a
     *  WiFi roam to force a fresh capabilities callback. */
    @Volatile private var lastSeenSsid: String? = null

    /** Start the detector. Idempotent. */
    fun start() {
        if (started) return
        started = true

        // Re-seed the AR bus from the persisted IN_VEHICLE flag so the
        // signal carries across process death. AR transitions fire via
        // PendingIntent and can wake the process after kill, but the bus
        // is in-memory — without this restore, a process boot would clear
        // the signal to false even though the device is still moving.
        ownScope.launch {
            runCatching {
                val (value, atMs) = settings.readInVehicleState()
                val age = System.currentTimeMillis() - atMs
                if (value && atMs > 0L && age < SettingsRepository.AR_STATE_STALE_MS) {
                    activityBus.setInVehicle(true)
                    logBuffer.info(
                        "AR state restored on boot",
                        mapOf("age_ms" to age),
                    )
                }
            }
        }

        // Watch the toggle + SSID list. Apply changes immediately.
        settingsJob = ownScope.launch {
            settings.settings.collect { s ->
                val priorEnabled = enabled
                val priorActivityToggle = activityToggleOn
                enabled = s.bridgeAutoTrigger
                activityToggleOn = s.bridgeAutoTriggerActivityEnabled
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
                        // Withdraw any lingering hint so the merge falls
                        // back to BLE-only behaviour.
                        stateBus.setInCarEngineHint(null)
                    }
                }
                // Reconcile AR subscription against the toggle + parent
                // enabled flag. Also re-checks on every emission so OS-level
                // permission revocation (which doesn't fire a settings
                // change) is caught next time the user touches Settings.
                reconcileActivityRecognition(
                    parentEnabled = enabled,
                    toggleOn = activityToggleOn,
                    toggleChanged = priorActivityToggle != activityToggleOn,
                )
            }
        }

        // Auto-start / auto-stop the bridge based on the combined signal.
        observerJob = ownScope.launch {
            inCar.collect { v ->
                if (!enabled) return@collect
                // Push the hint into the state bus regardless of the
                // auto-start side effect. The BridgeStateBus merges this
                // with the BLE-derived engine signal — in BLE-only mode
                // it's a no-op (BLE wins); in GPS-only mode it's the
                // ONLY engine-state source, which is what enables drive
                // sealing without a WiCAN link (v0.1.176 fix).
                stateBus.setInCarEngineHint(v)
                if (v) {
                    pendingStopJob?.cancel()
                    pendingStopJob = null
                    startBridge("in-car signal up")
                } else {
                    // Already debounced for DEBOUNCE_MS by the inCar
                    // flow itself. Add a grace window on top so a
                    // momentary all-false window in the driveway
                    // doesn't immediately tear down the service. The
                    // hint flip above already happened — the merged
                    // engineState transitions Off and the bridge's
                    // engine-state listener seals the open drive BEFORE
                    // this grace window expires and stopBridge fires.
                    pendingStopJob?.cancel()
                    pendingStopJob = ownScope.launch {
                        delay(STOP_GRACE_MS)
                        // Don't tear the bridge down while a drive is still
                        // live (BLE connected / engine on / recent OBD frame).
                        // A flappy presence signal — an AR IN_VEHICLE EXIT at a
                        // long light, a brief HFP/WiFi drop — must not seal an
                        // in-progress BLE drive and split it (the same failure
                        // the CDM path had, 2026-07-18). Require IDLE_CONFIRM_POLLS
                        // consecutive idle polls (hysteresis) so a single
                        // transient reading can't seal a live drive, then stop —
                        // which still guarantees teardown once the user has
                        // really left and the WiCAN sleeps. In GPS-only mode the
                        // hint above already flipped engineState Off, so this
                        // confirms idle immediately.
                        var idlePolls = 0
                        while (isActive) {
                            if (stateBus.isDriveLikelyActive()) {
                                idlePolls = 0
                            } else if (++idlePolls >= IDLE_CONFIRM_POLLS) {
                                break
                            }
                            delay(ALIVE_POLL_MS)
                        }
                        if (isActive) stopBridge("in-car signal down")
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
        unsubscribeActivityRecognition()
        _ssidMatched.value = false
        stateBus.setInCarEngineHint(null)
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

    /**
     * Apply the desired AR-subscription state given the user toggles +
     * runtime permission. Idempotent and safe to call on every settings
     * emission. When permission has been revoked under our feet (eg.
     * user toggled it off in OS Settings), we silently unsubscribe and
     * clear the bus value — the toggle stays "on" in our Settings so a
     * re-grant + next settings emission resubscribes without UI action.
     */
    private fun reconcileActivityRecognition(
        parentEnabled: Boolean,
        toggleOn: Boolean,
        toggleChanged: Boolean,
    ) {
        val want = parentEnabled && toggleOn && hasActivityRecognitionPermission()
        if (toggleChanged) {
            logBuffer.info(
                "AR sub-toggle changed",
                mapOf("on" to toggleOn, "perm" to hasActivityRecognitionPermission()),
            )
        }
        when {
            want && !activitySubscribed -> subscribeActivityRecognition()
            !want && activitySubscribed -> unsubscribeActivityRecognition()
        }
        if (!want) {
            // No subscription = no incoming transitions to drive the bus
            // back to false. Force it down so a stale `true` from a prior
            // session can't keep the bridge stuck up.
            activityBus.setInVehicle(false)
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        // The OS-level permission only exists on Q+. On earlier API
        // levels the GMS manifest permission is auto-granted at install
        // time (it's a normal permission for GMS) — no runtime check.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun activityRecognitionPendingIntent(): PendingIntent {
        val intent = Intent(context, ActivityRecognitionReceiver::class.java)
        // FLAG_MUTABLE: GMS writes the result extras into the intent
        // before delivery. PendingIntent compat shim helps API <23 but
        // our minSdk is 26 so we can use the constant directly.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, AR_PI_REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeActivityRecognition() {
        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            ),
        )
        val client: ActivityRecognitionClient = ActivityRecognition.getClient(context)
        runCatching {
            client.requestActivityTransitionUpdates(
                request,
                activityRecognitionPendingIntent(),
            ).addOnSuccessListener {
                activitySubscribed = true
                logBuffer.info("AR subscribed")
            }.addOnFailureListener { exc ->
                activitySubscribed = false
                logBuffer.warn(
                    "AR subscribe failed",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
                )
            }
        }.onFailure { exc ->
            logBuffer.warn(
                "AR subscribe threw",
                mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun unsubscribeActivityRecognition() {
        if (!activitySubscribed) return
        val client: ActivityRecognitionClient = ActivityRecognition.getClient(context)
        runCatching {
            client.removeActivityTransitionUpdates(activityRecognitionPendingIntent())
        }
        activitySubscribed = false
        logBuffer.info("AR unsubscribed")
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
        /** PendingIntent request code for the AR transition broadcast.
         *  Stable across process boots so removeActivityTransitionUpdates
         *  matches the same PendingIntent we registered with. */
        private const val AR_PI_REQUEST_CODE: Int = 0x5A170A1C

        /** Debounce window for the combined signal flipping false.
         *  Smoothes momentary BT disconnects + WiFi blips. */
        private const val DEBOUNCE_MS: Long = 30_000L

        /** Additional grace after the debounced false before we send
         *  the stop intent. Keeps the bridge alive through a brief
         *  in-driveway lull (eg. user shuts off the hotspot, runs back
         *  inside, comes out, turns it back on). */
        private const val STOP_GRACE_MS: Long = 120_000L

        /** Re-check cadence while waiting for an active drive to end before the
         *  presence-down stop tears the bridge down. */
        private const val ALIVE_POLL_MS: Long = 30_000L

        /** Consecutive idle polls required before the presence-down stop fires,
         *  so a single transient "not active" reading can't seal a live drive. */
        private const val IDLE_CONFIRM_POLLS: Int = 2

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
