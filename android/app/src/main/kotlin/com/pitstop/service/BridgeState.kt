package com.pitstop.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * Engine-state merge inputs. The published [BridgeStatus.engineState]
     * is derived from these two whenever either changes, so callers
     * shouldn't write `engineState` directly — go through
     * [setBleEngineState] (BLE/OBD-derived) or [setInCarEngineHint]
     * (presence-derived, e.g. [com.pitstop.presence.InCarDetector]).
     *
     * Merge precedence (per the v0.1.176 GPS-only fix):
     *   - BLE explicit On wins
     *   - BLE explicit Off wins (the WiCAN reported STOPPED, trust it
     *     over a stale Bluetooth-headset-still-connected hint)
     *   - Otherwise (BLE Unknown / link down / collector disabled) the
     *     in-car hint becomes the source of truth so GPS-only drives
     *     transition engine state at all
     */
    @Volatile private var bleEngineState: EngineState = EngineState.Unknown
    @Volatile private var inCarEngineHint: Boolean? = null

    /**
     * External signal that an immediate BLE reconnect attempt is now
     * worth trying — e.g. WiCAN just came online via MQTT, so the
     * adaptive backoff loop should stop sleeping and try BLE now.
     * One-shot fire-and-forget; new subscribers don't replay.
     */
    private val _wakeEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

    fun wakeUp() {
        _wakeEvents.tryEmit(Unit)
    }

    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        _status.update(transform)
    }

    /**
     * Record the BLE/OBD view of the engine. Callers feed this from
     * the WiCAN response parser (engine-on / STOPPED), the OBD-quiet
     * watchdog, and the BLE-lost watchdog. The merged
     * [BridgeStatus.engineState] is recomputed and the change-time
     * stamp updated only when the public state actually flips.
     */
    fun setBleEngineState(state: EngineState, atMs: Long = System.currentTimeMillis()) {
        bleEngineState = state
        applyEngineMerge(atMs)
    }

    /**
     * Record the presence-detector hint that the user is in the car.
     * Used as the engine-state source of truth when BLE is disabled,
     * the link is down, or the WiCAN hasn't reported anything yet —
     * see the merge rules on [bleEngineState]. Pass `null` to clear
     * the hint (e.g. when the InCarDetector is turned off).
     */
    fun setInCarEngineHint(active: Boolean?, atMs: Long = System.currentTimeMillis()) {
        inCarEngineHint = active
        applyEngineMerge(atMs)
    }

    private fun applyEngineMerge(atMs: Long) {
        val merged = when {
            bleEngineState == EngineState.On -> EngineState.On
            bleEngineState == EngineState.Off -> EngineState.Off
            inCarEngineHint == true -> EngineState.On
            inCarEngineHint == false -> EngineState.Off
            else -> EngineState.Unknown
        }
        _status.update { cur ->
            if (cur.engineState == merged) cur
            else cur.copy(engineState = merged, engineStateChangedAtMs = atMs)
        }
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
        bleEngineState = EngineState.Unknown
        inCarEngineHint = null
        _status.value = BridgeStatus()
        _latestByMetric.value = emptyMap()
    }

    /**
     * Drop every cached metric sample (Live screen goes blank). Called
     * on `engine_off` so the user doesn't see stale RPM / speed from
     * the prior drive sitting on the gauges indefinitely. The next
     * engine_on repopulates as fresh OBD frames arrive.
     */
    fun clearMetrics() {
        _latestByMetric.value = emptyMap()
        _status.update { it.copy(metricsActive = 0) }
    }
}
