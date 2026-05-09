package com.pitstop.ui.config

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.ble.BleScanner
import com.pitstop.ble.ScannedDevice
import com.pitstop.data.Settings
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.update.UpdateChecker
import com.pitstop.update.UpdateInfo
import com.pitstop.update.UpdateInstaller
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
    val queryToken: String = "",
    val bleDeviceMac: String? = null,
    val bleDeviceName: String? = null,
    val verboseLogging: Boolean = false,
    val aaTilesHome: List<String> = emptyList(),
    val aaTilesDiag: List<String> = emptyList(),
    val unitSystem: String = "imperial",
    val saved: Boolean = false,
)

/** Messages the Config screen surfaces in a snackbar (eg. "Sent 17 logs"). */
sealed interface ConfigToast {
    data class FlushedOk(val count: Int) : ConfigToast
    object FlushedEmpty : ConfigToast
    data class FlushedError(val message: String) : ConfigToast
    data class UpdateAvailable(val current: String, val latest: String, val url: String) : ConfigToast
    data class UpdateUpToDate(val current: String) : ConfigToast
    data class UpdateCheckError(val message: String) : ConfigToast
    data class UpdateDownloadStarted(val version: String) : ConfigToast
    object UpdateDownloadFailed : ConfigToast
}

@HiltViewModel
class ConfigViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val scanner: BleScanner,
    private val logBuffer: LogBuffer,
    private val logShipper: LogShipper,
    stateBus: BridgeStateBus,
    private val mqttPublisher: MqttPublisher,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : AndroidViewModel(application) {

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    private val _latestUpdate = MutableStateFlow<UpdateInfo?>(null)
    val latestUpdate: StateFlow<UpdateInfo?> = _latestUpdate.asStateFlow()

    /** Live "buffered: N" count for the toggle's helper line. */
    val bufferedCount: StateFlow<Int> = logBuffer.bufferedCount

    val lastFlushAtMs: StateFlow<Long?> = logShipper.lastFlushAtMs

    /** Live bridge state for the Bridge service section. */
    val bridgeStatus: StateFlow<BridgeStatus> = stateBus.status

    /** Live MQTT broker connection state. Polled from MqttPublisher;
     *  exposed as a StateFlow so the UI re-renders without per-tick work. */
    private val _brokerConnected = MutableStateFlow(false)
    val brokerConnected: StateFlow<Boolean> = _brokerConnected.asStateFlow()

    val totalPublished: StateFlow<Long> = MutableStateFlow(0L).also { sf ->
        viewModelScope.launch {
            while (true) {
                sf.value = mqttPublisher.totalPublished
                _brokerConnected.value = mqttPublisher.isConnected()
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }

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
                queryToken = secrets.queryToken,
                aaTilesHome = secrets.settings.aaTilesHome
                    .ifEmpty { com.pitstop.car.CarTileCatalog.DEFAULT_HOME },
                aaTilesDiag = secrets.settings.aaTilesDiag
                    .ifEmpty { com.pitstop.car.CarTileCatalog.DEFAULT_DIAG },
                unitSystem = secrets.settings.unitSystem,
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
                    aaTilesHome = f.aaTilesHome,
                    aaTilesDiag = f.aaTilesDiag,
                    unitSystem = f.unitSystem,
                ),
                mqttPassword = f.mqttPassword,
                ingestToken = f.ingestToken,
                queryToken = f.queryToken,
            )
            _form.value = _form.value.copy(saved = true)
        }
    }

    /**
     * Start the foreground bridge service. Mirrors StatusViewModel.startService
     * — kept here so the redesigned Settings → Bridge service section can
     * own start/stop without a ViewModel hop.
     */
    fun startBridge() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(
            ctx,
            com.pitstop.service.PitstopBridgeService.startIntent(ctx),
        )
    }

    /**
     * Manual update check — same UpdateChecker the periodic
     * UpdateCheckWorker uses, but driven by the user pressing the
     * "Check now" button in Settings → App. Surfaces the result via a
     * ConfigToast.UpdateAvailable / UpdateUpToDate / UpdateCheckError so
     * the snackbar host on Settings can render it.
     */
    fun checkForUpdates() {
        if (_checkingUpdate.value) return
        viewModelScope.launch {
            _checkingUpdate.value = true
            try {
                val info = updateChecker.check()
                _latestUpdate.value = info
                val emit = when {
                    info == null -> ConfigToast.UpdateCheckError("Couldn't reach GitHub")
                    info.isNewer -> ConfigToast.UpdateAvailable(
                        current = info.currentVersion,
                        latest = info.latestVersion,
                        url = info.releaseUrl,
                    )
                    else -> ConfigToast.UpdateUpToDate(info.currentVersion)
                }
                _toast.emit(emit)
            } finally {
                _checkingUpdate.value = false
            }
        }
    }

    /**
     * Manually reconnect the MQTT client. Useful when the phone has been
     * sleeping (Doze) and the user wants to immediately verify the bridge
     * is talking to the broker without waiting for HiveMQ's
     * automatic-reconnect timer. Tears down the existing client and
     * re-runs connectMqttWithRetry against the saved settings.
     */
    fun reconnectBroker() {
        viewModelScope.launch {
            val secrets = settingsRepository.current()
            if (secrets.settings.brokerUrl.isBlank()) return@launch
            runCatching {
                mqttPublisher.connect(
                    secrets.settings.brokerUrl,
                    secrets.settings.mqttUser,
                    secrets.mqttPassword,
                )
            }.onFailure { exc ->
                logBuffer.warn(
                    "manual mqtt reconnect failed",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
                )
            }
        }
    }

    /**
     * Kick off an APK download via [UpdateInstaller]. When the download
     * finishes, the system installer dialog opens automatically.
     * Snackbar reports start/failure; progress shows in the system
     * download notification, which is the right surface for it.
     */
    fun downloadAndInstall() {
        val info = _latestUpdate.value ?: return
        if (!info.isNewer) return
        viewModelScope.launch {
            val id = updateInstaller.startDownload(info)
            _toast.emit(
                if (id != null) ConfigToast.UpdateDownloadStarted(info.latestVersion)
                else ConfigToast.UpdateDownloadFailed,
            )
        }
    }

    fun stopBridge() {
        val ctx = getApplication<Application>()
        ctx.startService(com.pitstop.service.PitstopBridgeService.stopIntent(ctx))
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
