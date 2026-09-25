package com.pitstop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pitstop.http.MpgPointDto
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.util.UnitFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Clarified MPG-over-time chart — the old sparkline labelled "MPG (year)"
 * was unreadable because it had no axes, no period labels, no tooltip,
 * and (because the backend's `window=year` returns *month* buckets, not
 * a 12-month rolling) the title was a lie.
 *
 * The raw monthly series is too noisy to read — a single partial fillup
 * can spike one month from 18 to 33 mpg and squash everything else into
 * a flat ribbon. We render a 3-month rolling median instead, which
 * preserves the trend shape while killing single-point spikes. Each
 * smoothed value is `median(points[i-1], points[i], points[i+1])`,
 * with the edges using a shorter window.
 *
 * Layout:
 *   - title: "MPG · last 12 months"
 *   - min/max y-axis labels at the left edge of the plot (smoothed)
 *   - first/last period labels at the bottom edge
 *   - tap or drag to show a vertical guide + smoothed-value bubble
 *   - subtitle: "3-mo rolling median · {min}–{max} mpg"
 */
@Composable
fun MpgYearChart(
    points: List<MpgPointDto>,
    modifier: Modifier = Modifier,
    /** False when rendered as a [TrendsCarousel] page: no card, no title. */
    framed: Boolean = true,
) {
    val cleaned = remember(points) {
        points.filter { (it.mpg ?: 0.0) > 0 }.takeLast(12)
    }
    val system = LocalUnitSystem.current
    // Smooth in mpg (the server's unit), THEN convert: the median of the
    // inverted values is not the inversion of the median once a window
    // spans a skewed month. `mpg` below holds the DISPLAY value from here
    // on — mpg or L/100km — so the canvas stays unit-agnostic.
    val smoothed = remember(cleaned, system) {
        rollingMedian(cleaned, window = 3).map {
            it.copy(mpg = UnitFormat.economyValue(it.mpg, system))
        }
    }
    val unit = UnitFormat.economyUnit(system)
    TrendFrame(framed = framed, modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (framed) {
                Text(
                    "${if (system == "imperial") "MPG" else "L/100 km"}  ·  last 12 months",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (smoothed.size < 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Not enough data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val minMpg = smoothed.minOf { it.mpg!! }
            val maxMpg = smoothed.maxOf { it.mpg!! }
            Spacer(Modifier.height(8.dp))
            MpgLineCanvas(
                points = smoothed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics {
                        contentDescription = "Fuel economy by month, " +
                            "${formatPeriodShort(smoothed.first().period)} to " +
                            "${formatPeriodShort(smoothed.last().period)}: " +
                            "low ${"%.1f".format(minMpg)}, high ${"%.1f".format(maxMpg)}, " +
                            "latest ${"%.1f".format(smoothed.last().mpg ?: 0.0)} $unit"
                    },
                color = MaterialTheme.colorScheme.primary,
                gridColor = MaterialTheme.colorScheme.outlineVariant,
                onSurfaceColor = MaterialTheme.colorScheme.onSurface,
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPeriodShort(smoothed.first().period),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatPeriodShort(smoothed.last().period),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "3-mo rolling median  ·  ${"%.1f".format(minMpg)}–${"%.1f".format(maxMpg)} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Replace each point's mpg with the median of itself and its
 * `window/2` neighbours on either side. Edge points use shorter
 * windows — index 0 uses median(0, 1), the last index uses
 * median(n-2, n-1). Period strings are preserved unchanged.
 */
private fun rollingMedian(points: List<MpgPointDto>, window: Int): List<MpgPointDto> {
    if (points.size < 2) return points
    val half = window / 2
    return points.mapIndexed { i, p ->
        val lo = (i - half).coerceAtLeast(0)
        val hi = (i + half).coerceAtMost(points.lastIndex)
        val slice = points.subList(lo, hi + 1).mapNotNull { it.mpg }
        if (slice.isEmpty()) {
            p
        } else {
            val sorted = slice.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
            p.copy(mpg = median)
        }
    }
}

@Composable
private fun MpgLineCanvas(
    points: List<MpgPointDto>,
    modifier: Modifier,
    color: Color,
    gridColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
) {
    val mpgs = points.mapNotNull { it.mpg }
    val minV = mpgs.min()
    val maxV = mpgs.max()
    val rangeV = (maxV - minV).coerceAtLeast(0.0001)

    // Selected point index, -1 = nothing selected. Tap to set, drag
    // updates as the finger moves, tap empty area sets nothing.
    var selected by remember { mutableIntStateOf(-1) }
    val padL = 38f
    val padR = 8f
    val padTop = 12f
    val padBot = 8f
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Type metrics for the y-axis labels — pre-measured so we draw at
    // the same baseline every recompose without round-tripping through
    // TextLayout each frame.
    val labelTextSizePx = with(density) { 10.sp.toPx() }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(points) {
                    detectTapGestures(
                        onTap = { offset ->
                            selected = pickIndex(offset.x, padL, padR, size.width.toFloat(), points.size)
                        },
                    )
                }
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            selected = pickIndex(offset.x, padL, padR, size.width.toFloat(), points.size)
                        },
                        onDrag = { change, _ ->
                            selected = pickIndex(
                                change.position.x,
                                padL,
                                padR,
                                size.width.toFloat(),
                                points.size,
                            )
                            change.consume()
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val plotW = w - padL - padR
            val plotH = h - padTop - padBot
            val stepX = if (points.size <= 1) 0f else plotW / (points.size - 1)

            // Midline grid only — keeps the eye anchored without
            // clutter at min/max (which we label numerically instead).
            drawLine(
                color = gridColor.copy(alpha = 0.35f),
                start = Offset(padL, padTop + plotH / 2),
                end = Offset(padL + plotW, padTop + plotH / 2),
                strokeWidth = 1f,
            )

            // Line + fill
            val path = Path()
            points.forEachIndexed { i, p ->
                val v = p.mpg ?: return@forEachIndexed
                val x = padL + stepX * i
                val y = padTop + plotH - ((v - minV) / rangeV).toFloat() * plotH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = color, style = Stroke(width = 2.5f))
            val fill = Path().apply {
                addPath(path)
                lineTo(padL + stepX * (points.size - 1), padTop + plotH)
                lineTo(padL, padTop + plotH)
                close()
            }
            drawPath(path = fill, color = color.copy(alpha = 0.12f))

            // Y-axis min + max labels at left edge of plot.
            val paint = android.graphics.Paint().apply {
                this.color = onSurfaceVariant.toArgb()
                this.textSize = labelTextSizePx
                this.isAntiAlias = true
                this.typeface = android.graphics.Typeface.MONOSPACE
            }
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(maxV),
                4f,
                padTop + labelTextSizePx,
                paint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "%.0f".format(minV),
                4f,
                padTop + plotH,
                paint,
            )

            // Selected-point overlay: vertical guide + dot + numeric bubble.
            if (selected in points.indices) {
                val p = points[selected]
                val v = p.mpg ?: return@Canvas
                val x = padL + stepX * selected
                val y = padTop + plotH - ((v - minV) / rangeV).toFloat() * plotH
                drawLine(
                    color = onSurfaceVariant.copy(alpha = 0.45f),
                    start = Offset(x, padTop),
                    end = Offset(x, padTop + plotH),
                    strokeWidth = 1f,
                )
                drawCircle(
                    color = color,
                    radius = 4.5f,
                    center = Offset(x, y),
                )
                // Value bubble — anchored just above the dot. Keep
                // inside the canvas (clip x near the edges).
                val bubbleW = 64f
                val bubbleH = 22f
                val bx = (x - bubbleW / 2).coerceIn(0f, w - bubbleW)
                val by = (y - 28f).coerceAtLeast(0f)
                drawRoundRect(
                    color = onSurfaceColor.copy(alpha = 0.92f),
                    topLeft = Offset(bx, by),
                    size = androidx.compose.ui.geometry.Size(bubbleW, bubbleH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
                val bubblePaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.BLACK
                    this.textSize = labelTextSizePx
                    this.isAntiAlias = true
                    this.typeface = android.graphics.Typeface.MONOSPACE
                    this.textAlign = android.graphics.Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "${"%.1f".format(v)}  ${formatPeriodShort(p.period)}",
                    bx + bubbleW / 2,
                    by + bubbleH - 6f,
                    bubblePaint,
                )
            }
        }
    }
}

private fun pickIndex(rawX: Float, padL: Float, padR: Float, w: Float, n: Int): Int {
    if (n <= 0) return -1
    val plotW = w - padL - padR
    val step = if (n <= 1) plotW else plotW / (n - 1)
    val x = (rawX - padL).coerceIn(0f, plotW)
    return (x / step).roundToInt().coerceIn(0, n - 1)
}

private fun formatPeriodShort(period: String): String {
    return runCatching {
        when {
            period.length == 7 -> {
                val ym = YearMonth.parse(period)
                ym.format(DateTimeFormatter.ofPattern("MMM ''yy"))
            }
            period.length == 4 -> period
            else -> period
        }
    }.getOrDefault(period)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
