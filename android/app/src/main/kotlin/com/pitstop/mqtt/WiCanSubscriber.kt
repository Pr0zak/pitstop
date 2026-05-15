package com.pitstop.mqtt

import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.service.BridgeStateBus
import com.pitstop.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to WiCAN's AutoPID consolidated topic on the broker and
 * fans each reading into [BridgeStateBus] so the Live screen can show
 * metrics the phone bridge isn't polling itself (ATF temp, oxygen
 * sensor trims, fuel rate, odometer, etc.).
 *
 * WiCAN publishes one JSON object per polling cycle to a single topic
 * — our convention is `wican/<vehicle_slug>/pid` — with metric →
 * value pairs at the top level. Names use WiCAN's hex-prefixed format
 * (e.g. `0C-EngineRPM`, `5C-EngineOilTemp`) which we translate to
 * pitstop's canonical names ([WICAN_TO_CANONICAL]) before publishing.
 *
 * Why phone-side: when the phone is on the same Wi-Fi as WiCAN (e.g.
 * driveway, in-garage), this gives the Live screen a much richer view
 * than the bridge's BLE poll list without re-polling everything over
 * BLE. When the phone is on cellular and WiCAN's WiFi MQTT is silent
 * (away from home), this subscription just sits idle — no harm.
 *
 * Lifecycle: started by [com.pitstop.service.PitstopBridgeService]
 * when the bridge spins up, stopped on bridge teardown. Internally it
 * registers a [MqttPublisher.subscribe] callback that survives
 * MQTT reconnects.
 */
@Singleton
class WiCanSubscriber @Inject constructor(
    private val mqttPublisher: MqttPublisher,
    private val stateBus: BridgeStateBus,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
    private val widgetRefresher: WidgetRefresher,
) {

    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var started = false
    @Volatile private var msgCount: Long = 0L
    @Volatile private var retainedDropped: Long = 0L

    fun start() {
        if (started) return
        started = true
        ownScope.launch {
            val slug = settings.settings.first().vehicleSlug.trim()
            // Subscribe whether or not slug is set — the wildcard form
            // catches every vehicle the broker carries, but we filter
            // server-side by slug match before fanout. Bare `+` keeps
            // the MQTT subscription minimal (one filter).
            val filter = if (slug.isNotEmpty()) "wican/$slug/pid" else "wican/+/pid"
            mqttPublisher.subscribe(filter) { topic, payload, isRetain ->
                // Drop retained messages. WiCAN's AutoPID publishes with
                // retain=true so the broker delivers the last known value
                // to any new subscriber. On phone subscribe (or after a
                // broker reconnect) we'd otherwise paint stale RPM /
                // coolant / battery from the prior drive onto the Live
                // screen. Only live messages — published while we were
                // already subscribed — update the state bus.
                if (isRetain) {
                    retainedDropped += 1
                    return@subscribe
                }
                handleMessage(topic, payload)
            }

            // Second subscription: WiCAN publishes its CAN-bus presence
            // to `wican/<device_id>/can/status` keyed by device MAC (NOT
            // vehicle slug). When the user starts their car after the
            // BLE backoff has stretched out to 60+ seconds, the WiCAN
            // boots and posts `{"status":"online"}` — that's our wake
            // signal to break the backoff and try BLE now. Wildcard on
            // device id since the phone only knows the BLE MAC and the
            // two aren't identical. False-positives (another WiCAN
            // somewhere else flipping online) just trigger a BLE attempt
            // that fails harmlessly.
            mqttPublisher.subscribe("wican/+/can/status") { topic, payload, isRetain ->
                if (isRetain) return@subscribe
                handleCanStatus(topic, payload)
            }
        }
    }

    fun stop() {
        // The MqttPublisher subscription registry is global; we leave
        // the callback in place. start()/stop() of the bridge does not
        // re-add — a guard in start() prevents duplicates. Worst case:
        // stale callback fires on a not-running bridge → state-bus
        // updates the same as before, no observable harm.
        started = false
    }

    val totalMessages: Long get() = msgCount

    private fun handleMessage(topic: String, payload: ByteArray) {
        msgCount += 1
        val text = try {
            String(payload, Charsets.UTF_8)
        } catch (_: Throwable) {
            return
        }
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject ?: return
        } catch (_: Throwable) {
            return
        }
        for ((key, value) in obj) {
            val canonical = canonicalName(key) ?: continue
            val num = numericValue(value) ?: continue
            stateBus.publishMetric(canonical, num)
            // Mirror to the home-screen widget so it doesn't sit stale
            // between Android's 30-min updatePeriodMillis ticks. Rate
            // limit lives in WidgetRefresher itself.
            if (canonical == "fuel_level") widgetRefresher.refreshFuelWidget()
        }
    }

    private fun handleCanStatus(topic: String, payload: ByteArray) {
        val text = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return
        if (status == "online") {
            logBuffer.info(
                "wican CAN online via MQTT — waking BLE backoff",
                mapOf("topic" to topic),
            )
            stateBus.wakeUp()
        }
    }

    private fun numericValue(v: kotlinx.serialization.json.JsonElement): Double? {
        if (v !is JsonPrimitive) return null
        return v.doubleOrNull
            ?: v.intOrNull?.toDouble()
            ?: v.booleanOrNull?.let { if (it) 1.0 else 0.0 }
    }

    companion object {
        /**
         * Mirror of `backend/.../wican_aliases.py`'s WICAN_TO_CANONICAL.
         * Keep the two in sync — the backend uses this map at ingest
         * time so the gauges + analytics work; we use it phone-side so
         * the Live view sees the same names. `_drop_` means "discard".
         */
        private val WICAN_TO_CANONICAL: Map<String, String> = mapOf(
            "04-CalcEngineLoad" to "engine_load",
            "05-EngineCoolantTemp" to "coolant_temp",
            "0B-IntakeManiAbsPress" to "manifold_pressure",
            "0C-EngineRPM" to "engine_rpm",
            "0D-VehicleSpeed" to "vehicle_speed",
            "0E-TimingAdvance" to "timing_advance",
            "0F-IntakeAirTemp" to "intake_air_temp",
            "10-MAFAirFlowRate" to "maf_air_flow",
            "11-ThrottlePosition" to "throttle_position",
            "1F-TimeSinceEngStart" to "run_time_since_start",
            "21-DistanceMILOn" to "distance_mil_on",
            "2F-FuelTankLevel" to "fuel_level",
            "31-DistanceSinceCodeClear" to "distance_since_code_clear",
            "33-AbsBaroPres" to "barometric_pressure",
            "42-ControlModuleVolt" to "control_module_voltage",
            "43-AbsLoadValue" to "absolute_load",
            "45-RelThrottlePos" to "relative_throttle_position",
            "5C-EngineOilTemp" to "engine_oil_temp",
            "62-ActualEngTorqPct" to "engine_torque_pct",
            "63-EngRefTorq" to "engine_reference_torque",
            "9D-EngineFuelRate" to "fuel_rate",
            "A6-Odometer" to "odometer",
            "06-ShortFuelTrimBank1" to "stft_b1",
            "07-LongFuelTrimBank1" to "ltft_b1",
            "08-ShortFuelTrimBank2" to "stft_b2",
            "09-LongFuelTrimBank2" to "ltft_b2",
            "55-ShortSecOxyTrimBank1" to "stft_sec_b1",
            "56-LongSecOxyTrimBank1" to "ltft_sec_b1",
            "57-ShortSecOxyTrimBank2" to "stft_sec_b2",
            "58-LongSecOxyTrimBank2" to "ltft_sec_b2",
            "67-EngineCoolantTemp1" to "coolant_temp",
            "68-IntakeAirTempSens1" to "intake_air_temp",
            "66-MAFSensorA" to "maf_air_flow",
            // Honda Pilot ATF custom PID — name comes from WiCAN's
            // user-configured PID. Memory has it as `223083` to TCM
            // 7E1 with formula `(B6*9/5)-40` (Fahrenheit). Whatever
            // name the user gave it in WiCAN's UI lands here as-is;
            // the alias below covers the common ones.
            "atf_temp" to "atf_temp_f",
            "ATF_Temp" to "atf_temp_f",
            "atf_temp_f" to "atf_temp_f",
            "timestamp" to "_drop_",
            "_hdr_reset" to "_drop_",
        )

        private fun canonicalName(metric: String): String? {
            val target = WICAN_TO_CANONICAL[metric]
            return when (target) {
                null -> metric  // pass-through (e.g. Honda hex-named PIDs)
                "_drop_" -> null
                else -> target
            }
        }
    }
}
