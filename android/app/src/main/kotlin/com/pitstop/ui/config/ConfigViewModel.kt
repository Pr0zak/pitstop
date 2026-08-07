package com.pitstop.ui.config

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import com.pitstop.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.IntentSender
import com.pitstop.ble.BleScanner
import com.pitstop.ble.ScannedDevice
import com.pitstop.companion.WicanCompanionManager
import com.pitstop.data.Settings
import com.pitstop.data.SettingsRepository
import com.pitstop.data.effectiveBrokerUrl
import com.pitstop.http.PitstopApi
import com.pitstop.http.VehicleDto
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.presence.ActivityRecognitionBus
import com.pitstop.presence.InCarDetector
import com.pitstop.presence.PresenceTracker
import com.pitstop.service.BridgePhase
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.update.UpdateChecker
import com.pitstop.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException
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
    val aaTilesEngine: List<String> = emptyList(),
    val aaTilesFuel: List<String> = emptyList(),
    val aaTilesDiag: List<String> = emptyList(),
    val unitSystem: String = "imperial",
    val manualSyncOnly: Boolean = false,
    /** Opt-in Mode 22 extended PIDs (ZF TCM: ATF temp + gear). NOT part of the
     *  Settings object — it has its own DataStore key, so persistForm must
     *  never write it and the loader reads it separately. */
    val extendedPidsEnabled: Boolean = false,
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
    object CompanionPaired : ConfigToast
    object BridgePausedForPairing : ConfigToast
    object CompanionUnpaired : ConfigToast
    data class CompanionError(val message: String) : ConfigToast
    data class SetupImported(val fields: List<String>) : ConfigToast
    object SetupLinkInvalid : ConfigToast
    object SetupLinkEmpty : ConfigToast
}

/** Result of the inline "Test connection" probe (GET /api/vehicles). One
 *  shared type reused by the Connection status chip, the vehicle picker, and
 *  (later) the setup wizard — so every failure mode is named the same way. */
sealed interface ConnTest {
    object Idle : ConnTest
    object InProgress : ConnTest
    /** Reached the server and read [vehicles] — the picker consumes this. */
    data class Ok(val vehicles: List<VehicleDto>) : ConnTest
    /** URL isn't a valid http(s)://host:port — caught before any request. */
    object BadUrl : ConnTest
    /** 401/403 — the Query token is wrong (or missing). */
    object BadToken : ConnTest
    /** Couldn't reach the host at all (network / wrong IP / port). */
    data class Unreachable(val msg: String) : ConnTest
    /** Reached the server but it returned an error status. */
    data class ServerError(val code: Int) : ConnTest
}

/** Result of the "Test broker" probe — a throwaway MQTT connect that never
 *  disturbs the live bridge connection. Distinct from [ConnTest] because the
 *  broker is a different host/protocol with its own failure modes (auth reject
 *  vs. unreachable socket). */
sealed interface BrokerTest {
    object Idle : BrokerTest
    object InProgress : BrokerTest
    /** CONNACK accepted — the broker is reachable and the credentials work. */
    object Ok : BrokerTest
    /** Broker URL isn't parseable as tcp://host:port — caught before connecting. */
    object BadUrl : BrokerTest
    /** Broker refused the credentials (NOT_AUTHORIZED / bad user/pass). */
    object BadAuth : BrokerTest
    /** Couldn't open the socket at all (DNS, refused, timed out). */
    data class Unreachable(val msg: String) : BrokerTest
}

/** Whether auto-start is actually wired up to fire — the single verdict the
 *  status card leads with. */
enum class AutoStartVerdict {
    /** Master toggle is off. */
    Off,

    /** Toggle on, but on API 31+ the WiCAN isn't paired as a companion device —
     *  the OS will block the background service start, so drives won't auto-start
     *  even though the in-car signals fire. The one thing users miss. */
    NeedsPairing,

    /** Toggle on and (companion paired, or pre-API-31 where it isn't needed). */
    Armed,
}

/** One tri-state row in the live signal ledger. */
enum class SignalState { Active, Idle, Disabled }

/** Snapshot that drives the "Auto-start status" card — the verdict plus a live
 *  ledger of each in-car signal so a missed auto-start is diagnosable at a
 *  glance instead of via the log buffer. */
data class AutoStartStatus(
    val verdict: AutoStartVerdict = AutoStartVerdict.Off,
    /** The combined 4-signal live "we're in the car" verdict. */
    val inCarNow: Boolean = false,
    /** Car WiFi SSID: matched / connected-but-no-match / no SSIDs configured. */
    val wifi: SignalState = SignalState.Disabled,
    /** Android Auto projection or paired-car Bluetooth (combined). */
    val projection: SignalState = SignalState.Idle,
    /** Activity-recognition in-vehicle motion (opt-in). */
    val motion: SignalState = SignalState.Disabled,
    val companionSupported: Boolean = false,
    val companionPaired: Boolean = false,
    /** Epoch-ms of the last auto-triggered start; 0 = never. */
    val lastAutoStartAtMs: Long = 0L,
)

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
    private val companionManager: WicanCompanionManager,
    private val api: PitstopApi,
    private val inCarDetector: InCarDetector,
    private val presenceTracker: PresenceTracker,
    private val activityBus: ActivityRecognitionBus,
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

    /** True from the moment [pairCompanion] starts until the OS consent chooser
     *  is handed off (or the flow resolves/errors). Drives a spinner over the
     *  otherwise-silent bridge-stop + advertising wait so the user doesn't tap
     *  "Pair" again thinking nothing happened. Also guards re-entry. */
    private val _pairingInProgress = MutableStateFlow(false)
    val pairingInProgress: StateFlow<Boolean> = _pairingInProgress.asStateFlow()

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

    /** False until the form has been populated from disk. Save() refuses
     *  to write while false — prevents the init-race that wiped saved
     *  secrets when a Save fired before init's coroutine completed. */
    private val _formReady = MutableStateFlow(false)
    val formReady: StateFlow<Boolean> = _formReady.asStateFlow()

    // ── Auto-start status (task #18) ────────────────────────────────────
    private data class LiveSignals(
        val inCar: Boolean,
        val ssidMatched: Boolean,
        val projection: Boolean,
        val motion: Boolean,
        val lastAutoStartAtMs: Long,
    )

    private val liveSignals = combine(
        inCarDetector.inCar,
        inCarDetector.ssidMatched,
        presenceTracker.inCar,
        activityBus.inVehicle,
        settingsRepository.lastAutoStartAtMs,
    ) { inCar, ssid, projection, motion, lastAuto ->
        LiveSignals(inCar, ssid, projection, motion, lastAuto)
    }

    /** One authoritative snapshot for the Settings "Auto-start status" card:
     *  the armed/needs-pairing/off verdict plus a live per-signal ledger. Reads
     *  config off [_form] and the live signals off the presence singletons. */
    val autoStartStatus: StateFlow<AutoStartStatus> = combine(
        _form,
        _companionAssociated,
        liveSignals,
    ) { form, paired, live ->
        val supported = companionPresenceSupported
        AutoStartStatus(
            verdict = when {
                !form.bridgeAutoTrigger -> AutoStartVerdict.Off
                supported && !paired -> AutoStartVerdict.NeedsPairing
                else -> AutoStartVerdict.Armed
            },
            inCarNow = live.inCar,
            wifi = when {
                form.bridgeAutoTriggerSsids.isEmpty() -> SignalState.Disabled
                live.ssidMatched -> SignalState.Active
                else -> SignalState.Idle
            },
            projection = if (live.projection) SignalState.Active else SignalState.Idle,
            motion = when {
                !form.bridgeAutoTriggerActivityEnabled -> SignalState.Disabled
                live.motion -> SignalState.Active
                else -> SignalState.Idle
            },
            companionSupported = supported,
            companionPaired = paired,
            lastAutoStartAtMs = live.lastAutoStartAtMs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoStartStatus())

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    val isScanning: StateFlow<Boolean> = scanner.isScanning

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val secrets = settingsRepository.current()
            // Read separately: extendedPidsEnabled lives in its own DataStore
            // key, deliberately outside the Settings object.
            val extendedPidsInitial =
                runCatching { settingsRepository.extendedPidsEnabledNow() }.getOrDefault(false)
            _form.value = ConfigFormState(
                brokerUrl = secrets.settings.brokerUrl,
                mqttUser = secrets.settings.mqttUser,
                mqttPassword = secrets.mqttPassword,
                vehicleSlug = secrets.settings.vehicleSlug,
                apiBaseUrl = secrets.settings.apiBaseUrl,
                ingestToken = secrets.ingestToken,
                queryToken = secrets.queryToken,
                aaTilesHome = secrets.settings.aaTilesHome,
                aaTilesEngine = secrets.settings.aaTilesEngine,
                aaTilesFuel = secrets.settings.aaTilesFuel
                    .ifEmpty { com.pitstop.car.CarTileCatalog.DEFAULT_HOME },
                aaTilesDiag = secrets.settings.aaTilesDiag
                    .ifEmpty { com.pitstop.car.CarTileCatalog.DEFAULT_DIAG },
                unitSystem = secrets.settings.unitSystem,
                bleDeviceMac = secrets.settings.bleDeviceMac,
                bleDeviceName = secrets.settings.bleDeviceName,
                verboseLogging = secrets.settings.verboseLogging,
                manualSyncOnly = secrets.settings.manualSyncOnly,
                extendedPidsEnabled = extendedPidsInitial,
                bridgeBleEnabled = secrets.settings.bridgeBleEnabled,
                bridgeGpsEnabled = secrets.settings.bridgeGpsEnabled,
                bridgeAutoTrigger = secrets.settings.bridgeAutoTrigger,
                bridgeAutoTriggerSsids = secrets.settings.bridgeAutoTriggerSsids,
                bridgeAutoTriggerActivityEnabled =
                    secrets.settings.bridgeAutoTriggerActivityEnabled,
                // The just-loaded form already matches disk, so mark it saved.
                // This keeps the debounced auto-save from firing an unsolicited
                // persist the moment Settings opens (which would otherwise bake
                // the auto-derived broker URL onto disk and pin it to the
                // current server host).
                saved = true,
            )
            _formReady.value = true
            _companionAssociated.value = companionManager.hasAssociation()
        }
        startAutoSave()
    }

    /**
     * Debounced auto-save for the text/secret fields (URL, tokens, vehicle
     * slug). The boolean toggles already persist immediately via their focused
     * setters — this closes the gap where the connection config was only saved
     * by the bottom Save button and got silently discarded on a tab switch.
     * Normalizing the `saved` flag before distinctUntilChanged stops
     * persistForm()'s own `saved = true` write from re-triggering the loop.
     */
    @OptIn(FlowPreview::class)
    private fun startAutoSave() {
        viewModelScope.launch {
            _form
                .map { it.copy(saved = false) }
                .distinctUntilChanged()
                .debounce(800L) // ~1 keystroke pause before persisting
                .collect {
                    if (_formReady.value && !_form.value.saved) {
                        // Guard like the focused boolean setters do — a transient
                        // DataStore/keystore write fault must not kill this
                        // collector (which would permanently stop auto-save) or
                        // crash the app via the uncaught-exception handler.
                        runCatching { persistForm() }.onFailure {
                            logBuffer.warn(
                                "auto-save failed",
                                mapOf("err" to (it.message ?: it::class.java.simpleName)),
                            )
                        }
                    }
                }
        }
    }

    /** Re-read the live companion-association state (eg. after returning to
     *  Settings, or after the consent dialog resolves). */
    fun refreshCompanionState() {
        _companionAssociated.value = companionManager.hasAssociation()
    }

    /** True while we stopped the bridge specifically to free the BLE link for
     *  pairing — so [restoreBridgeAfterPairingIfNeeded] knows to bring it back
     *  once the consent flow resolves. */
    private var pausedBridgeForPairing = false

    /** Launch the CDM association flow for the WiCAN. The manager submits
     *  the request asynchronously; when the OS hands back an IntentSender we
     *  forward it to the screen (via [companionIntentSender]) to show the
     *  consent dialog. If already associated, we just refresh state.
     *
     *  The CDM chooser scans for the WiCAN's BLE advertisement, but a BLE
     *  device stops advertising while connected. If the bridge holds the link,
     *  the chooser is empty and associate() comes back `user_rejected` — the
     *  recurring "can't pair the WiCAN" trap. So we stop the bridge first,
     *  wait for the link to drop and the WiCAN to resume advertising, then
     *  associate; [restoreBridgeAfterPairingIfNeeded] restarts it when the
     *  consent flow resolves. */
    fun pairCompanion() {
        // Guard re-entry: a second tap while the bridge-stop wait is in flight
        // would stack another associate() and desync pausedBridgeForPairing.
        if (_pairingInProgress.value) return
        _pairingInProgress.value = true
        viewModelScope.launch {
            val phase = bridgeStatus.value.phase
            val bridgeHeldLink = phase == BridgePhase.Connected ||
                phase == BridgePhase.Connecting ||
                phase == BridgePhase.Scanning
            if (bridgeHeldLink) {
                pausedBridgeForPairing = true
                _toast.emit(ConfigToast.BridgePausedForPairing)
                stopBridge()
                // Wait for the link to actually drop, then give the WiCAN a
                // moment to resume advertising before the chooser scans.
                withTimeoutOrNull(5_000) {
                    while (
                        bridgeStatus.value.phase != BridgePhase.Idle &&
                        bridgeStatus.value.phase != BridgePhase.Disconnected
                    ) {
                        delay(200)
                    }
                }
                delay(2_500)
            }
            val mac = _form.value.bleDeviceMac
            try {
                companionManager.associate(
                    knownMac = mac,
                    onIntentSender = { sender ->
                        // The OS consent chooser owns the UI from here; clear our
                        // spinner. The screen's launcher result resolves the rest.
                        _pairingInProgress.value = false
                        viewModelScope.launch { _companionIntentSender.emit(sender) }
                    },
                    onAlreadyAssociated = {
                        _pairingInProgress.value = false
                        refreshCompanionState()
                        restoreBridgeAfterPairingIfNeeded()
                        viewModelScope.launch {
                            _toast.emit(ConfigToast.CompanionPaired)
                        }
                    },
                    onError = { reason ->
                        _pairingInProgress.value = false
                        restoreBridgeAfterPairingIfNeeded()
                        viewModelScope.launch {
                            _toast.emit(ConfigToast.CompanionError(reason))
                        }
                    },
                )
            } catch (e: Exception) {
                // associate() can throw synchronously (SecurityException if a
                // BLE permission was revoked, IllegalStateException, …). Without
                // this the spinner would hang forever and the bridge stay down.
                _pairingInProgress.value = false
                restoreBridgeAfterPairingIfNeeded()
                _toast.emit(ConfigToast.CompanionError(e.message ?: e::class.java.simpleName))
            }
        }
    }

    /** Restart the bridge if [pairCompanion] stopped it to free the BLE link.
     *  Idempotent — a no-op unless we actually paused it. Called from every
     *  resolution point of the consent flow (confirm / cancel / error). On a
     *  successful pair CDM also auto-starts the bridge; the bridge's
     *  onStartCommand is idempotent so a double start is harmless. */
    fun restoreBridgeAfterPairingIfNeeded() {
        if (!pausedBridgeForPairing) return
        pausedBridgeForPairing = false
        startBridge()
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

    /**
     * Opt-in Mode 22 extended PIDs. Writes its OWN DataStore key rather than
     * going through persistForm: the whole-object Settings write must never be
     * able to clobber it (and vice versa).
     *
     * Default OFF on purpose. These PIDs change the ELM session header to reach
     * the ZF TCM, and a header left un-restored makes every subsequent standard
     * PID be answered by the wrong module — the failure that collapsed the
     * dongle's own published payload from 62 keys to 19. See ADR-022.
     */
    fun setExtendedPidsEnabled(value: Boolean) {
        _form.value = _form.value.copy(extendedPidsEnabled = value)
        viewModelScope.launch {
            runCatching { settingsRepository.setExtendedPidsEnabled(value) }
                .onFailure { t ->
                    logBuffer.warn(
                        "config: extendedPidsEnabled auto-save failed",
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

    private val _connTest = MutableStateFlow<ConnTest>(ConnTest.Idle)
    /** Live result of the last "Test connection". The Connection section shows
     *  a status chip off this; the vehicle picker reads Ok.vehicles. */
    val connTest: StateFlow<ConnTest> = _connTest.asStateFlow()

    /** Persist the current form to disk. Extracted so [testConnection] can flush
     *  before probing (the auth interceptor reads persisted values). */
    private suspend fun persistForm() {
        val f = _form.value
        // Store the broker URL EXACTLY as entered (blank stays blank). The
        // "derive tcp://<api-host>:1883 when blank" rule lives in
        // Settings.effectiveBrokerUrl() and is applied live at connect time, so
        // it can't pin the broker to a stale host after an apiBaseUrl change.
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
                aaTilesEngine = f.aaTilesEngine,
                aaTilesFuel = f.aaTilesFuel,
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
        // Mark saved ONLY if the form still matches the snapshot we persisted.
        // update() suspends (DataStore + 3 keystore writes); a keystroke landing
        // in that window mutates _form. Blindly stamping saved=true on the new
        // value would drop that edit (both the debounce retry and onDispose
        // flush skip when saved==true) — the silent-truncated-token trap.
        _form.update { if (it == f) it.copy(saved = true) else it }
    }

    fun save() {
        // Refuse to write while the form hasn't loaded existing values
        // off disk yet — saving the default-empty state would clobber
        // every persisted field.
        if (!_formReady.value) {
            logBuffer.warn("config save blocked: form not yet loaded")
            return
        }
        viewModelScope.launch { persistForm() }
    }

    /**
     * Probe the server with the CURRENT form values and report one typed
     * result inline. Persists first (the auth interceptor reads the stored
     * URL + Query token), then GET /api/vehicles — which doubles as the load
     * for the vehicle picker. Doesn't touch the bridge or MQTT.
     */
    fun testConnection() {
        val f = _form.value
        // Synchronous URL-shape check — no request for an obviously-bad URL.
        if (f.apiBaseUrl.isBlank() || f.apiBaseUrl.toHttpUrlOrNull() == null) {
            _connTest.value = ConnTest.BadUrl
            return
        }
        if (f.queryToken.isBlank()) {
            _connTest.value = ConnTest.BadToken
            return
        }
        _connTest.value = ConnTest.InProgress
        viewModelScope.launch {
            if (_formReady.value) persistForm()
            _connTest.value = runCatching { api.getVehicles() }.fold(
                onSuccess = { ConnTest.Ok(it) },
                onFailure = { e ->
                    when {
                        e is HttpException && (e.code() == 401 || e.code() == 403) -> ConnTest.BadToken
                        e is HttpException -> ConnTest.ServerError(e.code())
                        e is IOException -> ConnTest.Unreachable(e.message ?: "unreachable")
                        else -> ConnTest.Unreachable(e.message ?: e::class.java.simpleName)
                    }
                },
            )
        }
    }

    /** Clear a stale test result — call on any edit to the URL/token so a green
     *  "Connected" chip can't linger over untested values. */
    fun resetConnTest() {
        if (_connTest.value != ConnTest.Idle && _connTest.value != ConnTest.InProgress) {
            _connTest.value = ConnTest.Idle
        }
    }

    private val _brokerTest = MutableStateFlow<BrokerTest>(BrokerTest.Idle)
    /** Live result of the last "Test broker". The MQTT section shows a chip. */
    val brokerTest: StateFlow<BrokerTest> = _brokerTest.asStateFlow()

    /**
     * Probe the MQTT broker with the CURRENT form values via a throwaway client
     * — never disturbs the live bridge connection. Mirrors persistForm()'s
     * derive-host-from-API-URL rule so the probe works even when the broker URL
     * is left blank in the canonical single-host deploy.
     */
    fun testBroker() {
        val f = _form.value
        val brokerUrl = Settings(
            brokerUrl = f.brokerUrl,
            apiBaseUrl = f.apiBaseUrl,
        ).effectiveBrokerUrl()
        if (brokerUrl.isBlank()) {
            _brokerTest.value = BrokerTest.BadUrl
            return
        }
        _brokerTest.value = BrokerTest.InProgress
        viewModelScope.launch {
            _brokerTest.value = try {
                mqttPublisher.testConnect(brokerUrl, f.mqttUser, f.mqttPassword)
                BrokerTest.Ok
            } catch (e: TimeoutCancellationException) {
                BrokerTest.Unreachable("timed out")
            } catch (e: Mqtt3ConnAckException) {
                // Socket connected but the broker rejected the CONNECT — by far
                // the common case is bad user/password on Mosquitto.
                BrokerTest.BadAuth
            } catch (e: IllegalArgumentException) {
                BrokerTest.BadUrl
            } catch (e: Exception) {
                BrokerTest.Unreachable(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** Clear a stale broker-test result on any edit to the broker fields. */
    fun resetBrokerTest() {
        if (_brokerTest.value != BrokerTest.Idle && _brokerTest.value != BrokerTest.InProgress) {
            _brokerTest.value = BrokerTest.Idle
        }
    }

    /** A parsed setup payload STAGED for confirmation. The UI shows a dialog off
     *  this; nothing is applied until [confirmImport]. Non-null = dialog open. */
    private val _pendingImport = MutableStateFlow<SetupPayload?>(null)
    val pendingImport: StateFlow<SetupPayload?> = _pendingImport.asStateFlow()

    /**
     * Parse a pasted / deep-linked `pitstop://setup?…` string and STAGE it for
     * user confirmation — it does NOT apply anything yet. A `pitstop://setup`
     * link is reachable from any web page, QR, message, or co-installed app, and
     * this is a location/telemetry tracker: applying a link-supplied server +
     * tokens without consent would let a tapped link silently repoint uploads at
     * an attacker and hand over the auth token. So we require an explicit
     * [confirmImport] via the dialog. Invalid / empty links just toast.
     */
    fun importSetupLink(raw: String) {
        when (val result = SetupLink.parse(raw)) {
            is SetupImportResult.Ok -> _pendingImport.value = result.payload
            SetupImportResult.NotASetupLink ->
                viewModelScope.launch { _toast.emit(ConfigToast.SetupLinkInvalid) }
            SetupImportResult.Empty ->
                viewModelScope.launch { _toast.emit(ConfigToast.SetupLinkEmpty) }
        }
    }

    /** Dismiss the staged import without applying it. */
    fun cancelImport() {
        _pendingImport.value = null
    }

    /**
     * Apply the staged setup payload — fills only the fields it carries,
     * persists, and (when it now has a server + query token) kicks a connection
     * test so the vehicle picker populates. Only ever called from the
     * confirmation dialog's positive button.
     */
    fun confirmImport() {
        val p = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            // A deep link arrives at cold start — wait until the form has loaded
            // from disk so init's load can't clobber the imported values.
            _formReady.first { it }
            _form.value = _form.value.copy(
                apiBaseUrl = p.apiBaseUrl ?: _form.value.apiBaseUrl,
                queryToken = p.queryToken ?: _form.value.queryToken,
                ingestToken = p.ingestToken ?: _form.value.ingestToken,
                vehicleSlug = p.vehicleSlug ?: _form.value.vehicleSlug,
                brokerUrl = p.brokerUrl ?: _form.value.brokerUrl,
                mqttUser = p.mqttUser ?: _form.value.mqttUser,
                mqttPassword = p.mqttPassword ?: _form.value.mqttPassword,
                saved = false,
            )
            resetConnTest()
            resetBrokerTest()
            val canTest = _form.value.apiBaseUrl.isNotBlank() &&
                _form.value.queryToken.isNotBlank()
            runCatching { persistForm() }.onFailure {
                logBuffer.warn(
                    "setup-link persist failed",
                    mapOf("err" to (it.message ?: it::class.java.simpleName)),
                )
            }
            _toast.emit(ConfigToast.SetupImported(p.importedFields()))
            if (canTest) testConnection()
        }
    }

    /**
     * A redacted, paste-ready snapshot for triaging the "everything's green but
     * nothing's captured" trap — app/device/OS, config presence (not values),
     * and the live bridge/companion/collector state in one block. Tokens and
     * the full URL are never included.
     */
    fun buildDiagnostics(): String {
        val f = _form.value
        val apiHost = f.apiBaseUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: "(not set)"
        val bs = bridgeStatus.value
        return buildString {
            appendLine("— pitstop diagnostics —")
            appendLine("app: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("server: $apiHost · query token ${if (f.queryToken.isBlank()) "MISSING" else "set"}")
            appendLine("vehicle: ${f.vehicleSlug.ifBlank { "(not set)" }}")
            appendLine("collectors: ble=${f.bridgeBleEnabled} gps=${f.bridgeGpsEnabled} manualSync=${f.manualSyncOnly}")
            appendLine("auto-start: on=${f.bridgeAutoTrigger} wican-paired=${companionAssociated.value} ssids=${f.bridgeAutoTriggerSsids.size} motion=${f.bridgeAutoTriggerActivityEnabled}")
            appendLine("broker: connected=${mqttPublisher.isConnected()}")
            appendLine("bridge: phase=${bs.phase} engine=${bs.engineState} metrics=${bs.metricsActive}")
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
            val brokerUrl = secrets.settings.effectiveBrokerUrl()
            if (brokerUrl.isBlank()) return@launch
            runCatching {
                mqttPublisher.connect(
                    brokerUrl,
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

    fun stopBridge() {
        val ctx = getApplication<Application>()
        ctx.startService(com.pitstop.service.PitstopBridgeService.stopIntent(ctx))
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
