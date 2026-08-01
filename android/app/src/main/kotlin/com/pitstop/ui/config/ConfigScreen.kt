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
import com.pitstop.ui.components.PillState
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.SettingsSection
import com.pitstop.ui.components.StatusPill
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Settings — a single scrollable page of three collapsible accordion
 * groups, no drill-in navigation. The brand top bar stays put; tapping
 * Settings in the bottom nav lands here and system-back behaves like any
 * other top-level tab (no nested stack to pop).
 *
 *   Connection  → server URL, vehicle slug, MQTT broker creds
 *   Capture     → bridge collectors, auto-start, OBD device, WiCAN pairing
 *   App         → display units, logs, version + updates
 *
 * Accordion behaviour: exactly one group is open at a time, held in a
 * single [rememberSaveable] key so it survives rotation. Connection is
 * open on entry. Tapping a collapsed header opens it and folds the rest;
 * tapping the open header collapses it.
 *
 * Live bridge status (status pill, active metrics, last frame, OBD
 * freshness, offline buffer) and the Start/Stop bridge controls live on
 * Home — this view is purely configuration. All sections share the single
 * [ConfigViewModel] so the DataStore writes, secret-field rules, BLE scan
 * flow, and CDM association flow are unchanged. The Save bar sits once at
 * the bottom of the page.
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
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val connTest by viewModel.connTest.collectAsStateWithLifecycle()
    val brokerTest by viewModel.brokerTest.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

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

    // Which accordion group is expanded — exactly one (or none, if the
    // open one is tapped shut). Saved so rotation/process-death restores
    // the user's place. Default: all collapsed — the SetupWizard now owns
    // first-run, and each collapsed header self-reports its status, so the
    // landing view is the pinned auto-start strip + four status-bearing headers.
    var expandedGroup by rememberSaveable { mutableStateOf("") }
    val toggle: (String) -> Unit = { key ->
        expandedGroup = if (expandedGroup == key) "" else key
    }

    // CDM association consent dialog launcher — hosted at the screen level
    // so the IntentSender flow survives recomposition while the Capture
    // group is expanded. The OS hands the manager an IntentSender; we
    // launch it here. The result carries the resolved AssociationInfo
    // (API 33+) / BluetoothDevice (API 31–32) — extract the association id
    // + MAC and hand back to the VM.
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

    // Snackbar host lives at the screen level so toasts raised from any
    // group still show.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toast.collect { t ->
            val msg = when (t) {
                is ConfigToast.FlushedOk -> "Sent ${t.count} log${if (t.count == 1) "" else "s"}"
                ConfigToast.FlushedEmpty -> "Buffer empty"
                is ConfigToast.FlushedError -> "Flush failed: ${t.message}"
                is ConfigToast.UpdateUpToDate -> "You're on the latest (v${t.current})"
                is ConfigToast.UpdateAvailable ->
                    "v${t.latest} available — tap Download to install"
                is ConfigToast.UpdateCheckError -> "Update check failed: ${t.message}"
                is ConfigToast.UpdateDownloadStarted ->
                    "Downloading v${t.version}… installer will open when ready"
                ConfigToast.UpdateDownloadFailed ->
                    "Download failed — check Logs and try again"
                ConfigToast.CompanionPaired -> "WiCAN paired for reliable auto-start"
                ConfigToast.BridgePausedForPairing ->
                    "Stopping bridge so the WiCAN can be discovered…"
                ConfigToast.CompanionUnpaired -> "WiCAN unpaired"
                is ConfigToast.CompanionError -> "Pairing failed: ${t.message}"
                is ConfigToast.SetupImported ->
                    if (t.fields.isEmpty()) "Setup link imported"
                    else "Imported ${t.fields.joinToString(", ")}"
                ConfigToast.SetupLinkInvalid ->
                    "Clipboard isn't a pitstop setup link"
                ConfigToast.SetupLinkEmpty ->
                    "That setup link had nothing to import"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    pendingImport?.let { payload ->
        ImportConfirmDialog(
            payload = payload,
            onConfirm = { viewModel.confirmImport() },
            onCancel = { viewModel.cancelImport() },
        )
    }

    // Status subtitles rendered on each collapsed group header (IA move #2) —
    // every folded group self-reports so the accordion reads as a health
    // dashboard without opening anything.
    val (connState, connLabel) = when (val c = connTest) {
        is ConnTest.Ok -> PillState.Healthy to form.vehicleSlug.ifBlank { "Connected" }
        ConnTest.InProgress -> PillState.Connecting to "Testing…"
        ConnTest.BadUrl -> PillState.Offline to "Check URL"
        ConnTest.BadToken -> PillState.Offline to "Check token"
        is ConnTest.Unreachable -> PillState.Offline to "Unreachable"
        is ConnTest.ServerError -> PillState.Degraded to "Server ${c.code}"
        ConnTest.Idle ->
            if (form.apiBaseUrl.isBlank()) PillState.Neutral to "Not set"
            else PillState.Neutral to "Not tested"
    }
    val (autoState, autoLabel) = when (autoStartStatus.verdict) {
        AutoStartVerdict.Armed -> PillState.Healthy to "Armed"
        AutoStartVerdict.NeedsPairing -> PillState.Degraded to "Needs pairing"
        AutoStartVerdict.Off -> PillState.Neutral to "Off"
    }
    val capturingNothing = !form.bridgeBleEnabled && !form.bridgeGpsEnabled
    val captureLabel = when {
        form.bridgeBleEnabled && form.bridgeGpsEnabled -> "OBD + GPS"
        form.bridgeBleEnabled -> "OBD only"
        form.bridgeGpsEnabled -> "GPS only"
        else -> "Nothing"
    }
    val captureState = if (capturingNothing) PillState.Offline else PillState.Healthy
    val (appState, appLabel) =
        if (latestUpdate?.isNewer == true) PillState.Degraded to "Update ready"
        else PillState.Neutral to "Up to date"

    // No brand bar — the bottom nav already labels this screen, and
    // Settings is a long scroll that wants the vertical room.
    Scaffold(
        // Outer Scaffold (MainActivity) already consumed the system-bar
        // insets; re-applying them here leaves an empty status-bar-tall
        // band above the first setting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            // Pinned auto-start status strip (IA move #1) — always visible
            // above the accordion so the #1 "why didn't my drive log?" answer
            // is zero-tap.
            AutoStartStrip(
                status = autoStartStatus,
                pairing = pairingInProgress,
                onPair = { viewModel.pairCompanion() },
                onCopyDiagnostics = {
                    clipboard.setText(AnnotatedString(viewModel.buildDiagnostics()))
                    scope.launch { snackbarHostState.showSnackbar("Diagnostics copied") }
                },
            )

            // ── Connection ──────────────────────────────────────────
            CollapsibleGroup(
                title = "Connection",
                expanded = expandedGroup == GROUP_CONNECTION,
                onToggle = { toggle(GROUP_CONNECTION) },
                subtitle = connLabel,
                subtitleState = connState,
            ) {
                PitstopServerSection(
                    form = form,
                    connTest = connTest,
                    onTest = { viewModel.testConnection() },
                    onEdit = { viewModel.resetConnTest() },
                    onImportLink = {
                        val pasted = clipboard.getText()?.text
                        if (pasted.isNullOrBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Clipboard is empty")
                            }
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

            // ── Auto-start ──────────────────────────────────────────
            CollapsibleGroup(
                title = "Auto-start",
                expanded = expandedGroup == GROUP_AUTOSTART,
                onToggle = { toggle(GROUP_AUTOSTART) },
                subtitle = autoLabel,
                subtitleState = autoState,
            ) {
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
                        onUnpair = { viewModel.unpairCompanion() },
                    )
                }
            }

            // ── Devices & capture ───────────────────────────────────
            CollapsibleGroup(
                title = "Devices & capture",
                expanded = expandedGroup == GROUP_DEVICES,
                onToggle = { toggle(GROUP_DEVICES) },
                subtitle = captureLabel,
                subtitleState = captureState,
            ) {
                CaptureCollectorsSection(
                    bleEnabled = form.bridgeBleEnabled,
                    gpsEnabled = form.bridgeGpsEnabled,
                    manualSyncOnly = form.manualSyncOnly,
                    onBleEnabledChange = { v -> viewModel.setBridgeBleEnabled(v) },
                    onGpsEnabledChange = { v -> viewModel.setBridgeGpsEnabled(v) },
                    onManualSyncChange = { v -> viewModel.setManualSyncOnly(v) },
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

            // ── App ─────────────────────────────────────────────────
            CollapsibleGroup(
                title = "App",
                expanded = expandedGroup == GROUP_APP,
                onToggle = { toggle(GROUP_APP) },
                subtitle = appLabel,
                subtitleState = appState,
            ) {
                DisplaySection(
                    unitSystem = form.unitSystem,
                    onChange = { v -> viewModel.update { it.copy(unitSystem = v) } },
                )
                LogsSection(
                    verbose = form.verboseLogging,
                    buffered = bufferedCount,
                    lastFlushMs = lastFlushAt,
                    onVerboseChange = { v -> viewModel.update { it.copy(verboseLogging = v) } },
                    onFlush = { viewModel.flushLogsNow() },
                    onCopyDiagnostics = {
                        clipboard.setText(AnnotatedString(viewModel.buildDiagnostics()))
                    },
                )
                AppSection(
                    checking = checkingUpdate,
                    latestVersionFound = latestUpdate?.latestVersion,
                    latestIsNewer = latestUpdate?.isNewer == true,
                    hasApkAsset = latestUpdate?.apkUrl != null,
                    downloadState = downloadState,
                    onCheck = { viewModel.checkForUpdates() },
                    onDownload = { viewModel.downloadAndInstall() },
                )
            }

            // Single Save bar for the whole page.
            SaveBar(saved = form.saved, onSave = { viewModel.save() })
        }
    }
}

private const val GROUP_CONNECTION = "connection"
private const val GROUP_AUTOSTART = "autostart"
private const val GROUP_DEVICES = "devices"
private const val GROUP_APP = "app"

// ── Accordion group ─────────────────────────────────────────────────

/**
 * One collapsible accordion group. The header reads as a section divider
 * (caps title + a chevron that rotates 90° from pointing-right to
 * pointing-down when [expanded]); the body — a stack of [SettingsSection]s
 * supplied by the caller — is wrapped in [AnimatedVisibility]. State is
 * fully hoisted: the parent owns the single open-group key.
 */
@Composable
private fun CollapsibleGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    subtitleState: PillState = PillState.Neutral,
    content: @Composable () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "chevron-$title",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (expanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // While collapsed, the header self-reports its status so the folded
            // accordion reads as a health dashboard — no tap needed to see
            // "reachable" / "OBD+GPS" / "up to date". Hidden when expanded (the
            // body shows the real detail).
            if (!expanded && subtitle != null) {
                StatusPill(state = subtitleState, label = subtitle, compact = true)
                Spacer(Modifier.size(10.dp))
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
                Spacer(Modifier.size(8.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SaveBar(saved: Boolean, onSave: () -> Unit) {
    Spacer(Modifier.size(20.dp))
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save settings")
        }
    }
    Spacer(Modifier.size(28.dp))
}

// ── Capture: collectors + manual-sync ───────────────────────────────

@Composable
private fun CaptureCollectorsSection(
    bleEnabled: Boolean,
    gpsEnabled: Boolean,
    manualSyncOnly: Boolean,
    onBleEnabledChange: (Boolean) -> Unit,
    onGpsEnabledChange: (Boolean) -> Unit,
    onManualSyncChange: (Boolean) -> Unit,
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
                if (manualSyncOnly) "Uploads on demand — sync from History"
                else "Uploads live over cellular",
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
    }
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
    onUnpair: () -> Unit,
) {
    SettingsSection(
        title = "Reliable background auto-start",
        description = "Pairing the WiCAN as a companion device lets pitstop start " +
            "logging automatically the moment the dongle is in range — even from " +
            "the background. This is what makes auto-start reliable. Pair it from " +
            "the status strip at the top when auto-start needs it.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                state = if (associated) PillState.Healthy else PillState.Neutral,
                label = if (associated) "Associated" else "Not paired",
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (associated) "WiCAN companion active" else "Pair to enable reliable auto-start",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // The strip owns the Pair CTA (it only matters when auto-start needs
            // it); the group keeps Unpair for removing an existing association.
            OutlinedButton(onClick = onUnpair, enabled = associated && !pairing) {
                Text("Unpair")
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
    onPair: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    val (verdictState, verdictLabel) = when (status.verdict) {
        AutoStartVerdict.Armed -> PillState.Healthy to "Armed"
        AutoStartVerdict.NeedsPairing -> PillState.Degraded to "Needs pairing"
        AutoStartVerdict.Off -> PillState.Neutral to "Off"
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
                StatusPill(state = verdictState, label = verdictLabel)
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
                Text(
                    lastAutoStartLabel(status.lastAutoStartAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
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
        SignalState.Active -> PillState.Healthy to active
        SignalState.Idle -> PillState.Neutral to idle
        SignalState.Disabled -> PillState.Neutral to disabled
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
        StatusPill(state = pill, label = text, compact = true)
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
private fun extractCompanionResult(
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
            StatusPill(
                state = if (brokerConnected) PillState.Healthy else PillState.Offline,
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
        BrokerTest.Idle -> PillState.Neutral to "Not tested"
        BrokerTest.InProgress -> PillState.Connecting to "Testing…"
        BrokerTest.Ok -> PillState.Healthy to "Broker reachable"
        BrokerTest.BadUrl -> PillState.Offline to "Check the broker URL"
        BrokerTest.BadAuth -> PillState.Offline to "Rejected — check user / password"
        is BrokerTest.Unreachable -> PillState.Offline to "Can't reach the broker"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatusPill(state = state, label = label, compact = true)
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
        ConnTest.Idle -> PillState.Neutral to "Not tested"
        ConnTest.InProgress -> PillState.Connecting to "Testing…"
        is ConnTest.Ok -> PillState.Healthy to
            "Connected · ${c.vehicles.size} vehicle${if (c.vehicles.size == 1) "" else "s"}"
        ConnTest.BadUrl -> PillState.Offline to "Check the URL"
        ConnTest.BadToken -> PillState.Offline to "401 — check the Query token"
        is ConnTest.Unreachable -> PillState.Offline to "Can't reach the server"
        is ConnTest.ServerError -> PillState.Degraded to "Server error ${c.code}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(state = state, label = label, compact = true)
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
 * Display units toggle. Imperial / Metric segment row matching the web's
 * Settings → Display affordance. Mirrors the value into SettingsRepository
 * and the LiveScreen formatters re-render on next frame because they
 * read the same settings flow.
 */
@Composable
private fun DisplaySection(
    unitSystem: String,
    onChange: (String) -> Unit,
) {
    SettingsSection(
        title = "Display",
        description = "Imperial converts °C → °F, kPa → psi, g/s → lb/min, m → ft. Metric leaves canonical OBD units as-is.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnitChip(label = "Imperial", selected = unitSystem == "imperial", onClick = { onChange("imperial") })
            UnitChip(label = "Metric", selected = unitSystem == "metric", onClick = { onChange("metric") })
        }
    }
}

@Composable
private fun UnitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

// ── App / version ──────────────────────────────────────────────────

@Composable
private fun AppSection(
    checking: Boolean,
    latestVersionFound: String?,
    latestIsNewer: Boolean,
    hasApkAsset: Boolean,
    downloadState: com.pitstop.update.DownloadState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
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
            val inProgress = downloadState is com.pitstop.update.DownloadState.InProgress
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        downloadState is com.pitstop.update.DownloadState.Complete ->
                            "v$latestVersionFound downloaded — installer opening."
                        downloadState is com.pitstop.update.DownloadState.Failed ->
                            "Download failed: ${downloadState.reason}. Tap to retry."
                        hasApkAsset -> "v$latestVersionFound is ready to install."
                        else -> "v$latestVersionFound has no APK asset on the release page."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onDownload,
                    enabled = hasApkAsset && !inProgress,
                ) {
                    Text(if (inProgress) "Downloading…" else "Download & install")
                }
            }
            // In-app progress bar (UPDATE-1). System notification still
            // shows globally; this gives the user a clear "yes, bytes
            // are moving" indicator without leaving Settings.
            if (inProgress) {
                val s = downloadState as com.pitstop.update.DownloadState.InProgress
                Spacer(Modifier.size(8.dp))
                val fraction = if (s.totalBytes > 0) {
                    (s.bytesSoFar.toFloat() / s.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else null
                if (fraction != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 4.dp),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 4.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = if (s.totalBytes > 0)
                        "${humanBytes(s.bytesSoFar)} / ${humanBytes(s.totalBytes)}" +
                            (fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
                    else humanBytes(s.bytesSoFar),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun SecretField(
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
