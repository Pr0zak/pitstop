package com.pitstop.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Phone-side mirror of the web frontend/components/Pill.vue. Same four
 * states: healthy / connecting / degraded / offline (+ neutral). Same
 * coral / amber / red / muted colour mapping. Used wherever the
 * StatusScreen wants a one-glance "is this surface OK?" indicator —
 * service phase, broker connection, BLE link, etc.
 *
 * Healthy gets a subtle dot halo; connecting pulses. The animation is
 * cheap (one infinite-transition float) so dropping multiple onto a
 * screen has no measurable cost.
 */
enum class PillState { Healthy, Connecting, Degraded, Offline, Neutral }

@Composable
fun StatusPill(
    state: PillState,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val palette = when (state) {
        PillState.Healthy -> Triple(
            Color(0x1A4ADE80),
            Color(0x4D4ADE80),
            Color(0xFF4ADE80),
        )
        PillState.Connecting -> Triple(
            Color(0x1AFFB020),
            Color(0x4DFFB020),
            Color(0xFFFFB020),
        )
        PillState.Degraded -> Triple(
            Color(0x14FFB020),
            Color(0x3DFFB020),
            Color(0xFFFFB020),
        )
        PillState.Offline -> Triple(
            Color(0x1AFF3A2E),
            Color(0x47FF3A2E),
            Color(0xFFFF3A2E),
        )
        PillState.Neutral -> Triple(
            Color(0x0AFFFFFF),
            cs.outlineVariant,
            cs.onSurfaceVariant,
        )
    }
    val (bg, border, fg) = palette

    val pulseAlpha by if (state == PillState.Connecting) {
        val t = rememberInfiniteTransition(label = "pill-pulse")
        t.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    } else {
        // ~no-op
        rememberInfiniteTransition(label = "pill-static")
            .animateFloat(initialValue = 1f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(1)), label = "static")
    }

    val padH = if (compact) 8.dp else 10.dp
    val padV = if (compact) 2.dp else 4.dp

    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Dot — alpha animated for the connecting state.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(pulseAlpha)
                .background(fg, CircleShape),
        )
        Text(
            label,
            color = fg,
            style = if (compact) MaterialTheme.typography.labelMedium
            else MaterialTheme.typography.labelLarge,
        )
    }
    Spacer(Modifier.size(0.dp))
}
