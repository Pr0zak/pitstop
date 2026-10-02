package com.pitstop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pitstop.domain.FuelCharts
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Fuel spend, this year vs last": running total of fillup cost by day of
 * year, this year (accent, end dot) against last year (blue), as stepped
 * lines — spend only moves on a fillup day. The headline compares this
 * year's total with last year's on the date of this year's latest fillup.
 *
 * Tap selects a fillup (no drag: on Home this is a pager page and the
 * horizontal drag belongs to the pager).
 */
@Composable
fun SpendYoyChart(
    yoy: FuelCharts.SpendYoy,
    modifier: Modifier = Modifier,
    /** False when rendered as a [TrendsCarousel] page: no card, no title. */
    framed: Boolean = true,
    initialSelected: Int = -1,
) {
    var selected by remember(yoy) { mutableIntStateOf(initialSelected) }
    val accent = MaterialTheme.colorScheme.primary
    val compare = MaterialTheme.ext.compare
    val year = yoy.year
    val prev = year - 1
    val now = yoy.thisYear.map { PlotPoint(it.x, it.total) }
    val last = yoy.lastYear.map { PlotPoint(it.x, it.total) }
    fun step(p: List<PlotPoint>) = FuelCharts.stepped(p.map { it.x to it.y }).map { PlotPoint(it.first, it.second) }
    val yMax = maxOf(yoy.lastYear.last().total, yoy.total) * 1.06
    val ticks = FuelCharts.niceTicks(0.0, yMax)
    TrendFrame(framed = framed, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            if (framed) {
                Text(
                    "Fuel spend, $year vs $prev",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    UnitFormat.money(yoy.total, 0),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    " so far in $year",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                spendYoySubline(yoy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PlotChart(
                xRange = 0.0 to 365.0,
                yRange = 0.0 to yMax,
                yTicks = ticks,
                yLabel = ::compactMoney,
                xTicks = FuelCharts.quarterTicks(year),
                description = "Fuel spend by day of year: ${UnitFormat.money(yoy.total, 0)} so far in $year, " +
                    spendYoySubline(yoy),
                lines = listOf(PlotLine(step(last), compare), PlotLine(step(now), accent, endDot = true)),
                hover = now.drop(1),
                selected = selected,
                onSelect = { selected = it },
                scrub = false,
                height = TrendChartHeight,
            )
            Spacer(Modifier.height(4.dp))
            val p = yoy.thisYear.drop(1).getOrNull(selected)
            Text(
                text = if (p?.date == null) {
                    "Tap the line to read a fillup"
                } else {
                    "${p.date.format(DAY)} · $year: ${UnitFormat.money(p.total, 0)}\n" +
                        "$prev by then: ${UnitFormat.money(yoy.lastYearAt(p.x), 0)}"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (p == null) null else FontFamily.Monospace,
                color = if (p == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.heightIn(min = 34.dp),
            )
            Spacer(Modifier.height(4.dp))
            ChartLegend(
                listOfNotNull(
                    LegendEntry(year.toString(), accent, LegendGlyph.LineWithDot),
                    LegendEntry(prev.toString(), compare, LegendGlyph.Line).takeIf { yoy.lastYearHasData },
                ),
            )
        }
    }
}

/** "$231 more than 2025 by the same date ($1,143)". */
internal fun spendYoySubline(yoy: FuelCharts.SpendYoy): String {
    val prev = yoy.year - 1
    if (!yoy.lastYearHasData) return "No fillups logged in $prev to compare"
    val d = yoy.delta
    val diff = UnitFormat.money(kotlin.math.abs(d), 0) + if (d >= 0) " more" else " less"
    return "$diff than $prev by the same date (${UnitFormat.money(yoy.lastYearSameDate, 0)})"
}

/** "$800", "$1.5k", "$2k". */
private fun compactMoney(v: Double): String =
    if (v >= 1000) {
        val k = "%.1f".format(v / 1000).removeSuffix(".0").removeSuffix(",0")
        UnitFormat.money(0.0, 0).replace("0", "") + k + "k"
    } else {
        UnitFormat.money(v, 0)
    }

private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
