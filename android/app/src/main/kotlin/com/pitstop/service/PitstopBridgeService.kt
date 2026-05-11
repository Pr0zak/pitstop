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
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import com.pitstop.log.loggableUrl
import com.pitstop.log.maskMac
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.obd.ObdResponseParser
import com.pitstop.obd.Pid
import com.pitstop.obd.Pids
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
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
            while (isActive) {
                kotlinx.coroutines.delay(10_000L)
                val s = stateBus.status.value
                if (s.engineState != EngineState.On) continue
                val lastFrame = s.lastFrameAtMs ?: continue
                val ageMs = System.currentTimeMillis() - lastFrame
                if (lastFrame > 0 && ageMs > obdQuietThresholdMs) {
                    logBuffer.info(
                        "engine off (OBD-quiet watchdog)",
                        mapOf("frame_age_ms" to ageMs),
                    )
                    val tMs = System.currentTimeMillis()
                    stateBus.update {
                        it.copy(
                            engineState = EngineState.Off,
                            engineStateChangedAtMs = tMs,
                        )
                    }
                    publishEngineState("off", tMs)
                    stateBus.clearMetrics()
                    val deviceId = settingsRepository.deviceIdOrNull() ?: "unknown"
                    driveSealer.seal(tMs, deviceId, kind = "quiet")
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
                if (pollJob == null) startBridge()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        logBuffer.info("bridge service destroying")
        scope.cancel()
        bleManager?.disconnectDevice()
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

    @SuppressLint("MissingPermission")
    private fun startBridge() {
        pollJob = scope.launch {
            // Mark "user wants this running" so BootReceiver auto-starts the
            // service after a phone reboot. Cleared in stopBridge() when the
            // user explicitly stops.
            runCatching { settingsRepository.setBridgeAutoStart(true) }

            // GPS: parallel to the BLE/MQTT path. If permission is missing
            // we log + skip; the bridge still publishes OBD telemetry.
            startGpsUpdates()
            // IMU: accel + gyro at 5 Hz on the same lifecycle.
            startImuUpdates()
            val secrets = settingsRepository.current()
            val s = secrets.settings
            vehicleSlug = s.vehicleSlug.ifBlank { "vehicle" }
            logBuffer.info(
                "bridge start",
                mapOf(
                    "vehicle_slug" to vehicleSlug,
                    "broker" to loggableUrl(s.brokerUrl),
                    "ble_mac" to maskMac(s.bleDeviceMac),
                    "verbose_logging" to s.verboseLogging,
                ),
            )

            stateBus.update {
                it.copy(
                    phase = BridgePhase.Connecting,
                    brokerUrl = s.brokerUrl.takeIf { url -> url.isNotBlank() },
                    deviceName = s.bleDeviceName,
                    deviceMac = s.bleDeviceMac,
                )
            }
            updateNotification(getString(R.string.bridge_state_scanning))

            // Connect MQTT in parallel — the bridge can publish even while BLE is down
            // (eg. heartbeat) and the moment BLE comes up we don't want to wait on a
            // TCP handshake.
            launch { connectMqttWithRetry(s.brokerUrl, s.mqttUser, secrets.mqttPassword) }
            // Drain backlog whenever the broker is reachable. Runs forever
            // until the bridge is stopped.
            launch { runOfflineDrainLoop() }
            // Mirror HiveMQ's live connection state into the bridge bus so
            // every screen / Android Auto tile reflects reconnects driven by
            // the client's automatic-reconnect loop without us re-running
            // connectMqttWithRetry.
            launch { trackBrokerLiveness() }

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

    // Engine-state hysteresis: WiCAN occasionally answers STOPPED for one
    // specific PID (e.g. an unsupported one) while the engine is running.
    // Require a few consecutive STOPPED responses with no real frame in
    // between before we declare engine-off. A single live frame resets the
    // counter and immediately flips to engine-on.
    private var stoppedRunLength = 0
    private val engineOffThreshold = 6

    private fun onEngineOnSignal() {
        stoppedRunLength = 0
        val s = stateBus.status.value
        if (s.engineState != EngineState.On) {
            logBuffer.info("engine on")
            val tMs = System.currentTimeMillis()
            stateBus.update {
                it.copy(
                    engineState = EngineState.On,
                    engineStateChangedAtMs = tMs,
                )
            }
            publishEngineState("on", tMs)
            // Open a phone-canonical drive buffer (#117). The recorder
            // mirrors every PID / GPS / IMU sample the bridge publishes
            // while open; on engine_off DriveSealer.seal() persists +
            // uploads. If the prior buffer never closed cleanly (BLE
            // dropped before engine_off fired), seal the orphan with
            // incomplete=true so the drive isn't lost.
            if (vehicleSlug.isNotBlank()) {
                val result = driveRecorder.open(vehicleSlug, tMs)
                result.orphan?.let { orphan ->
                    scope.launch {
                        val deviceId = settingsRepository.deviceIdOrNull() ?: "unknown"
                        driveSealer.sealOrphan(orphan, deviceId)
                    }
                }
            }
        }
    }

    private fun onEngineOffSignal() {
        stoppedRunLength += 1
        if (stoppedRunLength < engineOffThreshold) return
        val s = stateBus.status.value
        if (s.engineState != EngineState.Off) {
            logBuffer.info("engine off (wican reports stopped)")
            val tMs = System.currentTimeMillis()
            stateBus.update {
                it.copy(
                    engineState = EngineState.Off,
                    engineStateChangedAtMs = tMs,
                )
            }
            publishEngineState("off", tMs)
            // Clear cached metric samples so the Live screen doesn't
            // sit on stale RPM / speed / coolant values from the
            // just-finished drive. GPS + IMU stop being published to
            // the bus too (see publish gates below) so the screen
            // stays blank until the next engine_on.
            stateBus.clearMetrics()
            // Seal the open drive (#117). The sealer persists to Room
            // and kicks the upload worker; subsequent retries collapse
            // on the deterministic client_drive_uuid.
            scope.launch {
                val deviceId = settingsRepository.deviceIdOrNull() ?: "unknown"
                driveSealer.seal(tMs, deviceId)
            }
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

        while (scope.isActive) {
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

            val mgr = WiCanBleManager(applicationContext, logBuffer)
            bleManager = mgr
            mgr.setStateCallback(object : WiCanBleManager.UartStateCallback {
                override fun onConnectionStateChange(state: WiCanBleManager.ConnectionState) {
                    val phase = when (state) {
                        WiCanBleManager.ConnectionState.READY -> BridgePhase.Connected
                        WiCanBleManager.ConnectionState.CONNECTED -> BridgePhase.Connected
                        WiCanBleManager.ConnectionState.CONNECTING -> BridgePhase.Connecting
                        WiCanBleManager.ConnectionState.DISCONNECTED -> BridgePhase.Disconnected
                        WiCanBleManager.ConnectionState.FAILED -> BridgePhase.Error
                    }
                    stateBus.update { it.copy(phase = phase) }
                }

                override fun onDataReceived(bytes: ByteArray) {
                    onUartFrame(bytes)
                }
            })
            mgr.connectToDevice(device)

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
            // Adaptive backoff (Task #77):
            //   - in car (AA up or paired-car BT connected) → aggressive
            //     5s cap. WiCAN is near, may be waking from sleep, we
            //     want the first frame ASAP so the trip opens correctly.
            //   - parked (engine off & ≥3 attempts with zero frames) →
            //     stretch cap to 5 min. WiCAN sleeps after voltage drops;
            //     hammering BLE while it's asleep just burns battery and
            //     doesn't wake it (wake is voltage-based, not CAN-based).
            //   - else → existing exponential 1→30s.
            val priorEngine = stateBus.status.value.engineState
            val inCar = stateBus.status.value.inCar
            val backoffSec = when {
                inCar -> baseSec.coerceAtMost(5)
                priorEngine == EngineState.Off && reconnectAttempt >= 3 -> 300
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
            stateBus.update {
                it.copy(
                    phase = BridgePhase.Disconnected,
                    engineState = EngineState.Unknown,
                )
            }
            // Wake early if presence flips to true mid-sleep — entering
            // the car is the strongest signal that a new BLE attempt is
            // worthwhile right now.
            kotlinx.coroutines.withTimeoutOrNull(backoffSec * 1_000L) {
                presence.inCar.first { it }
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
                bleManager?.writeCommand(due.command())
                nextDue[due.name] = t + due.periodMs
            }

            // Tight loop, but bounded — sleep until the next due PID.
            val nextWake = pidsToPoll.minOf { nextDue[it.name] ?: t }
            val sleep = (nextWake - System.currentTimeMillis()).coerceIn(50L, 1_000L)
            delay(sleep)
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
            // WiCAN's "STOPPED" / "NO DATA" / "UNABLE TO CONNECT" responses are
            // not parser errors — they're the device telling us the engine is
            // off. Surface that as an engine-state change instead of spamming
            // /debug with warn-level "obd parse failed" lines, and skip the
            // log entirely (parser already filters these from real frames).
            val upper = trimmed.uppercase()
            val isEngineOffSignal = upper.startsWith("STOPPED") ||
                upper.startsWith("NO DATA") ||
                upper.startsWith("UNABLE TO CONNECT")
            if (isEngineOffSignal) {
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
        // Real OBD response → engine is awake.
        onEngineOnSignal()
        val pid = pidsToPoll.firstOrNull {
            it.mode == parsed.mode && it.pid == parsed.pid
        } ?: return
        val value = pid.parser(parsed.data)
        if (value == null) {
            logBuffer.warn(
                "obd value parser returned null",
                mapOf("pid" to pid.name, "data_bytes" to parsed.data.size),
            )
            return
        }

        stateBus.publishMetric(pid.name, value)
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
     * publish site (OBD parser, GPS handler, IMU tick).
     */
    private fun publishOrBuffer(topic: String, payload: String) {
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
        else "%.3f".format(v)
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

    // -------- IMU publishing (Task #41) --------
    //
    // Subscribes to TYPE_LINEAR_ACCELERATION (gravity-stripped m/s²) and
    // TYPE_GYROSCOPE (rad/s) and republishes every 200 ms (5 Hz):
    //   bridge/<slug>/accel_x, accel_y, accel_z
    //   bridge/<slug>/gyro_x, gyro_y, gyro_z
    // Sensor callbacks fire much faster than 5 Hz on most phones — we keep
    // only the freshest sample between ticks. This keeps the broker stream
    // bounded (~30 messages/s combined with GPS) without loading-up
    // post-drive analysis pipelines.

    private var imuListener: android.hardware.SensorEventListener? = null
    private var lastAccel: FloatArray? = null
    private var lastGyro: FloatArray? = null
    private var imuPublishJob: kotlinx.coroutines.Job? = null

    private fun startImuUpdates() {
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        if (sm == null) {
            logBuffer.warn("imu: SensorManager unavailable")
            return
        }
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        if (accel == null && gyro == null) {
            logBuffer.warn("imu: device has neither linear-accel nor gyro sensor")
            return
        }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                when (event.sensor.type) {
                    android.hardware.Sensor.TYPE_LINEAR_ACCELERATION ->
                        lastAccel = event.values.copyOf(3)
                    android.hardware.Sensor.TYPE_GYROSCOPE ->
                        lastGyro = event.values.copyOf(3)
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        imuListener = listener
        // SENSOR_DELAY_GAME = ~20 ms; we throttle to 5 Hz on the publish side.
        accel?.let {
            sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        gyro?.let {
            sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        imuPublishJob = scope.launch {
            while (scope.isActive) {
                kotlinx.coroutines.delay(200L)
                publishImuFrame()
            }
        }
        logBuffer.info(
            "imu: updates started",
            mapOf("accel" to (accel != null), "gyro" to (gyro != null)),
        )
    }

    private fun stopImuUpdates() {
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        imuListener?.let { runCatching { sm?.unregisterListener(it) } }
        imuListener = null
        imuPublishJob?.cancel()
        imuPublishJob = null
        lastAccel = null
        lastGyro = null
    }

    // IMU motion floor — below these magnitudes the values are sensor
    // noise on a phone sitting on a desk or in a parked car. We always
    // mirror the sample to BridgeStateBus (so on-phone Live view still
    // shows zeros) but don't bother publishing to MQTT.
    private val imuAccelNoiseMs2 = 0.15f      // ~1.5% of g
    private val imuGyroNoiseRadS = 0.05f      // ~3°/s

    private fun publishImuFrame() {
        val slug = vehicleSlug
        if (slug.isBlank()) return

        // Gate the firehose: only ship to the broker when the engine is
        // Gate every IMU update — local state bus, MQTT publish, and
        // drive recorder — on engine_state=On. While parked at home /
        // WiCAN asleep / out of the car this saves ~30 useless rows/s
        // going into pid_readings AND prevents the Live screen from
        // showing the phone's own accelerometer noise while sitting on
        // a table.
        if (stateBus.status.value.engineState != EngineState.On) return
        val publishToBroker = true

        // Compose one IMU sample for the drive recorder per tick.
        // Recorder takes both accel + gyro together so the per-sample
        // timestamp lines up — analytics that pair the two axes get
        // the same instant of data, instead of two stagger-ed rows.
        val tImu = System.currentTimeMillis()
        val a = lastAccel
        val g = lastGyro
        if (a != null || g != null) {
            driveRecorder.current()?.addImu(
                t = tImu,
                ax = a?.get(0)?.toDouble(),
                ay = a?.get(1)?.toDouble(),
                az = a?.get(2)?.toDouble(),
                gx = g?.get(0)?.toDouble(),
                gy = g?.get(1)?.toDouble(),
                gz = g?.get(2)?.toDouble(),
            )
        }
        a?.let {
            stateBus.publishMetric("accel_x", it[0].toDouble())
            stateBus.publishMetric("accel_y", it[1].toDouble())
            stateBus.publishMetric("accel_z", it[2].toDouble())
            // Skip publishing if the phone is essentially still — even
            // during a drive, idling at a red light produces all-zero
            // samples that just bloat the DB.
            val mag = kotlin.math.sqrt((it[0] * it[0] + it[1] * it[1] + it[2] * it[2]).toDouble())
            if (publishToBroker && mag >= imuAccelNoiseMs2) {
                publishOrBuffer("bridge/$slug/accel_x", v2EnvelopeStr("%.3f".format(it[0])))
                publishOrBuffer("bridge/$slug/accel_y", v2EnvelopeStr("%.3f".format(it[1])))
                publishOrBuffer("bridge/$slug/accel_z", v2EnvelopeStr("%.3f".format(it[2])))
            }
        }
        g?.let {
            stateBus.publishMetric("gyro_x", it[0].toDouble())
            stateBus.publishMetric("gyro_y", it[1].toDouble())
            stateBus.publishMetric("gyro_z", it[2].toDouble())
            val mag = kotlin.math.sqrt((it[0] * it[0] + it[1] * it[1] + it[2] * it[2]).toDouble())
            if (publishToBroker && mag >= imuGyroNoiseRadS) {
                publishOrBuffer("bridge/$slug/gyro_x", v2EnvelopeStr("%.3f".format(it[0])))
                publishOrBuffer("bridge/$slug/gyro_y", v2EnvelopeStr("%.3f".format(it[1])))
                publishOrBuffer("bridge/$slug/gyro_z", v2EnvelopeStr("%.3f".format(it[2])))
            }
        }
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
        // Don't update the local state bus while parked either.
        // Otherwise the Live screen shows GPS coords ticking up while
        // the car is off, which is confusing — the screen should be
        // blank between drives.
        if (stateBus.status.value.engineState != EngineState.On) return
        stateBus.publishMetric("gps_lat", latR)
        stateBus.publishMetric("gps_lon", lonR)
        if (loc.hasSpeed()) stateBus.publishMetric("gps_speed", loc.speed.toDouble())
        if (loc.hasAltitude()) stateBus.publishMetric("gps_alt", loc.altitude)

        // Bridge payload v2: one /location row carries the whole fix
        // (lat/lon/alt/speed/heading/accuracy) with the original capture
        // time. Backend writes to the gps_points hypertable so trip
        // detail can render a route polyline.
        val sb = StringBuilder()
        sb.append("{\"t\":").append(loc.time.coerceAtLeast(0L))
        sb.append(",\"lat\":").append("%.5f".format(latR))
        sb.append(",\"lon\":").append("%.5f".format(lonR))
        if (loc.hasAltitude()) sb.append(",\"alt\":").append("%.1f".format(loc.altitude))
        if (loc.hasSpeed()) sb.append(",\"speed\":").append("%.2f".format(loc.speed))
        if (loc.hasBearing()) sb.append(",\"heading\":").append("%.1f".format(loc.bearing))
        if (loc.hasAccuracy()) sb.append(",\"acc\":").append("%.1f".format(loc.accuracy))
        sb.append("}")
        publishJson("bridge/$slug/location", sb.toString())
        // Mirror to the drive recorder. Server's gps_points table
        // expects km/h speed? No — it's m/s, matching the Location
        // API. Heading + accuracy come from the same Location obj.
        driveRecorder.current()?.addGps(
            t = loc.time.coerceAtLeast(System.currentTimeMillis()),
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
        stopGpsUpdates()
        stopImuUpdates()
        pollJob?.cancel()
        pollJob = null
        bleManager?.disconnectDevice()
        bleManager = null
        mqttPublisher.disconnect()
        stateBus.reset()
    }

    // -------- Notification --------

    private fun startForegroundWithNotification() {
        val notification = buildNotification(getString(R.string.bridge_state_idle))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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

        fun startIntent(context: Context): Intent =
            Intent(context, PitstopBridgeService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, PitstopBridgeService::class.java).apply { action = ACTION_STOP }
    }
}

// CoroutineScope.isActive shortcut
private val CoroutineScope.isActive: Boolean
    get() = coroutineContext[Job]?.isActive == true
