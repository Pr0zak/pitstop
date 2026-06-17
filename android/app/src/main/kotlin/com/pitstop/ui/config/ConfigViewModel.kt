package com.pitstop.ui.config

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.IntentSender
import com.pitstop.ble.BleScanner
import com.pitstop.ble.ScannedDevice
import com.pitstop.companion.WicanCompanionManager
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
    val manualSyncOnly: Boolean = false,
    val bridgeBleEnabled: Boolean = true,
    val bridgeGpsEnabled: Boolean = true,
    val bridgeAutoTrigger: Boolean = true,
    val bridgeAutoTriggerSsids: List<String> = emptyList(),
    val bridgeAutoTriggerActivityEnabled: Boolean = false,
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
    object CompanionPaired : ConfigToast
    object CompanionUnpaired : ConfigToast
    data class CompanionError(val message: String) : ConfigToast
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
    private val companionManager: WicanCompanionManager,
) : AndroidViewModel(application) {

    // ── CompanionDeviceManager (reliable background auto-start) ──────────

    /** True on OS versions where companion presence-observe + the
     *  FGS-from-background exemption actually apply (API 31+). The UI hides
     *  the pairing card below this since it buys nothing there. */
    val companionPresenceSupported: Boolean = companionManager.presenceSupported

    private val _companionAssociated = MutableStateFlow(false)
    /** True when the WiCAN is associated as a companion device. */
    val companionAssociated: StateFlow<Boolean> = _companionAssociated.asStateFlow()

    /** One-shot IntentSender the Settings screen must launch via an
     *  ActivityResultLauncher to show the CDM association consent dialog. */
    private val _companionIntentSender = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val companionIntentSender = _companionIntentSender.asSharedFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    private val _latestUpdate = MutableStateFlow<UpdateInfo?>(null)
    val latestUpdate: StateFlow<UpdateInfo?> = _latestUpdate.asStateFlow()

    /** Live download-progress mirror from [UpdateInstaller]. Pass-through
     *  so the App section can render an in-app progress bar instead of
     *  relying on the system notification alone. */
    val downloadState: StateFlow<com.pitstop.update.DownloadState> =
        updateInstaller.downloadState

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

    /** False until the form has been populated from disk. Save() refuses
     *  to write while false — prevents the init-race that wiped saved
     *  secrets when a Save fired before init's coroutine completed. */
    private val _formReady = MutableStateFlow(false)
    val formReady: StateFlow<Boolean> = _formReady.asStateFlow()

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
                manualSyncOnly = secrets.settings.manualSyncOnly,
                bridgeBleEnabled = secrets.settings.bridgeBleEnabled,
                bridgeGpsEnabled = secrets.settings.bridgeGpsEnabled,
                bridgeAutoTrigger = secrets.settings.bridgeAutoTrigger,
                bridgeAutoTriggerSsids = secrets.settings.bridgeAutoTriggerSsids,
                bridgeAutoTriggerActivityEnabled =
                    secrets.settings.bridgeAutoTriggerActivityEnabled,
            )
            _formReady.value = true
            _companionAssociated.value = companionManager.hasAssociation()
        }
    }

    /** Re-read the live companion-association state (eg. after returning to
     *  Settings, or after the consent dialog resolves). */
    fun refreshCompanionState() {
        _companionAssociated.value = companionManager.hasAssociation()
    }

    /** Launch the CDM association flow for the WiCAN. The manager submits
     *  the request asynchronously; when the OS hands back an IntentSender we
     *  forward it to the screen (via [companionIntentSender]) to show the
     *  consent dialog. If already associated, we just refresh state. */
    fun pairCompanion() {
        val mac = _form.value.bleDeviceMac
        companionManager.associate(
            knownMac = mac,
            onIntentSender = { sender ->
                viewModelScope.launch { _companionIntentSender.emit(sender) }
            },
            onAlreadyAssociated = {
                refreshCompanionState()
                viewModelScope.launch {
                    _toast.emit(ConfigToast.CompanionPaired)
                }
            },
            onError = { reason ->
                viewModelScope.launch {
                    _toast.emit(ConfigToast.CompanionError(reason))
                }
            },
        )
    }

    /** Called by the screen after the consent dialog returns OK with the
     *  resolved association id (+ optional MAC). Persists + starts observing. */
    fun onCompanionConfirmed(associationId: Int, mac: String?) {
        companionManager.onAssociationConfirmed(associationId, mac)
        refreshCompanionState()
        viewModelScope.launch { _toast.emit(ConfigToast.CompanionPaired) }
    }

    /** Remove the WiCAN companion association + stop observing presence. */
    fun unpairCompanion() {
        companionManager.unpair()
        // Optimistic; the OS-side disassociate is async on an IO scope.
        _companionAssociated.value = false
        viewModelScope.launch { _toast.emit(ConfigToast.CompanionUnpaired) }
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

    /** Auto-persist the manual-sync toggle. Skips the Save-button dance —
     *  a user in v0.1.125 flipped it on without tapping Save and drives
     *  kept auto-uploading because the disk value stayed false. */
    fun setManualSyncOnly(value: Boolean) {
        _form.value = _form.value.copy(manualSyncOnly = value)
        viewModelScope.launch {
            runCatching { settingsRepository.setManualSyncOnly(value) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: manualSyncOnly auto-save failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
        }
    }

    /** Auto-persist the Bridge → "OBD via BLE" switch. Same pattern as
     *  [setManualSyncOnly] — non-secret booleans are safe to commit
     *  immediately and we want the bridge service's settings-flow
     *  watcher to react without the user having to tap Save. */
    fun setBridgeBleEnabled(value: Boolean) {
        _form.value = _form.value.copy(bridgeBleEnabled = value)
        viewModelScope.launch {
            runCatching { settingsRepository.setBridgeBleEnabled(value) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: bridgeBleEnabled auto-save failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
        }
    }

    /** Auto-persist the Bridge → "GPS capture" switch. See
     *  [setBridgeBleEnabled] for the rationale. */
    fun setBridgeGpsEnabled(value: Boolean) {
        _form.value = _form.value.copy(bridgeGpsEnabled = value)
        viewModelScope.launch {
            runCatching { settingsRepository.setBridgeGpsEnabled(value) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: bridgeGpsEnabled auto-save failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
        }
    }

    /** Auto-persist the Bridge → "Auto-start in car" switch. Observed
     *  by [com.pitstop.presence.InCarDetector] mid-session — flipping
     *  off immediately unregisters the WiFi NetworkCallback. */
    fun setBridgeAutoTrigger(value: Boolean) {
        _form.value = _form.value.copy(bridgeAutoTrigger = value)
        viewModelScope.launch {
            runCatching { settingsRepository.setBridgeAutoTrigger(value) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: bridgeAutoTrigger auto-save failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
        }
    }

    /** Auto-persist the Activity-Recognition sub-toggle. The Settings
     *  UI is responsible for ensuring the runtime ACTIVITY_RECOGNITION
     *  permission is granted before passing `true` — denial flips the
     *  toggle back off via a snackbar handler in the composable. */
    fun setBridgeAutoTriggerActivityEnabled(value: Boolean) {
        _form.value = _form.value.copy(bridgeAutoTriggerActivityEnabled = value)
        viewModelScope.launch {
            runCatching { settingsRepository.setBridgeAutoTriggerActivityEnabled(value) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: bridgeAutoTriggerActivityEnabled auto-save failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
        }
    }

    /** Auto-persist the editable SSID allowlist for the in-car
     *  detector. Form receives the raw comma-separated text — we
     *  split + clean here so DataStore stores a normalised value. */
    fun setBridgeAutoTriggerSsids(commaSeparated: String) {
        val parsed = commaSeparated
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        _form.value = _form.value.copy(bridgeAutoTriggerSsids = parsed)
        viewModelScope.launch {
            runCatching { settingsRepository.setBridgeAutoTriggerSsids(parsed) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: bridgeAutoTriggerSsids auto-save failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName)),
                    )
                }
        }
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
        // Refuse to write while the form hasn't loaded existing values
        // off disk yet — saving the default-empty state would clobber
        // every persisted field.
        if (!_formReady.value) {
            logBuffer.warn("config save blocked: form not yet loaded")
            return
        }
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
                    manualSyncOnly = f.manualSyncOnly,
                    bridgeBleEnabled = f.bridgeBleEnabled,
                    bridgeGpsEnabled = f.bridgeGpsEnabled,
                    bridgeAutoTrigger = f.bridgeAutoTrigger,
                    bridgeAutoTriggerSsids = f.bridgeAutoTriggerSsids,
                    bridgeAutoTriggerActivityEnabled = f.bridgeAutoTriggerActivityEnabled,
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
     * "Check now" button in Settings → App. Surfaces ONLY non-success
     * states via snackbar (error, up-to-date). The "newer available"
     * case is communicated by the inline UI state (latestUpdate);
     * a snackbar there was overlapping the Download button.
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
                    info.isNewer -> null
                    else -> ConfigToast.UpdateUpToDate(info.currentVersion)
                }
                if (emit != null) _toast.emit(emit)
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
     * Snackbar only fires on FAILURE — the success path is represented
     * by the Download button changing to "Downloading…" plus the system
     * download notification. A success snackbar overlapped the button.
     */
    fun downloadAndInstall() {
        val info = _latestUpdate.value ?: return
        if (!info.isNewer) return
        viewModelScope.launch {
            val id = updateInstaller.startDownload(info)
            if (id == null) _toast.emit(ConfigToast.UpdateDownloadFailed)
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
