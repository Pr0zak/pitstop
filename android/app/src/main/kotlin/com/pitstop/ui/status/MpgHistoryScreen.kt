package com.pitstop.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pitstop.domain.FuelCharts
import com.pitstop.domain.FuelCharts.MpgSpan
import com.pitstop.http.MpgPointDto
import com.pitstop.ui.components.ChartLegend
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.LegendEntry
import com.pitstop.ui.components.LegendGlyph
import com.pitstop.ui.components.PlotArea
import com.pitstop.ui.components.PlotChart
import com.pitstop.ui.components.PlotDots
import com.pitstop.ui.components.PlotLine
import com.pitstop.ui.components.PlotPoint
import com.pitstop.ui.components.tickLabel
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.util.UnitFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "MPG over time": Home's MPG trend page opened full screen. Monthly
 * points from `/analytics/mpg?window=month` (Home already holds them),
 * over 12 months, 3 years (default) or everything.
 */
@Composable
fun MpgHistoryScreen(points: List<MpgPointDto>?, onBack: () -> Unit) {
    var span by rememberSaveable { mutableStateOf(MpgSpan.ThreeYears) }
    var selected by rememberSaveable(span) { mutableIntStateOf(-1) }
    MpgHistoryContent(
        points = points,
        span = span,
        onSpan = { span = it },
        selected = selected,
        onSelect = { selected = it },
        onBack = onBack,
    )
}

/** Stateless body of [MpgHistoryScreen], rendered by screenshot tests. */
@Composable
internal fun MpgHistoryContent(
    points: List<MpgPointDto>?,
    span: MpgSpan,
    onSpan: (MpgSpan) -> Unit,
    selected: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val system = LocalUnitSystem.current
    val imperial = system == "imperial"
    val unit = UnitFormat.economyUnit(system)
    val series = remember(points, span) { points?.let { FuelCharts.mpgOverTime(it, span) } }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { DetailTopAppBar(title = if (imperial) "MPG over time" else "L/100 km over time", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in MpgSpan.entries) {
                    FilterChip(selected = s == span, onClick = { onSpan(s) }, label = { Text(s.label) })
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    when {
                        points == null -> Text(
                            "Loading…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        series == null || series.months.size < 2 -> Text(
                            "Not enough fillups yet to chart fuel economy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> MpgOverTimeBody(series, system, unit, selected, onSelect)
                    }
                }
            }
            Text(
                "Each dot is one month's economy, from that month's full-tank fillups. " +
                    "The line is the median of each month and the two before it, so one odd tank " +
                    "doesn't bend the trend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MpgOverTimeBody(
    series: FuelCharts.MpgOverTime,
    system: String,
    unit: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Display units: mpg, or L/100 km (converted point by point after the
    // median, as the Home chart does).
    fun disp(mpg: Double) = UnitFormat.economyValue(mpg, system) ?: mpg
    val dots = series.months.map { PlotPoint(it.x, disp(it.mpg)) }
    val roll = series.months.map { PlotPoint(it.x, disp(it.median3)) }
    val ys = dots.map { it.y }
    val yRange = FuelCharts.pad(ys.min(), ys.max())
    val ticks = FuelCharts.niceTicks(yRange.first, yRange.second)
    val decimals = FuelCharts.tickDecimals(FuelCharts.niceStep(yRange.first, yRange.second))
    val (x0, x1) = series.xRange
    val xTicks = if (series.span == MpgSpan.Year) FuelCharts.monthTicks(x0, x1, 3) else FuelCharts.yearTicks(x0, x1)

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "%.1f".format(disp(series.avgMpg)),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            " $unit",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    Text(
        "average over ${series.spanPhrase}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    PlotChart(
        xRange = series.xRange,
        yRange = yRange,
        yTicks = ticks,
        yLabel = { tickLabel(it, decimals) },
        xTicks = xTicks,
        description = "Monthly fuel economy over ${series.spanPhrase}: average " +
            "%.1f $unit, latest 3-month median %.1f".format(disp(series.avgMpg), roll.last().y),
        areas = listOf(PlotArea(roll, accent)),
        dots = listOf(PlotDots(dots, accent, radius = 3.dp, alpha = 0.55f)),
        lines = listOf(PlotLine(roll, accent, endDot = true)),
        hover = roll,
        selected = selected,
        onSelect = onSelect,
        height = 240.dp,
    )
    Spacer(Modifier.height(8.dp))
    val m = series.months.getOrNull(selected)
    Text(
        text = if (m == null) {
            "Tap or drag the chart to read a month"
        } else {
            "${m.period.format(MONTH_YEAR)} · ${"%.1f".format(disp(m.mpg))} $unit · " +
                "${m.fills} fill${if (m.fills == 1) "" else "s"}\n3-mo median ${"%.1f".format(disp(m.median3))}"
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (m == null) null else FontFamily.Monospace,
        color = if (m == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(8.dp))
    ChartLegend(
        listOf(
            LegendEntry("Month", accent.copy(alpha = 0.55f), LegendGlyph.Dot),
            LegendEntry("3-month median", accent, LegendGlyph.LineWithDot),
        ),
    )
}

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
