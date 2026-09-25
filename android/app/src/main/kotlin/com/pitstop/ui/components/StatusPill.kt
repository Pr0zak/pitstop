package com.pitstop.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.pitstop.service.BridgePhase
import com.pitstop.service.EngineState
import com.pitstop.ui.theme.ext

/**
 * Phone-side mirror of the web frontend/components/Pill.vue — the ONE
 * status pill on the phone. It used to be two components (this and a
 * separate BridgeStatePill) with two copies of the same hex table; the
 * bridge variant is now just [bridgePillOf] feeding this.
 *
 *   Healthy    green, subtle dot
 *   Connecting amber, pulsing dot
 *   Degraded   amber
 *   Offline    red
 *   Neutral    muted ink
 *   LocalOnly  slate — "running, but holding data on the phone"
 *
 * Colours come from [com.pitstop.ui.theme.ExtendedColors]; nothing here
 * carries its own hex.
 *
 * Accessibility: the dot + text are collapsed into one node whose
 * description names the subject — "Bridge: Running" rather than a bare
 * "Running" a screen reader can't place. Pass [subject] for that; the
 * pill is not clickable, so it gets no role.
 */
enum class PillTone { Healthy, Connecting, Degraded, Offline, Neutral, LocalOnly }

@Composable
fun StatusPill(
    tone: PillTone,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    subject: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val ext = MaterialTheme.ext
    val (bg, fg) = when (tone) {
        PillTone.Healthy -> ext.goodContainer to ext.good
        PillTone.Connecting, PillTone.Degraded -> ext.warnContainer to ext.warn
        PillTone.Offline -> ext.badContainer to ext.bad
        PillTone.LocalOnly -> ext.infoContainer to ext.info
        PillTone.Neutral -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    // Border is the foreground at ~30 %: one derivation, not a third table.
    val border = if (tone == PillTone.Neutral) cs.outlineVariant else fg.copy(alpha = 0.3f)

    // Only the connecting tone animates; the others skip the transition
    // entirely rather than running a no-op infinite animation.
    val dotAlpha = if (tone == PillTone.Connecting) {
        val t = rememberInfiniteTransition(label = "pill-pulse")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        a
    } else {
        1f
    }

    val padH = if (compact) 8.dp else 10.dp
    val padV = if (compact) 2.dp else 4.dp
    val description = if (subject != null) "$subject: $label" else label

    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 6.dp else 8.dp)
                .alpha(dotAlpha)
                .background(fg, CircleShape),
        )
        Text(
            label,
            color = fg,
            style = if (compact) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
        )
    }
}

/**
 * Label + tone for the bridge's one-glance state — Home's top-bar pill.
 *
 * Real-error and Idle states always win over the LocalOnly badge — we
 * never want to mask a misconfigured bridge behind "Local-only". Engine
 * off / asleep / reconnecting still take precedence so the user can see
 * why no data is flowing right now.
 */
fun bridgePillOf(
    phase: BridgePhase,
    brokerConnected: Boolean,
    engineState: EngineState = EngineState.Unknown,
    manualSyncOnly: Boolean = false,
): Pair<String, PillTone> {
    val showLocalOnly = manualSyncOnly && phase in setOf(
        BridgePhase.Scanning,
        BridgePhase.Connecting,
        BridgePhase.Connected,
    ) && engineState != EngineState.Off
    if (showLocalOnly) return "Local-only" to PillTone.LocalOnly
    return when (phase) {
        BridgePhase.Idle -> "Idle" to PillTone.Neutral
        BridgePhase.Scanning -> "Scanning" to PillTone.Connecting
        BridgePhase.Connecting -> "Connecting" to PillTone.Connecting
        BridgePhase.Connected -> when {
            engineState == EngineState.Off -> "Engine off" to PillTone.Neutral
            !brokerConnected -> "Broker offline" to PillTone.Degraded
            else -> "Running" to PillTone.Healthy
        }
        BridgePhase.Disconnected ->
            if (engineState == EngineState.Off) {
                "Asleep" to PillTone.Neutral
            } else {
                "Reconnecting" to PillTone.Degraded
            }
        BridgePhase.Error -> "Error" to PillTone.Offline
    }
}

/** Home's top-bar bridge pill: [bridgePillOf] rendered through [StatusPill]. */
@Composable
fun BridgeStatePill(
    phase: BridgePhase,
    brokerConnected: Boolean,
    engineState: EngineState = EngineState.Unknown,
    manualSyncOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (label, tone) = bridgePillOf(phase, brokerConnected, engineState, manualSyncOnly)
    StatusPill(tone = tone, label = label, modifier = modifier, compact = true, subject = "Bridge")
}
