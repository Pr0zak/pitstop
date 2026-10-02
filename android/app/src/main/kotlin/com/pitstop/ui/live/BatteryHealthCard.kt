package com.pitstop.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitstop.domain.BatteryHealth
import com.pitstop.ui.history.TagChip
import com.pitstop.ui.theme.ext
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val LO = 11.6
private const val HI = 12.8

/**
 * Resting battery voltage: the WiCAN wakes the car's bus while parked and
 * reports the battery, which is the reading that tells battery health
 * (a running engine's 14 V is the alternator). Daily averages, last 14
 * days, against faint good / fair / low bands.
 */
@Composable
fun BatteryHealthCard(days: List<BatteryDay>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val latest = days.last().volts
    val status = BatteryHealth.status(latest)
    val (fg, bg) = when (status) {
        BatteryHealth.Status.Good -> MaterialTheme.ext.good to MaterialTheme.ext.goodContainer
        BatteryHealth.Status.Fair -> MaterialTheme.ext.warn to MaterialTheme.ext.warnContainer
        BatteryHealth.Status.Low -> MaterialTheme.ext.bad to MaterialTheme.ext.badContainer
    }
    val line = MaterialTheme.colorScheme.onSurface
    val good = MaterialTheme.ext.good
    val warn = MaterialTheme.ext.warn
    val bad = MaterialTheme.ext.bad
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val textPx = with(LocalDensity.current) { 10.sp.toPx() }
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Battery, resting voltage",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TagChip(status.label, fg, bg)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.2f V".format(latest),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "latest parked reading · ${days.size} day${if (days.size == 1) "" else "s"} with data",
                    style = MaterialTheme.typography.bodySmall,
                    color = faint,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .semantics {
                        contentDescription = "Resting battery voltage over ${days.size} days, latest " +
                            "%.2f volts, ${status.label}".format(latest)
                    },
            ) {
                val padL = 32.dp.toPx()
                val padR = 8.dp.toPx()
                val padT = 6.dp.toPx()
                val padB = 18.dp.toPx()
                val w = size.width
                val h = size.height
                fun x(i: Int) = padL + (if (days.size < 2) 0.5f else i / (days.size - 1f)) * (w - padL - padR)
                fun y(v: Double) = h - padB - ((v.coerceIn(LO, HI) - LO) / (HI - LO)).toFloat() * (h - padT - padB)
                fun band(a: Double, b: Double, c: androidx.compose.ui.graphics.Color) =
                    drawRect(c.copy(alpha = 0.10f), Offset(padL, y(b)), Size(w - padL - padR, y(a) - y(b)))
                band(BatteryHealth.GOOD_V, HI, good)
                band(BatteryHealth.LOW_V, BatteryHealth.GOOD_V, warn)
                band(LO, BatteryHealth.LOW_V, bad)
                val paint = android.graphics.Paint().apply {
                    color = faint.toArgb()
                    textSize = textPx
                    isAntiAlias = true
                }
                val nc = drawContext.canvas.nativeCanvas
                paint.textAlign = android.graphics.Paint.Align.RIGHT
                for (v in listOf(BatteryHealth.LOW_V, BatteryHealth.GOOD_V)) {
                    nc.drawText("%.1f".format(v), padL - 6.dp.toPx(), y(v) + textPx * 0.35f, paint)
                }
                paint.textAlign = android.graphics.Paint.Align.LEFT
                nc.drawText(days.first().date.format(fmt), padL, h - 3.dp.toPx(), paint)
                paint.textAlign = android.graphics.Paint.Align.RIGHT
                nc.drawText(days.last().date.format(fmt), w - padR, h - 3.dp.toPx(), paint)
                val path = Path()
                days.forEachIndexed { i, d -> if (i == 0) path.moveTo(x(i), y(d.volts)) else path.lineTo(x(i), y(d.volts)) }
                drawPath(path, line, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                days.forEachIndexed { i, d -> drawCircle(line, 3.dp.toPx(), Offset(x(i), y(d.volts))) }
            }
            Text(
                BatteryHealth.guidance(days.map { it.volts }),
                style = MaterialTheme.typography.bodySmall,
                color = faint,
            )
        }
    }
}
