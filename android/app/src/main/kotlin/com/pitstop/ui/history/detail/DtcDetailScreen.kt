package com.pitstop.ui.history.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.DtcTimelineCode
import com.pitstop.http.DtcTimelineEvent
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.LoadErrorState
import com.pitstop.ui.components.OverflowAction
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One trouble code's history: status + counts, a 90-day occurrence chart,
 * and the recent occurrences — each linked to the trip it fired during
 * when the server could attribute one. The code is the top-bar title.
 */
@Composable
fun DtcDetailScreen(
    onBack: () -> Unit,
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DtcDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(
                title = ui.code,
                onBack = onBack,
                overflow = listOf(
                    OverflowAction("Copy code", Icons.Filled.ContentCopy) {
                        clipboard.setText(AnnotatedString(ui.code))
                    },
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        val inner = Modifier.padding(padding)
        when {
            ui.loading -> Box(inner.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> LoadErrorState(what = "this code's history", onRetry = viewModel::refresh, modifier = inner)
            ui.entry == null -> EmptyState(
                icon = Icons.Outlined.History,
                title = "No history for ${ui.code}",
                body = "It hasn't been seen in the last year.",
                modifier = inner,
            )
            else -> DtcDetailContent(entry = ui.entry!!, onOpenTrip = onOpenTrip, modifier = inner)
        }
    }
}

/** Stateless body of [DtcDetailScreen], rendered by screenshot tests. */
@Composable
internal fun DtcDetailContent(
    entry: DtcTimelineCode,
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val system = LocalUnitSystem.current
    val is24h = is24HourClock()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Description + status badge (the code itself is the bar title).
        Row(verticalAlignment = Alignment.Top) {
            Text(
                entry.description?.takeIf { it.isNotBlank() } ?: "No description on file",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusBadge(active = entry.active)
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
                    "Count" to UnitFormat.count(entry.count.toLong()),
                    "First seen" to (entry.firstSeen?.let { DateLabel.list(it, withTime = true, grouped = false, is24h = is24h) } ?: "—"),
                    "Last seen" to (entry.lastSeen?.let { DateLabel.list(it, withTime = true, grouped = false, is24h = is24h) } ?: "—"),
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
                        "Occurrences (last $OCCURRENCE_WINDOW_DAYS days)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    OccurrenceBars(
                        events = entry.events,
                        accent = MaterialTheme.colorScheme.primary,
                        grid = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }

        // Recent event list — last 20 events, most-recent first. A row
        // whose event falls inside a trip opens that trip.
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Recent occurrences",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                val recent = entry.events.sortedByDescending { it.seenAt }.take(20)
                for ((i, ev) in recent.withIndex()) {
                    if (i > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    val tripId = ev.tripId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (tripId != null) {
                                    Modifier.clickable(onClickLabel = "Open trip") { onOpenTrip(tripId) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                DateLabel.list(ev.seenAt, withTime = true, grouped = false, is24h = is24h),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                when {
                                    tripId == null -> "Not during a recorded trip"
                                    ev.tripDistanceKm != null ->
                                        "During a ${UnitFormat.distanceKm(ev.tripDistanceKm, system)} trip"
                                    else -> "During a trip"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (tripId != null) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Active / Cleared as a read-only badge. It was an AssistChip with an empty
 * onClick — which looked tappable, announced itself as a button, and did
 * nothing.
 */
@Composable
private fun StatusBadge(active: Boolean) {
    val (bg, fg) = if (active) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.ext.goodContainer to MaterialTheme.ext.good
    }
    Text(
        if (active) "Active" else "Cleared",
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: ${if (active) "active" else "cleared"}" },
    )
}

private const val OCCURRENCE_WINDOW_DAYS = 90

@Composable
private fun OccurrenceBars(
    events: List<DtcTimelineEvent>,
    accent: Color,
    grid: Color,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val windowDays = OCCURRENCE_WINDOW_DAYS
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
    val start = today.minusDays((windowDays - 1).toLong())
    val mid = today.minusDays((windowDays / 2).toLong())
    val inWindow = countByDate.filterKeys { !it.isBefore(start) }.values.sum()
    val dayFmt = remember { DateTimeFormatter.ofPattern("MMM d") }

    Row(Modifier.fillMaxWidth()) {
        Text(
            "max $maxCount / day",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics {
                contentDescription = "$inWindow occurrences between ${start.format(dayFmt)} " +
                    "and ${today.format(dayFmt)}, at most $maxCount in one day"
            },
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
    Row(Modifier.fillMaxWidth()) {
        for ((i, d) in listOf(start, mid, today).withIndex()) {
            Text(
                d.format(dayFmt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = when (i) {
                    0 -> TextAlign.Start
                    1 -> TextAlign.Center
                    else -> TextAlign.End
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
