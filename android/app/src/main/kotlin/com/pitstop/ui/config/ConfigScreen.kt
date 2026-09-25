package com.pitstop.ui.config

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.pitstop.ble.ScannedDevice
import com.pitstop.http.VehicleDto
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.SettingsSection
import com.pitstop.ui.components.StatusPill
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.car.CarTileCatalog.CarScreenKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Settings, in the standard Android settings pattern: a root list of
 * top-level rows — each with its live status as trailing content — that
 * push sub-screens. Back pops a sub-screen like any other stack.
 *
 *   Connection        → server URL + tokens, vehicle, MQTT broker
 *   Auto-start        → in-car triggers, companion pairing
 *   Devices & capture → collectors, sync mode, OBD dongle
 *   Android Auto      → head-unit tabs and tiles
 *   App               → units, notifications, logs, version
 *
 * The auto-start strip stays pinned on the root: it is the #1 "why didn't
 * my drive log?" answer and must be zero taps away. It also carries the
 * auto-save state — there is no Save button: edits persist on a debounce
 * (and on leaving the screen), failures surface in the snackbar.
 *
 * All routes share the single Activity-scoped [ConfigViewModel] (hoisted
 * here, passed down — a NavHost destination would otherwise mint its own),
 * so DataStore writes, secret-field rules, the BLE scan and the CDM
 * pairing flow are unchanged. Live bridge status + Start/Stop live on Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = hiltViewModel(),
    pendingSetupLinkFlow: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
) {
    // Consume a pitstop://setup?… deep link forwarded by MainActivity: import
    // it once, then clear so a recompose doesn't re-import.
    val pendingSetupLink = pendingSetupLinkFlow?.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSetupLink?.value) {
        val link = pendingSetupLink?.value
        if (!link.isNullOrBlank()) {
            viewModel.importSetupLink(link)
            pendingSetupLinkFlow?.value = null
        }
    }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val brokerConnected by viewModel.brokerConnected.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val companionAssociated by viewModel.companionAssociated.collectAsStateWithLifecycle()
    val pairingInProgress by viewModel.pairingInProgress.collectAsStateWithLifecycle()
    val autoStartStatus by viewModel.autoStartStatus.collectAsStateWithLifecycle()
    val bufferedCount by viewModel.bufferedCount.collectAsStateWithLifecycle()
    val lastFlushAt by viewModel.lastFlushAtMs.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.checkingUpdate.collectAsStateWithLifecycle()
    val latestUpdate by viewModel.latestUpdate.collectAsStateWithLifecycle()
    val connTest by viewModel.connTest.collectAsStateWithLifecycle()
    val brokerTest by viewModel.brokerTest.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()

    // Persist any unsaved edits when the user leaves Settings (tab switch /
    // back). Without this, the form lives in viewModelScope and swiping away
    // mid-edit silently discards the whole connection config. Gated on
    // formReady so a fast dispose before the form loads from disk can't write
    // blanks; the blank-secret guard in update() protects the tokens too.
    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.formReady.value && !viewModel.form.value.saved) viewModel.save()
        }
    }

    // CDM association consent dialog launcher — hosted at the screen level
    // so the IntentSender flow survives navigation between sub-screens.
    // The OS hands the manager an IntentSender; we launch it here. The
    // result carries the resolved AssociationInfo (API 33+) /
    // BluetoothDevice (API 31–32) — extract the association id + MAC and
    // hand back to the VM.
    val companionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        extractCompanionResult(result.resultCode, result.data)?.let { (id, mac) ->
            viewModel.onCompanionConfirmed(id, mac)
        } ?: viewModel.refreshCompanionState()
        // Restart the bridge if pairing had stopped it to free the BLE link
        // (no-op unless it did). Covers both the confirm and cancel paths.
        viewModel.restoreBridgeAfterPairingIfNeeded()
    }
    LaunchedEffect(Unit) {
        viewModel.companionIntentSender.collect { sender ->
            runCatching {
                companionLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                )
            }
        }
    }

    // One snackbar host, shared by every route's Scaffold, so toasts
    // raised anywhere show wherever the user is.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toast.collect { t ->
            snackbarHostState.showSnackbar(toastMessage(t))
        }
    }

    pendingImport?.let { payload ->
        ImportConfirmDialog(
            payload = payload,
            onConfirm = { viewModel.confirmImport() },
            onCancel = { viewModel.cancelImport() },
        )
    }

    // Android Auto only ever lists apps that Play installed. A sideloaded
    // build declares an identical CarAppService and is filtered out silently
    // — no error, no entry, nothing to debug — which cost this project weeks
    // before it was identified. Surfacing the installer here turns that into
    // a one-glance answer instead of a mystery.
    val ctxForInstaller = LocalContext.current
    val installedByPlay = remember {
        runCatching {
            ctxForInstaller.packageManager.getInstallSourceInfo(ctxForInstaller.packageName)
                .installingPackageName == "com.android.vending"
        }.getOrDefault(false)
    }
    val rows = settingsRows(
        form = form,
        connTest = connTest,
        autoStartStatus = autoStartStatus,
        latestIsNewer = latestUpdate?.isNewer == true,
        installedByPlay = installedByPlay,
    )
    val saveLabel = saveStatusLabel(saveStatus, form.saved)
    val copyDiagnostics: () -> Unit = {
        clipboard.setText(AnnotatedString(viewModel.buildDiagnostics()))
        scope.launch { snackbarHostState.showSnackbar("Diagnostics copied") }
    }

    NavHost(navController = nav, startDestination = ROUTE_ROOT) {
        composable(ROUTE_ROOT) {
            ConfigRootContent(
                rows = rows,
                autoStartStatus = autoStartStatus,
                pairing = pairingInProgress,
                saveLabel = saveLabel,
                snackbarHostState = snackbarHostState,
                onPair = { viewModel.pairCompanion() },
                onCopyDiagnostics = copyDiagnostics,
                onOpen = { route -> nav.navigate(route) },
            )
        }
        composable(ROUTE_CONNECTION) {
            SettingsSubScreen("Connection", saveLabel, snackbarHostState, onBack = { nav.popBackStack() }) {
                PitstopServerSection(
                    form = form,
                    connTest = connTest,
                    onTest = { viewModel.testConnection() },
                    onEdit = { viewModel.resetConnTest() },
                    onImportLink = {
                        val pasted = clipboard.getText()?.text
                        if (pasted.isNullOrBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
                        } else {
                            viewModel.importSetupLink(pasted)
                        }
                    },
                    update = { transform -> viewModel.update(transform) },
                )
                VehicleSection(
                    slug = form.vehicleSlug,
                    connTest = connTest,
                    onSlugChange = { v -> viewModel.update { it.copy(vehicleSlug = v) } },
                )
                MqttBrokerSection(
                    form = form,
                    brokerConnected = brokerConnected,
                    brokerTest = brokerTest,
                    onReconnect = { viewModel.reconnectBroker() },
                    onTestBroker = { viewModel.testBroker() },
                    onEditBroker = { viewModel.resetBrokerTest() },
                    update = { transform -> viewModel.update(transform) },
                )
            }
        }
        composable(ROUTE_AUTOSTART) {
            SettingsSubScreen("Auto-start", saveLabel, snackbarHostState, onBack = { nav.popBackStack() }) {
                AutoStartSection(
                    autoTrigger = form.bridgeAutoTrigger,
                    autoTriggerSsids = form.bridgeAutoTriggerSsids,
                    autoTriggerActivityEnabled = form.bridgeAutoTriggerActivityEnabled,
                    onAutoTriggerChange = { v -> viewModel.setBridgeAutoTrigger(v) },
                    onAutoTriggerSsidsChange = { v -> viewModel.setBridgeAutoTriggerSsids(v) },
                    onAutoTriggerActivityEnabledChange = { v ->
                        viewModel.setBridgeAutoTriggerActivityEnabled(v)
                    },
                    onShowSnackbar = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                )
                if (viewModel.companionPresenceSupported) {
                    CompanionPairingSection(
                        associated = companionAssociated,
                        pairing = pairingInProgress,
                        onPair = { viewModel.pairCompanion() },
                        onUnpair = { viewModel.unpairCompanion() },
                    )
                }
            }
        }
        composable(ROUTE_DEVICES) {
            SettingsSubScreen("Devices & capture", saveLabel, snackbarHostState, onBack = { nav.popBackStack() }) {
                CaptureCollectorsSection(
                    bleEnabled = form.bridgeBleEnabled,
                    gpsEnabled = form.bridgeGpsEnabled,
                    manualSyncOnly = form.manualSyncOnly,
                    uploadOnWifi = form.uploadOnWifi,
                    uploadOnWifiSsids = form.uploadOnWifiSsids,
                    canReadSsid = remember(form.uploadOnWifi) { viewModel.canReadWifiSsid() },
                    currentSsid = { viewModel.currentWifiSsid() },
                    onBleEnabledChange = { v -> viewModel.setBridgeBleEnabled(v) },
                    onGpsEnabledChange = { v -> viewModel.setBridgeGpsEnabled(v) },
                    onManualSyncChange = { v -> viewModel.setManualSyncOnly(v) },
                    onUploadOnWifiChange = { v -> viewModel.setUploadOnWifi(v) },
                    onUploadOnWifiSsidsChange = { v -> viewModel.setUploadOnWifiSsids(v) },
                )
                BleDeviceSection(
                    deviceName = form.bleDeviceName,
                    deviceMac = form.bleDeviceMac,
                    scanning = scanning,
                    scanResults = scanResults,
                    onToggleScan = {
                        if (scanning) viewModel.stopScan() else viewModel.startScan()
                    },
                    onPick = { viewModel.pickDevice(it) },
                )
            }
        }
        composable(ROUTE_ANDROID_AUTO) {
            SettingsSubScreen("Android Auto", saveLabel, snackbarHostState, onBack = { nav.popBackStack() }) {
                CarTilesSection(
                    tabs = form.aaTabs,
                    onTabsChange = { v -> viewModel.update { it.copy(aaTabs = v) } },
                    home = form.aaTilesHome,
                    engine = form.aaTilesEngine,
                    fuel = form.aaTilesFuel,
                    diag = form.aaTilesDiag,
                    onHomeChange = { v -> viewModel.update { it.copy(aaTilesHome = v) } },
                    onEngineChange = { v -> viewModel.update { it.copy(aaTilesEngine = v) } },
                    onFuelChange = { v -> viewModel.update { it.copy(aaTilesFuel = v) } },
                    onDiagChange = { v -> viewModel.update { it.copy(aaTilesDiag = v) } },
                )
            }
        }
        composable(ROUTE_APP) {
            SettingsSubScreen("App", saveLabel, snackbarHostState, onBack = { nav.popBackStack() }) {
                DisplaySection(
                    unitSystem = form.unitSystem,
                    onChange = { v -> viewModel.update { it.copy(unitSystem = v) } },
                )
                NotificationsSection(
                    dongleAlert = form.dongleAlertEnabled,
                    onDongleAlertChange = { v ->
                        viewModel.update { it.copy(dongleAlertEnabled = v) }
                    },
                )
                LogsSection(
                    verbose = form.verboseLogging,
                    buffered = bufferedCount,
                    lastFlushMs = lastFlushAt,
                    onVerboseChange = { v -> viewModel.update { it.copy(verboseLogging = v) } },
                    onFlush = { viewModel.flushLogsNow() },
                    onCopyDiagnostics = copyDiagnostics,
                )
                AppSection(
                    checking = checkingUpdate,
                    latestVersionFound = latestUpdate?.latestVersion,
                    latestIsNewer = latestUpdate?.isNewer == true,
                    onCheck = { viewModel.checkForUpdates() },
                )
            }
        }
    }
}

private const val ROUTE_ROOT = "root"
private const val ROUTE_CONNECTION = "connection"
private const val ROUTE_AUTOSTART = "autostart"
private const val ROUTE_DEVICES = "devices"
private const val ROUTE_ANDROID_AUTO = "android_auto"
private const val ROUTE_APP = "app"

private fun toastMessage(t: ConfigToast): String = when (t) {
    is ConfigToast.FlushedOk -> "Sent ${t.count} log${if (t.count == 1) "" else "s"}"
    ConfigToast.FlushedEmpty -> "Buffer empty"
    is ConfigToast.FlushedError -> "Couldn't send logs — try again later"
    is ConfigToast.UpdateUpToDate -> "You're on the latest (v${t.current})"
    is ConfigToast.UpdateAvailable -> "v${t.latest} available — update from Google Play"
    is ConfigToast.UpdateCheckError -> "Couldn't check for updates"
    ConfigToast.CompanionPaired -> "WiCAN paired for reliable auto-start"
    ConfigToast.BridgePausedForPairing -> "Stopping bridge so the WiCAN can be discovered…"
    ConfigToast.CompanionUnpaired -> "WiCAN unpaired"
    is ConfigToast.CompanionError -> "Pairing failed: ${t.message}"
    is ConfigToast.SetupImported ->
        if (t.fields.isEmpty()) "Setup link imported" else "Imported ${t.fields.joinToString(", ")}"
    ConfigToast.SetupLinkInvalid -> "Clipboard isn't a pitstop setup link"
    ConfigToast.SetupLinkEmpty -> "That setup link had nothing to import"
    ConfigToast.SaveFailed -> "Couldn't save settings — your last change may be lost"
}

/** "Saving…" while a write is pending or running; "All changes saved" once
 *  disk matches the form. Never blank — the line doubles as reassurance
 *  that there is nothing to press. */
internal fun saveStatusLabel(status: SaveStatus, formSaved: Boolean): String = when {
    status == SaveStatus.Failed -> "Couldn't save — will retry on the next change"
    status == SaveStatus.Saving || !formSaved -> "Saving…"
    else -> "All changes saved"
}

/** One top-level Settings row: title, one-line summary, live status. */
internal data class SettingsRow(
    val route: String,
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tone: PillTone,
    val status: String,
)

/**
 * The root list's rows with their status pills — every row self-reports,
 * so the root reads as a health dashboard without opening anything.
 */
internal fun settingsRows(
    form: ConfigFormState,
    connTest: ConnTest,
    autoStartStatus: AutoStartStatus,
    latestIsNewer: Boolean,
    installedByPlay: Boolean,
): List<SettingsRow> {
    val (connState, connLabel) = when (val c = connTest) {
        is ConnTest.Ok -> PillTone.Healthy to form.vehicleSlug.ifBlank { "Connected" }
        ConnTest.InProgress -> PillTone.Connecting to "Testing…"
        ConnTest.BadUrl -> PillTone.Offline to "Check URL"
        ConnTest.BadToken -> PillTone.Offline to "Check token"
        is ConnTest.Unreachable -> PillTone.Offline to "Unreachable"
        is ConnTest.ServerError -> PillTone.Degraded to "Server ${c.code}"
        ConnTest.Idle ->
            if (form.apiBaseUrl.isBlank()) PillTone.Neutral to "Not set"
            else PillTone.Neutral to "Not tested"
    }
    val (autoState, autoLabel) = when (autoStartStatus.verdict) {
        AutoStartVerdict.Armed -> PillTone.Healthy to "Armed"
        AutoStartVerdict.NeedsPairing -> PillTone.Degraded to "Needs pairing"
        AutoStartVerdict.Off -> PillTone.Neutral to "Off"
    }
    val captureLabel = when {
        form.bridgeBleEnabled && form.bridgeGpsEnabled -> "OBD + GPS"
        form.bridgeBleEnabled -> "OBD only"
        form.bridgeGpsEnabled -> "GPS only"
        else -> "Nothing"
    }
    val captureState =
        if (!form.bridgeBleEnabled && !form.bridgeGpsEnabled) PillTone.Offline else PillTone.Healthy
    val tileCount = form.aaTilesHome.size + form.aaTilesEngine.size +
        form.aaTilesFuel.size + form.aaTilesDiag.size
    val (aaState, aaLabel) = when {
        !installedByPlay -> PillTone.Degraded to "Not from Play"
        tileCount == 0 -> PillTone.Neutral to "Defaults"
        else -> PillTone.Neutral to "$tileCount tiles"
    }
    val (appState, appLabel) =
        if (latestIsNewer) PillTone.Degraded to "Update ready" else PillTone.Neutral to "Up to date"
    return listOf(
        SettingsRow(ROUTE_CONNECTION, "Connection", "Server, vehicle and MQTT broker",
            Icons.Outlined.Cloud, connState, connLabel),
        SettingsRow(ROUTE_AUTOSTART, "Auto-start", "Start logging when you get in the car",
            Icons.Outlined.DirectionsCar, autoState, autoLabel),
        SettingsRow(ROUTE_DEVICES, "Devices & capture", "OBD dongle, GPS and uploads",
            Icons.Outlined.Bluetooth, captureState, captureLabel),
        SettingsRow(ROUTE_ANDROID_AUTO, "Android Auto", "Car-screen tabs and tiles",
            Icons.Outlined.Dashboard, aaState, aaLabel),
        SettingsRow(ROUTE_APP, "App", "Units, notifications, logs and version",
            Icons.Outlined.Tune, appState, appLabel),
    )
}

/**
 * Stateless Settings root: the pinned auto-start strip, then one ListItem
 * per group. Split out so screenshot tests can render it from fixtures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigRootContent(
    rows: List<SettingsRow>,
    autoStartStatus: AutoStartStatus,
    pairing: Boolean,
    saveLabel: String,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onPair: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onOpen: (String) -> Unit = {},
) {
    Scaffold(
        // Outer Scaffold (MainActivity) already consumed the system-bar
        // insets; re-applying them here leaves an empty status-bar-tall
        // band above the first setting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { PitstopTopAppBar() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            AutoStartStrip(
                status = autoStartStatus,
                pairing = pairing,
                saveLabel = saveLabel,
                onPair = onPair,
                onCopyDiagnostics = onCopyDiagnostics,
            )
            for (row in rows) {
                ListItem(
                    headlineContent = { Text(row.title) },
                    supportingContent = { Text(row.summary) },
                    leadingContent = {
                        Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(tone = row.tone, label = row.status, compact = true, subject = row.title)
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable(onClickLabel = "Open ${row.title}") { onOpen(row.route) },
                )
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** A pushed Settings page: back-arrow bar, the save line, then sections. */
@Composable
private fun SettingsSubScreen(
    title: String,
    saveLabel: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { DetailTopAppBar(title = title, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            SaveStatusLine(saveLabel, Modifier.padding(horizontal = 20.dp))
            content()
        }
    }
}

@Composable
private fun SaveStatusLine(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (label.startsWith("Couldn't")) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

// ── Capture: collectors + manual-sync ───────────────────────────────

@Composable
private fun CaptureCollectorsSection(
    bleEnabled: Boolean,
    gpsEnabled: Boolean,
    manualSyncOnly: Boolean,
    uploadOnWifi: Boolean,
    uploadOnWifiSsids: List<String>,
    canReadSsid: Boolean,
    currentSsid: () -> String?,
    onBleEnabledChange: (Boolean) -> Unit,
    onGpsEnabledChange: (Boolean) -> Unit,
    onManualSyncChange: (Boolean) -> Unit,
    onUploadOnWifiChange: (Boolean) -> Unit,
    onUploadOnWifiSsidsChange: (String) -> Unit,
) {
    SettingsSection(
        title = "Collectors",
        description = "Pick what the bridge captures during a drive. Manual-sync " +
            "saves cellular data — drives stay queued until you sync.",
    ) {
        // Plain-language summary of the resulting mode, derived from the two
        // collector toggles — so the consequence of "both off" (capturing
        // nothing) reads at a glance instead of being a silent footgun.
        val nothing = !bleEnabled && !gpsEnabled
        val captureLabel = when {
            bleEnabled && gpsEnabled -> "Capturing OBD + GPS"
            bleEnabled -> "Capturing OBD only"
            gpsEnabled -> "Capturing GPS only"
            else -> "Not capturing anything"
        }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                captureLabel,
                style = MaterialTheme.typography.titleSmall,
                color = if (nothing) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
            Text(
                uploadSummary(manualSyncOnly, uploadOnWifi, uploadOnWifiSsids),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nothing) {
                Text(
                    "⚠ Both collectors are off — the bridge won't record anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.size(8.dp))
        // Per-collector toggles. Splitting BLE from GPS lets the user run
        // a GPS-only bridge (eg. while OBD comes through the WiCAN's own
        // WiFi path) or a BLE-only bridge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OBD via BLE", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (bleEnabled) "Polls the WiCAN over BLE for OBD frames"
                    else "Bridge skips BLE — OBD must reach the broker by another path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = bleEnabled, onCheckedChange = onBleEnabledChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GPS capture", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (gpsEnabled) "Publishes location fixes during drives"
                    else "Bridge does not request or publish GPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = gpsEnabled, onCheckedChange = onGpsEnabledChange)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Manual-sync mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (manualSyncOnly) "Captures locally; tap Sync to upload"
                    else "Streams every metric to the broker during drives",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = manualSyncOnly, onCheckedChange = onManualSyncChange)
        }
        // Auto-upload on WiFi. Sits under manual-sync because it is the
        // automation of manual-sync's one manual step — and because it
        // deliberately overrides it: with this on, a queued drive uploads
        // itself the moment the phone is back on a network the user named.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-upload on WiFi", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (uploadOnWifi) {
                        if (uploadOnWifiSsids.isEmpty()) {
                            "Uploads queued drives on any unmetered WiFi"
                        } else {
                            "Uploads queued drives on ${uploadOnWifiSsids.joinToString(", ")}"
                        }
                    } else "Off — queued drives wait for a manual sync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = uploadOnWifi, onCheckedChange = onUploadOnWifiChange)
        }
        if (uploadOnWifi) {
            // Persists on every keystroke via the setter, like the
            // auto-start SSID field — no Save button required.
            val ssidText = remember(uploadOnWifiSsids) {
                mutableStateOf(uploadOnWifiSsids.joinToString(", "))
            }
            OutlinedTextField(
                value = ssidText.value,
                onValueChange = { v ->
                    ssidText.value = v
                    onUploadOnWifiSsidsChange(v)
                },
                label = { Text("Upload WiFi SSIDs") },
                placeholder = { Text("e.g. HomeNetwork") },
                supportingText = {
                    Text(
                        if (!canReadSsid && uploadOnWifiSsids.isNotEmpty()) {
                            "Comma-separated. ⚠ Location permission is off, so the " +
                                "network name can't be read and none of these will match."
                        } else {
                            "Comma-separated. Leave blank to upload on any unmetered WiFi."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!canReadSsid && uploadOnWifiSsids.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Typing an SSID from memory is where this feature quietly
            // fails — one character off and it never uploads. Offer the
            // name the phone is actually associated with.
            val here = remember(uploadOnWifi) { currentSsid() }
            if (here != null && !uploadOnWifiSsids.any { it.equals(here, ignoreCase = true) }) {
                TextButton(
                    onClick = {
                        val merged = (uploadOnWifiSsids + here).joinToString(", ")
                        ssidText.value = merged
                        onUploadOnWifiSsidsChange(merged)
                    },
                ) {
                    Text("Add current network ($here)")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One line describing where a finished drive actually goes, given the two
 *  sync switches. Kept out of the composable so the wording is testable. */
internal fun uploadSummary(
    manualSyncOnly: Boolean,
    uploadOnWifi: Boolean,
    uploadOnWifiSsids: List<String>,
): String = when {
    uploadOnWifi && uploadOnWifiSsids.isEmpty() -> "Uploads on any unmetered WiFi"
    uploadOnWifi -> "Uploads on ${uploadOnWifiSsids.joinToString(", ")}"
    manualSyncOnly -> "Uploads on demand — sync from History"
    else -> "Uploads live over cellular"
}

// ── Capture: auto-start ─────────────────────────────────────────────

@Composable
private fun AutoStartSection(
    autoTrigger: Boolean,
    autoTriggerSsids: List<String>,
    autoTriggerActivityEnabled: Boolean,
    onAutoTriggerChange: (Boolean) -> Unit,
    onAutoTriggerSsidsChange: (String) -> Unit,
    onAutoTriggerActivityEnabledChange: (Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    SettingsSection(
        title = "Auto-start",
        description = "Starts the bridge automatically when the phone detects " +
            "you're in the car (WiFi SSID, Android Auto, or paired-car Bluetooth).",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-start in car", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (autoTrigger) {
                        "Watches WiFi SSID, Android Auto, and paired-car Bluetooth"
                    } else "Off — start the bridge from Home",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoTrigger, onCheckedChange = onAutoTriggerChange)
        }
        // The "won't start from the background without pairing" warning now
        // lives in the AutoStartStatusCard above (its "Needs pairing" verdict +
        // Pair button), so it isn't duplicated here.
        if (autoTrigger) {
            // Editable comma-separated list. Empty = the WiFi signal is
            // disabled (AA + BT still fire). Persists on every keystroke
            // via the setter — no Save button required.
            val ssidText = remember(autoTriggerSsids) {
                mutableStateOf(autoTriggerSsids.joinToString(", "))
            }
            OutlinedTextField(
                value = ssidText.value,
                onValueChange = { v ->
                    ssidText.value = v
                    onAutoTriggerSsidsChange(v)
                },
                label = { Text("Car WiFi SSIDs") },
                placeholder = { Text("e.g. MyCarHotspot") },
                supportingText = {
                    Text(
                        "Comma-separated. Reads associated WiFi name; needs Location permission.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── 4th signal: Activity Recognition (opt-in) ────────────────
            // Fires within ~5–15 s of vehicle motion, before WiFi can
            // hand off from the home network to the car hotspot. Off by
            // default — flipping this on triggers the runtime permission
            // prompt. On denial we surface a snackbar and leave the
            // toggle off (the setter is only called on grant).
            val ctx = LocalContext.current
            val permGranted = remember(autoTriggerActivityEnabled) {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.ACTIVITY_RECOGNITION,
                    ) == PackageManager.PERMISSION_GRANTED
            }
            val permLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    onAutoTriggerActivityEnabledChange(true)
                } else {
                    onShowSnackbar(
                        "Permission denied — falling back to WiFi/Bluetooth signals",
                    )
                    onAutoTriggerActivityEnabledChange(false)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Use motion detection for faster start",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Detects vehicle motion within ~15 s, independent of WiFi handoff",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoTriggerActivityEnabled && permGranted,
                    onCheckedChange = { wanted ->
                        if (!wanted) {
                            onAutoTriggerActivityEnabledChange(false)
                            return@Switch
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !permGranted) {
                            permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        } else {
                            onAutoTriggerActivityEnabledChange(true)
                        }
                    },
                )
            }
        }
    }
}

// ── Reliable background auto-start (CompanionDeviceManager) ──────────

@Composable
private fun CompanionPairingSection(
    associated: Boolean,
    pairing: Boolean,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
) {
    SettingsSection(
        title = "Reliable background auto-start",
        description = "Pairing the WiCAN as a companion device lets pitstop start " +
            "logging automatically the moment the dongle is in range — even from " +
            "the background. This is what makes auto-start reliable.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(tone = if (associated) PillTone.Healthy else PillTone.Neutral,
                label = if (associated) "Associated" else "Not paired",
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (associated) "WiCAN companion active" else "Pair to enable reliable auto-start",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (associated) {
                OutlinedButton(onClick = onUnpair, enabled = !pairing) { Text("Unpair") }
            } else {
                Button(onClick = onPair, enabled = !pairing) { Text("Pair") }
            }
        }
        if (pairing) {
            Text(
                "Freeing the dongle for pairing — stopping the live link so it " +
                    "starts advertising, then the system pairing dialog will appear…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Auto-start status strip (task #18 + IA move #1) ─────────────────

/**
 * The always-pinned strip above the accordion that answers "will a drive start
 * on its own, and why isn't it?" at zero taps — the #1 recurring reason to open
 * Settings. Leads with an armed / needs-pairing / off verdict. When it needs
 * pairing it opens up: the Pair CTA + a live ledger of each in-car signal so a
 * missed auto-start is diagnosable in place. When armed (or off) it collapses to
 * one quiet line so the landing view stays short. A "Copy diagnostics" shortcut
 * sits with the verdict so a red state is one tap from a shareable snapshot.
 */
@Composable
private fun AutoStartStrip(
    status: AutoStartStatus,
    pairing: Boolean,
    saveLabel: String,
    onPair: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    val (verdictState, verdictLabel) = when (status.verdict) {
        AutoStartVerdict.Armed -> PillTone.Healthy to "Armed"
        AutoStartVerdict.NeedsPairing -> PillTone.Degraded to "Needs pairing"
        AutoStartVerdict.Off -> PillTone.Neutral to "Off"
    }
    val needsPairing = status.verdict == AutoStartVerdict.NeedsPairing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusPill(tone = verdictState, label = verdictLabel, subject = "Auto-start")
                Text(
                    text = when (status.verdict) {
                        AutoStartVerdict.Armed ->
                            if (status.inCarNow) "In the car now — ready to log."
                            else "Auto-start armed."
                        AutoStartVerdict.NeedsPairing ->
                            "Pair the WiCAN so drives can start from the background."
                        AutoStartVerdict.Off ->
                            "Off — start drives manually from Home."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            if (needsPairing) {
                Button(onClick = onPair, enabled = !pairing) {
                    if (pairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Pair WiCAN")
                }
                // Live signal ledger — shown only when there's a problem to
                // diagnose (needs-pairing); when armed/off it stays collapsed.
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(
                    "LIVE SIGNALS",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SignalRow(
                    "In car right now",
                    if (status.inCarNow) SignalState.Active else SignalState.Idle,
                    active = "Yes", idle = "No", disabled = "No",
                )
                SignalRow(
                    "Car WiFi",
                    status.wifi,
                    active = "Connected", idle = "No match", disabled = "No SSIDs set",
                )
                SignalRow(
                    "Android Auto / car Bluetooth",
                    status.projection,
                    active = "Connected", idle = "Not connected", disabled = "Not connected",
                )
                SignalRow(
                    "Motion (in vehicle)",
                    status.motion,
                    active = "Driving", idle = "Still", disabled = "Off",
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        lastAutoStartLabel(status.lastAutoStartAtMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SaveStatusLine(saveLabel)
                }
                TextButton(onClick = onCopyDiagnostics) { Text("Copy diagnostics") }
            }
        }
    }
}

/** One signal ledger row: label on the left, a compact state pill on the right. */
@Composable
private fun SignalRow(
    label: String,
    state: SignalState,
    active: String,
    idle: String,
    disabled: String,
) {
    val (pill, text) = when (state) {
        SignalState.Active -> PillTone.Healthy to active
        SignalState.Idle -> PillTone.Neutral to idle
        SignalState.Disabled -> PillTone.Neutral to disabled
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == SignalState.Disabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        StatusPill(tone = pill, label = text, compact = true)
    }
}

/** "Last auto-started N min ago" / "Never auto-started yet". Non-live (computed
 *  at composition) — good enough for a status footer. */
private fun lastAutoStartLabel(atMs: Long): String {
    if (atMs <= 0L) return "Never auto-started yet"
    val diff = System.currentTimeMillis() - atMs
    val rel = when {
        diff < 0L -> "just now"
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} hr ago"
        else -> "${diff / 86_400_000L} d ago"
    }
    return "Last auto-started $rel"
}

/**
 * Pull the resolved CDM association id (+ MAC) out of the consent-dialog
 * result. API 33+ returns an [android.companion.AssociationInfo]; API 31–32
 * returns a [android.bluetooth.BluetoothDevice]. We surface the id where
 * available (33+) and the MAC for the legacy observe-by-address path.
 * Returns null on cancel / unrecognised payload — the caller just refreshes
 * the association list in that case.
 */
internal fun extractCompanionResult(
    resultCode: Int,
    data: android.content.Intent?,
): Pair<Int, String?>? {
    if (resultCode != android.app.Activity.RESULT_OK || data == null) return null
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = data.getParcelableExtra(
                android.companion.CompanionDeviceManager.EXTRA_ASSOCIATION,
                android.companion.AssociationInfo::class.java,
            )
            if (info != null) {
                return@runCatching info.id to info.deviceMacAddress?.toString()
            }
        }
        // Legacy (API 31–32) — payload is a BluetoothDevice. We don't get an
        // integer id back here, but onAssociationCreated / the manager's
        // myAssociations lookup persists it; pass the MAC through with a
        // sentinel id so persist-and-observe can resolve the address.
        @Suppress("DEPRECATION")
        val device: android.bluetooth.BluetoothDevice? =
            data.getParcelableExtra(android.companion.CompanionDeviceManager.EXTRA_DEVICE)
        device?.let { COMPANION_ID_UNRESOLVED to it.address }
    }.getOrNull()
}

/** Sentinel returned on the API 31–32 path where the consent result yields a
 *  BluetoothDevice (MAC) but no integer association id. The manager resolves
 *  the real id from myAssociations by MAC before observing. */
private const val COMPANION_ID_UNRESOLVED: Int = -1

// ── BLE device ─────────────────────────────────────────────────────

@Composable
private fun BleDeviceSection(
    deviceName: String?,
    deviceMac: String?,
    scanning: Boolean,
    scanResults: List<ScannedDevice>,
    onToggleScan: () -> Unit,
    onPick: (ScannedDevice) -> Unit,
) {
    SettingsSection(
        title = "OBD device",
        description = "WiCAN-Pro pairs over BLE. Power the dongle by plugging it in, then Scan and pick.",
    ) {
        Text(
            text = deviceName ?: deviceMac ?: "(none picked)",
            style = MaterialTheme.typography.titleMedium,
        )
        deviceMac?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onToggleScan) {
            Text(if (scanning) "Stop scan" else "Scan for devices")
        }
        if (scanResults.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            ) {
                items(scanResults, key = { it.mac }) { device ->
                    ScanRow(device = device, onClick = { onPick(device) })
                }
            }
        }
    }
}

// ── MQTT broker ────────────────────────────────────────────────────

@Composable
private fun MqttBrokerSection(
    form: ConfigFormState,
    brokerConnected: Boolean,
    brokerTest: BrokerTest,
    onReconnect: () -> Unit,
    onTestBroker: () -> Unit,
    onEditBroker: () -> Unit,
    update: ((ConfigFormState) -> ConfigFormState) -> Unit,
) {
    SettingsSection(
        title = "MQTT broker",
        description = "Live telemetry transport for the OBD bridge.",
    ) {
        // MQTT is never used in manual-sync mode (drives ship over HTTP), so
        // hide the whole credential surface + its scary "Offline" pill.
        if (form.manualSyncOnly) {
            Text(
                "Not used in manual-sync mode — drives upload over HTTP. " +
                    "Turn off manual-sync to stream live telemetry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsSection
        }
        // Compact connection-state hint only — the live "published N"
        // metrics readout moved to Home (this is config, not status).
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(tone = if (brokerConnected) PillTone.Healthy else PillTone.Offline,
                label = if (brokerConnected) "Connected" else "Offline",
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onReconnect) {
                Text(if (brokerConnected) "Reconnect" else "Connect")
            }
        }
        OutlinedTextField(
            value = form.brokerUrl,
            onValueChange = { v -> onEditBroker(); update { it.copy(brokerUrl = v) } },
            label = { Text("Broker URL") },
            placeholder = { Text("tcp://10.0.0.x:1883") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val derivedBroker = form.apiBaseUrl.toHttpUrlOrNull()?.host?.let { "tcp://$it:1883" }
        if (form.brokerUrl.isBlank() && derivedBroker != null) {
            Text(
                "Leave blank to use $derivedBroker (your Pitstop server host).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = form.mqttUser,
            onValueChange = { v -> onEditBroker(); update { it.copy(mqttUser = v) } },
            label = { Text("MQTT username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            label = "MQTT password",
            value = form.mqttPassword,
            onValueChange = { v -> onEditBroker(); update { it.copy(mqttPassword = v) } },
        )
        BrokerStatusRow(brokerTest = brokerTest, onTest = onTestBroker)
    }
}

/** Status chip (reuses [StatusPill]) + "Test broker" button — a throwaway MQTT
 *  connect that validates the broker URL + credentials without touching the
 *  live bridge connection. Mirrors [ConnStatusRow] for the server. */
@Composable
private fun BrokerStatusRow(brokerTest: BrokerTest, onTest: () -> Unit) {
    val (state, label) = when (val b = brokerTest) {
        BrokerTest.Idle -> PillTone.Neutral to "Not tested"
        BrokerTest.InProgress -> PillTone.Connecting to "Testing…"
        BrokerTest.Ok -> PillTone.Healthy to "Broker reachable"
        BrokerTest.BadUrl -> PillTone.Offline to "Check the broker URL"
        BrokerTest.BadAuth -> PillTone.Offline to "Rejected — check user / password"
        is BrokerTest.Unreachable -> PillTone.Offline to "Can't reach the broker"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatusPill(tone = state, label = label, compact = true)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onTest, enabled = brokerTest != BrokerTest.InProgress) {
            if (brokerTest == BrokerTest.InProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Test broker")
        }
    }
}

// ── Vehicle ────────────────────────────────────────────────────────

@Composable
private fun VehicleSection(slug: String, connTest: ConnTest, onSlugChange: (String) -> Unit) {
    SettingsSection(
        title = "Vehicle",
        description = "Which vehicle this phone logs as. Test the connection above to load the list from your server.",
    ) {
        val vehicles = (connTest as? ConnTest.Ok)?.vehicles.orEmpty()
        if (vehicles.isNotEmpty()) {
            // Picker fed by the test-connection /vehicles response — a mistyped
            // slug is impossible once the server is reachable.
            Column {
                vehicles.forEach { v ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSlugChange(v.slug) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = slug == v.slug, onClick = { onSlugChange(v.slug) })
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(vehicleLabel(v), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                v.slug,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            // Fallback until a successful test: keep manual entry, but nudge
            // toward the zero-typo picker.
            OutlinedTextField(
                value = slug,
                onValueChange = onSlugChange,
                label = { Text("Vehicle slug") },
                placeholder = { Text("e.g. pilot19") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tip: tap Test connection above to pick from your server's vehicles instead of typing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun vehicleLabel(v: VehicleDto): String {
    val label = listOfNotNull(v.year?.toString(), v.make, v.model).joinToString(" ")
    return label.ifBlank { v.name }
}

/** Status chip (reuses [StatusPill]) + Test-connection button, sitting under
 *  the token fields so a 401/unreachable lands next to the cause. */
@Composable
private fun ConnStatusRow(connTest: ConnTest, onTest: () -> Unit) {
    val (state, label) = when (val c = connTest) {
        ConnTest.Idle -> PillTone.Neutral to "Not tested"
        ConnTest.InProgress -> PillTone.Connecting to "Testing…"
        is ConnTest.Ok -> PillTone.Healthy to
            "Connected · ${c.vehicles.size} vehicle${if (c.vehicles.size == 1) "" else "s"}"
        ConnTest.BadUrl -> PillTone.Offline to "Check the URL"
        ConnTest.BadToken -> PillTone.Offline to "401 — check the Query token"
        is ConnTest.Unreachable -> PillTone.Offline to "Can't reach the server"
        is ConnTest.ServerError -> PillTone.Degraded to "Server error ${c.code}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(tone = state, label = label, compact = true)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onTest, enabled = connTest != ConnTest.InProgress) {
            if (connTest == ConnTest.InProgress) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Test connection")
            }
        }
    }
}

// ── Pitstop server ─────────────────────────────────────────────────

@Composable
private fun PitstopServerSection(
    form: ConfigFormState,
    connTest: ConnTest,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onImportLink: () -> Unit,
    update: ((ConfigFormState) -> ConfigFormState) -> Unit,
) {
    SettingsSection(
        title = "Pitstop server",
        description = "Reads use the Query token, writes use the Ingest token. Get both from ~/.pitstop-deploy-secrets.txt on the host.",
    ) {
        // One-tap credential handoff: paste a pitstop://setup?… link and it
        // fills the URL + both tokens (+ vehicle/MQTT if present) at once, so
        // nobody has to thumb-type two 40-char tokens on a phone.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Have a setup link?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onImportLink) {
                Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Paste link")
            }
        }
        val urlError = form.apiBaseUrl.isNotBlank() && form.apiBaseUrl.toHttpUrlOrNull() == null
        val urlSupport: (@Composable () -> Unit)? =
            if (urlError) { { Text("Enter a full URL like http://10.0.0.x:8080") } } else null
        OutlinedTextField(
            value = form.apiBaseUrl,
            onValueChange = { v -> update { it.copy(apiBaseUrl = v) }; onEdit() },
            label = { Text("API base URL") },
            placeholder = { Text("http://10.0.0.x:8080") },
            isError = urlError,
            supportingText = urlSupport,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            label = "Ingest token",
            value = form.ingestToken,
            onValueChange = { v -> update { it.copy(ingestToken = v) }; onEdit() },
        )
        SecretField(
            label = "Query token",
            value = form.queryToken,
            onValueChange = { v -> update { it.copy(queryToken = v) }; onEdit() },
        )
        ConnStatusRow(connTest = connTest, onTest = onTest)
    }
}

// ── Logs ───────────────────────────────────────────────────────────

@Composable
private fun NotificationsSection(
    dongleAlert: Boolean,
    onDongleAlertChange: (Boolean) -> Unit,
) {
    SettingsSection(
        title = "Notifications",
        description = "Alerts about the capture hardware. The ongoing " +
            "recording notification is required by Android and can't be " +
            "turned off here.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Dongle stopped responding", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Alerts on the phone and the car screen when engine data " +
                        "stops while you're still moving — the dongle has hung " +
                        "and needs unplugging. Only fires when GPS confirms " +
                        "you're driving, so parking never triggers it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dongleAlert, onCheckedChange = onDongleAlertChange)
        }
        if (!dongleAlert) {
            Spacer(Modifier.size(6.dp))
            Text(
                "Drives are still kept whole through a stall — that isn't a " +
                    "notification setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogsSection(
    verbose: Boolean,
    buffered: Int,
    lastFlushMs: Long?,
    onVerboseChange: (Boolean) -> Unit,
    onFlush: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    SettingsSection(title = "Logs") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Verbose logging", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Includes debug-level entries when shipping logs to the depot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = verbose, onCheckedChange = onVerboseChange)
        }
        Text(
            "Buffered: $buffered  ·  Last flush: ${formatRelative(lastFlushMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onFlush, modifier = Modifier.fillMaxWidth()) {
            Text("Send logs now")
        }
        TextButton(onClick = onCopyDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Copy diagnostics")
        }
    }
}

// ── Display ────────────────────────────────────────────────────────

/**
 * Display units. One segmented Imperial | Metric choice that applies
 * EVERYWHERE — Live tiles, History, trip and fillup detail, the Fuel form,
 * charts and the Android Auto grid all read it through LocalUnitSystem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySection(
    unitSystem: String,
    onChange: (String) -> Unit,
) {
    SettingsSection(
        title = "Units",
        description = "Applies everywhere in the app and on the car screen. " +
            "Imperial: mi, mph, gal, mpg, °F, psi. Metric: km, km/h, L, L/100km, °C, kPa.",
    ) {
        val options = listOf("imperial" to "Imperial", "metric" to "Metric")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = unitSystem == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    label = { Text(label) },
                )
            }
        }
    }
}

/**
 * Which tabs the head unit shows, and which metrics fill each one.
 *
 * Updates are in-place refreshes (value in GridItem text, not title), so a
 * tab can hold the full grid without the host resetting scroll or burning
 * its template quota — see CarTileCatalog.DEFAULT_HOME. Pickers are shown
 * only for tabs that are actually enabled: configuring a hidden tab was
 * the most common "why didn't my change show up?".
 */
@Composable
private fun CarTilesSection(
    tabs: List<String>,
    onTabsChange: (List<String>) -> Unit,
    home: List<String>,
    engine: List<String>,
    fuel: List<String>,
    diag: List<String>,
    onHomeChange: (List<String>) -> Unit,
    onEngineChange: (List<String>) -> Unit,
    onFuelChange: (List<String>) -> Unit,
    onDiagChange: (List<String>) -> Unit,
) {
    val effectiveTabs = tabs.ifEmpty { CarScreenKind.DEFAULT_TABS }
    SettingsSection(
        title = "Android Auto tabs",
        description = "Up to ${com.pitstop.car.CarTileCatalog.MAX_TILES} tiles per tab; " +
            "values update in place about every 2 s.",
    ) {
        // Which screens occupy the four tabs. There are more screens than
        // tabs on purpose — the head unit takes at most four, so the SET is
        // the user's choice rather than a fixed layout.
        CarTabPicker(selected = tabs, onChange = onTabsChange)
    }
    data class TabTiles(
        val kind: com.pitstop.car.CarTileCatalog.CarScreenKind,
        val stored: List<String>,
        val onChange: (List<String>) -> Unit,
    )
    val pickers = listOf(
        TabTiles(CarScreenKind.Drive, home, onHomeChange),
        TabTiles(CarScreenKind.Engine, engine, onEngineChange),
        TabTiles(CarScreenKind.Fuel, fuel, onFuelChange),
        TabTiles(CarScreenKind.Diagnostics, diag, onDiagChange),
    ).filter { it.kind.id in effectiveTabs }
    for (p in pickers) {
        SettingsSection(title = "${p.kind.title} tab") {
            CarTilePicker(
                selected = p.stored.ifEmpty { p.kind.defaults.orEmpty() },
                onChange = p.onChange,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CarTabPicker(
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val max = com.pitstop.car.CarTileCatalog.CarScreenKind.MAX_TABS
    val effective = selected.ifEmpty {
        com.pitstop.car.CarTileCatalog.CarScreenKind.DEFAULT_TABS
    }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tabs on the head unit",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${effective.size} / $max",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(6.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (kind in com.pitstop.car.CarTileCatalog.CarScreenKind.entries) {
                val on = kind.id in effective
                androidx.compose.material3.FilterChip(
                    selected = on,
                    enabled = on || effective.size < max,
                    onClick = {
                        onChange(if (on) effective - kind.id else effective + kind.id)
                    },
                    label = {
                        Text(kind.title, style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
}

/**
 * One tab's tiles: the chosen ones as a numbered list in car order (with
 * move up / down and remove), then the rest as chips to add. The order
 * here IS the grid order on the head unit, which a set of toggled chips
 * could never express.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CarTilePicker(
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val max = com.pitstop.car.CarTileCatalog.MAX_TILES
    val catalog = com.pitstop.car.CarTileCatalog
    val chosen = selected.mapNotNull { catalog.byKey(it) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${chosen.size} / $max tiles",
            style = MaterialTheme.typography.labelMedium,
            color = if (chosen.size > max) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        chosen.forEachIndexed { i, spec ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp),
                )
                Text(spec.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                val keys = chosen.map { it.key }
                IconButton(
                    onClick = { onChange(keys.toMutableList().apply { add(i - 1, removeAt(i)) }) },
                    enabled = i > 0,
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${spec.label} up") }
                IconButton(
                    onClick = { onChange(keys.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                    enabled = i < chosen.lastIndex,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${spec.label} down") }
                IconButton(onClick = { onChange(keys - spec.key) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${spec.label}")
                }
            }
        }
        val rest = catalog.ALL.filter { spec -> chosen.none { it.key == spec.key } }
        if (rest.isNotEmpty()) {
            Text(
                if (chosen.size >= max) "Remove a tile to add another" else "Add a tile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (spec in rest) {
                    androidx.compose.material3.AssistChip(
                        // Selecting is capped; removing (above) always works,
                        // so the user can never get stuck at the limit.
                        enabled = chosen.size < max,
                        onClick = { onChange(chosen.map { it.key } + spec.key) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(spec.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

// ── App / version ──────────────────────────────────────────────────

@Composable
private fun AppSection(
    checking: Boolean,
    latestVersionFound: String?,
    latestIsNewer: Boolean,
    onCheck: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingsSection(title = "Version") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Version", style = MaterialTheme.typography.titleSmall)
                Text(
                    "v${com.pitstop.BuildConfig.VERSION_NAME}  ·  build ${com.pitstop.BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Pr0zak/pitstop"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(
                        "github.com/Pr0zak/pitstop",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (latestVersionFound != null) {
                    Text(
                        if (latestIsNewer) "Latest on GitHub: v$latestVersionFound (newer)"
                        else "Latest on GitHub: v$latestVersionFound",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (latestIsNewer) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = onCheck,
                enabled = !checking,
            ) {
                if (checking) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Check now")
                }
            }
        }
        if (latestIsNewer && latestVersionFound != null) {
            Spacer(Modifier.size(8.dp))
            // Updates arrive through Google Play. The app used to download
            // the APK from the GitHub release and open the system
            // installer, which needed REQUEST_INSTALL_PACKAGES — a
            // permission Play does not allow an app to ship for the
            // purpose of updating itself. Both the permission and the
            // installer are gone; this hands off to the store listing.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v$latestVersionFound is available. Updates install through Google Play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { com.pitstop.update.PlayStore.open(ctx) },
                ) {
                    Text("Open Play Store")
                }
            }
        }
    }
}

// ── Shared bits ────────────────────────────────────────────────────

private fun formatRelative(tsMs: Long?): String {
    if (tsMs == null) return "never"
    val delta = kotlin.math.max(0L, System.currentTimeMillis() - tsMs)
    return when {
        delta < 1_500 -> "just now"
        delta < 60_000 -> "${delta / 1000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        else -> "${delta / 3_600_000}h ago"
    }
}

private fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / 1024.0 / 1024.0)
}

@Composable
internal fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScanRow(device: ScannedDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(device.name ?: "(unknown)", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${device.mac}  •  ${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) { Text("Pick") }
    }
}
