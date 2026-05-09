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
    @Inject lateinit var stateBus: BridgeStateBus
    @Inject lateinit var logBuffer: LogBuffer
    @Inject lateinit var logShipper: LogShipper

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

            val backoffSec = (1 shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30)
            reconnectAttempt += 1
            // After 4 consecutive misses (~30s+), tell the log shipper we're in a long
            // disconnect — it'll halve its flush rate to save battery.
            if (reconnectAttempt >= 4) {
                logShipper.batterySaving = true
            }
            logBuffer.info(
                "ble reconnect scheduled",
                mapOf("attempt" to reconnectAttempt, "backoff_s" to backoffSec),
            )
            updateNotification("Disconnected — retrying in ${backoffSec}s")
            stateBus.update { it.copy(phase = BridgePhase.Disconnected) }
            delay(backoffSec * 1_000L)
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
            // Don't log every short fragment — only frames that look like full responses.
            // Heuristic: contains '>' or is at least 6 chars after trimming.
            val trimmed = frameText.trim()
            if (trimmed.length >= 6) {
                logBuffer.warn(
                    "obd parse failed",
                    mapOf("frame" to trimmed.take(48)),
                )
            }
            return
        }
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
        mqttPublisher.publish(topic, formatValue(value))
    }

    private fun formatValue(v: Double): String {
        // Integers as integers, fractional with up to 3 decimals. The backend parser
        // accepts both forms.
        return if (kotlin.math.abs(v - v.toLong()) < 1e-9) v.toLong().toString()
        else "%.3f".format(v)
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
        runCatching {
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                /* minTimeMs = */ 5_000L,
                /* minDistanceM = */ 0f,
                listener,
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

    private fun publishImuFrame() {
        val slug = vehicleSlug
        if (slug.isBlank()) return
        lastAccel?.let { a ->
            mqttPublisher.publish("bridge/$slug/accel_x", "%.3f".format(a[0]))
            mqttPublisher.publish("bridge/$slug/accel_y", "%.3f".format(a[1]))
            mqttPublisher.publish("bridge/$slug/accel_z", "%.3f".format(a[2]))
            stateBus.publishMetric("accel_x", a[0].toDouble())
            stateBus.publishMetric("accel_y", a[1].toDouble())
            stateBus.publishMetric("accel_z", a[2].toDouble())
        }
        lastGyro?.let { g ->
            mqttPublisher.publish("bridge/$slug/gyro_x", "%.3f".format(g[0]))
            mqttPublisher.publish("bridge/$slug/gyro_y", "%.3f".format(g[1]))
            mqttPublisher.publish("bridge/$slug/gyro_z", "%.3f".format(g[2]))
            stateBus.publishMetric("gyro_x", g[0].toDouble())
            stateBus.publishMetric("gyro_y", g[1].toDouble())
            stateBus.publishMetric("gyro_z", g[2].toDouble())
        }
    }

    private fun handleLocationFix(loc: android.location.Location) {
        val slug = vehicleSlug
        if (slug.isBlank()) return
        val lat = loc.latitude
        val lon = loc.longitude
        // Round to 5 decimals (~1.1 m). Avoids an overly precise dump in
        // logs and over the wire while preserving driving accuracy.
        val latR = (lat * 1e5).toLong() / 1e5
        val lonR = (lon * 1e5).toLong() / 1e5
        mqttPublisher.publish("bridge/$slug/gps_lat", latR.toString())
        mqttPublisher.publish("bridge/$slug/gps_lon", lonR.toString())
        if (loc.hasSpeed()) mqttPublisher.publish("bridge/$slug/gps_speed", "%.2f".format(loc.speed))
        if (loc.hasAltitude()) mqttPublisher.publish("bridge/$slug/gps_alt", "%.1f".format(loc.altitude))
        stateBus.publishMetric("gps_lat", latR)
        stateBus.publishMetric("gps_lon", lonR)
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
