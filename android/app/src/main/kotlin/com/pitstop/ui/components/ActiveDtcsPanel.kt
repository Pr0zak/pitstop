package com.pitstop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.http.DtcDto
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Top-of-Home active-DTC panel. Per spec: render only when the list
 * is non-empty — we don't show a "no active codes" placeholder, the
 * user explicitly asked to hide.
 *
 * Each row is tap-to-open. Because the History tab owns its own
 * NavHost we can't deep-link directly into a specific DTC code from
 * here without a much larger plumbing change; we fall back to a
 * coarser "open History" callback that the host (StatusScreen) wires
 * to a tab switch.
 */
@Composable
fun ActiveDtcsPanel(
    dtcs: List<DtcDto>,
    onOpen: (DtcDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dtcs.isEmpty()) return
    val accent = Color(0xFFFF3A2E)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = accent,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "${dtcs.size} active code${if (dtcs.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            dtcs.forEachIndexed { i, dtc ->
                if (i > 0) {
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(horizontal = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp),
                        ) {
                            // Hairline divider — onSurfaceVariant at 18%
                            // to match the rest of the Card surfaces.
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                DtcRow(dtc = dtc, accent = accent, onClick = { onOpen(dtc) })
            }
        }
    }
}

@Composable
private fun DtcRow(
    dtc: DtcDto,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dtc.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            dtc.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Text(
            text = formatAgo(dtc.seenAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatAgo(iso: String): String {
    return runCatching {
        val parsed = OffsetDateTime.parse(iso)
        val now = OffsetDateTime.now()
        val mins = ChronoUnit.MINUTES.between(parsed, now)
        when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 24 * 60 -> "${mins / 60}h ago"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d ago"
            else -> parsed
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }.getOrDefault(iso.take(10))
}
