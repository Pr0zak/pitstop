package com.pitstop.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.service.MetricSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(
    stateBus: BridgeStateBus,
    private val mqttPublisher: MqttPublisher,
    settingsRepository: com.pitstop.data.SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val api: com.pitstop.http.PitstopApi,
    private val activeVehicle: com.pitstop.data.ActiveVehicle,
    private val directory: com.pitstop.data.VehicleDirectory,
) : ViewModel() {

    /**
     * The newest stored value of every metric (`/readings/latest`), keyed
     * like the live stream — what Live shows, dimmed, while the bridge is
     * off. Empty until loaded or when the server can't be reached.
     */
    private val _parked = MutableStateFlow<Map<String, MetricSample>>(emptyMap())
    val parked: StateFlow<Map<String, MetricSample>> = _parked.asStateFlow()

    /** Daily resting battery voltage, last 14 days; null = hide the card. */
    private val _battery = MutableStateFlow<List<BatteryDay>?>(null)
    val battery: StateFlow<List<BatteryDay>?> = _battery.asStateFlow()

    /** Pull the parked snapshot + battery history. Best-effort: a failure leaves the empty state. */
    fun loadServerSide() {
        viewModelScope.launch {
            val slug = runCatching { activeVehicle.current() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
            val id = (directory.bySlug(slug) ?: runCatching { directory.refresh() }.getOrNull()?.firstOrNull { it.slug == slug })
                ?.id ?: return@launch
            val from = java.time.Instant.now().minus(java.time.Duration.ofDays(14)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            kotlinx.coroutines.coroutineScope {
                val latest = async { runCatching { api.getReadingsLatest(id) }.getOrNull() }
                val volts = async {
                    runCatching { api.getReadingsAggregate(id, "battery_voltage", "day", from.toString()) }.getOrNull()
                }
                latest.await()?.let { rows ->
                    _parked.value = rows.mapNotNull { r ->
                        val v = r.value ?: return@mapNotNull null
                        val t = com.pitstop.util.DateLabel.epochMs(r.time) ?: return@mapNotNull null
                        r.metric to MetricSample(r.metric, v, t)
                    }.toMap()
                }
                _battery.value = volts.await()?.mapNotNull { b ->
                    val v = b.avg ?: return@mapNotNull null
                    val d = runCatching { java.time.OffsetDateTime.parse(b.bucket).toLocalDate() }.getOrNull()
                        ?: return@mapNotNull null
                    BatteryDay(d, v)
                }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Live's empty-state "Start bridge": the same foreground-service start
     *  Home's bridge card issues, so the user needn't leave the tab. */
    fun startBridge() {
        androidx.core.content.ContextCompat.startForegroundService(
            appContext,
            com.pitstop.service.PitstopBridgeService.startIntent(appContext),
        )
    }

    val latestByMetric: StateFlow<Map<String, MetricSample>> = stateBus.latestByMetric

    /** Bridge phase — drives the BLE-link pill at the top of LiveScreen. */
    val status: StateFlow<BridgeStatus> = stateBus.status

    /** Imperial / metric preference. */
    val unitSystem: StateFlow<String> = settingsRepository.settings
        .map { it.unitSystem }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "imperial")

    /** Live MQTT broker connection state. Polled every 1 s; cheap. */
    private val _brokerConnected = MutableStateFlow(false)
    val brokerConnected: StateFlow<Boolean> = _brokerConnected.asStateFlow()

    /**
     * Age (seconds) of the last BLE OBD frame, or null if none yet.
     * Ticks once a second off the dedicated [BridgeStatus.lastObdFrameAtMs]
     * clock (BLE-3) so the Live "OBD Ns" pill counts up while the link is
     * quiet without the metric stream itself having to fire.
     */
    private val _obdAgeS = MutableStateFlow<Long?>(null)
    val obdAgeS: StateFlow<Long?> = _obdAgeS.asStateFlow()

    init {
        loadServerSide()
        viewModelScope.launch {
            while (true) {
                _brokerConnected.value = mqttPublisher.isConnected()
                val last = status.value.lastObdFrameAtMs
                _obdAgeS.value = last?.let { (System.currentTimeMillis() - it) / 1000L }
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }
}

/** One day's average resting battery voltage. */
data class BatteryDay(val date: java.time.LocalDate, val volts: Double)
