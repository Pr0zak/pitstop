package com.pitstop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Phone-side mirror of the web Overview hero strip (frontend OverviewView.vue).
 * Same four metrics, same instrument-cluster type treatment, derived from the
 * same /fillups + /analytics/mpg payloads.
 *
 * On the phone we render a 2×2 grid (two rows of two cards) instead of the web
 * single-row strip — fits the 412 dp Pixel viewport without horizontal scroll.
 */

data class HeroCardData(
    val avgConsumptionMpg: Double?,
    val latestPpg: Double?,
    val ppgDeltaPct: Double?,
    val monthCost: Double,
    val monthCount: Int,
    val milesSinceFill: Double?,
    val mpgSeries: List<Double>,
)

@Composable
fun FuelHeroCards(
    data: HeroCardData,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCard(
                modifier = Modifier.weight(1f),
                title = "Avg consumption",
                value = data.avgConsumptionMpg?.let { "%.1f".format(it) } ?: "—",
                unit = "mpg",
                sub = "90-day rolling",
            )
            HeroCard(
                modifier = Modifier.weight(1f),
                title = "Gas price",
                value = data.latestPpg?.let { "$%.3f".format(it) } ?: "—",
                unit = "/gal",
                sub = data.ppgDeltaPct?.let { delta ->
                    val arrow = when {
                        delta > 0.5 -> "▲"
                        delta < -0.5 -> "▼"
                        else -> "·"
                    }
                    "$arrow ${"%.1f".format(abs(delta))}% vs avg"
                } ?: "—",
                subColor = data.ppgDeltaPct?.let { delta ->
                    when {
                        delta > 0.5 -> Color(0xFFFF3A2E) // danger when current is more expensive
                        delta < -0.5 -> Color(0xFF4ADE80) // success when cheaper
                        else -> null
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCard(
                modifier = Modifier.weight(1f),
                title = "This month",
                value = "$%.2f".format(data.monthCost),
                unit = "",
                sub = "${data.monthCount} fillup${if (data.monthCount == 1) "" else "s"}",
            )
            HeroCard(
                modifier = Modifier.weight(1f),
                title = "Since last fill",
                value = data.milesSinceFill?.let { "%.0f".format(it) } ?: "—",
                unit = "mi",
                sub = "live odo vs last",
            )
        }

        if (data.mpgSeries.size >= 2) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "MPG (year)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(6.dp))
                    Sparkline(
                        series = data.mpgSeries,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String,
    sub: String,
    subColor: Color? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing + 0.4.sp(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        letterSpacing = (-1.2).sp(),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                sub,
                modifier = Modifier.padding(top = 2.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tiny line chart drawn directly with Canvas. Adequate for the hero strip's
 * sparkline (no axes, no legend, no zoom — just the shape of the trend).
 */
@Composable
private fun Sparkline(
    series: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val cleaned = series.filter { !it.isNaN() && it > 0 }
    if (cleaned.size < 2) {
        Box(modifier) {
            Text(
                "Not enough data",
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val minV = cleaned.min()
    val maxV = cleaned.max()
    val rangeV = (maxV - minV).coerceAtLeast(0.0001)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padX = 4f
        val padY = 6f
        val plotW = w - padX * 2
        val plotH = h - padY * 2

        val stepX = if (cleaned.size <= 1) 0f else plotW / (cleaned.size - 1)

        // Faint grid line at min + max + mid for context
        drawLine(
            color = color.copy(alpha = 0.10f),
            start = Offset(padX, padY + plotH / 2),
            end = Offset(padX + plotW, padY + plotH / 2),
            strokeWidth = 1f,
        )

        val path = Path()
        cleaned.forEachIndexed { i, v ->
            val x = padX + stepX * i
            val y = padY + plotH - ((v - minV) / rangeV).toFloat() * plotH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 2.5f))

        // Filled area under the line for visual mass — coral 12% so the
        // sparkline reads like the gauge's active-fill arc.
        val fillPath = Path().apply {
            addPath(path)
            lineTo(padX + stepX * (cleaned.size - 1), padY + plotH)
            lineTo(padX, padY + plotH)
            close()
        }
        drawPath(path = fillPath, color = color.copy(alpha = 0.12f))
    }
}

private fun Number.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(),
    androidx.compose.ui.unit.TextUnitType.Sp,
)
