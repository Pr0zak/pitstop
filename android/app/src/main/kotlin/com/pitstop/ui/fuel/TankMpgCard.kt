package com.pitstop.ui.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pitstop.domain.FuelCharts
import com.pitstop.ui.components.ChartLegend
import com.pitstop.ui.components.HoverMode
import com.pitstop.ui.components.LegendEntry
import com.pitstop.ui.components.LegendGlyph
import com.pitstop.ui.components.PlotChart
import com.pitstop.ui.components.PlotDots
import com.pitstop.ui.components.PlotLine
import com.pitstop.ui.components.PlotPoint
import com.pitstop.ui.components.tickLabel
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import kotlin.math.abs

/**
 * "MPG per tank": the last 20 full tanks (partial and missed fills left
 * out) as dots with a 5-tank rolling average. The headline compares the
 * latest tank with the series average. A dot opens that fillup; the rest
 * of the card opens the Fuel tab.
 */
@Composable
fun TankMpgCard(
    series: FuelCharts.TankSeries,
    modifier: Modifier = Modifier,
    onOpenFillup: (String) -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    val system = LocalUnitSystem.current
    val unit = UnitFormat.economyUnit(system)
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.onSurface
    fun disp(mpg: Double) = UnitFormat.economyValue(mpg, system) ?: mpg
    val dots = series.tanks.map { PlotPoint(it.x, disp(it.mpg)) }
    val roll = series.tanks.map { PlotPoint(it.x, disp(it.roll5)) }
    val yRange = FuelCharts.pad(dots.minOf { it.y }, dots.maxOf { it.y })
    val decimals = FuelCharts.tickDecimals(FuelCharts.niceStep(yRange.first, yRange.second))
    val (x0, x1) = series.xRange
    val last = disp(series.last.mpg)
    val avg = disp(series.avg)
    val delta = last - avg
    val better = if (UnitFormat.economyHigherIsBetter(system)) delta >= 0 else delta <= 0
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = "Open the Fuel tab", role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (system == "imperial") "MPG per tank" else "Economy per tank",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Last ${series.tanks.size} full tanks  ·  tap a dot for the fillup",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.1f".format(last),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    " $unit last tank",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                "${if (delta >= 0) "+" else "−"}${"%.1f".format(abs(delta))} vs the ${series.tanks.size}-tank " +
                    "average of ${"%.1f".format(avg)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (abs(delta) < 0.05) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else if (better) {
                    MaterialTheme.ext.good
                } else {
                    MaterialTheme.ext.warn
                },
            )
            Spacer(Modifier.height(8.dp))
            PlotChart(
                xRange = series.xRange,
                yRange = yRange,
                yTicks = FuelCharts.niceTicks(yRange.first, yRange.second),
                yLabel = { tickLabel(it, decimals) },
                xTicks = FuelCharts.monthTicks(x0, x1, 4),
                description = "Economy of the last ${series.tanks.size} full tanks: latest " +
                    "%.1f $unit, average %.1f".format(last, avg),
                dots = listOf(PlotDots(dots, accent, radius = 4.5.dp)),
                lines = listOf(PlotLine(roll, line)),
                hover = dots,
                hoverMode = HoverMode.NearestXY,
                onSelect = { i -> series.tanks.getOrNull(i)?.let { onOpenFillup(it.fillupId) } },
                scrub = false,
                height = 150.dp,
            )
            Spacer(Modifier.height(6.dp))
            ChartLegend(
                listOf(
                    LegendEntry("Tank", accent, LegendGlyph.Dot),
                    LegendEntry("5-tank average", line, LegendGlyph.Line),
                ),
            )
        }
    }
}
