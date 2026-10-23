package com.pitstop.ui.insights

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitstop.http.InsightBandDto
import com.pitstop.http.InsightChartDto
import com.pitstop.http.InsightRefDto
import com.pitstop.ui.theme.ext
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The compact chart on an insight card. One composable for the three kinds
 * the server sends (`bars`, `lines`, `points`); an unknown kind or a chart
 * with nothing to draw renders nothing. Values are drawn as sent.
 */
@Composable
internal fun InsightChart(
    chart: InsightChartDto,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Line colours by series name (e.g. fixed tire-corner colours); others use the palette. */
    seriesColors: Map<String, Color> = emptyMap(),
    /** Series drawn dashed and thinner (a backdrop such as outside temp). */
    dashedSeries: Set<String> = emptySet(),
) {
    when (chart.kind) {
        "bars" -> if (!chart.bars.isNullOrEmpty()) BarsChart(chart, modifier, height)
        "lines" -> if (chart.series.orEmpty().any { s -> s.points.any { it.xy() != null } }) {
            LinesChart(chart, modifier, height, zone, seriesColors, dashedSeries)
        }
        "points" -> if (chart.points.orEmpty().any { it.xy() != null }) PointsChart(chart, modifier, height)
    }
}

/** `[x, y]` with both present, else null. */
internal fun List<Double?>.xy(): Pair<Double, Double>? {
    val x = getOrNull(0) ?: return null
    val y = getOrNull(1) ?: return null
    return x to y
}

/** Colours, text paint and sizes shared by the three chart kinds. */
private class ChartStyle(
    val primary: Color,
    val muted: Color,
    val text: Color,
    val grid: Color,
    val good: Color,
    val surface: Color,
    val palette: List<Color>,
    val textPx: Float,
    val density: Float,
) {
    fun paint(color: Color = text, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) = Paint().apply {
        this.color = color.toArgb()
        textSize = textPx
        isAntiAlias = true
        textAlign = align
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}

@Composable
private fun rememberChartStyle(): ChartStyle {
    val cs = MaterialTheme.colorScheme
    val ext = MaterialTheme.ext
    val density = LocalDensity.current
    return ChartStyle(
        primary = cs.primary,
        muted = cs.onSurfaceVariant.copy(alpha = 0.38f),
        text = cs.onSurfaceVariant,
        grid = cs.outlineVariant,
        good = ext.good,
        surface = cs.surface,
        palette = listOf(cs.primary, ext.compare, ext.good, cs.tertiary, ext.warn),
        textPx = with(density) { 11.sp.toPx() },
        density = density.density,
    )
}

/** Small unit caption above a chart ("mpg", "psi at 68°F"). */
@Composable
private fun UnitCaption(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

// ── bars ────────────────────────────────────────────────────────────

@Composable
private fun BarsChart(chart: InsightChartDto, modifier: Modifier, height: Dp) {
    val style = rememberChartStyle()
    val bars = chart.bars.orEmpty()
    val anyHighlight = bars.any { it.highlight }
    val description = remember(chart) {
        bars.joinToString(prefix = "Bar chart${chart.yUnit?.let { " in $it" } ?: ""}: ") { b ->
            "${b.label} ${b.value?.let { InsightFormat.number(it) } ?: "no data"}"
        }
    }
    Column(modifier.fillMaxWidth()) {
        UnitCaption(chart.yUnit)
        Spacer(Modifier.height(2.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height + 28.dp)
                .semantics { contentDescription = description },
        ) {
            val dp = style.density
            val labelH = style.textPx * 2.3f // room for two lines of label
            val valueH = style.textPx * 1.5f
            val values = bars.mapNotNull { it.value }
            val hasNeg = values.any { it < 0 }
            var lo = min(0.0, values.minOrNull() ?: 0.0)
            var hi = max(0.0, values.maxOrNull() ?: 0.0)
            chart.band?.let { b -> b.lo?.let { lo = min(lo, it) }; b.hi?.let { hi = max(hi, it) } }
            chart.ref?.value?.let { lo = min(lo, it); hi = max(hi, it) }
            if (hi - lo < 1e-9) hi = lo + 1.0
            val top = valueH
            val bottom = size.height - labelH - (if (hasNeg) valueH else 0f)
            fun sy(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)

            drawBandAndRef(chart.band, chart.ref, ::sy, 0f, size.width, style)

            val n = bars.size
            val slot = size.width / n
            val barW = (slot * 0.62f).coerceAtMost(48f * dp)
            val step = InsightFormat.labelStep(n, (size.width / (44f * dp)).toInt().coerceAtLeast(1))
            val shown = InsightFormat.shownLabels(n, step, bars.indices.filter { bars[it].highlight })
            val canvas = drawContext.canvas.nativeCanvas
            val zeroY = sy(0.0)
            // Zero baseline (also the axis when everything is positive).
            drawLine(style.grid, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f * dp)

            bars.forEachIndexed { i, b ->
                val cx = slot * i + slot / 2
                val v = b.value
                val color = when {
                    b.highlight -> style.primary
                    anyHighlight -> style.muted
                    else -> style.primary.copy(alpha = 0.7f)
                }
                if (v != null) {
                    val y = sy(v)
                    val t = min(y, zeroY)
                    val h = max(abs(zeroY - y), 1.5f * dp)
                    drawRoundRectTop(color, Offset(cx - barW / 2, t), Size(barW, h), 3f * dp, up = v >= 0)
                }
                val show = i in shown
                if (show) {
                    val vp = style.paint(
                        color = if (b.highlight) style.primary else style.text,
                        bold = b.highlight,
                    )
                    val label = v?.let { InsightFormat.number(it, 1) } ?: "—"
                    val vy = when {
                        v == null -> zeroY - 4f * dp
                        v >= 0 -> sy(v) - 4f * dp
                        else -> sy(v) + style.textPx + 2f * dp
                    }
                    canvas.drawText(label, cx, vy, vp)
                }
                if (show) {
                    val lp = style.paint(color = if (b.highlight) style.primary else style.text, bold = b.highlight)
                    val width = slot * step - 4f * dp
                    val lines = wrapLines(b.label, lp, width, maxLines = 2)
                    lines.forEachIndexed { li, line ->
                        canvas.drawText(line, cx, size.height - labelH + style.textPx * (1f + li * 1.15f), lp)
                    }
                }
            }
        }
        ChartLegend(emptyList(), chart.band, chart.ref, style)
    }
}

private fun abs(f: Float) = kotlin.math.abs(f)

/** Rounded at the end away from zero, square at the baseline. */
private fun DrawScope.drawRoundRectTop(color: Color, topLeft: Offset, size: Size, r: Float, up: Boolean) {
    val rr = min(r, min(size.width / 2, size.height))
    val path = Path().apply {
        val l = topLeft.x
        val t = topLeft.y
        val rgt = l + size.width
        val b = t + size.height
        if (up) {
            moveTo(l, b)
            lineTo(l, t + rr)
            quadraticTo(l, t, l + rr, t)
            lineTo(rgt - rr, t)
            quadraticTo(rgt, t, rgt, t + rr)
            lineTo(rgt, b)
        } else {
            moveTo(l, t)
            lineTo(rgt, t)
            lineTo(rgt, b - rr)
            quadraticTo(rgt, b, rgt - rr, b)
            lineTo(l + rr, b)
            quadraticTo(l, b, l, b - rr)
        }
        close()
    }
    drawPath(path, color)
}

/** Greedy word wrap into at most [maxLines], ellipsizing the last line. */
private fun wrapLines(text: String, paint: Paint, width: Float, maxLines: Int): List<String> {
    if (paint.measureText(text) <= width) return listOf(text)
    val words = text.split(' ')
    val lines = mutableListOf<String>()
    var cur = ""
    var i = 0
    while (i < words.size) {
        val next = if (cur.isEmpty()) words[i] else "$cur ${words[i]}"
        if (paint.measureText(next) <= width || cur.isEmpty()) {
            cur = next
            i++
        } else {
            lines += cur
            cur = ""
            if (lines.size == maxLines - 1) break
        }
    }
    val rest = (listOf(cur).filter { it.isNotEmpty() } + words.drop(i)).joinToString(" ")
    if (rest.isNotEmpty()) lines += rest
    return lines.take(maxLines).map { l ->
        if (paint.measureText(l) <= width) {
            l
        } else {
            val cut = paint.breakText(l, true, (width - paint.measureText("…")).coerceAtLeast(0f), null)
            l.take(cut.coerceAtLeast(1)).trimEnd() + "…"
        }
    }
}

/** Shaded healthy band and dashed reference line; their labels go in [ChartLegend]. */
private fun DrawScope.drawBandAndRef(
    band: InsightBandDto?,
    ref: InsightRefDto?,
    sy: (Double) -> Float,
    left: Float,
    right: Float,
    style: ChartStyle,
) {
    val dp = style.density
    if (band?.lo != null && band.hi != null) {
        val y1 = sy(band.hi)
        val y2 = sy(band.lo)
        drawRect(style.good.copy(alpha = 0.09f), Offset(left, min(y1, y2)), Size(right - left, abs(y2 - y1)))
    }
    val rv = ref?.value
    if (rv != null) {
        val y = sy(rv)
        drawLine(
            style.text,
            Offset(left, y),
            Offset(right, y),
            strokeWidth = 1.2f * dp,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 4f * dp)),
        )
    }
}

// ── lines ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinesChart(
    chart: InsightChartDto,
    modifier: Modifier,
    height: Dp,
    zone: ZoneId,
    seriesColors: Map<String, Color> = emptyMap(),
    dashedSeries: Set<String> = emptySet(),
) {
    val style = rememberChartStyle()
    val series = chart.series.orEmpty().map { s -> s.name to s.points.mapNotNull { it.xy() }.sortedBy { it.first } }
    fun colorOf(i: Int, name: String) = seriesColors[name] ?: if (name in dashedSeries) style.text else style.palette[i % style.palette.size]
    val all = series.flatMap { it.second }
    val description = remember(chart) {
        "Line chart${chart.yUnit?.let { " in $it" } ?: ""}: " + series.joinToString { (name, pts) ->
            "$name from ${pts.firstOrNull()?.second?.let { InsightFormat.number(it) } ?: "?"} to ${pts.lastOrNull()?.second?.let { InsightFormat.number(it) } ?: "?"}"
        }
    }
    Column(modifier.fillMaxWidth()) {
        UnitCaption(chart.yUnit)
        Spacer(Modifier.height(2.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description },
        ) {
            val dp = style.density
            var lo = all.minOf { it.second }
            var hi = all.maxOf { it.second }
            chart.band?.let { b -> b.lo?.let { lo = min(lo, it) }; b.hi?.let { hi = max(hi, it) } }
            chart.ref?.value?.let { lo = min(lo, it); hi = max(hi, it) }
            val ticks = InsightFormat.niceTicks(lo, hi, 3)
            val dec = InsightFormat.tickDecimals(ticks)
            val y0 = ticks.first()
            val y1 = ticks.last()
            var x0 = all.minOf { it.first }
            var x1 = all.maxOf { it.first }
            if (x1 - x0 < 1.0) {
                x0 -= 86_400_000.0
                x1 += 86_400_000.0
            }
            val tickPaint = style.paint(align = Paint.Align.RIGHT)
            val padL = ticks.maxOf { tickPaint.measureText(InsightFormat.number(it, dec)) } + 8f * dp
            val padR = 6f * dp
            val padT = 6f * dp
            val padB = style.textPx + 8f * dp
            val w = size.width
            val h = size.height
            fun sx(v: Double) = padL + ((v - x0) / (x1 - x0)).toFloat() * (w - padL - padR)
            fun sy(v: Double) = h - padB - ((v - y0) / (y1 - y0)).toFloat() * (h - padT - padB)
            val canvas = drawContext.canvas.nativeCanvas
            for (t in ticks) {
                val y = sy(t)
                drawLine(style.grid, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f * dp)
                canvas.drawText(InsightFormat.number(t, dec), padL - 6f * dp, y + style.textPx * 0.35f, tickPaint)
            }
            drawBandAndRef(chart.band, chart.ref, ::sy, padL, w - padR, style)
            // Three date ticks: start, middle, end.
            val span = x1 - x0
            listOf(x0, (x0 + x1) / 2, x1).forEachIndexed { i, x ->
                val align = when (i) {
                    0 -> Paint.Align.LEFT
                    2 -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                canvas.drawText(InsightFormat.dateTick(x, span, zone), sx(x), h - 2f * dp, style.paint(align = align))
            }
            series.forEachIndexed { i, (name, pts) ->
                val color = colorOf(i, name)
                val dashed = name in dashedSeries
                if (pts.size >= 2) {
                    val path = Path()
                    pts.forEachIndexed { j, (x, y) -> if (j == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y)) }
                    drawPath(
                        path,
                        color,
                        style = Stroke(
                            width = (if (dashed) 1.4f else 2f) * dp,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f * dp, 3f * dp)) else null,
                        ),
                    )
                }
                if (pts.size <= 12) {
                    for ((x, y) in pts) {
                        drawCircle(style.surface, 4f * dp, Offset(sx(x), sy(y)))
                        drawCircle(color, 3f * dp, Offset(sx(x), sy(y)))
                    }
                } else {
                    val (lx, ly) = pts.last()
                    drawCircle(style.surface, 4.5f * dp, Offset(sx(lx), sy(ly)))
                    drawCircle(color, 3.5f * dp, Offset(sx(lx), sy(ly)))
                }
            }
        }
        ChartLegend(
            series.filter { it.second.isNotEmpty() }.map { (name, _) ->
                name to colorOf(series.indexOfFirst { it.first == name }, name)
            },
            chart.band,
            chart.ref,
            style,
            dashedSeries,
        )
    }
}

private enum class Glyph { Line, Band, Dashed }

/** Series names, then the band and reference line, under the chart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(
    series: List<Pair<String, Color>>,
    band: InsightBandDto?,
    ref: InsightRefDto?,
    style: ChartStyle,
    dashedSeries: Set<String> = emptySet(),
) {
    val entries = buildList {
        series.forEach { (name, color) -> add(Triple(name, color, if (name in dashedSeries) Glyph.Dashed else Glyph.Line)) }
        if (band?.lo != null && band.hi != null) add(Triple(band.label ?: "Healthy range", style.good, Glyph.Band))
        if (ref?.value != null) add(Triple(ref.label ?: "Reference", style.text, Glyph.Dashed))
    }
    if (entries.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((label, color, glyph) in entries) LegendItem(label, color, glyph)
    }
}

@Composable
private fun LegendItem(label: String, color: Color, glyph: Glyph) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
            val mid = Offset(0f, size.height / 2)
            val end = Offset(size.width, size.height / 2)
            when (glyph) {
                Glyph.Line -> drawLine(color, mid, end, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                Glyph.Band -> drawRect(color.copy(alpha = 0.3f))
                Glyph.Dashed -> drawLine(
                    color, mid, end, strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── points (scatter) ───────────────────────────────────────────────

@Composable
private fun PointsChart(chart: InsightChartDto, modifier: Modifier, height: Dp) {
    val style = rememberChartStyle()
    val pts = chart.points.orEmpty().mapNotNull { it.xy() }
    val description = remember(chart) {
        "Scatter of ${pts.size} points: ${chart.yUnit ?: "y"} against ${chart.xUnit ?: "x"}"
    }
    Column(modifier.fillMaxWidth()) {
        UnitCaption(chart.yUnit)
        Spacer(Modifier.height(2.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description },
        ) {
            val dp = style.density
            val yt = InsightFormat.niceTicks(pts.minOf { it.second }, pts.maxOf { it.second }, 3)
            val xt = InsightFormat.niceTicks(pts.minOf { it.first }, pts.maxOf { it.first }, 4)
            val yd = InsightFormat.tickDecimals(yt)
            val xd = InsightFormat.tickDecimals(xt)
            val tickPaint = style.paint(align = Paint.Align.RIGHT)
            val padL = yt.maxOf { tickPaint.measureText(InsightFormat.number(it, yd)) } + 8f * dp
            val padR = 8f * dp
            val padT = 6f * dp
            val padB = style.textPx + 8f * dp
            val w = size.width
            val h = size.height
            fun sx(v: Double) = padL + ((v - xt.first()) / (xt.last() - xt.first())).toFloat() * (w - padL - padR)
            fun sy(v: Double) = h - padB - ((v - yt.first()) / (yt.last() - yt.first())).toFloat() * (h - padT - padB)
            val canvas = drawContext.canvas.nativeCanvas
            for (t in yt) {
                val y = sy(t)
                drawLine(style.grid, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f * dp)
                canvas.drawText(InsightFormat.number(t, yd), padL - 6f * dp, y + style.textPx * 0.35f, tickPaint)
            }
            xt.forEachIndexed { i, t ->
                val align = when (i) {
                    0 -> Paint.Align.LEFT
                    xt.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                canvas.drawText(InsightFormat.number(t, xd), sx(t), h - 2f * dp, style.paint(align = align))
            }
            val r = if (pts.size > 60) 2.5f * dp else 3.5f * dp
            for ((x, y) in pts) drawCircle(style.primary.copy(alpha = 0.6f), r, Offset(sx(x), sy(y)))
        }
        chart.xUnit?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
