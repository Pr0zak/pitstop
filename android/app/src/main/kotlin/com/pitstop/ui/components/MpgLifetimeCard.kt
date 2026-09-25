package com.pitstop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pitstop.http.MpgPointDto
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import kotlin.math.abs

/**
 * Lifetime-average MPG card. Hits /analytics/mpg?window=all (yearly
 * buckets, one row per calendar year with fillup_count). Big number is
 * the weighted lifetime average; chart is one bar per year, tap to see
 * that year's mpg + fillup count.
 *
 * Trend chip compares the last 90 days to lifetime — but since we only
 * have yearly granularity on this endpoint, we use the *most recent
 * year* (the partial in-progress one) as a proxy for "recent" and the
 * weighted average across every other year for "lifetime". Good enough
 * for a single-glance chip; the user clicks through to the Live tab
 * for nuance.
 */
@Composable
fun MpgLifetimeCard(
    yearlyPoints: List<MpgPointDto>,
    modifier: Modifier = Modifier,
    /** False when rendered as a [TrendsCarousel] page: no card, no title. */
    framed: Boolean = true,
) {
    if (yearlyPoints.size < 2) {
        return
    }
    val valid = yearlyPoints.filter { (it.mpg ?: 0.0) > 0 }
    if (valid.size < 2) return

    // Fill counts are known only if the server sent one on every point. An
    // older backend omits the field; summing `?: 0` then printed "0 fillups"
    // under a perfectly real MPG. Unknown → no count line, plain average.
    val countsKnown = valid.all { it.fillupCount != null }
    val totalFills = if (countsKnown) valid.sumOf { it.fillupCount!!.toLong() } else 0L
    val lifetime = if (totalFills > 0) {
        valid.sumOf { (it.mpg ?: 0.0) * (it.fillupCount ?: 0) } / totalFills
    } else {
        valid.mapNotNull { it.mpg }.average()
    }

    // "Recent" = most recent year's point; "baseline" = weighted avg
    // of all earlier years. % delta drives the trend chip.
    val mostRecent = valid.last()
    val older = valid.dropLast(1)
    val olderFills = if (countsKnown) older.sumOf { it.fillupCount!!.toLong() } else 0L
    val baseline = if (olderFills > 0) {
        older.sumOf { (it.mpg ?: 0.0) * (it.fillupCount ?: 0) } / olderFills
    } else {
        older.mapNotNull { it.mpg }.takeIf { it.isNotEmpty() }?.average()
    }
    val recentMpg = mostRecent.mpg
    val deltaPct = if (recentMpg != null && baseline != null && baseline > 0) {
        ((recentMpg - baseline) / baseline) * 100.0
    } else null

    // Everything above is in mpg, the server's unit. Display values are
    // converted once here. The trend chip's COLOUR follows the mpg delta
    // (better economy is green in either system) while its ARROW and % are
    // taken from the displayed numbers, so a metric user sees L/100km fall
    // with a ▼ that is still green.
    val system = LocalUnitSystem.current
    val lifetimeDisplay = UnitFormat.economyValue(lifetime, system)
    val recentDisplay = UnitFormat.economyValue(recentMpg, system)
    val baselineDisplay = UnitFormat.economyValue(baseline, system)
    val displayDeltaPct = if (recentDisplay != null && baselineDisplay != null && baselineDisplay > 0) {
        ((recentDisplay - baselineDisplay) / baselineDisplay) * 100.0
    } else null
    val displayYearly = valid.map { it.copy(mpg = UnitFormat.economyValue(it.mpg, system)) }

    TrendFrame(framed = framed, modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (framed) {
                Text(
                    if (system == "imperial") "Lifetime MPG" else "Lifetime L/100 km",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = lifetimeDisplay?.let { "%.1f".format(it) } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        letterSpacing = (-1.2).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    UnitFormat.economyUnit(system),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (deltaPct != null && displayDeltaPct != null) {
                    val improved = deltaPct > 0.5
                    val worse = deltaPct < -0.5
                    val arrow = when {
                        displayDeltaPct > 0.5 -> "▲"
                        displayDeltaPct < -0.5 -> "▼"
                        else -> "·"
                    }
                    val tint = when {
                        improved -> MaterialTheme.ext.good
                        worse -> MaterialTheme.ext.bad
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "$arrow ${"%.1f".format(abs(displayDeltaPct))}%",
                        color = tint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (totalFills > 0) Text(
                "${UnitFormat.count(totalFills)} fillup${if (totalFills == 1L) "" else "s"}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            YearlyBarChart(
                yearly = displayYearly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .semantics {
                        contentDescription = "Yearly fuel economy: " +
                            displayYearly.joinToString(", ") {
                                "${it.period} ${"%.1f".format(it.mpg ?: 0.0)}"
                            } + " ${UnitFormat.economyUnit(system)}"
                    },
                accent = MaterialTheme.colorScheme.primary,
                neutral = MaterialTheme.colorScheme.onSurfaceVariant,
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
                onSurface = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                valid.forEach { p ->
                    Text(
                        text = p.period.takeLast(2),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun YearlyBarChart(
    yearly: List<MpgPointDto>,
    modifier: Modifier,
    accent: Color,
    neutral: Color,
    onSurfaceVariant: Color,
    onSurface: Color,
) {
    val mpgs = yearly.mapNotNull { it.mpg }
    val minV = (mpgs.min() * 0.9).coerceAtLeast(0.0)
    val maxV = mpgs.max() * 1.05
    val rangeV = (maxV - minV).coerceAtLeast(0.0001)
    var selected by remember { mutableIntStateOf(-1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(yearly) {
                    detectTapGestures(
                        onTap = { offset ->
                            val w = size.width.toFloat()
                            val barCount = yearly.size
                            val slotW = w / barCount
                            val idx = (offset.x / slotW).toInt().coerceIn(0, barCount - 1)
                            selected = if (selected == idx) -1 else idx
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val padTop = 8f
            val padBot = 4f
            val plotH = h - padTop - padBot
            val barCount = yearly.size
            val slotW = w / barCount
            val barW = slotW * 0.62f
            yearly.forEachIndexed { i, p ->
                val v = p.mpg ?: return@forEachIndexed
                val barH = ((v - minV) / rangeV).toFloat() * plotH
                val x = i * slotW + (slotW - barW) / 2f
                val y = padTop + plotH - barH
                // Accent marks one bar — the tapped one, else the latest —
                // the rest stay neutral, like the monthly-spend chart.
                val highlight = if (selected >= 0) selected else yearly.lastIndex
                val tint = if (i == highlight) accent else neutral.copy(alpha = 0.35f)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                )
            }

            // Selected-bar value label on top.
            if (selected in yearly.indices) {
                val p = yearly[selected]
                val v = p.mpg
                if (v != null) {
                    val barH = ((v - minV) / rangeV).toFloat() * plotH
                    val x = selected * slotW + slotW / 2f
                    val y = padTop + plotH - barH - 6f
                    val paint = android.graphics.Paint().apply {
                        this.color = onSurface.toArgb()
                        this.textSize = labelTextSizePx
                        this.isAntiAlias = true
                        this.textAlign = android.graphics.Paint.Align.CENTER
                        this.typeface = android.graphics.Typeface.MONOSPACE
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "%.1f".format(v),
                        x,
                        y,
                        paint,
                    )
                }
            }
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
