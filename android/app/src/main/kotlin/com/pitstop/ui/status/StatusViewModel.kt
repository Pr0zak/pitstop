package com.pitstop.ui.status

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.service.PitstopBridgeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class StatusUiState(
    val status: BridgeStatus = BridgeStatus(),
    val brokerInfo: String? = null,
    val deepLinkUrl: String? = null,
    val totalPublished: Long = 0,
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    application: Application,
    settingsRepository: SettingsRepository,
    stateBus: BridgeStateBus,
    private val mqttPublisher: MqttPublisher,
) : AndroidViewModel(application) {

    val uiState: StateFlow<StatusUiState> =
        combine(stateBus.status, settingsRepository.settings) { status, settings ->
            StatusUiState(
                status = status.copy(
                    brokerConnected = mqttPublisher.isConnected(),
                    totalPublished = mqttPublisher.totalPublished,
                ),
                brokerInfo = settings.brokerUrl.takeIf { it.isNotBlank() },
                deepLinkUrl = settings.apiBaseUrl
                    .takeIf { it.isNotBlank() }
                    ?.trimEnd('/')
                    ?.let { "$it/live" },
                totalPublished = mqttPublisher.totalPublished,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatusUiState(),
        )

    fun startService() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, PitstopBridgeService.startIntent(ctx))
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        ctx.startService(PitstopBridgeService.stopIntent(ctx))
    }
}
