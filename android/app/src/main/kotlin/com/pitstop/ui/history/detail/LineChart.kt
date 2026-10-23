package com.pitstop.ui.history.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme

/**
 * Single Compose-Canvas line chart used by the trip-detail timeline.
 * Multiple series share the same X axis (time) and each plots against
 * its own auto-fit Y range so a 0–80 mph speed line and a 600–4000 RPM
 * line can coexist on the same canvas. Each series is drawn in its own
 * color; the legend chip row is rendered by the caller via the
 * series visibility map.
 *
 * Light/dark is handled by passing MaterialTheme colors as ink/grid.
 * No tests + no preview — wired in production by TripDetailScreen.
 */
@Composable
internal fun LineChart(
    series: List<DisplaySeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    /**
     * One Y range for every series (with tick labels), instead of each
     * auto-fitting its own. For like-for-like lines — four tire pressures —
     * where the spread between them IS the story.
     */
    sharedYAxis: Boolean = false,
    /** Dashed horizontal guides (shared-axis charts), e.g. "placard 35 psi". */
    referenceLines: List<ReferenceLine> = emptyList(),
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val textPxSize = with(density) { 10.sp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val left = 48f
            val right = w - 12f
            val top = 12f
            val bottom = h - 24f
            val plotW = (right - left).coerceAtLeast(1f)
            val plotH = (bottom - top).coerceAtLeast(1f)

            // Axis lines.
            drawLine(
                color = grid,
                start = Offset(left, top),
                end = Offset(left, bottom),
                strokeWidth = 1f,
            )
            drawLine(
                color = grid,
                start = Offset(left, bottom),
                end = Offset(right, bottom),
                strokeWidth = 1f,
            )

            if (series.isEmpty()) return@Canvas

            // Shared time bounds across all visible series.
            val tBounds = series.timeBoundsForDisplay() ?: return@Canvas
            val tMin = tBounds.first.toDouble()
            val tMax = tBounds.second.toDouble()
            val tRange = (tMax - tMin).coerceAtLeast(1.0)

            // Horizontal grid + tick labels — three rows. Labels only
            // shown when there's a single series (otherwise the dual-
            // axis math gets noisy on a phone).
            for (i in 1..3) {
                val y = top + (plotH * i / 4)
                drawLine(
                    color = grid.copy(alpha = 0.35f),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1f,
                )
            }

            val shared: Pair<Double, Double>? = if (sharedYAxis) {
                series.flatMap { it.series.points }.bounds()?.let { (lo, hi) ->
                    // Stretch the axis so every guide is on the canvas.
                    val refs = referenceLines.map { it.value }
                    (refs + lo).min() to (refs + hi).max()
                }
            } else {
                null
            }
            if (shared != null) {
                val (yMin, yMax) = shared
                val yRange = (yMax - yMin).coerceAtLeast(1e-6)
                val nc = drawContext.canvas.nativeCanvas
                for (ref in referenceLines) {
                    val y = bottom - (((ref.value - yMin) / yRange) * plotH).toFloat()
                    drawLine(
                        color = axis.copy(alpha = 0.7f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 2f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                    nc.drawText(
                        ref.label, right - 4f, y - 6f,
                        android.graphics.Paint().apply {
                            color = axis.toArgb()
                            textSize = textPxSize
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.RIGHT
                        },
                    )
                }
            }
            // Plot each series in its own y-range (or the shared one).
            for (ds in series) {
                if (ds.series.points.size < 2) continue
                val ybounds = shared ?: ds.yRange ?: ds.series.points.bounds() ?: continue
                val (yMin, yMax) = ybounds
                val yRange = (yMax - yMin).coerceAtLeast(1e-6)

                val path = Path()
                var started = false
                var prevT = 0L
                var prevY = 0f
                val carry = ds.carryMs
                fun xAt(t: Double) = left + (((t - tMin) / tRange) * plotW).toFloat()
                for (p in ds.series.points) {
                    val x = xAt(p.tMillis.toDouble())
                    val yN = (p.value - yMin) / yRange
                    val y = bottom - (yN * plotH).toFloat()
                    val gap = carry != null && started && p.tMillis - prevT > carry
                    when {
                        !started -> {
                            path.moveTo(x, y)
                            started = true
                        }
                        gap -> {
                            // Carry the last value for [carry] at most, then
                            // lift the pen: a dropout is a gap, never a line
                            // through data that doesn't exist (and never 0).
                            if (ds.step) path.lineTo(xAt((prevT + carry!!).toDouble()), prevY)
                            path.moveTo(x, y)
                        }
                        ds.step -> {
                            path.lineTo(x, prevY)
                            path.lineTo(x, y)
                        }
                        else -> path.lineTo(x, y)
                    }
                    prevT = p.tMillis
                    prevY = y
                }
                drawPath(
                    path = path,
                    color = ds.color,
                    style = Stroke(width = 2.5f),
                )
            }

            // If exactly one series is visible, draw its Y-axis labels
            // — gives a sense of scale that's otherwise lost when each
            // series is auto-fit to its own range.
            if (series.size == 1 || shared != null) {
                val s = series.first()
                val bounds = shared ?: s.yRange ?: s.series.points.bounds()
                if (bounds != null) {
                    val (yMin, yMax) = bounds
                    val ticks = listOf(yMin, (yMin + yMax) / 2, yMax)
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val paint = android.graphics.Paint().apply {
                        color = axis.toArgb()
                        textSize = textPxSize
                        isAntiAlias = true
                    }
                    for ((i, v) in ticks.withIndex()) {
                        val y = bottom - (i * plotH / 2f)
                        val label = s.tickFormat?.invoke(v)
                            ?: formatTick(v, s.unitLabel, s.digits)
                        nativeCanvas.drawText(label, 4f, y + 4f, paint)
                    }
                }
            }
        }
    }
}

/** A dashed horizontal guide at [value] (display units) labelled [label]. */
internal data class ReferenceLine(val value: Double, val label: String)

/**
 * Wrapper that pairs a [MetricSeries] with the color + (optional) unit
 * label the chart should render it with.
 */
internal data class DisplaySeries(
    val series: MetricSeries,
    val color: Color,
    val unitLabel: String = "",
    /**
     * Decimals for the Y-axis tick labels. Null keeps the old
     * magnitude-based guess, which rounds a 0.98–1.02 lambda series to
     * a constant "1.0" — hence the explicit override.
     */
    val digits: Int? = null,
    /** Draw as steps (hold each value until the next sample). */
    val step: Boolean = false,
    /** Carry a value forward at most this long, then break the line. */
    val carryMs: Long? = null,
    /**
     * Y range in display units, instead of auto-fitting this series' own
     * points. Set for a fixed range (cylinders 0..6) or a range shared by
     * a family of like-for-like series (the four fuel trims, the six misfire
     * counters) so a bank-to-bank gap isn't auto-scaled away.
     */
    val yRange: Pair<Double, Double>? = null,
    /** Tick text for one display value — discrete codes such as gear ("D4"). */
    val tickFormat: ((Double) -> String)? = null,
)

private fun Color.toArgb(): Int {
    val a = (alpha * 255f).toInt()
    val r = (red * 255f).toInt()
    val g = (green * 255f).toInt()
    val b = (blue * 255f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun formatTick(value: Double, unitLabel: String, digits: Int? = null): String {
    val mag = kotlin.math.abs(value)
    val v = when {
        digits != null -> if (mag >= 1000) "%,.${digits}f".format(value)
            else "%.${digits}f".format(value)
        mag >= 1000 -> "%,.0f".format(value)
        mag >= 100 -> "%.0f".format(value)
        mag >= 10 -> "%.0f".format(value)
        else -> "%.1f".format(value)
    }
    return if (unitLabel.isBlank()) v else "$v $unitLabel"
}

private fun List<DisplaySeries>.timeBoundsForDisplay(): Pair<Long, Long>? {
    var mn = Long.MAX_VALUE
    var mx = Long.MIN_VALUE
    for (ds in this) for (p in ds.series.points) {
        if (p.tMillis < mn) mn = p.tMillis
        if (p.tMillis > mx) mx = p.tMillis
    }
    return if (mn == Long.MAX_VALUE || mx <= mn) null else mn to mx
}

