package com.pitstop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pitstop.service.BridgePhase
import com.pitstop.service.EngineState

/**
 * Small pill summarising bridge state, designed to sit in the
 * Home top-app-bar actions slot. Replaces the old hero banner card —
 * the user has long since internalised what "Running" means; the
 * hero banner was wasting prime screen real estate.
 *
 * State derivation mirrors StatusScreen.ServiceStateCard / HeroStatusBanner
 * so the rendering stays in sync if those surfaces show again later.
 */
@Composable
fun BridgeStatePill(
    phase: BridgePhase,
    brokerConnected: Boolean,
    engineState: EngineState = EngineState.Unknown,
    manualSyncOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Real-error and Idle states always win over the LocalOnly badge —
    // we never want to mask a misconfigured bridge behind "Local-only".
    // Engine-off / asleep / reconnecting still take precedence so the
    // user can see why no data is flowing right now.
    val showLocalOnly = manualSyncOnly && phase in setOf(
        BridgePhase.Scanning,
        BridgePhase.Connecting,
        BridgePhase.Connected,
    ) && engineState != EngineState.Off
    val (label, dot) = if (showLocalOnly) {
        "Local-only" to PillTone.LocalOnly
    } else when (phase) {
        BridgePhase.Idle -> "Idle" to PillTone.Neutral
        BridgePhase.Scanning -> "Scanning" to PillTone.Amber
        BridgePhase.Connecting -> "Connecting" to PillTone.Amber
        BridgePhase.Connected -> when {
            engineState == EngineState.Off -> "Engine off" to PillTone.Neutral
            !brokerConnected -> "Broker offline" to PillTone.Amber
            else -> "Running" to PillTone.Green
        }
        BridgePhase.Disconnected ->
            if (engineState == EngineState.Off) "Asleep" to PillTone.Neutral
            else "Reconnecting" to PillTone.Amber
        BridgePhase.Error -> "Error" to PillTone.Red
    }
    val (bg, border, fg) = when (dot) {
        PillTone.Green -> Triple(
            Color(0x1A4ADE80),
            Color(0x4D4ADE80),
            Color(0xFF4ADE80),
        )
        PillTone.Amber -> Triple(
            Color(0x1AFFB020),
            Color(0x4DFFB020),
            Color(0xFFFFB020),
        )
        PillTone.Red -> Triple(
            Color(0x1AFF3A2E),
            Color(0x47FF3A2E),
            Color(0xFFFF3A2E),
        )
        PillTone.Neutral -> Triple(
            Color(0x0AFFFFFF),
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Slate / grey-blue: visually distinct from Neutral so the user
        // can see at a glance that the bridge is running but data is
        // being held locally.
        PillTone.LocalOnly -> Triple(
            Color(0x1A8AA4C9),
            Color(0x4D8AA4C9),
            Color(0xFF8AA4C9),
        )
    }
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .background(fg, CircleShape),
        )
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private enum class PillTone { Green, Amber, Red, Neutral, LocalOnly }
