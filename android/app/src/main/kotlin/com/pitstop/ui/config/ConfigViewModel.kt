package com.pitstop.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.ble.BleScanner
import com.pitstop.ble.ScannedDevice
import com.pitstop.data.Settings
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConfigFormState(
    val brokerUrl: String = "",
    val mqttUser: String = "",
    val mqttPassword: String = "",
    val vehicleSlug: String = "",
    val apiBaseUrl: String = "",
    val ingestToken: String = "",
    val bleDeviceMac: String? = null,
    val bleDeviceName: String? = null,
    val verboseLogging: Boolean = false,
    val saved: Boolean = false,
)

/** Messages the Config screen surfaces in a snackbar (eg. "Sent 17 logs"). */
sealed interface ConfigToast {
    data class FlushedOk(val count: Int) : ConfigToast
    object FlushedEmpty : ConfigToast
    data class FlushedError(val message: String) : ConfigToast
}

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scanner: BleScanner,
    private val logBuffer: LogBuffer,
    private val logShipper: LogShipper,
) : ViewModel() {

    /** Live "buffered: N" count for the toggle's helper line. */
    val bufferedCount: StateFlow<Int> = logBuffer.bufferedCount

    val lastFlushAtMs: StateFlow<Long?> = logShipper.lastFlushAtMs

    private val _toast = MutableSharedFlow<ConfigToast>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    private val _form = MutableStateFlow(ConfigFormState())
    val form: StateFlow<ConfigFormState> = _form.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    val isScanning: StateFlow<Boolean> = scanner.isScanning

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val secrets = settingsRepository.current()
            _form.value = ConfigFormState(
                brokerUrl = secrets.settings.brokerUrl,
                mqttUser = secrets.settings.mqttUser,
                mqttPassword = secrets.mqttPassword,
                vehicleSlug = secrets.settings.vehicleSlug,
                apiBaseUrl = secrets.settings.apiBaseUrl,
                ingestToken = secrets.ingestToken,
                bleDeviceMac = secrets.settings.bleDeviceMac,
                bleDeviceName = secrets.settings.bleDeviceName,
                verboseLogging = secrets.settings.verboseLogging,
            )
        }
    }

    /**
     * Flush the log buffer immediately. Surfaces the result via [toast] so the screen can
     * pop a snackbar.
     */
    fun flushLogsNow() {
        viewModelScope.launch {
            val toEmit = when (val r = runCatching { logShipper.flushNow() }.getOrElse { -1 }) {
                0 -> ConfigToast.FlushedEmpty
                in 1..Int.MAX_VALUE -> ConfigToast.FlushedOk(r)
                else -> ConfigToast.FlushedError(
                    (logShipper.lastFlushResult.value as? LogShipper.FlushResult.Error)?.message
                        ?: "flush failed",
                )
            }
            _toast.emit(toEmit)
        }
    }

    fun update(transform: (ConfigFormState) -> ConfigFormState) {
        _form.value = transform(_form.value).copy(saved = false)
    }

    fun pickDevice(device: ScannedDevice) {
        _form.value = _form.value.copy(
            bleDeviceMac = device.mac,
            bleDeviceName = device.name,
            saved = false,
        )
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        if (!scanner.hasScanPermission()) return
        scanJob = viewModelScope.launch {
            scanner.scan().collect { _scanResults.value = it }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun save() {
        viewModelScope.launch {
            val f = _form.value
            settingsRepository.update(
                settings = Settings(
                    brokerUrl = f.brokerUrl,
                    mqttUser = f.mqttUser,
                    vehicleSlug = f.vehicleSlug,
                    apiBaseUrl = f.apiBaseUrl,
                    bleDeviceMac = f.bleDeviceMac,
                    bleDeviceName = f.bleDeviceName,
                    verboseLogging = f.verboseLogging,
                ),
                mqttPassword = f.mqttPassword,
                ingestToken = f.ingestToken,
            )
            _form.value = _form.value.copy(saved = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
