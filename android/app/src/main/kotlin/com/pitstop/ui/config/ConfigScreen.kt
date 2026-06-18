package com.pitstop.ui.config

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.pitstop.ble.ScannedDevice
import com.pitstop.ui.components.PillState
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.SettingsSection
import com.pitstop.ui.components.StatusPill

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
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val brokerConnected by viewModel.brokerConnected.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val companionAssociated by viewModel.companionAssociated.collectAsStateWithLifecycle()
    val bufferedCount by viewModel.bufferedCount.collectAsStateWithLifecycle()
    val lastFlushAt by viewModel.lastFlushAtMs.collectAsStateWithLifecycle()
    val checkingUpdate by viewModel.checkingUpdate.collectAsStateWithLifecycle()
    val latestUpdate by viewModel.latestUpdate.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Which accordion group is expanded — exactly one (or none, if the
    // open one is tapped shut). Saved so rotation/process-death restores
    // the user's place. Default: Connection.
    var expandedGroup by rememberSaveable { mutableStateOf(GROUP_CONNECTION) }
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
                ConfigToast.CompanionUnpaired -> "WiCAN unpaired"
                is ConfigToast.CompanionError -> "Pairing failed: ${t.message}"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
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
            // ── Connection ──────────────────────────────────────────
            CollapsibleGroup(
                title = "Connection",
                expanded = expandedGroup == GROUP_CONNECTION,
                onToggle = { toggle(GROUP_CONNECTION) },
            ) {
                PitstopServerSection(
                    form = form,
                    update = { transform -> viewModel.update(transform) },
                )
                VehicleSection(
                    slug = form.vehicleSlug,
                    onSlugChange = { v -> viewModel.update { it.copy(vehicleSlug = v) } },
                )
                MqttBrokerSection(
                    form = form,
                    brokerConnected = brokerConnected,
                    onReconnect = { viewModel.reconnectBroker() },
                    update = { transform -> viewModel.update(transform) },
                )
            }

            // ── Capture ─────────────────────────────────────────────
            CollapsibleGroup(
                title = "Capture",
                expanded = expandedGroup == GROUP_CAPTURE,
                onToggle = { toggle(GROUP_CAPTURE) },
            ) {
                CaptureCollectorsSection(
                    bleEnabled = form.bridgeBleEnabled,
                    gpsEnabled = form.bridgeGpsEnabled,
                    manualSyncOnly = form.manualSyncOnly,
                    onBleEnabledChange = { v -> viewModel.setBridgeBleEnabled(v) },
                    onGpsEnabledChange = { v -> viewModel.setBridgeGpsEnabled(v) },
                    onManualSyncChange = { v -> viewModel.setManualSyncOnly(v) },
                )
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
                if (viewModel.companionPresenceSupported) {
                    CompanionPairingSection(
                        associated = companionAssociated,
                        onPair = { viewModel.pairCompanion() },
                        onUnpair = { viewModel.unpairCompanion() },
                    )
                }
            }

            // ── App ─────────────────────────────────────────────────
            CollapsibleGroup(
                title = "App",
                expanded = expandedGroup == GROUP_APP,
                onToggle = { toggle(GROUP_APP) },
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
private const val GROUP_CAPTURE = "capture"
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
            StatusPill(
                state = if (associated) PillState.Healthy else PillState.Neutral,
                label = if (associated) "Associated" else "Not paired",
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (associated) "WiCAN companion active" else "Pair to enable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPair, enabled = !associated) {
                Text("Pair WiCAN")
            }
            OutlinedButton(onClick = onUnpair, enabled = associated) {
                Text("Unpair")
            }
        }
    }
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
    onReconnect: () -> Unit,
    update: ((ConfigFormState) -> ConfigFormState) -> Unit,
) {
    SettingsSection(
        title = "MQTT broker",
        description = "Mosquitto on the pitstop CT. Used by the OBD bridge to publish telemetry.",
    ) {
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
            onValueChange = { v -> update { it.copy(brokerUrl = v) } },
            label = { Text("Broker URL") },
            placeholder = { Text("tcp://10.0.0.x:1883") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.mqttUser,
            onValueChange = { v -> update { it.copy(mqttUser = v) } },
            label = { Text("MQTT username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            label = "MQTT password",
            value = form.mqttPassword,
            onValueChange = { v -> update { it.copy(mqttPassword = v) } },
        )
    }
}

// ── Vehicle ────────────────────────────────────────────────────────

@Composable
private fun VehicleSection(slug: String, onSlugChange: (String) -> Unit) {
    SettingsSection(
        title = "Vehicle",
        description = "MQTT topic id this bridge publishes under. Must match a vehicle on the server (mapped via Settings → Devices).",
    ) {
        OutlinedTextField(
            value = slug,
            onValueChange = onSlugChange,
            label = { Text("Vehicle slug") },
            placeholder = { Text("e.g. pilot19") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Pitstop server ─────────────────────────────────────────────────

@Composable
private fun PitstopServerSection(
    form: ConfigFormState,
    update: ((ConfigFormState) -> ConfigFormState) -> Unit,
) {
    SettingsSection(
        title = "Pitstop server",
        description = "Reads use the Query token, writes use the Ingest token. Get both from ~/.pitstop-deploy-secrets.txt on the host.",
    ) {
        OutlinedTextField(
            value = form.apiBaseUrl,
            onValueChange = { v -> update { it.copy(apiBaseUrl = v) } },
            label = { Text("API base URL") },
            placeholder = { Text("http://10.0.0.x:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            label = "Ingest token",
            value = form.ingestToken,
            onValueChange = { v -> update { it.copy(ingestToken = v) } },
        )
        SecretField(
            label = "Query token",
            value = form.queryToken,
            onValueChange = { v -> update { it.copy(queryToken = v) } },
        )
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
