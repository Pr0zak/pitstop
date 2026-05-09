package com.pitstop.ui.status

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.service.PitstopBridgeService
import com.pitstop.update.UpdateChecker
import com.pitstop.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatusUiState(
    val status: BridgeStatus = BridgeStatus(),
    val brokerInfo: String? = null,
    val deepLinkUrl: String? = null,
    val totalPublished: Long = 0,
    val logsBuffered: Int = 0,
    val logsLastFlushMs: Long? = null,
    val logsLastResult: String = "—",
    val update: UpdateInfo? = null,
    val updateChecking: Boolean = false,
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    application: Application,
    settingsRepository: SettingsRepository,
    stateBus: BridgeStateBus,
    private val mqttPublisher: MqttPublisher,
    logBuffer: LogBuffer,
    logShipper: LogShipper,
    private val updateChecker: UpdateChecker,
) : AndroidViewModel(application) {

    private val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    private val updateChecking = MutableStateFlow(false)

    init {
        // Background check on first observe — best-effort, no UI block.
        viewModelScope.launch {
            updateChecking.value = true
            updateInfo.value = updateChecker.check()
            updateChecking.value = false
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateChecking.value = true
            updateInfo.value = updateChecker.check()
            updateChecking.value = false
        }
    }

    val uiState: StateFlow<StatusUiState> =
        combine(
            stateBus.status,
            settingsRepository.settings,
            logBuffer.bufferedCount,
            logShipper.lastFlushAtMs,
            logShipper.lastFlushResult,
            updateInfo,
            updateChecking,
        ) { values ->
            val status = values[0] as BridgeStatus
            val settings = values[1] as com.pitstop.data.Settings
            val buffered = values[2] as Int
            val lastFlush = values[3] as Long?
            val lastResult = values[4] as LogShipper.FlushResult?
            val info = values[5] as UpdateInfo?
            val checking = values[6] as Boolean
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
                logsBuffered = buffered,
                logsLastFlushMs = lastFlush,
                logsLastResult = when (val r = lastResult) {
                    null -> "—"
                    LogShipper.FlushResult.Empty -> "empty"
                    is LogShipper.FlushResult.Ok -> "OK"
                    is LogShipper.FlushResult.Error -> "err: ${r.message}"
                },
                update = info,
                updateChecking = checking,
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
