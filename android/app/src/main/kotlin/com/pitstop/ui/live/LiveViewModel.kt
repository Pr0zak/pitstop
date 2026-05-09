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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(
    stateBus: BridgeStateBus,
    private val mqttPublisher: MqttPublisher,
) : ViewModel() {

    val latestByMetric: StateFlow<Map<String, MetricSample>> = stateBus.latestByMetric

    /** Bridge phase — drives the BLE-link pill at the top of LiveScreen. */
    val status: StateFlow<BridgeStatus> = stateBus.status

    /** Live MQTT broker connection state. Polled every 1 s; cheap. */
    private val _brokerConnected = MutableStateFlow(false)
    val brokerConnected: StateFlow<Boolean> = _brokerConnected.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _brokerConnected.value = mqttPublisher.isConnected()
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }
}
