package com.pitstop.ui.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitstop.domain.ParkedSpot
import com.pitstop.domain.RangeEstimate
import com.pitstop.domain.RouteShape
import com.pitstop.domain.SpeedBand
import com.pitstop.ui.components.RangeFormat
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ── At-a-glance strip ──────────────────────────────────────────────────

/**
 * Range, last drive and parked time as three equal chips at the very top
 * of Home. A chip whose data is missing is left out rather than showing
 * zeros. Each one is a shortcut to its full card or screen.
 */
@Composable
internal fun GlanceStrip(
    range: RangeEstimate?,
    lastDrive: LastDrive?,
    parked: ParkedSpot?,
    nowMs: Long,
    onRange: () -> Unit,
    onLastDrive: () -> Unit,
    onParked: () -> Unit,
) {
    val system = LocalUnitSystem.current
    val is24h = is24HourClock()
    val chips = buildList {
        range?.let { r ->
            val value = if (r.rangeMi != null) {
                RangeFormat.range(r.rangeMi, system)
            } else {
                r.fuel.usGallons?.let { UnitFormat.volumeGal(it, system, 1) }
            }
            if (value != null) add(GlanceItem("Range", value, RangeFormat.fuelLine(r, system), onRange))
        }
        lastDrive?.trip?.let { t ->
            val mpg = UnitFormat.mpgFrom(t.distanceKm, t.fuelUsedL)
            val start = DateLabel.epochMs(t.startedAt)?.let { clock(it, is24h) }
            add(
                GlanceItem(
                    "Last drive",
                    UnitFormat.distanceKm(t.distanceKm, system),
                    listOfNotNull(start, mpg?.let { UnitFormat.economy(it, system) }).joinToString(" · "),
                    onLastDrive,
                ),
            )
        }
        parked?.sinceMs?.let { since ->
            add(GlanceItem("Parked", parkedFor(nowMs - since), parkedSinceShort(since, nowMs, is24h), onParked))
        }
    }
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (c in chips) {
            Card(
                onClick = c.onClick,
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        c.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        c.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        c.sub,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private class GlanceItem(val label: String, val value: String, val sub: String, val onClick: () -> Unit)

private fun clock(ms: Long, is24h: Boolean): String =
    DateLabel.time(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()), is24h)

/** "45 min" / "16 h" / "3 d". */
internal fun parkedFor(ageMs: Long): String {
    val mins = (ageMs / 60_000L).coerceAtLeast(0)
    return when {
        mins < 60 -> "$mins min"
        mins < 48 * 60 -> "${mins / 60} h"
        else -> "${mins / (24 * 60)} d"
    }
}

/** "since 7:17 AM" within a day, else "since Sep 28". */
internal fun parkedSinceShort(sinceMs: Long, nowMs: Long, is24h: Boolean): String =
    if (nowMs - sinceMs < 24 * 3_600_000L) {
        "since ${clock(sinceMs, is24h)}"
    } else {
        "since " + Instant.ofEpochMilli(sinceMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }

// ── Range arc gauge ────────────────────────────────────────────────────

private const val ARC_START = 153f
private const val ARC_SWEEP = 234f

/**
 * The fuel gauge: an arc from E to F filled to the tank level, with the
 * range in the middle. Colour thresholds match the old bar: bad < 15 %,
 * warn < 30 % (or a low range), good otherwise.
 */
@Composable
internal fun RangeGauge(range: RangeEstimate, color: Color, modifier: Modifier = Modifier) {
    val system = LocalUnitSystem.current
    val pct = range.fuel.pct
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val number: String
    val unit: String
    if (range.rangeMi != null) {
        number = UnitFormat.Quantity.DistanceMi.number(range.rangeMi, system, 0)
        unit = if (system == "imperial") "miles" else "km"
    } else {
        number = UnitFormat.Quantity.VolumeGal.number(range.fuel.usGallons, system, 1)
        unit = if (system == "imperial") "gal left" else "L left"
    }
    val labelPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.sp.toPx() }
    Box(
        modifier = modifier
            .size(width = 240.dp, height = 176.dp)
            .semantics {
                contentDescription = "Fuel ${pct?.roundToInt() ?: "unknown"} percent, " +
                    if (range.rangeMi != null) "range $number $unit" else "$number $unit"
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val r = 88.dp.toPx()
            val c = Offset(size.width / 2, 104.dp.toPx())
            val tl = Offset(c.x - r, c.y - r)
            val box = Size(2 * r, 2 * r)
            drawArc(track, ARC_START, ARC_SWEEP, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (pct != null) {
                val f = (pct / 100.0).toFloat().coerceIn(0.02f, 1f)
                drawArc(color, ARC_START, ARC_SWEEP * f, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val paint = android.graphics.Paint().apply {
                this.color = labelColor.toArgb()
                textSize = labelPx
                isAntiAlias = true
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            fun at(deg: Float) = Math.toRadians(deg.toDouble()).let { Offset(c.x + r * cos(it).toFloat(), c.y + r * sin(it).toFloat()) }
            val e = at(ARC_START)
            val fPt = at(ARC_START + ARC_SWEEP)
            drawContext.canvas.nativeCanvas.drawText("E", e.x - 4.dp.toPx(), e.y + 24.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText("F", fPt.x + 4.dp.toPx(), fPt.y + 24.dp.toPx(), paint)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                number,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = if (range.low) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurface,
            )
            Text(unit, style = MaterialTheme.typography.labelLarge, color = labelColor)
            Spacer(Modifier.height(8.dp))
            Text(RangeFormat.fuelLine(range, system), style = MaterialTheme.typography.titleSmall)
        }
    }
}

// ── Speed-coloured route sketch ────────────────────────────────────────

@Composable
internal fun speedColor(band: SpeedBand): Color = when (band) {
    SpeedBand.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    SpeedBand.City -> MaterialTheme.ext.good
    SpeedBand.Suburban -> MaterialTheme.ext.warn
    SpeedBand.Highway -> MaterialTheme.colorScheme.primary
}

/**
 * The route's shape with no map tiles (free, offline, renders in
 * screenshots). With speeds, each segment takes its speed band's colour;
 * without, the whole line is the accent. [shape] is built off the main
 * thread by the caller. A car glyph stands in when there's no GPS.
 */
@Composable
internal fun RouteSketch(shape: RouteShape?, modifier: Modifier = Modifier, strokeWidth: Float = 3f, padding: Float = 10f) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = MaterialTheme.colorScheme.primary
    val start = MaterialTheme.colorScheme.onSurface
    val bands = SpeedBand.entries.associateWith { speedColor(it) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (shape == null || shape.size < 2) {
            Icon(Icons.Outlined.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(padding.dp)) {
            val s = minOf(size.width / shape.w.coerceAtLeast(1e-3f), size.height / shape.h.coerceAtLeast(1e-3f))
                .coerceAtMost(minOf(size.width, size.height))
            val ox = (size.width - shape.w * s) / 2
            val oy = (size.height - shape.h * s) / 2
            fun pt(i: Int) = Offset(ox + shape.xs[i] * s, oy + shape.ys[i] * s)
            val w = strokeWidth.dp.toPx()
            val mph = shape.mph
            if (mph != null) {
                for (i in 1 until shape.size) {
                    drawLine(bands.getValue(SpeedBand.of(mph[i].toDouble())), pt(i - 1), pt(i), w, StrokeCap.Round)
                }
            } else {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pt(0).x, pt(0).y)
                    for (i in 1 until shape.size) lineTo(pt(i).x, pt(i).y)
                }
                drawPath(
                    path,
                    accent,
                    style = Stroke(w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
            }
            drawCircle(start, 3.dp.toPx(), pt(0))
            drawCircle(bg, 4.5.dp.toPx(), pt(shape.size - 1))
            drawCircle(accent, 3.5.dp.toPx(), pt(shape.size - 1))
        }
    }
}

/** "City <22 · Suburban 22–55 · Highway 55+" with colour swatches. */
@Composable
internal fun SpeedLegend(system: String, modifier: Modifier = Modifier) {
    val imperial = system == "imperial"
    val items = listOf(
        SpeedBand.City to if (imperial) "City <22" else "City <35",
        SpeedBand.Suburban to if (imperial) "Suburban 22–55" else "Suburban 35–89",
        SpeedBand.Highway to if (imperial) "Highway 55+" else "Highway 89+",
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((band, label) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(12.dp)
                        .height(3.dp)
                        .background(speedColor(band), RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
