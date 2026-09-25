package com.pitstop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.domain.DtcGuide
import com.pitstop.domain.DtcGuideEntry
import com.pitstop.domain.DtcSeverity
import com.pitstop.ui.theme.ext

/** Foreground / background for a severity — theme roles, no hex. */
@Composable
fun severityColors(s: DtcSeverity): Pair<Color, Color> = when (s) {
    DtcSeverity.STOP_NOW -> MaterialTheme.ext.bad to MaterialTheme.ext.badContainer
    DtcSeverity.CHECK_SOON -> MaterialTheme.ext.warn to MaterialTheme.ext.warnContainer
    DtcSeverity.MONITOR -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHighest
}

/** "Check soon" pill for list rows and the Home attention strip. */
@Composable
fun SeverityChip(severity: DtcSeverity, modifier: Modifier = Modifier) {
    val (fg, bg) = severityColors(severity)
    Text(
        severity.label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "Severity: ${severity.label}" },
    )
}

/**
 * DTC detail's plain-language card: severity, "Can I keep driving?",
 * common causes, and the not-a-diagnosis line. Unknown codes say so.
 */
@Composable
fun DtcGuidanceCard(entry: DtcGuideEntry, modifier: Modifier = Modifier, heading: String = entry.title) {
    val (fg, _) = severityColors(entry.severity)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (entry.severity) {
                        DtcSeverity.STOP_NOW -> Icons.Outlined.ReportProblem
                        DtcSeverity.CHECK_SOON -> Icons.Outlined.ErrorOutline
                        DtcSeverity.MONITOR -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    heading,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                SeverityChip(entry.severity)
            }
            if (!entry.known) {
                Text(
                    "Not in pitstop's table — this is a ${DtcGuide.familyLabel(entry.code)} code, so this is " +
                        "general guidance for that family.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Safe to drive?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.safeToDrive, style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.causes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Common causes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (c in entry.causes) {
                        Text("•  $c", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(
                DtcGuide.DISCLAIMER,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
