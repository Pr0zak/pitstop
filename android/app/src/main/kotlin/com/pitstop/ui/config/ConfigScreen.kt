package com.pitstop.ui.config

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.ble.ScannedDevice
import com.pitstop.service.BridgePhase
import com.pitstop.ui.components.PillState
import com.pitstop.ui.components.SettingsSection
import com.pitstop.ui.components.StatusPill
import kotlin.math.max

/**
 * Settings — Material 3 system-app style. Sections grouped by access
 * frequency; cold-config rows collapse by default so the daily-use
 * surfaces don't get buried by scroll.
 *
 *   ── always expanded (top) ──
 *   Bridge service     start/stop, live phase pill, last frame age
 *   OBD device         BLE picker (current pick + scan)
 *   MQTT broker        URL, user, password — with live connected pill
 *   Connectivity       manual-sync toggle + status
 *
 *   ── collapsed by default (set once at first config) ──
 *   Pitstop server     API base URL + ingest/query tokens
 *   Vehicle            vehicle slug for the bridge
 *   Display            imperial / metric units
 *   Logs               verbose toggle, buffered count, manual flush
 *
 *   ── always expanded (bottom anchor) ──
 *   App                version + build code + check-for-updates
 *
 * The "fuel-card" / Bridge-state widgets that used to live on Home now
 * live in this view. Home is the dashboard; here is where you configure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val bufferedCount by viewModel.bufferedCount.collectAsStateWithLifecycle()
    val lastFlushAt by viewModel.lastFlushAtMs.collectAsStateWithLifecycle()
    val bridge by viewModel.bridgeStatus.collectAsStateWithLifecycle()
    val brokerConnected by viewModel.brokerConnected.collectAsStateWithLifecycle()
    val totalPublished by viewModel.totalPublished.collectAsStateWithLifecycle()

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
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = { com.pitstop.ui.components.PitstopTopAppBar() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── always expanded: daily-use surfaces ──
            BridgeServiceSection(
                phase = bridge.phase,
                deviceName = bridge.deviceName,
                deviceMac = bridge.deviceMac,
                error = bridge.errorMessage,
                lastFrameMs = bridge.lastFrameAtMs,
                metricsActive = bridge.metricsActive,
                offlineBufferBytes = bridge.offlineBufferBytes,
                onStart = { viewModel.startBridge() },
                onStop = { viewModel.stopBridge() },
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

            MqttBrokerSection(
                form = form,
                brokerConnected = brokerConnected,
                totalPublished = totalPublished,
                onReconnect = { viewModel.reconnectBroker() },
                update = { transform -> viewModel.update(transform) },
            )

            ConnectivitySection(
                manualSyncOnly = form.manualSyncOnly,
                onChange = { v -> viewModel.setManualSyncOnly(v) },
            )

            // ── collapsed by default: set once at first config ──
            PitstopServerSection(
                form = form,
                update = { transform -> viewModel.update(transform) },
            )

            VehicleSection(
                slug = form.vehicleSlug,
                onSlugChange = { v -> viewModel.update { it.copy(vehicleSlug = v) } },
            )

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

            // ── bottom anchor: version + check-for-updates ──
            val checkingUpdate by viewModel.checkingUpdate.collectAsStateWithLifecycle()
            val latestUpdate by viewModel.latestUpdate.collectAsStateWithLifecycle()
            AppSection(
                checking = checkingUpdate,
                latestVersionFound = latestUpdate?.latestVersion,
                latestIsNewer = latestUpdate?.isNewer == true,
                hasApkAsset = latestUpdate?.apkUrl != null,
                onCheck = { viewModel.checkForUpdates() },
                onDownload = { viewModel.downloadAndInstall() },
            )

            // Save bar — sticky-ish at the very bottom of the scroll.
            Spacer(Modifier.size(20.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (form.saved) "Saved" else "Save settings")
                }
            }
            Spacer(Modifier.size(28.dp))
        }
    }
}

// ── Bridge service ─────────────────────────────────────────────────

@Composable
private fun BridgeServiceSection(
    phase: BridgePhase,
    deviceName: String?,
    deviceMac: String?,
    error: String?,
    lastFrameMs: Long?,
    metricsActive: Int,
    offlineBufferBytes: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val (statusText, pillState) = when (phase) {
        BridgePhase.Idle -> "Idle" to PillState.Neutral
        BridgePhase.Scanning -> "Scanning" to PillState.Connecting
        BridgePhase.Connecting -> "Connecting" to PillState.Connecting
        BridgePhase.Connected -> "Running" to PillState.Healthy
        BridgePhase.Disconnected -> "Reconnecting" to PillState.Degraded
        BridgePhase.Error -> "Error" to PillState.Offline
    }
    SettingsSection(title = "Bridge service") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(state = pillState, label = statusText)
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (deviceName != null || deviceMac != null) {
            Text(
                deviceName ?: deviceMac ?: "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Active metrics: $metricsActive · Last frame: ${formatRelative(lastFrameMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (offlineBufferBytes > 0) {
            Text(
                "Offline buffer: ${humanBytes(offlineBufferBytes)} queued",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = phase == BridgePhase.Idle || phase == BridgePhase.Error,
            ) { Text("Start") }
            OutlinedButton(
                onClick = onStop,
                enabled = phase != BridgePhase.Idle,
            ) { Text("Stop") }
        }
    }
}

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
    totalPublished: Long,
    onReconnect: () -> Unit,
    update: ((ConfigFormState) -> ConfigFormState) -> Unit,
) {
    SettingsSection(
        title = "MQTT broker",
        description = "Mosquitto on the pitstop CT. Used by the OBD bridge to publish telemetry.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                state = if (brokerConnected) PillState.Healthy else PillState.Offline,
                label = if (brokerConnected) "Connected" else "Offline",
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Published: $totalPublished",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
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

// ── Connectivity ──────────────────────────────────────────────────

/**
 * Manual-sync mode toggle. When ON the bridge keeps capturing OBD +
 * GPS locally (Live screen still updates, drives still seal to the
 * Room queue) but every outgoing MQTT publish is suppressed and the
 * post-seal auto-upload is disabled. Drives wait in the queue until
 * the user explicitly hits "Sync now" in History or the persistent
 * reminder notification.
 */
@Composable
private fun ConnectivitySection(
    manualSyncOnly: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsSection(
        title = "Connectivity",
        description = "Saves cellular data and battery. Drives won't appear in the live dashboard until you sync.",
    ) {
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
            Switch(checked = manualSyncOnly, onCheckedChange = onChange)
        }
    }
}

// ── Vehicle ────────────────────────────────────────────────────────

@Composable
private fun VehicleSection(slug: String, onSlugChange: (String) -> Unit) {
    SettingsSection(
        title = "Vehicle",
        description = "MQTT topic id this bridge publishes under. Must match a vehicle on the server (mapped via Settings → Devices).",
        collapsible = true,
        initiallyExpanded = false,
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
        collapsible = true,
        initiallyExpanded = false,
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
    SettingsSection(
        title = "Logs",
        collapsible = true,
        initiallyExpanded = false,
    ) {
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

// ── App ────────────────────────────────────────────────────────────

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
        collapsible = true,
        initiallyExpanded = false,
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

@Composable
private fun AppSection(
    checking: Boolean,
    latestVersionFound: String?,
    latestIsNewer: Boolean,
    hasApkAsset: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingsSection(title = "App") {
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hasApkAsset) "v$latestVersionFound is ready to install."
                    else "v$latestVersionFound has no APK asset on the release page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onDownload,
                    enabled = hasApkAsset,
                ) {
                    Text("Download & install")
                }
            }
        }
    }
}

// ── Shared bits ────────────────────────────────────────────────────

private fun formatRelative(tsMs: Long?): String {
    if (tsMs == null) return "never"
    val delta = max(0L, System.currentTimeMillis() - tsMs)
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
