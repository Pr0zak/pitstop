package com.pitstop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One plotted value in data units. */
data class PlotPoint(val x: Double, val y: Double)

data class PlotLine(
    val points: List<PlotPoint>,
    val color: Color,
    val width: Dp = 2.dp,
    /** Emphasised dot on the last point. */
    val endDot: Boolean = false,
)

data class PlotDots(
    val points: List<PlotPoint>,
    val color: Color,
    val radius: Dp = 4.dp,
    val alpha: Float = 1f,
)

/** Filled region between a polyline and the bottom of the y range. */
data class PlotArea(val points: List<PlotPoint>, val color: Color, val alpha: Float = 0.12f)

enum class HoverMode {
    /** Snap to the nearest x (line charts): crosshair + ring. */
    NearestX,

    /** Snap to the nearest point in 2-D (scatter): ring only. */
    NearestXY,
}

/**
 * The Canvas behind the MPG-over-time, paid-vs-market and spend-by-year
 * charts: a y grid with labels, x labels, filled areas, dots and lines,
 * and a tap / drag selection layer. It draws a ring (and a crosshair in
 * [HoverMode.NearestX]) on [selected]; the caller owns the selection and
 * prints the readout, so the readout is plain text a screen reader gets.
 *
 * [scrub] adds horizontal-drag selection; leave it off inside a
 * horizontal pager, where the drag belongs to the pager.
 */
@Composable
fun PlotChart(
    xRange: Pair<Double, Double>,
    yRange: Pair<Double, Double>,
    yTicks: List<Double>,
    yLabel: (Double) -> String,
    xTicks: List<Pair<Double, String>>,
    description: String,
    modifier: Modifier = Modifier,
    areas: List<PlotArea> = emptyList(),
    dots: List<PlotDots> = emptyList(),
    lines: List<PlotLine> = emptyList(),
    hover: List<PlotPoint> = emptyList(),
    hoverMode: HoverMode = HoverMode.NearestX,
    selected: Int = -1,
    onSelect: (Int) -> Unit = {},
    scrub: Boolean = true,
    height: Dp = 210.dp,
) {
    val density = LocalDensity.current
    val grid = MaterialTheme.colorScheme.outlineVariant
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val textPx = with(density) { 11.sp.toPx() }
    val padL = with(density) { 40.dp.toPx() }
    val padR = with(density) { 12.dp.toPx() }
    val padT = with(density) { 10.dp.toPx() }
    val padB = with(density) { 24.dp.toPx() }
    val select by rememberUpdatedState(onSelect)
    val (x0, x1) = xRange
    val (y0, y1) = yRange
    val xSpan = (x1 - x0).takeIf { it > 0 } ?: 1.0
    val ySpan = (y1 - y0).takeIf { it > 0 } ?: 1.0

    fun nearest(px: Float, py: Float, w: Float, h: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        hover.forEachIndexed { i, p ->
            val dx = (padL + ((p.x - x0) / xSpan).toFloat() * (w - padL - padR)) - px
            val dy = if (hoverMode == HoverMode.NearestXY) {
                (h - padB - ((p.y - y0) / ySpan).toFloat() * (h - padT - padB)) - py
            } else {
                0f
            }
            val d = dx * dx + dy * dy
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    var gestures = Modifier.pointerInput(hover, xRange, yRange, hoverMode) {
        detectTapGestures { o -> select(nearest(o.x, o.y, size.width.toFloat(), size.height.toFloat())) }
    }
    if (scrub) {
        gestures = gestures.pointerInput(hover, xRange, yRange, hoverMode) {
            detectHorizontalDragGestures(
                onDragStart = { o -> select(nearest(o.x, o.y, size.width.toFloat(), size.height.toFloat())) },
            ) { change, _ ->
                change.consume()
                select(nearest(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat()))
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .then(if (hover.isEmpty()) Modifier else gestures),
    ) {
        val w = size.width
        val h = size.height
        fun sx(v: Double) = padL + ((v - x0) / xSpan).toFloat() * (w - padL - padR)
        fun sy(v: Double) = h - padB - ((v - y0) / ySpan).toFloat() * (h - padT - padB)
        val paint = android.graphics.Paint().apply {
            color = faint.toArgb()
            textSize = textPx
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val canvas = drawContext.canvas.nativeCanvas

        for (t in yTicks) {
            val y = sy(t)
            drawLine(grid, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1.dp.toPx())
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.drawText(yLabel(t), padL - 8.dp.toPx(), y + textPx * 0.35f, paint)
        }
        for ((v, label) in xTicks) {
            val x = sx(v)
            paint.textAlign = when {
                x < padL + 20.dp.toPx() -> android.graphics.Paint.Align.LEFT
                x > w - padR - 20.dp.toPx() -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(label, x.coerceIn(padL, w - padR), h - 6.dp.toPx(), paint)
        }
        for (a in areas) {
            if (a.points.size < 2) continue
            val path = polyline(a.points, ::sx, ::sy)
            path.lineTo(sx(a.points.last().x), sy(y0))
            path.lineTo(sx(a.points.first().x), sy(y0))
            path.close()
            drawPath(path, a.color.copy(alpha = a.alpha))
        }
        for (d in dots) {
            val r = d.radius.toPx()
            for (p in d.points) {
                val c = Offset(sx(p.x), sy(p.y))
                drawCircle(surface.copy(alpha = d.alpha), r + 2.dp.toPx() / 2, c)
                drawCircle(d.color.copy(alpha = d.alpha), r - 1.dp.toPx() / 2, c)
            }
        }
        for (l in lines) {
            if (l.points.isEmpty()) continue
            drawPath(
                polyline(l.points, ::sx, ::sy),
                l.color,
                style = Stroke(width = l.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (l.endDot) endDot(Offset(sx(l.points.last().x), sy(l.points.last().y)), l.color, surface)
        }
        hover.getOrNull(selected)?.let { p ->
            val c = Offset(sx(p.x), sy(p.y))
            if (hoverMode == HoverMode.NearestX) {
                drawLine(
                    faint,
                    Offset(c.x, padT),
                    Offset(c.x, h - padB),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
            }
            drawCircle(fg, 6.dp.toPx(), c, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private fun polyline(pts: List<PlotPoint>, sx: (Double) -> Float, sy: (Double) -> Float): Path =
    Path().apply {
        pts.forEachIndexed { i, p -> if (i == 0) moveTo(sx(p.x), sy(p.y)) else lineTo(sx(p.x), sy(p.y)) }
    }

private fun DrawScope.endDot(c: Offset, color: Color, ring: Color) {
    drawCircle(ring, 5.5.dp.toPx(), c)
    drawCircle(color, 4.5.dp.toPx(), c)
}

/** How a legend entry draws its series: the glyph, not just the colour. */
enum class LegendGlyph { Dot, Line, LineWithDot }

data class LegendEntry(val label: String, val color: Color, val glyph: LegendGlyph)

/** A row of legend entries; each shows the series' shape as well as its colour. */
@Composable
fun ChartLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (e in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 20.dp, height = 12.dp)) {
                    val mid = size.height / 2
                    when (e.glyph) {
                        LegendGlyph.Dot -> drawCircle(e.color, 4.dp.toPx(), Offset(size.width / 2, mid))
                        LegendGlyph.Line, LegendGlyph.LineWithDot -> {
                            drawLine(
                                e.color,
                                Offset(0f, mid),
                                Offset(size.width, mid),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            if (e.glyph == LegendGlyph.LineWithDot) {
                                drawCircle(e.color, 3.5.dp.toPx(), Offset(size.width - 3.5.dp.toPx(), mid))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    e.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Gridline label with as many decimals as the tick step needs. */
fun tickLabel(v: Double, decimals: Int): String = "%.${decimals}f".format(v)
