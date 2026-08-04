package com.pitstop.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pitstop.MainActivity
import com.pitstop.PitstopApp
import com.pitstop.R
import com.pitstop.ble.WiCanBleManager
import com.pitstop.data.SettingsRepository
import com.pitstop.data.effectiveBrokerUrl
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import com.pitstop.log.loggableUrl
import com.pitstop.log.maskMac
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.obd.ObdResponseParser
import com.pitstop.obd.Pid
import com.pitstop.obd.Pids
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that drives the BLE link to the WiCAN device, parses OBD responses,
 * and publishes them to MQTT. Survives screen-off + background.
 *
 * Lifecycle:
 *   1. Start → foreground notification → load settings → connect MQTT in parallel with
 *      BLE connect to the saved device MAC.
 *   2. Round-robin poll the active PID list. For each PID: write the ELM command, wait
 *      briefly for a response, parse, publish.
 *   3. On BLE disconnect: exponential backoff (1s → 30s cap), keep MQTT alive for the
 *      pending Live view metrics still on screen.
 *   4. On STOP_ACTION: tear down everything cleanly.
 */
@AndroidEntryPoint
class PitstopBridgeService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var mqttPublisher: MqttPublisher
    @Inject lateinit var offlineBuffer: com.pitstop.mqtt.OfflineBuffer
    @Inject lateinit var stateBus: BridgeStateBus
    @Inject lateinit var logBuffer: LogBuffer
    @Inject lateinit var logShipper: LogShipper
    @Inject lateinit var presence: com.pitstop.presence.PresenceTracker
    @Inject lateinit var wicanSubscriber: com.pitstop.mqtt.WiCanSubscriber
    @Inject lateinit var driveRecorder: com.pitstop.drive.DriveRecorder
    @Inject lateinit var driveSealer: com.pitstop.drive.DriveSealer
    @Inject lateinit var pendingDriveDao: com.pitstop.drive.PendingDriveDao
    @Inject lateinit var widgetRefresher: com.pitstop.widget.WidgetRefresher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bleManager: WiCanBleManager? = null
    private var pollJob: Job? = null
    private var reconnectAttempt = 0

    // Buffer for incoming TX bytes — assembled across notify packets until a terminator
    // ('>' from ELM, or natural pause). We also keep a rolling in-memory copy long enough
    // to parse one frame.
    private val rxBuffer = StringBuilder()

    @Volatile private var pidsToPoll: List<Pid> = Pids.DEFAULT
    @Volatile private var vehicleSlug: String = ""

    /**
     * Cached snapshot of [com.pitstop.data.Settings.manualSyncOnly]. When
     * true the publish gates ([publishOrBuffer], [publishJson]) become
     * no-ops — local state-bus updates + drive-recorder capture still
     * run so the Live screen + sealed drive payloads remain intact.
     * Watched off the settings flow in [onCreate] so flipping the
     * Settings toggle takes effect mid-drive without restarting the
     * service.
     */
    @Volatile private var manualSyncOnly: Boolean = false

    /**
     * Cached snapshots of the collector toggles. The settings-flow
     * watcher in [onCreate] keeps these in sync mid-session so the
     * user can flip "OBD via BLE" or "GPS capture" without restarting
     * the service. [startBridge] reads the flags at launch time to
     * decide which collectors to spin up; runtime flips of GPS apply
     * immediately via [applyGpsToggle] and runtime flips of BLE take
     * effect on the next reconnect cycle (we don't tear down a live
     * BLE link mid-frame).
     */
    @Volatile private var bridgeBleEnabled: Boolean = true
    @Volatile private var bridgeGpsEnabled: Boolean = true

    /**
     * Set by the watchdogs ([obdQuiet], [bleLost]) just before they
     * flip the BLE engine-state to Off, so the engine-state listener
     * (which actually seals the drive) records the right kind. Reset
     * to "off" after each seal so an ordinary engine-off seal doesn't
     * inherit a stale "ble_lost" tag.
     */
    @Volatile private var pendingSealKind: String = "off"

    /**
     * Last successful (or attempted) publish time per metric for the
     * manual-mode beacon allowlist. We update on every attempt rather
     * than only on success so a temporarily-unreachable broker doesn't
     * cause a burst once it comes back.
     */
    private val lastBeaconAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last PID the round-robin scheduler wrote to the WiCAN. Used so
     *  that when a NO DATA / STOPPED / UNABLE TO CONNECT response comes
     *  back, we can log which PID it was for instead of dropping it
     *  silently (OBD-1). Not load-bearing — if the response arrives
     *  out-of-order or two responses overlap, the log line may name
     *  the wrong PID; that's a hint, not a guarantee. */
    @Volatile private var lastSentPid: com.pitstop.obd.Pid? = null

    /**
     * Adaptive poll suppression (OBD-4). The Pilot answers some PIDs
     * (notably maf_air_flow) with NO DATA on the BLE path every single
     * poll — they only exist on the WiCAN WiFi/AutoPID path — which wastes
     * a round-robin slot ~1×/s and inflated the no-data noise (maf_air_flow
     * alone: 4,061 NO DATA / 7d). We count *consecutive* NO DATA attributed
     * to a PID via [lastSentPid] and, once a PID crosses
     * [NO_DATA_SUPPRESS_THRESHOLD], drop it from [pidsToPoll] for the rest
     * of the session. Attribution via lastSentPid is best-effort (an
     * out-of-order or overlapping response can name the wrong PID), so the
     * threshold is conservative; a single real frame for that PID resets
     * its counter. Both maps + [pidsToPoll] are reset in [startBridge] so
     * an intermittently-supported PID gets re-probed each session.
     *
     * STOPPED / UNABLE TO CONNECT / BUS errors do NOT feed this — those are
     * whole-bus "engine off / ECU unreachable" signals, not a per-PID
     * unsupported response, and would wrongly suppress the entire set.
     */
    private val noDataStreakByPid = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Once-per-run dedupe for the two formerly-silent OBD drop paths.
     *  Cleared in startBridge alongside the no-data streaks so each drive
     *  reports its own failures exactly once. */
    private val unmatchedFrameLogged: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val parserNullLogged: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val suppressedPids = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    override fun onBind(intent: Intent?): IBinder? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        // Hold a partial wake lock so the CPU stays awake for BLE callbacks
        // and 1 Hz MQTT publishes during long screen-off drives. Without
        // this, Doze mode can drop BLE notifications and stretch our
        // publish cadence to the maintenance window cap. The foreground
        // service alone keeps the *service* alive but doesn't block CPU
        // throttling.
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "pitstop:bridge",
        ).apply {
            setReferenceCounted(false)
            acquire(/* timeout = */ 12L * 60L * 60L * 1000L)  // 12 h sanity cap
        }
        // Start log shipping as soon as the service exists so initial connect failures
        // (eg. broker unreachable) reach the depot.
        logShipper.start()
        // Begin observing AA + car-Bluetooth presence; the BLE retry
        // path reads presence.inCar.value to pace its backoff.
        presence.start()
        // Subscribe to WiCAN's AutoPID broker topic so the Live screen
        // sees ATF temp / fuel rate / oxygen trims / Honda PIDs without
        // re-polling them via BLE. Subscription survives MQTT
        // reconnects via the publisher's registry.
        wicanSubscriber.start()
        // Mirror presence into the state bus + UI pills.
        scope.launch {
            presence.inCar.collect { v ->
                stateBus.update { it.copy(inCar = v) }
            }
        }
        // Watch the manual-sync + collector-toggle settings. Cached in
        // volatile fields for the hot publish/poll paths to read
        // without hitting DataStore on every frame. Flipping any
        // toggle in Settings takes effect mid-session — the service
        // doesn't need to restart. BLE flips are honoured at the next
        // reconnect cycle (we don't tear down a live link), GPS flips
        // apply immediately via [applyGpsToggle].
        scope.launch {
            settingsRepository.settings.collect { s ->
                val priorManual = manualSyncOnly
                manualSyncOnly = s.manualSyncOnly
                if (priorManual != s.manualSyncOnly) {
                    logBuffer.info(
                        "manual-sync mode changed",
                        mapOf("manual_sync_only" to s.manualSyncOnly),
                    )
                }
                val priorBle = bridgeBleEnabled
                bridgeBleEnabled = s.bridgeBleEnabled
                if (priorBle != s.bridgeBleEnabled) {
                    logBuffer.info(
                        "bridge ble-collector toggled",
                        mapOf("enabled" to s.bridgeBleEnabled),
                    )
                    if (!s.bridgeBleEnabled) {
                        // Drop the live link so the reconnect loop in
                        // connectBleWithRetry observes phase=Disconnected
                        // and re-checks the flag before re-arming.
                        runCatching { bleManager?.disconnectDevice() }
                    } else {
                        // Wake the BLE reconnect loop so a re-enable
                        // doesn't sit through the remaining backoff.
                        stateBus.wakeUp()
                    }
                    refreshNotificationForActive()
                }
                val priorGps = bridgeGpsEnabled
                bridgeGpsEnabled = s.bridgeGpsEnabled
                if (priorGps != s.bridgeGpsEnabled) {
                    logBuffer.info(
                        "bridge gps-collector toggled",
                        mapOf("enabled" to s.bridgeGpsEnabled),
                    )
                    applyGpsToggle()
                    refreshNotificationForActive()
                }
            }
        }
        // OBD-quiet watchdog: if engine_state has been On for ≥60s
        // with no fresh OBD frame, force engine_off and seal the
        // drive. Catches the case where the WiCAN goes silent
        // mid-trip (BLE drop / WiCAN power-management) — the
        // STOPPED-streak gate only fires when WiCAN is awake and
        // responding STOPPED; an outright silent WiCAN never
        // triggers it.
        //
        // Honda i-stop (engine auto-shut at long red lights) is NOT
        // a false-positive risk here — the Pilot's ECU stays awake
        // during i-stop and the WiCAN keeps polling normally, so
        // lastFrameAtMs keeps refreshing and the watchdog never
        // fires for that case.
        scope.launch {
            val obdQuietThresholdMs = 60_000L
            // BLE-lost threshold: if the link has been Disconnected /
            // Connecting / Error for this long while a drive buffer is
            // open, declare the drive over and seal it. Without this,
            // a drive that ended with the car going to sleep where
            // the BLE never comes back stays open until the next
            // "engine on" event triggers orphan recovery — could be
            // hours / days.
            //
            // Was 15 min originally, picked to safely outlast the
            // 2-minute BLE flap from 2026-05-12. User reported the
            // stale "engine on" state was annoying for 15 min after
            // parking when BLE refuses to come back. Dropped to 3 min:
            // still safely past any historical mid-drive flap, and
            // the engine-off state flips ~3 min after you park
            // instead of 15. The trade-off is that a >3 min BLE
            // flap mid-drive will split the trip — but the
            // manual-merge UI (MERGE-1) makes that recoverable in two
            // taps.
            val bleLostThresholdMs = 3L * 60L * 1000L
            while (isActive) {
                kotlinx.coroutines.delay(10_000L)
                val s = stateBus.status.value
                if (s.engineState != EngineState.On) continue
                // Gate on the dedicated OBD-frame clock, NOT lastFrameAtMs:
                // GPS (every 5s, un-gated by engine state) and WiCAN-MQTT
                // metrics bump lastFrameAtMs and would defeat both
                // watchdogs forever (ADR-017).
                val lastFrame = s.lastObdFrameAtMs ?: continue
                val ageMs = System.currentTimeMillis() - lastFrame
                // OBD-quiet path: BLE is alive but the ECU isn't
                // answering. Restricted to phase=Connected — a frame-age
                // over the 60s threshold during the reconnect loop just
                // means we can't talk to the WiCAN, NOT that the engine
                // went off. The 2026-05-12 trip split traced back to
                // this guard being absent.
                val obdQuiet = s.phase == BridgePhase.Connected &&
                    lastFrame > 0 && ageMs > obdQuietThresholdMs
                // BLE-lost path: we can't talk to the WiCAN at all and
                // the link has been down long enough that a continuing
                // drive is implausible. Seal as kind=ble_lost so the
                // trip's metadata is honest about why it cut off where
                // it did.
                //
                // Suppressed when BLE is disabled in settings (GPS-only
                // mode): "BLE not connected" is then the intentional
                // state, not a fault, and the drive's engine-state is
                // owned by the InCarDetector hint instead. Without this
                // guard a 3-minute GPS-only drive would be force-sealed
                // mid-trip the moment the watchdog tripped.
                val bleLost = bridgeBleEnabled &&
                    s.phase != BridgePhase.Connected &&
                    lastFrame > 0 && ageMs > bleLostThresholdMs
                if (!obdQuiet && !bleLost) continue
                val reason = if (bleLost) "ble_lost" else "quiet"
                val message = if (bleLost) {
                    "engine off (BLE-lost watchdog)"
                } else {
                    "engine off (OBD-quiet watchdog)"
                }
                logBuffer.info(
                    message,
                    mapOf("frame_age_ms" to ageMs, "phase" to s.phase.name),
                )
                val tMs = System.currentTimeMillis()
                // Watchdog is BLE-derived — route through the merge.
                // Drive sealing happens in the engine-state listener
                // (see [observeEngineStateForRecorder]) which sees the
                // On→Off transition and seals with this kind.
                pendingSealKind = reason
                stateBus.setBleEngineState(EngineState.Off, tMs)
                publishEngineState("off", tMs)
                stateBus.clearMetrics()
            }
        }
        // BLE-3: phone_health beacon. Every 60s publish a small telemetry
        // object to bridge/<slug>/phone_health so the backend (which has a
        // matching subscriber) can surface phone-side health: OBD frame
        // age, BLE phase, GPS age, queue depth, offline-buffer size, app
        // version. This is telemetry, NOT drive data — it publishes even
        // in manual-sync mode (best-effort direct publish, no offline
        // buffering: if MQTT is down we just skip this tick).
        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000L)
                val slug = vehicleSlug
                if (slug.isBlank()) continue
                if (!mqttPublisher.isConnected()) continue
                runCatching {
                    val now = System.currentTimeMillis()
                    val s = stateBus.status.value
                    val obdAge = s.lastObdFrameAtMs?.let { (now - it) / 1000L } ?: -1L
                    val gpsAge = s.lastFrameAtMs?.let { (now - it) / 1000L } ?: -1L
                    val queue = runCatching { pendingDriveDao.unackedCount() }.getOrDefault(0)
                    val buf = runCatching { offlineBuffer.byteCount() }.getOrDefault(0L)
                    val phase = s.phase.name.lowercase()
                    val payload = buildString {
                        append("{\"t\":").append(now)
                        append(",\"obd_age_s\":").append(obdAge)
                        append(",\"ble_phase\":\"").append(phase).append("\"")
                        append(",\"gps_age_s\":").append(gpsAge)
                        append(",\"queue_drives\":").append(queue)
                        append(",\"offline_buffer_bytes\":").append(buf)
                        append(",\"app_version\":\"")
                            .append(com.pitstop.BuildConfig.VERSION_NAME).append("\"")
                        append("}")
                    }
                    mqttPublisher.publish("bridge/$slug/phone_health", payload)
                }
            }
        }
        logBuffer.info("bridge service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Guard on isActive, NOT == null: an error path can leave a
                // completed (but non-null) Job assigned, which would make
                // == null false forever and the service a permanent no-op.
                if (pollJob?.isActive != true) startBridge()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        logBuffer.info("bridge service destroying")
        scope.cancel()
        bleManager?.let {
            it.setStateCallback(null)
            it.disconnectDevice()
            runCatching { it.close() }
        }
        bleManager = null
        mqttPublisher.disconnect()
        presence.stop()
        wicanSubscriber.stop()
        // Final flush: don't wait — the shipper job dies with the singleton scope only
        // when the process dies. Stopping it lets the buffer accrue until the next start.
        logShipper.stop()
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    // -------- Bridge driver --------

    // Auto-trigger now lives in [com.pitstop.presence.InCarDetector] —
    // app-singleton, observes paired-car WiFi SSID + AA + paired-car
    // HFP, calls startForegroundService on this service when in-car
    // and the stop intent when the combined signal stays false past
    // the debounce + grace window.
    @SuppressLint("MissingPermission")
    private fun startBridge() {
        pollJob = scope.launch {
            // Snapshot the collector toggles up front. The settings-flow
            // watcher in onCreate keeps the cached fields fresh after
            // this point, so flipping a toggle mid-session takes effect
            // without restarting.
            val secrets = settingsRepository.current()
            val s = secrets.settings
            bridgeBleEnabled = s.bridgeBleEnabled
            bridgeGpsEnabled = s.bridgeGpsEnabled

            // Reset adaptive poll suppression each session so a PID that was
            // dropped last drive (or an intermittently-supported one) gets
            // re-probed from a clean slate (OBD-4).
            pidsToPoll = Pids.DEFAULT
            noDataStreakByPid.clear()
            unmatchedFrameLogged.clear()
            parserNullLogged.clear()
            suppressedPids.clear()

            // Both collectors off → the bridge would do nothing useful.
            // Self-stop and surface a notification so the user sees why.
            if (!bridgeBleEnabled && !bridgeGpsEnabled) {
                logBuffer.warn("bridge: both BLE and GPS collectors disabled — stopping")
                updateNotification(getString(R.string.bridge_state_nothing_enabled))
                runCatching { settingsRepository.setBridgeAutoStart(false) }
                pollJob = null
                stopSelf()
                return@launch
            }

            // Mark "user wants this running" so BootReceiver auto-starts the
            // service after a phone reboot. Cleared in stopBridge() when the
            // user explicitly stops.
            runCatching { settingsRepository.setBridgeAutoStart(true) }

            // GPS: parallel to the BLE/MQTT path. If permission is missing
            // we log + skip; the bridge still publishes OBD telemetry. The
            // toggle gate is checked inside startGpsUpdates() — keeping the
            // check there means the runtime-toggle path can call the same
            // entry point to re-arm GPS without duplicating logic.
            startGpsUpdates()
            vehicleSlug = s.vehicleSlug.ifBlank { "vehicle" }
            logBuffer.info(
                "bridge start",
                mapOf(
                    "vehicle_slug" to vehicleSlug,
                    "broker" to loggableUrl(s.effectiveBrokerUrl()),
                    "ble_mac" to maskMac(s.bleDeviceMac),
                    "ble_enabled" to bridgeBleEnabled,
                    "gps_enabled" to bridgeGpsEnabled,
                    "verbose_logging" to s.verboseLogging,
                ),
            )
            // Kick the fuel widget on every bridge start so a fresh
            // user session doesn't have to wait 30 min for the OS tick
            // to refresh stale data.
            widgetRefresher.refreshFuelWidget()

            stateBus.update {
                it.copy(
                    // Phase reflects the BLE link state. With BLE disabled
                    // there's no link to negotiate — we sit in Idle (the
                    // BridgeStatePill renders "Idle" for that, which is
                    // honest about not maintaining a BLE connection).
                    phase = if (bridgeBleEnabled) BridgePhase.Connecting else BridgePhase.Idle,
                    brokerUrl = s.effectiveBrokerUrl().takeIf { url -> url.isNotBlank() },
                    deviceName = s.bleDeviceName,
                    deviceMac = s.bleDeviceMac,
                )
            }
            refreshNotificationForActive()

            // Connect MQTT in parallel — the bridge can publish even while BLE is down
            // (eg. heartbeat) and the moment BLE comes up we don't want to wait on a
            // TCP handshake. MQTT is shared infrastructure for both collectors so it
            // starts regardless of which one is enabled.
            launch { connectMqttWithRetry(s.effectiveBrokerUrl(), s.mqttUser, secrets.mqttPassword) }
            // Drain backlog whenever the broker is reachable. Runs forever
            // until the bridge is stopped.
            launch { runOfflineDrainLoop() }
            // Mirror HiveMQ's live connection state into the bridge bus so
            // every screen / Android Auto tile reflects reconnects driven by
            // the client's automatic-reconnect loop without us re-running
            // connectMqttWithRetry.
            launch { trackBrokerLiveness() }
            // Drive-recorder lifecycle (v0.1.176 fix). Started here —
            // not in onCreate — so vehicleSlug is guaranteed populated
            // before the first engineState=On lands. See observe...().
            launch { observeEngineStateForRecorder() }

            if (!bridgeBleEnabled) {
                logBuffer.info("bridge: BLE collector disabled by setting — GPS-only mode")
                // Hold the coroutine open so pollJob stays non-null until
                // the user stops the service or re-enables BLE. The
                // settings-flow watcher already updates the cached flag;
                // we poll every 5s (rare wake-up only — GPS-only mode is
                // typically a long-lived state). When BLE flips back on
                // we re-read MAC/name fresh from DataStore so a device
                // picker change during the GPS-only window is honoured.
                while (isActive) {
                    if (bridgeBleEnabled) {
                        val current = settingsRepository.current().settings
                        val mac = current.bleDeviceMac
                        if (!mac.isNullOrBlank()) {
                            connectBleWithRetry(mac, current.bleDeviceName)
                            // connectBleWithRetry returns only when the
                            // scope is cancelled OR BLE is disabled mid-loop.
                            // Either way, drop back to the idle-wait.
                            continue
                        }
                    }
                    delay(5_000L)
                }
                return@launch
            }

            val mac = s.bleDeviceMac
            if (mac.isNullOrBlank()) {
                logBuffer.warn("bridge: no ble device configured")
                stateBus.update {
                    it.copy(
                        phase = BridgePhase.Error,
                        errorMessage = "No BLE device configured. Open Pitstop and pick one.",
                    )
                }
                updateNotification("No BLE device configured")
                return@launch
            }

            connectBleWithRetry(mac, s.bleDeviceName)
        }
    }

    // Engine-state hysteresis: WiCAN occasionally answers STOPPED / NO DATA
    // for a stretch while the engine is still running — a Honda i-stop
    // auto-off at a light, a momentary CAN dropout, or the BLE link
    // degrading for a few seconds. A real frame resets everything and
    // immediately flips to engine-on. To declare engine-OFF we require ALL
    // of: a run of responses, a *sustained* quiet window, and no recent
    // vehicle motion. The bare 6-response count (~3 s) used before sealed
    // live drives the instant the WiCAN went quiet mid-drive — the
    // 2026-07-13 early-cutout, where a no-data burst as the car braked to a
    // stop ended the drive ~4 min before the engine actually went off.
    private var stoppedRunLength = 0
    private var stoppedRunStartMs = 0L
    @Volatile private var lastMotionAtMs = 0L
    private val engineOffThreshold = 6
    // The no-data / STOPPED burst must persist at least this long before we
    // treat it as a real key-off. i-stop and transient CAN/BLE hiccups
    // resume real frames well within this window and reset the streak.
    private val engineOffQuietMs = 30_000L
    // If the vehicle was moving this recently, a no-data burst is a link
    // hiccup mid-drive, not a key-off — hold the drive open regardless.
    private val recentMotionGraceMs = 15_000L
    private val motionKph = 3.0

    /**
     * Single chokepoint for drive open / seal, driven off the merged
     * `engineState` on the bus (NOT the BLE callbacks directly). This
     * unifies four sources that all want to start / end a drive:
     *
     *   - BLE OBD frame parser ([onEngineOnSignal] / [onEngineOffSignal])
     *   - OBD-quiet watchdog
     *   - BLE-lost watchdog
     *   - GPS-only [com.pitstop.presence.InCarDetector] hint (v0.1.176)
     *
     * Pre-v0.1.176 the open/seal lived inside the BLE callbacks, which
     * is why GPS-only mode silently captured nothing.
     *
     * Started from [startBridge] after `vehicleSlug` is populated so
     * the immediate replay of an already-on engine state lands with a
     * non-blank vehicle id.
     */
    private suspend fun observeEngineStateForRecorder() {
        var last: EngineState = EngineState.Unknown
        stateBus.status
            .map { it.engineState }
            .collect { current ->
                if (current == last) return@collect
                val prior = last
                last = current
                val tMs = System.currentTimeMillis()
                when (current) {
                    EngineState.On -> {
                        if (vehicleSlug.isBlank()) {
                            logBuffer.warn("drive open skipped: vehicleSlug blank")
                            return@collect
                        }
                        val result = driveRecorder.open(vehicleSlug, tMs)
                        result.orphan?.let { orphan ->
                            // Hand off on the service scope (not the
                            // pollJob child) so a concurrent stopBridge
                            // can't cancel us mid-write.
                            scope.launch {
                                val deviceId = settingsRepository.deviceIdOrNull() ?: "unknown"
                                driveSealer.sealOrphan(orphan, deviceId)
                            }
                        }
                    }
                    EngineState.Off -> {
                        // Only seal if we actually had a drive open —
                        // the Unknown→Off transition at bridge start
                        // (BLE comes up, ECU reports STOPPED) would
                        // otherwise call seal() against an empty
                        // recorder, which is just a debug no-op but
                        // looks wrong in the logs.
                        if (prior == EngineState.On) {
                            val kind = pendingSealKind
                            pendingSealKind = "off"
                            scope.launch {
                                val deviceId = settingsRepository.deviceIdOrNull() ?: "unknown"
                                driveSealer.seal(tMs, deviceId, kind = kind)
                            }
                        }
                    }
                    EngineState.Unknown -> Unit
                }
            }
    }

    private fun onEngineOnSignal() {
        stoppedRunLength = 0
        stoppedRunStartMs = 0L
        val s = stateBus.status.value
        if (s.engineState != EngineState.On) {
            logBuffer.info("engine on")
            val tMs = System.currentTimeMillis()
            // BLE-derived On — flip the merge input. The
            // engine-state listener in onCreate opens the drive
            // recorder when it sees the merged engineState reach On
            // (covers BLE-only, GPS-only, and hybrid paths uniformly).
            stateBus.setBleEngineState(EngineState.On, tMs)
            publishEngineState("on", tMs)
        }
    }

    private fun onEngineOffSignal() {
        val nowMs = System.currentTimeMillis()
        if (stoppedRunLength == 0) stoppedRunStartMs = nowMs
        stoppedRunLength += 1
        // Debounce (2026-07-13 early-cutout fix). Seal only when the quiet
        // is genuinely sustained AND we weren't just moving — otherwise an
        // i-stop / CAN dropout / BLE-degradation burst would end a live
        // drive. A real frame resets the streak via onEngineOnSignal, so a
        // burst that resumes never crosses these gates.
        if (stoppedRunLength < engineOffThreshold) return
        if (nowMs - stoppedRunStartMs < engineOffQuietMs) return
        if (lastMotionAtMs > 0L && nowMs - lastMotionAtMs < recentMotionGraceMs) return
        val s = stateBus.status.value
        if (s.engineState != EngineState.Off) {
            logBuffer.info(
                "engine off (wican reports stopped)",
                mapOf(
                    "quiet_ms" to (nowMs - stoppedRunStartMs),
                    "run" to stoppedRunLength,
                ),
            )
            val tMs = nowMs
            // BLE-derived Off — flip the merge input. The
            // engine-state listener handles drive sealing.
            stateBus.setBleEngineState(EngineState.Off, tMs)
            publishEngineState("off", tMs)
            // Clear cached metric samples so the Live screen doesn't
            // sit on stale RPM / speed / coolant values from the
            // just-finished drive. GPS + IMU stop being published to
            // the bus too (see publish gates below) so the screen
            // stays blank until the next engine_on.
            stateBus.clearMetrics()
        }
    }

    private fun publishEngineState(state: String, tMs: Long) {
        val slug = vehicleSlug
        if (slug.isBlank()) return
        publishJson(
            "bridge/$slug/engine_state",
            "{\"t\":$tMs,\"state\":\"$state\"}",
        )
    }

    private suspend fun trackBrokerLiveness() {
        var last: Boolean? = null
        while (scope.isActive) {
            val now = mqttPublisher.isConnected()
            if (now != last) {
                stateBus.update { it.copy(brokerConnected = now) }
                last = now
            }
            delay(2_000L)
        }
    }

    private suspend fun connectMqttWithRetry(url: String, user: String, password: String) {
        if (url.isBlank()) {
            logBuffer.warn("mqtt skipped: no broker url configured")
            stateBus.update { it.copy(brokerConnected = false) }
            return
        }
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                mqttPublisher.connect(url, user, password)
                stateBus.update { it.copy(brokerConnected = true) }
                return
            } catch (e: Exception) {
                logBuffer.warn(
                    "mqtt retry scheduled",
                    mapOf("backoff_ms" to delayMs, "err" to (e.message ?: e::class.java.simpleName)),
                )
                stateBus.update { it.copy(brokerConnected = false, errorMessage = "MQTT: ${e.message}") }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectBleWithRetry(mac: String, name: String?) {
        // Permission preflight — without these the system kills the process silently.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                logBuffer.error("ble: missing BLUETOOTH_CONNECT permission")
                stateBus.update {
                    it.copy(
                        phase = BridgePhase.Error,
                        errorMessage = "Missing BLUETOOTH_CONNECT permission",
                    )
                }
                return
            }
        }
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = btManager.adapter ?: run {
            logBuffer.error("ble: no bluetooth adapter on device")
            stateBus.update {
                it.copy(phase = BridgePhase.Error, errorMessage = "No Bluetooth adapter")
            }
            return
        }

        // ONE manager for the service lifetime. Nordic BleManager is
        // designed for reconnect reuse, so we create it once and call
        // connectToDevice() again per retry. Creating a fresh manager
        // per attempt (the old code) leaked the previous instance — its
        // stateCallback stayed attached and late FAILED/DISCONNECTED
        // callbacks from attempt N would flip `phase` mid-attempt N+1,
        // tearing down a healthy link (a contributor to status-133
        // churn) and accruing instances overnight. The callback is set
        // once here; it closes over the stable stateBus + onUartFrame.
        val mgr = bleManager ?: WiCanBleManager(applicationContext, logBuffer).also {
            it.setStateCallback(object : WiCanBleManager.UartStateCallback {
                override fun onConnectionStateChange(state: WiCanBleManager.ConnectionState) {
                    val phase = when (state) {
                        WiCanBleManager.ConnectionState.READY -> BridgePhase.Connected
                        WiCanBleManager.ConnectionState.CONNECTED -> BridgePhase.Connected
                        WiCanBleManager.ConnectionState.CONNECTING -> BridgePhase.Connecting
                        WiCanBleManager.ConnectionState.DISCONNECTED -> BridgePhase.Disconnected
                        WiCanBleManager.ConnectionState.FAILED -> BridgePhase.Error
                    }
                    stateBus.update { s -> s.copy(phase = phase) }
                }

                override fun onDataReceived(bytes: ByteArray) {
                    onUartFrame(bytes)
                }
            })
            bleManager = it
        }

        while (scope.isActive) {
            // Honour the runtime BLE-toggle flip: if the user disabled
            // the BLE collector while we were in backoff, exit the
            // retry loop cleanly. The outer startBridge() coroutine
            // observes pollJob still being non-null and parks in the
            // idle-wait until either BLE is re-enabled or the service
            // is stopped.
            if (!bridgeBleEnabled) {
                logBuffer.info("ble retry exiting: BLE collector disabled")
                stateBus.update { it.copy(phase = BridgePhase.Idle) }
                return
            }
            val device = try {
                adapter.getRemoteDevice(mac)
            } catch (e: Exception) {
                logBuffer.error(
                    "ble: bad mac",
                    mapOf("mac" to maskMac(mac), "err" to (e.message ?: e::class.java.simpleName)),
                )
                stateBus.update {
                    it.copy(phase = BridgePhase.Error, errorMessage = "Bad MAC: $mac")
                }
                return
            }

            stateBus.update { it.copy(phase = BridgePhase.Connecting, deviceName = name, deviceMac = mac) }
            updateNotification(getString(R.string.bridge_state_connecting, name ?: mac))

            // First attempt of a session = direct connect (fast first frame on
            // engine start). Every retry after that = autoConnect so the OS
            // reattaches opportunistically when the WiCAN next advertises,
            // instead of us burning a 15 s active-scan radio timeout per attempt
            // (the 626/685 REASON_TIMEOUT failures). reconnectAttempt is 0 on the
            // first pass and reset to 0 on every successful link, so a fresh
            // engine-start still gets the fast direct path.
            mgr.connectToDevice(device, reconnect = reconnectAttempt > 0)

            // Wait until READY (Connected via Nordic ble means the gatt is connected;
            // initialize() flips it to READY). Time out at 20s.
            val readyDeadline = System.currentTimeMillis() + 20_000
            while (scope.isActive && System.currentTimeMillis() < readyDeadline) {
                if (stateBus.status.value.phase == BridgePhase.Connected) break
                if (stateBus.status.value.phase == BridgePhase.Error) break
                delay(250)
            }

            if (stateBus.status.value.phase == BridgePhase.Connected) {
                reconnectAttempt = 0
                logShipper.batterySaving = false
                logBuffer.info("ble link ready; entering poll loop")
                updateNotification(
                    getString(
                        R.string.bridge_state_connected,
                        pidsToPoll.size,
                    ),
                )
                runPollLoop()
                // runPollLoop() returns when the BLE link drops. Fall through to backoff.
            } else {
                logBuffer.warn("ble link did not reach READY before timeout")
                bleManager?.disconnectDevice()
            }

            val baseSec = (1 shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30)
            reconnectAttempt += 1
            // Adaptive backoff (Task #77, BLE-1):
            //   - in car (AA up or paired-car BT connected) → aggressive
            //     5s cap. WiCAN is near, may be waking from sleep, we
            //     want the first frame ASAP so the trip opens correctly.
            //   - parked (engine off & ≥3 attempts with zero frames) →
            //     stretch cap to 60 s. WiCAN sleeps after voltage drops;
            //     hammering BLE while it's asleep burns battery. Was
            //     300 s in v0.1.129 but that meant a user returning to
            //     the car had to wait up to 5 minutes for BLE to retry —
            //     unacceptable when the AA/paired-BT presence signals
            //     aren't firing. The `stateBus.wakeEvents` MQTT path
            //     (WiCAN publishes `can/status: online` on boot) breaks
            //     the sleep early on the LAN; the 60 s cap is the
            //     belt-and-suspenders backup for the cellular case.
            //   - else → existing exponential 1→30s.
            val priorEngine = stateBus.status.value.engineState
            val inCar = stateBus.status.value.inCar
            // In-car cap decay (BLE-5): the old branch pinned inCar at a 5 s
            // cap forever, so a phone parked next to a sleeping / out-of-range
            // WiCAN retried every 5 s indefinitely — each retry a ~15 s radio
            // timeout, burning battery for nothing. Keep 5 s for the first few
            // attempts (fast first frame on engine start, when the WiCAN is
            // waking) then climb toward 30 s once several consecutive attempts
            // have failed (dominant failure is REASON_TIMEOUT — WiCAN not
            // advertising). reconnectAttempt resets to 0 on a successful link,
            // so it IS the consecutive-failure count; a genuine engine-start
            // re-entry gets the fast 5 s path again. The wakeEvents/inCar merge
            // below still breaks the backoff early on a real can/status:online
            // or fresh AA/BT signal, so the decay never delays a real reconnect.
            val inCarBackoffSec = when {
                reconnectAttempt <= IN_CAR_FAST_ATTEMPTS -> 5
                reconnectAttempt <= IN_CAR_FAST_ATTEMPTS + 2 -> 15
                else -> IN_CAR_BACKOFF_CAP_SEC
            }
            val backoffSec = when {
                inCar -> baseSec.coerceIn(5, inCarBackoffSec)
                priorEngine == EngineState.Off && reconnectAttempt >= 3 -> 60
                else -> baseSec
            }
            // After 4 consecutive misses (~30s+), tell the log shipper we're in a long
            // disconnect — it'll halve its flush rate to save battery.
            if (reconnectAttempt >= 4) {
                logShipper.batterySaving = true
            }
            // BLE link dropped — engine state is no longer observable. WiCAN
            // sleeps ~5 min after key-off and won't be reachable until CAN
            // traffic wakes it (engine start). Mark engineState=Unknown and
            // reset the STOPPED counter so the next session re-evaluates from
            // scratch instead of inheriting stale "Off" from a prior trip.
            stoppedRunLength = 0
            logBuffer.info(
                "ble reconnect scheduled",
                mapOf(
                    "attempt" to reconnectAttempt,
                    "backoff_s" to backoffSec,
                    "engine_was" to priorEngine.name.lowercase(),
                    "in_car" to inCar,
                ),
            )
            val msg = when {
                inCar -> "Looking for OBD — phone is in the car"
                priorEngine == EngineState.Off ->
                    "WiCAN asleep — will wake on engine start"
                else -> "Disconnected — retrying in ${backoffSec}s"
            }
            updateNotification(msg)
            // Don't reset engineState here. BLE state is independent
            // of engine state — clearing to Unknown on every disconnect
            // means the next reconnect's first frame is treated as a
            // fresh "engine on" (the guard in onEngineOnSignal trips
            // because state != On), which orphan-seals the in-progress
            // drive and starts a new one. Trust the watchdog +
            // explicit OBD STOPPED responses to flip engine state.
            stateBus.update {
                it.copy(phase = BridgePhase.Disconnected)
            }
            // Wake early on EITHER of two signals:
            //   - presence.inCar flips true (AA / paired-car BT detected)
            //   - stateBus.wakeEvents fires (WiCanSubscriber saw
            //     `wican/<id>/can/status: online` on MQTT — the WiCAN
            //     just powered up because the user started the car).
            // Whichever fires first breaks the sleep and we retry BLE.
            kotlinx.coroutines.withTimeoutOrNull(backoffSec * 1_000L) {
                merge(
                    presence.inCar.filter { it }.map { },
                    stateBus.wakeEvents,
                ).first()
            }
        }
    }

    private suspend fun runPollLoop() {
        // Round-robin scheduler: each PID has a periodMs; we maintain a "next due" timestamp
        // per PID, and at every tick we send the most-overdue one.
        val nextDue = HashMap<String, Long>()
        val now = System.currentTimeMillis()
        for (pid in pidsToPoll) nextDue[pid.name] = now

        while (scope.isActive) {
            if (stateBus.status.value.phase != BridgePhase.Connected) return

            val t = System.currentTimeMillis()
            val due = pidsToPoll
                .filter { (nextDue[it.name] ?: 0L) <= t }
                .minByOrNull { nextDue[it.name] ?: 0L }

            if (due != null) {
                lastSentPid = due
                bleManager?.writeCommand(due.command())
                nextDue[due.name] = t + due.periodMs
            }

            // Tight loop, but bounded — sleep until the next due PID.
            val nextWake = pidsToPoll.minOf { nextDue[it.name] ?: t }
            val sleep = (nextWake - System.currentTimeMillis()).coerceIn(50L, 1_000L)
            delay(sleep)
        }
    }

    /**
     * Adaptive suppression bookkeeping for a strict "NO DATA" attributed to
     * [pid] (best-effort via lastSentPid). After [NO_DATA_SUPPRESS_THRESHOLD]
     * consecutive NO DATA we drop the PID from [pidsToPoll] for the rest of
     * the session so the round-robin stops wasting a slot on it. A single real
     * parsed frame for the PID clears its streak (see [onUartFrame]).
     */
    private fun onNoDataForPid(pid: com.pitstop.obd.Pid?) {
        val name = pid?.name ?: return
        if (name in suppressedPids) return
        val streak = (noDataStreakByPid[name] ?: 0) + 1
        noDataStreakByPid[name] = streak
        if (streak >= NO_DATA_SUPPRESS_THRESHOLD) {
            suppressedPids.add(name)
            pidsToPoll = pidsToPoll.filter { it.name != name }
            logBuffer.info(
                "obd pid suppressed (consecutive NO DATA on BLE)",
                mapOf("pid" to name, "streak" to streak, "active_pids" to pidsToPoll.size),
            )
        }
    }

    private fun onUartFrame(bytes: ByteArray) {
        // Append, then attempt to parse. ELM-style frames terminate with '>' (the prompt);
        // raw CAN frames are usually one notify packet. If we don't find a terminator
        // within ~256 bytes, drop the buffer (corrupt stream).
        rxBuffer.append(String(bytes, Charsets.US_ASCII))
        if (rxBuffer.length > 1024) {
            rxBuffer.clear()
            return
        }

        val terminator = rxBuffer.indexOf('>')
        val frameText = if (terminator >= 0) {
            val out = rxBuffer.substring(0, terminator)
            rxBuffer.delete(0, terminator + 1)
            out
        } else if (bytes.size in 8..16 && bytes[0].toInt() and 0xF0 == 0) {
            // Looks like a raw single-frame CAN response — don't wait for '>'.
            rxBuffer.clear()
            String(bytes, Charsets.US_ASCII)
        } else {
            return
        }

        val parsed = ObdResponseParser.parse(frameText.toByteArray(Charsets.US_ASCII))
        if (parsed == null) {
            val trimmed = frameText.trim()
            // WiCAN's "STOPPED" / "NO DATA" / "UNABLE TO CONNECT" responses
            // are not parser errors — they're the device telling us either
            // (a) the engine is off, or (b) the PCM doesn't support the PID
            // we just asked about. We log which PID was outstanding so a
            // future case like fuel_level silently disappearing from a
            // drive is diagnosable from client_logs (OBD-1).
            // ELM327 echoes the request before the response, separated by \r:
            //   "010B\rSTOPPED" / "0108\rNO DATA"
            // so check each line, not the trimmed-whole-frame's prefix. Also
            // covers BUS INIT: ERROR / BUS BUSY / CAN ERROR / ? from the
            // ELM error responses table — all indicate "ECU not reachable",
            // which from our perspective is engine off.
            val upper = trimmed.uppercase()
            val signal = upper.split('\r', '\n')
                .map { it.trim() }
                .firstOrNull { line ->
                    line.startsWith("STOPPED") ||
                        line.startsWith("NO DATA") ||
                        line.startsWith("UNABLE TO CONNECT") ||
                        line.startsWith("BUS INIT") ||
                        line.startsWith("BUS BUSY") ||
                        line.startsWith("CAN ERROR") ||
                        line == "?"
                }
            if (signal != null) {
                // Log ONCE per no-data run, not on every quiet poll frame. This
                // line was ~90% of all client_logs (38,983 rows/7d, shipped
                // upstream over cellular) because the WiCAN answers STOPPED/NO
                // DATA every poll while the engine is off or a PID is
                // unsupported. stoppedRunLength is still 0 here (onEngineOffSignal
                // increments it just below), so ==0 means "entering a fresh run"
                // — we keep the first occurrence + last_pid for diagnosability
                // and suppress the rest until a real frame resets the streak via
                // onEngineOnSignal. This does NOT touch the engine-off debounce
                // in onEngineOffSignal (v0.1.199).
                if (stoppedRunLength == 0) {
                    logBuffer.info(
                        "obd no-data response",
                        mapOf(
                            "response" to signal.take(20),
                            "last_pid" to (lastSentPid?.name ?: "?"),
                        ),
                    )
                }
                // Only a strict "NO DATA" is a per-PID "unsupported on BLE"
                // signal. STOPPED / UNABLE TO CONNECT / BUS* are whole-bus
                // (engine off / ECU unreachable) and must not suppress
                // individual PIDs.
                if (signal.startsWith("NO DATA")) onNoDataForPid(lastSentPid)
                onEngineOffSignal()
                return
            }
            // Don't log every short fragment — only frames that look like full responses.
            // Heuristic: contains '>' or is at least 6 chars after trimming.
            if (trimmed.length >= 6) {
                logBuffer.warn(
                    "obd parse failed",
                    mapOf("frame" to trimmed.take(48)),
                )
            }
            return
        }
        // Real OBD response → engine is awake. Stamp the dedicated OBD
        // clock here (the only BLE parsed-frame site) so the ADR-017
        // watchdogs gate on a signal GPS/WiCAN traffic can't pollute.
        stateBus.recordObdFrame()
        onEngineOnSignal()
        val pid = pidsToPoll.firstOrNull {
            it.mode == parsed.mode && it.pid == parsed.pid
        }
        if (pid == null) {
            // Previously a bare `?: return` — a completely silent drop. A
            // PCM that echoes a mode/PID we didn't ask for (or answers a
            // polled PID with a different echo) vanished without a trace,
            // which is how MAF stayed broken for weeks: zero readings AND
            // zero diagnostics. Rate-limited per mode/PID so an
            // unrecognised frame at 1 Hz can't flood the depot.
            val key = "%02X%02X".format(parsed.mode, parsed.pid)
            if (unmatchedFrameLogged.add(key)) {
                logBuffer.warn(
                    "obd response for unpolled pid",
                    mapOf("echo" to key, "data_bytes" to parsed.data.size),
                )
            }
            return
        }
        val value = pid.parser(parsed.data)
        if (value == null) {
            // Also rate-limited: a PID whose parser rejects every response
            // (too few bytes, unsupported-sensor bitmap) would otherwise
            // log once per second for the whole drive.
            if (parserNullLogged.add(pid.name)) {
                logBuffer.warn(
                    "obd value parser returned null",
                    mapOf("pid" to pid.name, "data_bytes" to parsed.data.size),
                )
            }
            return
        }

        // A real frame for this PID clears its NO-DATA streak so an
        // intermittently-supported PID never accumulates toward suppression
        // across scattered no-data blips.
        noDataStreakByPid.remove(pid.name)
        stateBus.publishMetric(pid.name, value)
        // Track the last time we saw real vehicle motion — the engine-off
        // debounce holds the drive open through a no-data burst if the car
        // was moving moments ago (link hiccup, not a key-off).
        if (pid.name == "vehicle_speed" && value > motionKph) {
            lastMotionAtMs = System.currentTimeMillis()
        }
        // Home-screen fuel widget reads from the server's /vehicles
        // endpoint — without an explicit refresh kick, it only updates
        // on Android's 30-min `updatePeriodMillis` floor (and Doze can
        // stretch even that). Rate-limited to 1 refresh per 30 s.
        if (pid.name == "fuel_level") widgetRefresher.refreshFuelWidget()
        val topic = "bridge/${vehicleSlug}/${pid.name}"
        publishOrBuffer(topic, v2Envelope(value))
        // Mirror into the drive recorder so the canonical batch
        // upload (#117) has every PID frame even when the live MQTT
        // stream drops bytes.
        driveRecorder.current()?.addPid(System.currentTimeMillis(), pid.name, value)
    }

    /**
     * Try a live publish; on failure (no connection) push to the on-disk
     * [OfflineBuffer]. Return immediately — the buffer write is async so
     * we don't block sensor callbacks. Designed to be called from any
     * publish site (OBD parser, GPS handler).
     *
     * In manual-sync mode the call short-circuits before both the live
     * publish AND the offline-buffer enqueue: the user explicitly asked
     * us not to push this metric upstream right now, so don't queue it
     * for an opportunistic drain either. DriveRecorder still has the
     * frame in the sealed drive payload for the user-driven Sync now.
     *
     * Exception (WIDGET-3): metrics in [MANUAL_MODE_BEACON_METRICS] still
     * publish at low rate even in manual-sync mode so server-rendered
     * surfaces (home-screen fuel widget) don't sit stale for the whole
     * drive. Beacons are best-effort — if MQTT isn't connected we drop
     * them rather than buffering, because the user opted out of bulk
     * streaming and the eventual Sync-now upload will carry the full
     * history anyway.
     */
    private fun publishOrBuffer(topic: String, payload: String) {
        if (manualSyncOnly) {
            val metric = topic.substringAfterLast('/')
            if (metric !in MANUAL_MODE_BEACON_METRICS) return
            val now = System.currentTimeMillis()
            val last = lastBeaconAtMs[metric] ?: 0L
            if (now - last < MANUAL_MODE_BEACON_MS) return
            lastBeaconAtMs[metric] = now
            // Best-effort live publish only; no offline-buffer fallback.
            mqttPublisher.publish(topic, payload)
            return
        }
        if (mqttPublisher.publish(topic, payload)) return
        scope.launch { offlineBuffer.enqueue(topic, payload) }
    }

    /**
     * Background drain loop. Runs every 5 s; whenever MQTT is up and the
     * disk buffer has bytes, it tries to publish what it has. The drain
     * itself is bounded by [OfflineBuffer.drain] (one pass, stops on
     * first failure) so we never starve the live BLE/IMU/GPS publish
     * stream behind a multi-MB backlog.
     */
    private suspend fun runOfflineDrainLoop() {
        while (scope.isActive) {
            delay(5_000L)
            val pending = runCatching { offlineBuffer.byteCount() }.getOrDefault(0L)
            if (pending == 0L) {
                stateBus.update { it.copy(offlineBufferBytes = 0L) }
                continue
            }
            stateBus.update { it.copy(offlineBufferBytes = pending) }
            if (!mqttPublisher.isConnected()) continue
            val result = runCatching {
                offlineBuffer.drain { topic, payload -> mqttPublisher.publish(topic, payload) }
            }.getOrNull() ?: continue
            if (result.drained > 0) {
                logBuffer.info(
                    "offline buffer drain",
                    mapOf("drained" to result.drained, "remaining_bytes" to result.remainingBytes),
                )
            }
            stateBus.update { it.copy(offlineBufferBytes = result.remainingBytes) }
        }
    }

    private fun formatValue(v: Double): String {
        // Integers as integers, fractional with up to 3 decimals. The backend parser
        // accepts both forms.
        return if (kotlin.math.abs(v - v.toLong()) < 1e-9) v.toLong().toString()
        // Locale.US: a comma-decimal default locale would emit "12,345"
        // and break the JSON envelope (invalid → every publish rejected).
        else String.format(Locale.US, "%.3f", v)
    }

    /**
     * Bridge payload v2 envelope: ``{"v":<num>,"t":<unix_ms>}``. Backend
     * uses ``t`` as the row's capture time so an offline-buffer drain
     * doesn't collapse an hour of driving into the ingest window.
     * The bare-number form still works server-side for one release.
     */
    private fun v2Envelope(v: Double): String {
        val t = System.currentTimeMillis()
        return "{\"v\":${formatValue(v)},\"t\":$t}"
    }

    private fun v2EnvelopeStr(s: String): String {
        // Same envelope but for already-formatted numeric strings.
        val t = System.currentTimeMillis()
        return "{\"v\":$s,\"t\":$t}"
    }

    /**
     * Publish a JSON object payload directly (no v-envelope) to the given
     * topic. Used for the dedicated /location and /engine_state topics.
     */
    private fun publishJson(topic: String, json: String) {
        publishOrBuffer(topic, json)
    }

    // -------- GPS publishing (Task #40) --------
    //
    // Subscribes to the platform LocationManager (no Play Services dep) for
    // GPS + NETWORK fixes at ~5 s cadence and republishes them as
    //   bridge/<slug>/gps_lat
    //   bridge/<slug>/gps_lon
    //   bridge/<slug>/gps_speed   (m/s; backend / UI may convert)
    //   bridge/<slug>/gps_alt     (metres)
    // Also writes them through the BridgeStateBus so the on-phone Live view
    // can render the same numbers as the web.
    //
    // Requires ACCESS_FINE_LOCATION (already declared). The runtime grant
    // happens in MainActivity's permission flow; if not granted by the
    // time the bridge starts, we log a warn and skip GPS — the rest of
    // the bridge keeps working without it.

    private var gpsListener: android.location.LocationListener? = null

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        // Collector toggle: skip the LocationManager subscription entirely
        // when GPS is disabled. Crucially, we DON'T re-check the runtime
        // permission in that branch — the acceptance test calls for the
        // BLE-only mode to not even touch ACCESS_FINE_LOCATION.
        if (!bridgeGpsEnabled) {
            logBuffer.info("gps: collector disabled by setting — skipping subscription")
            return
        }
        if (gpsListener != null) {
            // Already subscribed — re-arm requests would just stack
            // duplicate callbacks. Idempotent so the runtime-toggle
            // path can call us without bookkeeping.
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            logBuffer.warn("gps: ACCESS_FINE_LOCATION not granted — skipping GPS publishing")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager
        if (lm == null) {
            logBuffer.warn("gps: LocationManager unavailable on this device")
            return
        }
        val listener = android.location.LocationListener { loc ->
            handleLocationFix(loc)
        }
        gpsListener = listener
        // requestLocationUpdates needs a Looper-bearing thread to deliver
        // callbacks. The bridge service starts these from a coroutine on
        // Dispatchers.IO, which has no Looper, and the 4-arg overload
        // calls Looper.myLooper() under the hood — that throws "Can't
        // create handler ... that has not called Looper.prepare()" and we
        // silently lose GPS for the entire bridge session. Pass main
        // looper explicitly via the 5-arg overload.
        val mainLooper = android.os.Looper.getMainLooper()
        runCatching {
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                /* minTimeMs = */ 5_000L,
                /* minDistanceM = */ 0f,
                listener,
                mainLooper,
            )
            // Network provider fills gaps when GPS is cold (start of trip,
            // garage, urban canyons). Both feed the same listener; whichever
            // produces the freshest fix wins.
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    5_000L,
                    0f,
                    listener,
                    mainLooper,
                )
            }
            logBuffer.info("gps: updates requested at 5 s cadence")
        }.onFailure { exc ->
            logBuffer.warn(
                "gps: requestLocationUpdates failed",
                mapOf("err" to (exc.message ?: exc.javaClass.simpleName)),
            )
        }
    }

    private fun stopGpsUpdates() {
        val listener = gpsListener ?: return
        gpsListener = null
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager
        runCatching { lm?.removeUpdates(listener) }
    }

    /**
     * Runtime-toggle entry point for GPS. Called from the settings-flow
     * watcher when the user flips the "GPS capture" switch mid-session.
     * Spinning up / tearing down LocationManager is cheap and we want
     * the change to take effect without restarting the foreground
     * service.
     */
    private fun applyGpsToggle() {
        if (bridgeGpsEnabled) {
            startGpsUpdates()
        } else {
            stopGpsUpdates()
        }
    }

    /**
     * Pick the right "what's actually running" notification line based
     * on the active collectors. Called whenever the toggle state changes
     * AND whenever the BLE connect/reconnect notification would have
     * pushed an "Idle" / "Connecting" / "Connected" line — we want
     * GPS-only mode to surface a stable, honest description rather
     * than the BLE-centric lines.
     */
    private fun refreshNotificationForActive() {
        val text = when {
            !bridgeBleEnabled && !bridgeGpsEnabled ->
                getString(R.string.bridge_state_nothing_enabled)
            !bridgeBleEnabled && bridgeGpsEnabled ->
                getString(R.string.bridge_state_gps_only)
            bridgeBleEnabled && !bridgeGpsEnabled ->
                getString(R.string.bridge_state_ble_only)
            else -> getString(R.string.bridge_state_ble_and_gps)
        }
        updateNotification(text)
    }

// Track when we last accepted a GPS-provider fix so we can ignore
    // network-provider noise that fires alongside it. Network locations
    // are based on cell + Wi-Fi triangulation and routinely return stale
    // points kilometres from the real position — polluting gps_points
    // with phantom 4 km jumps that explode haversine distance to nonsense.
    @Volatile private var lastGpsProviderFixMs: Long = 0L

    private fun handleLocationFix(loc: android.location.Location) {
        val slug = vehicleSlug
        if (slug.isBlank()) return

        // GPS-provider filter: prefer GPS over network. Accept network
        // fixes only when we haven't seen a GPS fix in the last 30s
        // (covers cold-start, garage, urban-canyon edge cases). And
        // reject any fix with worse-than-100m accuracy regardless of
        // provider — that's "wrong city" territory.
        val nowMs = System.currentTimeMillis()
        val isGps = loc.provider == android.location.LocationManager.GPS_PROVIDER
        if (isGps) {
            lastGpsProviderFixMs = nowMs
        } else {
            val staleGap = nowMs - lastGpsProviderFixMs
            if (lastGpsProviderFixMs > 0L && staleGap < 30_000L) return
        }
        if (loc.hasAccuracy() && loc.accuracy > 100f) {
            logBuffer.warn(
                "gps fix dropped (poor accuracy)",
                mapOf("provider" to (loc.provider ?: "unknown"), "acc_m" to loc.accuracy),
            )
            return
        }

        val lat = loc.latitude
        val lon = loc.longitude
        // Round to 5 decimals (~1.1 m). Avoids an overly precise dump in
        // logs and over the wire while preserving driving accuracy.
        val latR = (lat * 1e5).toLong() / 1e5
        val lonR = (lon * 1e5).toLong() / 1e5
        // Always feed the local state bus so the Live screen's GPS
        // tiles populate whenever the phone has a fix — previously we
        // gated this on engine-on, which meant the tiles were blank
        // any time the user opened Live without driving.
        stateBus.publishMetric("gps_lat", latR)
        stateBus.publishMetric("gps_lon", lonR)
        if (loc.hasSpeed()) stateBus.publishMetric("gps_speed", loc.speed.toDouble())
        if (loc.hasAltitude()) stateBus.publishMetric("gps_alt", loc.altitude)

        // MQTT publish stays soft-gated. Original gate was strictly
        // engine-on which silently dropped every GPS fix in GPS-only
        // mode (v0.1.176 bug: BLE-disabled = engineState never became
        // On = zero `bridge/+/location` ever published). Allow the
        // publish when EITHER:
        //   - merged engineState is On (BLE OBD, watchdog, OR
        //     InCarDetector hint — see BridgeStateBus merge rules), OR
        //   - the phone is actually moving (>1 m/s ≈ 2.2 mph): catches
        //     the early-drive window before any engine signal lands
        //     and is naturally false in a parked driveway.
        val movingMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val engineOn = stateBus.status.value.engineState == EngineState.On
        if (!engineOn && movingMps <= 1.0) return

        // Bridge payload v2: one /location row carries the whole fix
        // (lat/lon/alt/speed/heading/accuracy) with the original capture
        // time. Backend writes to the gps_points hypertable so trip
        // detail can render a route polyline.
        // Locale.US on every numeric field — a comma-decimal locale would
        // emit invalid JSON ("lat":12,345) and the backend would reject
        // every /location publish.
        val sb = StringBuilder()
        sb.append("{\"t\":").append(loc.time.coerceAtLeast(0L))
        sb.append(",\"lat\":").append(String.format(Locale.US, "%.5f", latR))
        sb.append(",\"lon\":").append(String.format(Locale.US, "%.5f", lonR))
        if (loc.hasAltitude()) {
            sb.append(",\"alt\":").append(String.format(Locale.US, "%.1f", loc.altitude))
        }
        if (loc.hasSpeed()) {
            sb.append(",\"speed\":").append(String.format(Locale.US, "%.2f", loc.speed))
        }
        if (loc.hasBearing()) {
            sb.append(",\"heading\":").append(String.format(Locale.US, "%.1f", loc.bearing))
        }
        if (loc.hasAccuracy()) {
            sb.append(",\"acc\":").append(String.format(Locale.US, "%.1f", loc.accuracy))
        }
        sb.append("}")
        publishJson("bridge/$slug/location", sb.toString())
        // Mirror to the drive recorder. Server's gps_points table
        // expects km/h speed? No — it's m/s, matching the Location
        // API. Heading + accuracy come from the same Location obj.
        driveRecorder.current()?.addGps(
            // coerceAtLeast(0L) — NOT currentTimeMillis — so the drive
            // payload and the gps_points row carry the SAME fix time as
            // the MQTT /location publish above (which already uses 0L).
            // Using now() here desynced the two by the publish latency.
            t = loc.time.coerceAtLeast(0L),
            lat = latR,
            lon = lonR,
            altM = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            headingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
        )
    }

    private fun stopBridge() {
        logBuffer.info("bridge stop requested")
        // Clear the auto-start-on-boot flag — explicit stop means the user
        // wants the bridge OFF, including across reboots, until they tap
        // Start again.
        scope.launch {
            runCatching { settingsRepository.setBridgeAutoStart(false) }
        }
        // Seal any in-progress drive BEFORE we cancel the pollJob (the
        // engine-state listener lives on pollJob and would otherwise be
        // killed before it sees the synthetic Off). Matters most for
        // GPS-only mode where the stop intent from InCarDetector is the
        // normal end-of-drive signal — without this, the GPS-captured
        // payload would sit in the recorder until the next bridge start
        // forced an orphan recovery.
        val hadOpenDrive = driveRecorder.current() != null
        if (hadOpenDrive) {
            val tMs = System.currentTimeMillis()
            val kind = pendingSealKind
            pendingSealKind = "off"
            scope.launch {
                val deviceId = settingsRepository.deviceIdOrNull() ?: "unknown"
                driveSealer.seal(tMs, deviceId, kind = kind)
            }
            publishEngineState("off", tMs)
        }
        stopGpsUpdates()
        pollJob?.cancel()
        pollJob = null
        bleManager?.let {
            it.setStateCallback(null)
            it.disconnectDevice()
            runCatching { it.close() }
        }
        bleManager = null
        mqttPublisher.disconnect()
        stateBus.reset()
    }

    // -------- Notification --------

    private fun startForegroundWithNotification() {
        val notification = buildNotification(getString(R.string.bridge_state_idle))
        // Only declare the LOCATION foreground-service type when location
        // permission is actually granted. On API 34+ startForeground()
        // throws SecurityException (→ crash loop under START_STICKY) if we
        // claim the LOCATION type without FINE/COARSE_LOCATION. GPS already
        // degrades gracefully in startGpsUpdates(), so dropping the type
        // when ungranted is safe.
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocation) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            t
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        // The bridge is now started FROM THE BACKGROUND by
        // CompanionDeviceManager (the OS wakes us when the WiCAN appears).
        // On API 34+ a foreground service whose type includes the
        // while-in-use LOCATION type cannot be started from the background
        // unless the app holds ACCESS_BACKGROUND_LOCATION — foreground
        // FINE/COARSE alone isn't enough off-foreground. That SecurityException
        // fires here in onCreate() and crash-loops the service under
        // START_STICKY (the 2026-06-20 "crashes when the bridge starts in the
        // car; sometimes works" report — foreground starts were legal, CDM
        // background starts weren't; the never-firing CDM path had masked it).
        // Try the full type set (so a foreground start still gives GPS its
        // type), and on ANY failure fall back to connectedDevice-only, which
        // is always legal from the CDM background-start exemption. GPS then
        // degrades gracefully — startGpsUpdates() already swallows failures.
        try {
            startForeground(NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            logBuffer.warn(
                "bridge: startForeground failed; retrying connectedDevice-only",
                mapOf("err" to (t.message ?: t::class.java.simpleName), "type" to type),
            )
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }.onFailure {
                logBuffer.error(
                    "bridge: startForeground retry failed",
                    mapOf("err" to (it.message ?: it::class.java.simpleName)),
                )
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PitstopBridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, PitstopApp.BRIDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle(getString(R.string.bridge_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.bridge_action_stop), stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.pitstop.bridge.STOP"

        /**
         * Consecutive strict-NO-DATA responses attributed to a single PID
         * before we drop it from the active poll set for the session (OBD-4).
         * Conservative because lastSentPid attribution is best-effort — a
         * lower value risks suppressing a PID that lost a couple of frames to
         * response overlap rather than genuine non-support.
         */
        private const val NO_DATA_SUPPRESS_THRESHOLD = 5

        /**
         * In-car BLE reconnect backoff decay (BLE-5). The first
         * [IN_CAR_FAST_ATTEMPTS] failed attempts stay at a 5 s cap for a fast
         * first frame on engine start; after that the cap climbs (5 → 15 →
         * [IN_CAR_BACKOFF_CAP_SEC]) so a parked phone next to a sleeping /
         * out-of-range WiCAN stops hammering the radio every 5 s. A real
         * wakeEvents / inCar signal still breaks the backoff early.
         */
        private const val IN_CAR_FAST_ATTEMPTS = 3
        private const val IN_CAR_BACKOFF_CAP_SEC = 30

        /**
         * Topic-last-segment names that still publish to MQTT even
         * when manual-sync mode is on, rate-limited to one frame per
         * [MANUAL_MODE_BEACON_MS]. Add new names sparingly — every
         * beacon costs cellular bytes.
         *   - `fuel_level` (WIDGET-3): keeps the home-screen widget
         *     warm so it doesn't sit on the post-drive value while a
         *     long manual-sync drive is in progress.
         *   - `location` (OBD-3): a GPS fix per minute is enough for
         *     trip_deriver to build a trip row and the route map to
         *     plot a coarse polyline. Without this, manual-sync
         *     drives where BLE is broken AND WiCAN-WiFi is out of
         *     range produce zero data — the drive is lost.
         */
        private val MANUAL_MODE_BEACON_METRICS = setOf("fuel_level", "location")
        private const val MANUAL_MODE_BEACON_MS = 60_000L

        fun startIntent(context: Context): Intent =
            Intent(context, PitstopBridgeService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, PitstopBridgeService::class.java).apply { action = ACTION_STOP }
    }
}

// CoroutineScope.isActive shortcut
private val CoroutineScope.isActive: Boolean
    get() = coroutineContext[Job]?.isActive == true
