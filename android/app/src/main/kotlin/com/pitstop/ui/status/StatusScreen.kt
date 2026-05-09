package com.pitstop.ui.status

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.service.BridgePhase
import com.pitstop.ui.components.PillState
import com.pitstop.ui.components.StatusPill
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            com.pitstop.ui.components.PitstopTopAppBar(title = "Home")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.update?.takeIf { it.isNewer }?.let { info ->
                UpdateAvailableCard(
                    info = info,
                    onOpen = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, info.releaseUrl.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }

            ServiceStateCard(
                phase = ui.status.phase,
                deviceName = ui.status.deviceName,
                deviceMac = ui.status.deviceMac,
                error = ui.status.errorMessage,
                lastFrameMs = ui.status.lastFrameAtMs,
                metricsActive = ui.status.metricsActive,
                onStart = { viewModel.startService() },
                onStop = { viewModel.stopService() },
            )

            BrokerCard(
                brokerInfo = ui.brokerInfo,
                connected = ui.status.brokerConnected,
                totalPublished = ui.totalPublished,
                offlineBufferBytes = ui.status.offlineBufferBytes,
            )

            LogsRow(
                buffered = ui.logsBuffered,
                lastFlushMs = ui.logsLastFlushMs,
                lastResult = ui.logsLastResult,
            )

            // The dedicated Live/Fuel buttons that used to live here moved
            // into the bottom NavigationBar (MainActivity.PitstopBottomBar).
            // We keep the deep-link-to-browser action because it's a flow
            // out of the app, not an intra-app navigation.

            ui.deepLinkUrl?.let { url ->
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Open Live in browser")
                }
            }
        }
    }
}

@Composable
private fun ServiceStateCard(
    phase: BridgePhase,
    deviceName: String?,
    deviceMac: String?,
    error: String?,
    lastFrameMs: Long?,
    metricsActive: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // Map bridge phase → design pill state. "Connected" is the only
    // healthy state from the user's POV; scanning + connecting both
    // count as in-progress (pulsing); disconnected + error read as
    // hard offline since the bridge isn't producing telemetry.
    val (statusText, pillState) = when (phase) {
        BridgePhase.Idle -> "Idle" to PillState.Neutral
        BridgePhase.Scanning -> "Scanning" to PillState.Connecting
        BridgePhase.Connecting -> "Connecting" to PillState.Connecting
        BridgePhase.Connected -> "Running" to PillState.Healthy
        BridgePhase.Disconnected -> "Reconnecting" to PillState.Degraded
        BridgePhase.Error -> "Error" to PillState.Offline
    }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(state = pillState, label = statusText)
            }
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                deviceName ?: deviceMac ?: "(no BLE device configured)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Active metrics: $metricsActive  •  Last frame: ${formatRelative(lastFrameMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = phase == BridgePhase.Idle || phase == BridgePhase.Error) {
                    Text("Start")
                }
                OutlinedButton(onClick = onStop, enabled = phase != BridgePhase.Idle) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun BrokerCard(
    brokerInfo: String?,
    connected: Boolean,
    totalPublished: Long,
    offlineBufferBytes: Long,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    state = if (connected) PillState.Healthy else PillState.Offline,
                    label = if (connected) "Broker connected" else "Broker offline",
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                brokerInfo ?: "(no broker configured)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Published total: $totalPublished",
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
        }
    }
}

@Composable
private fun UpdateAvailableCard(
    info: com.pitstop.update.UpdateInfo,
    onOpen: () -> Unit,
) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Update available",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "v${info.currentVersion} → v${info.latestVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpen) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Download v${info.latestVersion}")
            }
        }
    }
}

private fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / 1024.0 / 1024.0)
}

@Composable
private fun LogsRow(
    buffered: Int,
    lastFlushMs: Long?,
    lastResult: String,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Logs  ·  buffered $buffered  ·  last flush ${formatRelative(lastFlushMs)}  ·  last result $lastResult",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatRelative(tsMs: Long?): String {
    if (tsMs == null) return "—"
    val delta = max(0L, System.currentTimeMillis() - tsMs)
    return when {
        delta < 1_500 -> "just now"
        delta < 60_000 -> "${delta / 1000}s ago"
        else -> "${delta / 60_000}m ago"
    }
}
