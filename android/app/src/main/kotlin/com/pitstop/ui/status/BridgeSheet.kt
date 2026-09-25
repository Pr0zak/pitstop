package com.pitstop.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pitstop.ui.BridgeSheetState
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.components.StatusPill
import com.pitstop.ui.theme.ext

/**
 * What the top-bar logging chip opens: the bridge card that used to sit on
 * Home (Start / Stop and the live capture detail), plus the pairing and
 * setup nudges when those are what's actually wrong.
 */
@Composable
internal fun BridgeSheetContent(
    state: BridgeSheetState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Logging", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (!state.configured || state.needsPairing) {
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        if (!state.configured) "Finish setup to start logging drives."
                        else "Pair the WiCAN so drives start logging on their own.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onOpenSettings) { Text(if (!state.configured) "Set up" else "Pair") }
                }
            }
        }
        BridgeControlCard(status = state.status, onStart = onStart, onStop = onStop)
    }
}

/**
 * Bridge control + live status card. This is the primary place to
 * start / stop the bridge (moved off Settings) and the single surface
 * for the live status detail that used to live in Settings → Bridge
 * service: status pill + active collectors, active-metrics · last-frame,
 * OBD freshness, and offline-buffer-queued size.
 *
 * The top-bar [com.pitstop.ui.components.BridgeStatePill] is the at-a-glance
 * summary; this card is the expanded detail + controls. We deliberately do
 * NOT add a second pill here — the card leads with the device + collector
 * line so the two surfaces don't read as duplicates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BridgeControlCard(
    status: com.pitstop.service.BridgeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val phase = status.phase
    val (statusText, pillTone) = when (phase) {
        com.pitstop.service.BridgePhase.Idle -> "Idle" to PillTone.Neutral
        com.pitstop.service.BridgePhase.Scanning -> "Scanning" to PillTone.Connecting
        com.pitstop.service.BridgePhase.Connecting -> "Connecting" to PillTone.Connecting
        com.pitstop.service.BridgePhase.Connected -> "Running" to PillTone.Healthy
        com.pitstop.service.BridgePhase.Disconnected -> "Reconnecting" to PillTone.Degraded
        com.pitstop.service.BridgePhase.Error -> "Error" to PillTone.Offline
    }
    // Healthy capture collapses to one line; anything off-nominal (or a tap)
    // shows the full detail and controls.
    val obdAge = status.lastObdFrameAtMs?.let {
        ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L)
    }
    val healthy = phase == com.pitstop.service.BridgePhase.Connected &&
        obdAge != null && obdAge < 10 && status.errorMessage == null &&
        status.offlineBufferBytes == 0L
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (healthy && !expanded) {
        Card(onClick = { expanded = true }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(tone = pillTone, label = "Capturing", compact = true, subject = "Bridge")
                Spacer(Modifier.size(8.dp))
                Text(
                    listOfNotNull("OBD ${obdAge}s", status.deviceName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.FilledTonalIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop bridge")
                }
            }
        }
        return
    }
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(tone = pillTone, label = statusText, subject = "Bridge")
                Spacer(Modifier.size(8.dp))
                Text(
                    text = activeCollectorsLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status.errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            (status.deviceName ?: status.deviceMac)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Active metrics: ${status.metricsActive} · Last frame: ${formatRelative(status.lastFrameAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // OBD freshness (BLE-3): dedicated OBD-frame clock, independent
            // of GPS / WiCAN traffic. Healthy <10s / Degraded <60s / Offline.
            run {
                val ageS = status.lastObdFrameAtMs?.let {
                    ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L)
                }
                val (obdText, obdColor) = when (ageS) {
                    null -> "OBD: no frames yet" to MaterialTheme.colorScheme.onSurfaceVariant
                    in 0..9 -> "OBD: healthy (${ageS}s)" to MaterialTheme.ext.good
                    in 10..59 -> "OBD: degraded (${ageS}s)" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "OBD: offline (${ageS}s)" to MaterialTheme.colorScheme.error
                }
                Text(obdText, style = MaterialTheme.typography.bodySmall, color = obdColor)
            }
            if (status.offlineBufferBytes > 0) {
                Text(
                    "Offline buffer: ${humanBytes(status.offlineBufferBytes)} queued",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = phase == com.pitstop.service.BridgePhase.Idle ||
                        phase == com.pitstop.service.BridgePhase.Error,
                ) { Text("Start") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = phase != com.pitstop.service.BridgePhase.Idle,
                ) { Text("Stop") }
            }
        }
    }
}

private fun activeCollectorsLabel(status: com.pitstop.service.BridgeStatus): String {
    // The bridge doesn't echo per-collector enable flags into BridgeStatus,
    // so describe by phase: a running bridge with OBD frames flowing reads
    // "OBD active"; otherwise fall back to the device-presence hint.
    return when (status.phase) {
        com.pitstop.service.BridgePhase.Connected ->
            if (status.lastObdFrameAtMs != null) "Capturing" else "Connected — waiting for frames"
        com.pitstop.service.BridgePhase.Scanning -> "Looking for the WiCAN"
        com.pitstop.service.BridgePhase.Connecting -> "Linking up"
        com.pitstop.service.BridgePhase.Disconnected -> "Link dropped — retrying"
        com.pitstop.service.BridgePhase.Error -> "Stopped"
        com.pitstop.service.BridgePhase.Idle -> "Not running"
    }
}

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

