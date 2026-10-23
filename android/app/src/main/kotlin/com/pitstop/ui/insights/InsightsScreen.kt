package com.pitstop.ui.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.InsightCardDto
import com.pitstop.http.InsightGroupDto
import com.pitstop.http.InsightsResponseDto
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.rememberPitstopListState
import com.pitstop.ui.theme.ext
import com.pitstop.util.requireActivity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Car → Insights: the server's plain-language findings (`/analytics/insights`)
 * as cards, worst first in a "Needs attention" list, then by group. Nothing
 * here computes a number; the server did (ADR-013), the web shows the same.
 */
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(LocalContext.current.requireActivity()),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }
    InsightsContent(
        ui = ui,
        group = group,
        onGroup = { group = it },
        expandedHow = expanded,
        onToggleHow = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
        onRefresh = viewModel::refresh,
        onBack = onBack,
        jumpTo = jumpTo,
        onJump = { id ->
            group = null
            jumpTo = id
        },
        onJumped = { jumpTo = null },
    )
}

/** One entry in the Insights list, so a card's index is known for jumps. */
@Immutable
internal sealed interface InsightRow {
    val key: String

    data object Intro : InsightRow {
        override val key = "intro"
    }

    data class Attention(val cards: List<InsightCardDto>) : InsightRow {
        override val key = "attention"
    }

    data class Groups(val groups: List<InsightGroupDto>) : InsightRow {
        override val key = "groups"
    }

    data class Header(val group: InsightGroupDto) : InsightRow {
        override val key = "h:${group.id}"
    }

    data class Card(val card: InsightCardDto) : InsightRow {
        override val key = "c:${card.id}"
    }
}

/**
 * The rows for [data] filtered to [group] (null = all). Cards keep the
 * server's order inside a group, with "learning" cards moved to the end so
 * findings come before "still collecting". Cards whose group the server
 * didn't list go under an "Other" header at the end.
 */
internal fun insightRows(data: InsightsResponseDto, group: String?): List<InsightRow> {
    val rows = mutableListOf<InsightRow>(InsightRow.Intro)
    val byId = data.insights.associateBy { it.id }
    rows += InsightRow.Attention(data.attention.mapNotNull { byId[it] })
    val known = data.groups.map { it.id }.toSet()
    val groups = data.groups.filter { g -> data.insights.any { it.group == g.id } } +
        listOfNotNull(
            InsightGroupDto("other", "Other", null).takeIf { data.insights.any { it.group !in known } },
        )
    if (groups.size > 1) rows += InsightRow.Groups(groups)
    for (g in groups) {
        if (group != null && group != g.id) continue
        val cards = data.insights
            .filter { if (g.id == "other" && "other" !in known) it.group !in known else it.group == g.id }
            .sortedBy { if (it.status == "learning") 1 else 0 }
        if (cards.isEmpty()) continue
        rows += InsightRow.Header(g)
        cards.forEach { rows += InsightRow.Card(it) }
    }
    return rows
}

/** Stateless body of [InsightsScreen], rendered by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsContent(
    ui: InsightsUi,
    group: String?,
    onGroup: (String?) -> Unit,
    expandedHow: Set<String>,
    onToggleHow: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    jumpTo: String? = null,
    onJump: (String) -> Unit = {},
    onJumped: () -> Unit = {},
    listState: LazyListState = rememberPitstopListState(),
) {
    val data = ui.data
    val rows = remember(data, group) { data?.let { insightRows(it, group) } }
    LaunchedEffect(jumpTo, rows) {
        val id = jumpTo ?: return@LaunchedEffect
        val idx = rows?.indexOfFirst { it.key == "c:$id" } ?: -1
        if (idx >= 0) listState.animateScrollToItem(idx)
        onJumped()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(title = "Insights", onBack = onBack) {
                if (ui.loading && data != null) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onRefresh, enabled = !ui.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh insights")
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                rows == null && ui.error != null -> Column(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = "Couldn't load insights",
                        body = ui.error,
                        actionLabel = "Retry",
                        onAction = onRefresh,
                    )
                }
                rows == null -> Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Looking for patterns in your driving…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            InsightRow.Intro -> Intro(data, ui.error != null)
                            is InsightRow.Attention -> AttentionPanel(row.cards, onJump)
                            is InsightRow.Groups -> GroupChips(row.groups, group, onGroup)
                            is InsightRow.Header -> GroupHeader(row.group)
                            is InsightRow.Card -> InsightCard(
                                card = row.card,
                                howExpanded = row.card.id in expandedHow,
                                onToggleHow = { onToggleHow(row.card.id) },
                            )
                        }
                    }
                    if (data?.insights?.isEmpty() == true) {
                        item(key = "empty") {
                            Text(
                                "No insights for this period yet. Drive a few trips and check back.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── pieces ──────────────────────────────────────────────────────────

/**
 * What the page is, when it was built, and whether cards are still filling
 * in (trips the server hasn't summarised yet).
 */
@Composable
private fun Intro(data: InsightsResponseDto?, refreshFailed: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Each card looks at the period that fits its question: relationships use all your data, health checks use recent days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        updatedLabel(data?.generatedAt)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val pending = data?.pendingTrips?.toInt() ?: 0
        if (pending > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.ext.infoContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.ext.info)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Still analysing ${if (pending == 1) "1 trip" else "$pending trips"}. Some cards will fill in over the next few minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (refreshFailed) {
            Text(
                "Couldn't refresh. Showing the last result.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ext.warn,
            )
        }
    }
}

private fun updatedLabel(iso: String?): String? {
    val t = iso?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
    val local = t.atZoneSameInstant(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(local)
    return if (local.toLocalDate() == LocalDate.now()) {
        "Updated $time"
    } else {
        "Updated ${DateTimeFormatter.ofPattern("MMM d", Locale.US).format(local)}, $time"
    }
}

/** Plain-words label and colours for a card status. */
internal data class StatusLook(val label: String, val color: Color, val container: Color)

@Composable
internal fun statusLook(status: String): StatusLook {
    val ext = MaterialTheme.ext
    val cs = MaterialTheme.colorScheme
    return when (status) {
        "good" -> StatusLook("Good", ext.good, ext.goodContainer)
        "watch" -> StatusLook("Keep an eye on", ext.warn, ext.warnContainer)
        "bad" -> StatusLook("Needs attention", ext.bad, ext.badContainer)
        "learning" -> StatusLook("Learning", cs.onSurfaceVariant, cs.onSurfaceVariant.copy(alpha = 0.12f))
        else -> StatusLook("Insight", ext.info, ext.infoContainer)
    }
}

@Composable
private fun StatusChip(status: String) {
    val look = statusLook(status)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(look.container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(look.color))
        Spacer(Modifier.width(5.dp))
        Text(look.label, style = MaterialTheme.typography.labelMedium, color = look.color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AttentionPanel(cards: List<InsightCardDto>, onJump: (String) -> Unit) {
    val ok = cards.isEmpty()
    val tint = if (ok) MaterialTheme.ext.good else if (cards.any { it.status == "bad" }) MaterialTheme.ext.bad else MaterialTheme.ext.warn
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (ok) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Nothing needs attention", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        "Every check looks normal for this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Card
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
            Text(
                if (cards.size == 1) "1 thing needs attention" else "${cards.size} things need attention",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            cards.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 36.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val look = statusLook(c.status)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Show ${c.title}") { onJump(c.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(look.color))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.labelLarge, color = look.color)
                        Text(
                            c.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

@Composable
private fun GroupChips(groups: List<InsightGroupDto>, selected: String?, onGroup: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onGroup(null) }, label = { Text("All") })
        for (g in groups) {
            FilterChip(selected = selected == g.id, onClick = { onGroup(g.id) }, label = { Text(g.title) })
        }
    }
}

@Composable
private fun GroupHeader(group: InsightGroupDto) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
        Text(group.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        group.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One insight: verdict, the sentence, the number, the chart, the advice, the method. */
@Composable
internal fun InsightCard(
    card: InsightCardDto,
    howExpanded: Boolean,
    onToggleHow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = statusLook(card.status)
    val alert = card.status == "bad" || card.status == "watch"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (alert) BorderStroke(1.dp, look.color.copy(alpha = 0.45f)) else null,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(card.status)
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    card.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                card.value?.let { v ->
                    val parts = InsightFormat.valueParts(v, card.valueUnit)
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 120.dp)) {
                        Text(
                            parts.big,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (card.status == "info" || card.status == "learning") MaterialTheme.colorScheme.onSurface else look.color,
                            maxLines = 1,
                        )
                        parts.caption?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
            card.progress?.let { p ->
                val frac = if (p.need > 0) (p.have / p.need).toFloat().coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        drawStopIndicator = {},
                    )
                    Text(
                        InsightFormat.progress(p.have, p.need, p.unit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            card.chart?.let { InsightChart(it) }
            card.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.tip?.let { tip ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Lightbulb,
                        contentDescription = "Tip",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            val footnote = InsightFormat.footnote(card.window, card.sample)
            if (!card.how.isNullOrBlank() || footnote != null) {
                Column {
                    if (!card.how.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = onToggleHow)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "How we worked this out",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (howExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (howExpanded) "Hide method" else "Show method",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        AnimatedVisibility(visible = howExpanded) {
                            Text(
                                card.how,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    footnote?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── Car tab entry ───────────────────────────────────────────────────

/** The Car tab's way in: "Insights" with the attention count once loaded. */
@Composable
internal fun InsightsEntryCard(ui: InsightsUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val data = ui.data
    val byId = data?.insights?.associateBy { it.id }.orEmpty()
    val attention = data?.attention?.mapNotNull { byId[it] }.orEmpty()
    val ext = MaterialTheme.ext
    val (line, color) = when {
        data == null && ui.loading -> "Looking for patterns…" to MaterialTheme.colorScheme.onSurfaceVariant
        data == null -> "What your driving data says" to MaterialTheme.colorScheme.onSurfaceVariant
        attention.isEmpty() -> "All clear" to ext.good
        else -> (if (attention.size == 1) "1 needs attention" else "${attention.size} need attention") to
            (if (attention.any { it.status == "bad" }) ext.bad else ext.warn)
    }
    val detail = when {
        attention.isNotEmpty() -> attention.joinToString(", ") { it.title }
        data != null -> "${data.insights.count { it.status != "learning" }} findings, nothing to worry about"
        else -> null
    }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Insights", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(line, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                }
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Home tab entry ──────────────────────────────────────────────────

/**
 * Home's Insights card: the verdict (red / amber "N need attention", green
 * "All clear") and up to two attention headlines; tapping opens Insights.
 * Renders nothing when the load failed with nothing to show (an older server
 * without the endpoint), so Home never carries a broken card.
 */
@Composable
internal fun HomeInsightsCard(ui: InsightsUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val data = ui.data
    if (data == null && !ui.loading) return
    val byId = data?.insights?.associateBy { it.id }.orEmpty()
    val attention = data?.attention?.mapNotNull { byId[it] }.orEmpty()
    val ext = MaterialTheme.ext
    val tint = when {
        data == null -> MaterialTheme.colorScheme.onSurfaceVariant
        attention.isEmpty() -> ext.good
        attention.any { it.status == "bad" } -> ext.bad
        else -> ext.warn
    }
    val verdict = when {
        data == null -> "Looking for patterns…"
        attention.isEmpty() -> "All clear"
        attention.size == 1 -> "1 needs attention"
        else -> "${attention.size} need attention"
    }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (attention.isNotEmpty()) BorderStroke(1.dp, tint.copy(alpha = 0.45f)) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Insights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(tint.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (data != null) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(verdict, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (attention.isNotEmpty()) {
                for (c in attention.take(2)) {
                    val look = statusLook(c.status)
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(look.color))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            c.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (attention.size > 2) {
                    Text(
                        "+${attention.size - 2} more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp),
                    )
                }
            } else if (data != null) {
                Text(
                    "${data.insights.count { it.status != "learning" }} findings about your fuel, engine, tires and costs. Nothing to worry about.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
