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
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
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
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onOpenConfig: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenFuel: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pitstop")
                        Text(
                            "v${com.pitstop.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenConfig) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            )

            LogsRow(
                buffered = ui.logsBuffered,
                lastFlushMs = ui.logsLastFlushMs,
                lastResult = ui.logsLastResult,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenLive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Live")
                }
                OutlinedButton(
                    onClick = onOpenFuel,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.LocalGasStation, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Fuel")
                }
            }

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
    val (statusText, statusColor) = when (phase) {
        BridgePhase.Idle -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
        BridgePhase.Scanning -> "Scanning" to MaterialTheme.colorScheme.tertiary
        BridgePhase.Connecting -> "Connecting" to MaterialTheme.colorScheme.tertiary
        BridgePhase.Connected -> "Running" to MaterialTheme.colorScheme.primary
        BridgePhase.Disconnected -> "Reconnecting" to MaterialTheme.colorScheme.error
        BridgePhase.Error -> "Error" to MaterialTheme.colorScheme.error
    }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                Spacer(Modifier.size(8.dp))
                Text(statusText, style = MaterialTheme.typography.titleMedium)
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
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp),
                ) {}
                Spacer(Modifier.size(8.dp))
                Text(
                    if (connected) "Broker connected" else "Broker offline",
                    style = MaterialTheme.typography.titleMedium,
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
        }
    }
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
