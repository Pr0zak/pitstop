package com.pitstop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pitstop.service.BridgePhase

/**
 * Big "All systems live" / "Bridge stopped" headline that lives above
 * the cards stack on the Status screen. Mirrors the design's phone
 * board: the eye lands here first and decides "keep walking to the car"
 * vs "go fix something." The card-level pills underneath fill in the
 * detail.
 *
 * State derivation matches StatusScreen.ServiceStateCard so the two
 * never disagree:
 *
 *   Idle           → Bridge stopped               (neutral)
 *   Scanning       → Looking for OBD device       (connecting pulse)
 *   Connecting     → Connecting to OBD            (connecting pulse)
 *   Connected,
 *   broker up      → All systems live             (healthy)
 *   Connected,
 *   broker down    → Broker offline               (degraded)
 *   Disconnected   → Reconnecting                 (degraded pulse)
 *   Error          → Bridge error                 (offline)
 */
@Composable
fun HeroStatusBanner(
    phase: BridgePhase,
    brokerConnected: Boolean,
    vehicleName: String? = null,
    modifier: Modifier = Modifier,
) {
    val (headline, sub, pill) = when (phase) {
        BridgePhase.Idle -> Triple(
            "Bridge stopped",
            "Tap Start below to bring it up",
            PillState.Neutral,
        )
        BridgePhase.Scanning -> Triple(
            "Looking for OBD",
            "Scanning for the WiCAN device",
            PillState.Connecting,
        )
        BridgePhase.Connecting -> Triple(
            "Connecting to OBD",
            "Waiting on BLE handshake",
            PillState.Connecting,
        )
        BridgePhase.Connected ->
            if (brokerConnected) Triple(
                "All systems live",
                vehicleName?.let { "$it · streaming to broker" } ?: "Streaming to broker",
                PillState.Healthy,
            ) else Triple(
                "Broker offline",
                "OBD link is up but the broker is unreachable",
                PillState.Degraded,
            )
        BridgePhase.Disconnected -> Triple(
            "Reconnecting",
            "Lost the OBD link, retrying with backoff",
            PillState.Degraded,
        )
        BridgePhase.Error -> Triple(
            "Bridge error",
            "Tap Start to retry",
            PillState.Offline,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(state = pill, label = pillLabel(pill), compact = true)
        }
        Text(
            sub,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun pillLabel(state: PillState): String = when (state) {
    PillState.Healthy -> "live"
    PillState.Connecting -> "..."
    PillState.Degraded -> "stale"
    PillState.Offline -> "off"
    PillState.Neutral -> "idle"
}
