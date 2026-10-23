package com.pitstop.ui.tires

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.domain.TireHistory
import com.pitstop.domain.VehicleSignals
import com.pitstop.http.InsightCardDto
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.TiresCard
import com.pitstop.ui.insights.InsightCard
import com.pitstop.ui.insights.InsightChart
import com.pitstop.ui.insights.InsightsViewModel
import com.pitstop.ui.status.HomeTires
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.requireActivity
import java.time.ZoneId

/** Corner colours, the same four as the web's Tires charts and the trip Tires card. */
internal val TIRE_CORNER_COLORS: Map<String, Color> = mapOf(
    VehicleSignals.Corner.FL.label to Color(0xFF2F81F7),
    VehicleSignals.Corner.FR.label to Color(0xFFF97316),
    VehicleSignals.Corner.RL.label to Color(0xFF3FB950),
    VehicleSignals.Corner.RR.label to Color(0xFFD946EF),
)

/**
 * Home → Tires: tapping Home's Tires card. Top to bottom:
 *   1. Right now: the live four-corner grid vs the door placard
 *   2. Range chips (7 / 30 / 90 days)
 *   3. Each tire: current cold psi, flags, trend (psi/week), spread vs the
 *      highest tire, distance from the placard, typical warm-up
 *   4. Pressure over time per corner (cold or daily average) with the placard
 *   5. Tire temperature (daily hottest) against the outside temperature
 *   6. Leak check: the server's tire insights, incl. the temperature-corrected
 *      pressure trend
 * Web parity: Analytics → Tires (TireAnalyticsSection.vue) + the tire insight.
 */
@Composable
fun TireDetailScreen(
    live: HomeTires?,
    onBack: () -> Unit,
    viewModel: TireDetailViewModel = hiltViewModel(),
    insightsVm: InsightsViewModel = hiltViewModel(LocalContext.current.requireActivity()),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val insights by insightsVm.ui.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(TireHistory.PressureMode.Cold) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { insightsVm.refreshIfStale() }
    TireDetailContent(
        ui = ui,
        live = live,
        tireInsights = TireHistory.tireInsights(insights.data?.insights.orEmpty()),
        mode = mode,
        onMode = { mode = it },
        onDays = viewModel::setDays,
        expandedHow = expanded,
        onToggleHow = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
        onRefresh = viewModel::refresh,
        onBack = onBack,
    )
}

/** Stateless body of [TireDetailScreen], rendered by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TireDetailContent(
    ui: TireDetailUi,
    live: HomeTires?,
    tireInsights: List<InsightCardDto>,
    mode: TireHistory.PressureMode,
    onMode: (TireHistory.PressureMode) -> Unit,
    onDays: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    expandedHow: Set<String> = emptySet(),
    onToggleHow: (String) -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val system = LocalUnitSystem.current
    val data = ui.data
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(title = "Tires", onBack = onBack) {
                IconButton(onClick = onRefresh, enabled = !ui.loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh tire history")
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
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                live?.let {
                    TiresCard(
                        tires = it.tires,
                        newestMs = it.newestMs,
                        nowMs = nowMs,
                        system = system,
                        placardFrontKpa = it.placardFrontKpa,
                        placardRearKpa = it.placardRearKpa,
                        title = "Right now",
                    )
                }

                RangeChips(ui.days, onDays)
                if (ui.loading && data != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                when {
                    data == null && ui.error != null -> EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = "Couldn't load tire history",
                        body = ui.error,
                        actionLabel = "Retry",
                        onAction = onRefresh,
                    )
                    data == null -> Column(
                        Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Loading tire history…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    !data.hasData -> SectionCard(title = null) {
                        Text("No tire readings in the last ${ui.days} days.", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Tire pressures arrive from the WiCAN while the car is home, or from the phone while you drive.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        ui.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ext.warn)
                        }
                        val stats = remember(data, zone) { TireHistory.cornerStats(data, zone) }
                        SectionTitle("Each tire")
                        CornerGrid(stats, showPlacard = TireHistory.placardText(data) != null)

                        val pressure = remember(data, mode) { TireHistory.pressureChart(data, mode) }
                        SectionCard(title = "Pressure over time") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (m in TireHistory.PressureMode.entries) {
                                    FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(m.label) })
                                }
                            }
                            if (pressure == null) {
                                Text(
                                    if (mode == TireHistory.PressureMode.Cold) {
                                        "No cold readings yet. A tire is read cold after the car has been parked a few hours."
                                    } else {
                                        "No pressure readings in this range."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                InsightChart(pressure, height = 200.dp, zone = zone, seriesColors = TIRE_CORNER_COLORS)
                            }
                            val coldHours = (data.coldParkHours ?: 3.0).toInt()
                            Text(
                                if (mode == TireHistory.PressureMode.Cold) {
                                    "Cold = the first reading after the car sat parked $coldHours hours or more. That is the number to compare with the door placard."
                                } else {
                                    "The day's average of every reading. Tires run a few psi higher once warm from driving."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (TireHistory.placardRef(data) == null) {
                                TireHistory.placardText(data)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        val temps = remember(data, system) { TireHistory.tempChart(data, system) }
                        SectionCard(title = "Tire temperature") {
                            if (temps == null) {
                                Text(
                                    "No tire temperatures in this range.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                InsightChart(
                                    temps,
                                    height = 180.dp,
                                    zone = zone,
                                    seriesColors = TIRE_CORNER_COLORS,
                                    dashedSeries = setOf(TireHistory.OUTSIDE),
                                )
                            }
                            Text(
                                "Each tire's hottest reading of the day, with the day's average outside temperature.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (tireInsights.isNotEmpty()) {
                    SectionTitle("Leak check")
                    for (c in tireInsights) {
                        InsightCard(card = c, howExpanded = c.id in expandedHow, onToggleHow = { onToggleHow(c.id) })
                    }
                }

                if (data?.hasData == true) {
                    val leak = TireHistory.psiPerWeek(data.slowLeakPsiPerWeek)
                    val n = (data.slowLeakMinSamples ?: 4.0).toInt()
                    Text(
                        "Cold pressure moves about 1 psi for every 10 °F of outside temperature, so all four " +
                            "dropping together with the weather is normal. One tire dropping alone is the sign " +
                            "of a leak. \"Possible slow leak\" means falling $leak or faster over $n or more cold " +
                            "readings. Days are UTC days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeChips(days: Int, onDays: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (d in TireHistory.RANGES) {
            FilterChip(selected = d == days, onClick = { onDays(d) }, label = { Text("$d days") })
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SectionCard(title: String?, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

/** FL FR / RL RR, like the car seen from above. */
@Composable
private fun CornerGrid(stats: List<TireHistory.CornerStat>, showPlacard: Boolean) {
    val by = stats.associateBy { it.corner }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in listOf(
            VehicleSignals.Corner.FL to VehicleSignals.Corner.FR,
            VehicleSignals.Corner.RL to VehicleSignals.Corner.RR,
        )) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in listOf(row.first, row.second)) {
                    by[c]?.let { CornerCard(it, showPlacard, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun CornerCard(s: TireHistory.CornerStat, showPlacard: Boolean, modifier: Modifier = Modifier) {
    val ext = MaterialTheme.ext
    val flagColor = if (s.flagSevere) ext.bad else ext.warn
    val shape = RoundedCornerShape(12.dp)
    val a11y = buildString {
        append(s.corner.label).append(", cold ")
        append(if (s.coldPsi == "—") "no reading" else "${s.coldPsi} psi")
        s.flag?.let { append(", ").append(it.lowercase()) }
        append(", trend ").append(s.trend)
    }
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(1.dp, if (s.flag != null) flagColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                shape,
            )
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 12.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TIRE_CORNER_COLORS[s.corner.label] ?: MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                s.corner.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                s.coldPsi,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (s.flag != null) flagColor else MaterialTheme.colorScheme.onSurface,
            )
            if (s.coldPsi != "—") {
                Text(
                    " psi",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(s.coldCaption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.flag?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = flagColor)
        }
        Spacer(Modifier.height(4.dp))
        StatLine("Trend", s.trend, valueColor = if (s.slowLeak) ext.bad else null, muted = !s.trendKnown)
        StatLine("vs other tires", s.spread ?: "—", valueColor = if (s.low) ext.warn else null)
        if (showPlacard) {
            StatLine(
                "vs placard",
                s.vsPlacard ?: "—",
                valueColor = when {
                    s.underInflated -> ext.bad
                    s.belowSpec -> ext.warn
                    else -> null
                },
            )
        }
        StatLine("Warms up by", s.warmup ?: "—")
    }
}

@Composable
private fun StatLine(label: String, value: String, valueColor: Color? = null, muted: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (muted) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = valueColor ?: if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
