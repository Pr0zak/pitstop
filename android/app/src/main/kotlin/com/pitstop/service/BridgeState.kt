package com.pitstop.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class BridgePhase { Idle, Scanning, Connecting, Connected, Disconnected, Error }

/**
 * Engine state derived from WiCAN responses, not BLE link state. Knowing whether
 * the engine is *actually running* is independent from whether we are paired:
 *   - WiCAN sleeps ~5 min after key-off; BLE drops once it sleeps. Before that
 *     drop, we'll see [Off] frames (`STOPPED`/`NO DATA`) but BLE is still up.
 *   - When the phone returns to the car after a long break, BLE may not link
 *     until the engine starts and CAN traffic wakes WiCAN.
 *   - [Unknown] is the start-up / no-data-yet state — we haven't decided yet.
 */
enum class EngineState { Unknown, On, Off }

data class BridgeStatus(
    val phase: BridgePhase = BridgePhase.Idle,
    val deviceName: String? = null,
    val deviceMac: String? = null,
    val rssi: Int? = null,
    val errorMessage: String? = null,
    val brokerUrl: String? = null,
    val brokerConnected: Boolean = false,
    val publishedLastMinute: Int = 0,
    val totalPublished: Long = 0,
    val lastFrameAtMs: Long? = null,
    val metricsActive: Int = 0,
    /** Engine running / off / unknown — derived from OBD frames (see EngineState). */
    val engineState: EngineState = EngineState.Unknown,
    /** When the engine state was last positively asserted (ms epoch). */
    val engineStateChangedAtMs: Long? = null,
    /**
     * Phone-side "user is in the car" signal — combined Android Auto
     * projection state + the user's paired-car HFP Bluetooth profile
     * connection. Used by the adaptive BLE retry strategy and surfaced
     * as a Live-screen pill so the user can see why the bridge thinks
     * (or doesn't think) they're driving.
     */
    val inCar: Boolean = false,
    /**
     * Bytes currently sitting in the on-disk [com.pitstop.mqtt.OfflineBuffer]
     * waiting to drain. Drives the "X queued" pill in the Live view so the
     * user can see the bridge is keeping data even with no cellular.
     */
    val offlineBufferBytes: Long = 0L,
)

data class MetricSample(
    val name: String,
    val value: Double,
    val tsMs: Long,
)

/**
 * Singleton in-process bus the foreground service writes to and the UI reads from.
 *
 * The Live view reads [latestByMetric] directly; nothing is round-tripped through MQTT
 * for the on-screen gauges. That is the whole point — the broker may be unreachable
 * while driving and we still want gauges.
 */
@Singleton
class BridgeStateBus @Inject constructor() {

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _latestByMetric = MutableStateFlow<Map<String, MetricSample>>(emptyMap())
    val latestByMetric: StateFlow<Map<String, MetricSample>> = _latestByMetric.asStateFlow()

    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        _status.update(transform)
    }

    fun publishMetric(name: String, value: Double) {
        val sample = MetricSample(name = name, value = value, tsMs = System.currentTimeMillis())
        _latestByMetric.update { it + (name to sample) }
        _status.update {
            it.copy(
                lastFrameAtMs = sample.tsMs,
                metricsActive = (_latestByMetric.value.keys + name).size,
            )
        }
    }

    fun reset() {
        _status.value = BridgeStatus()
        _latestByMetric.value = emptyMap()
    }
}
