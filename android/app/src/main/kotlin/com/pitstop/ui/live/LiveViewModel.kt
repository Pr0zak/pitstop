package com.pitstop.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.service.MetricSample
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

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
