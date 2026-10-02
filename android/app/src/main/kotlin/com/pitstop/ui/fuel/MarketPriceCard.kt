package com.pitstop.ui.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "What you paid vs the market": every fillup's price per gallon (dots)
 * against the EIA US weekly retail average (line) over the last 52 weeks.
 * The headline is the mean gap per gallon; the dollar figure is that gap
 * times the gallons bought in the window. Metric users read it per litre
 * (the gallons-based dollar figure is unit-free).
 */
@Composable
fun MarketPriceCard(
    data: FuelCharts.MarketCompare,
    modifier: Modifier = Modifier,
    initialSelected: Int = -1,
    /**
     * Home's "Price you paid": shorter chart, no per-dot readout, and the
     * whole card is one button ([onClick] → the Fuel tab).
     */
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val system = LocalUnitSystem.current
    val perUnit = UnitFormat.perVolumeUnit(system)
    val unitWord = if (system == "imperial") "gallon" else "litre"
    val accent = MaterialTheme.colorScheme.primary
    val compare = MaterialTheme.ext.compare
    var selected by remember(data) { mutableIntStateOf(initialSelected) }
    fun pv(perGal: Double) = UnitFormat.pricePerVolumeValue(perGal, system) ?: perGal
    val fills = data.fills.map { PlotPoint(it.x, pv(it.pricePerGal)) }
    val market = data.weeks.map { PlotPoint(it.x, pv(it.price)) }
    val ys = fills.map { it.y } + market.map { it.y }
    val yRange = FuelCharts.pad(ys.min(), ys.max())
    val ticks = FuelCharts.niceTicks(yRange.first, yRange.second)
    val decimals = maxOf(2, FuelCharts.tickDecimals(FuelCharts.niceStep(yRange.first, yRange.second)))
    val (x0, x1) = data.xRange
    val cents = cents(pv(data.meanDiff))
    val headline = "$cents¢ ${if (data.below) "below" else "above"} average"
    val sub = "per $unitWord over ${data.fills.size} fillup${if (data.fills.size == 1) "" else "s"}  ·  " +
        "${if (data.below) "saved" else "cost"} about ${UnitFormat.money(data.dollars, 0)}"

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
                if (compact) "Price you paid" else "What you paid vs the market",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (compact) "Last 12 months  ·  vs the US weekly average" else "Last 52 weeks  ·  US weekly average from EIA",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$cents¢",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    " ${if (data.below) "below" else "above"} average",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (data.below) MaterialTheme.ext.good else MaterialTheme.ext.warn,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            PlotChart(
                xRange = data.xRange,
                yRange = yRange,
                yTicks = ticks,
                yLabel = { UnitFormat.money(it, decimals) },
                xTicks = FuelCharts.monthTicks(x0, x1, 2),
                description = "Price you paid per $unitWord at each fillup against the US weekly average: " +
                    "$headline, $sub",
                lines = listOf(PlotLine(market, compare)),
                dots = listOf(PlotDots(fills, accent, radius = 4.5.dp)),
                hover = if (compact) emptyList() else fills,
                hoverMode = HoverMode.NearestXY,
                selected = selected,
                onSelect = { selected = it },
                height = if (compact) 150.dp else 200.dp,
            )
            Spacer(Modifier.height(4.dp))
            val f = data.fills.getOrNull(selected)
            if (!compact) {
            Text(
                text = if (f == null) {
                    "Tap a dot to read a fillup"
                } else {
                    val d = pv(f.diff)
                    "${f.date.format(DAY)} · ${UnitFormat.pricePerVolume(f.pricePerGal, system)} · " +
                        "${UnitFormat.volumeGal(f.gallons, system)}\n" +
                        "US avg that week ${UnitFormat.money(pv(f.marketPrice), 3)} · " +
                        "${if (d < 0) "−" else "+"}${cents(d)}¢"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (f == null) null else FontFamily.Monospace,
                color = if (f == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            }
            Spacer(Modifier.height(6.dp))
            ChartLegend(
                listOf(
                    LegendEntry("Your fillups ($perUnit)", accent, LegendGlyph.Dot),
                    LegendEntry("US weekly average", compare, LegendGlyph.Line),
                ),
            )
        }
    }
}

/** Whole cents of a price gap, unsigned. */
private fun cents(perUnit: Double): Int = abs(perUnit * 100).roundToInt()

private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
