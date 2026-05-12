package com.pitstop.ui.history.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.DtcTimelineCode
import com.pitstop.http.DtcTimelineEvent
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun DtcDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: DtcDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    when {
        ui.loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        ui.error != null -> Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ui.error ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.entry == null -> Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No history for ${ui.code} in the last year",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Loaded(entry = ui.entry!!, modifier = modifier)
    }
}

@Composable
private fun Loaded(entry: DtcTimelineCode, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Headline + active/cleared chip.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(active = entry.active)
            }
            entry.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Stats card.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val rows = listOf(
                    "Count" to entry.count.toString(),
                    "First seen" to fmtShortDateTimeLocal(entry.firstSeen),
                    "Last seen" to fmtShortDateTimeLocal(entry.lastSeen),
                    "Status" to if (entry.active) "Active" else "Cleared",
                )
                for ((i, kv) in rows.withIndex()) {
                    if (i > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            kv.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            kv.second,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Daily occurrence bar chart — last 90 days, one bar per day,
        // height proportional to the count that day. Empty days drawn
        // as a 1-px baseline tick so the timeline reads continuous.
        if (entry.events.size >= 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Occurrences (last 90 days)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OccurrenceBars(
                        events = entry.events,
                        accent = MaterialTheme.colorScheme.primary,
                        grid = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }

        // Recent event list — last 20 events, most-recent first.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Recent occurrences",
                    style = MaterialTheme.typography.titleMedium,
                )
                val recent = entry.events.sortedByDescending { it.seenAt }.take(20)
                for ((i, ev) in recent.withIndex()) {
                    if (i > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            fmtShortDateTimeLocal(ev.seenAt),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusChip(active: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(if (active) "Active" else "Cleared") },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            labelColor = if (active) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun OccurrenceBars(
    events: List<DtcTimelineEvent>,
    accent: Color,
    grid: Color,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val windowDays = 90
    val countByDate: Map<LocalDate, Int> = remember(events) {
        events
            .mapNotNull { ev ->
                runCatching {
                    OffsetDateTime.parse(ev.seenAt).atZoneSameInstant(zone).toLocalDate()
                }.getOrNull()
            }
            .groupingBy { it }
            .eachCount()
    }
    val maxCount = (countByDate.values.maxOrNull() ?: 1).coerceAtLeast(1)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        val left = 4f
        val right = w - 4f
        val top = 4f
        val bottom = h - 14f
        val plotW = (right - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)
        val barW = plotW / windowDays

        drawLine(
            color = grid,
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1f,
        )

        for (i in 0 until windowDays) {
            val date = today.minusDays((windowDays - 1 - i).toLong())
            val count = countByDate[date] ?: 0
            val xN = i.toFloat() / windowDays
            val x = left + xN * plotW
            if (count == 0) {
                // Baseline tick for empty days — faint, lets the eye
                // trace the timeline even when nothing happened.
                drawLine(
                    color = grid.copy(alpha = 0.4f),
                    start = Offset(x + barW / 2f, bottom),
                    end = Offset(x + barW / 2f, bottom - 2f),
                    strokeWidth = 1f,
                )
            } else {
                val barH = (count.toFloat() / maxCount) * plotH
                drawRect(
                    color = accent,
                    topLeft = Offset(x + barW * 0.15f, bottom - barH),
                    size = Size((barW * 0.7f).coerceAtLeast(1.5f), barH),
                )
            }
        }
    }
}
