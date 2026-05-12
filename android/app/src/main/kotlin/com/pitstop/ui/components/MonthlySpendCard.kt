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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitstop.http.MonthlySpendPointDto
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Monthly fuel-spend card — last 12 months of $fuel as a bar chart.
 * The current month (matched against device clock — backend gives us
 * YYYY-MM strings) is rendered in the brand-coral accent; the other 11
 * sit in surface-variant so the eye picks out "this month vs the past
 * year" at a glance.
 *
 * `service` and `total` are deliberately ignored — service spend is
 * the maintenance card's job, total is just fuel+service. The user
 * wants fuel cost on the home screen.
 */
@Composable
fun MonthlySpendCard(
    months: List<MonthlySpendPointDto>,
    modifier: Modifier = Modifier,
) {
    if (months.size < 2) return
    val recent = months.takeLast(12)
    val total = recent.sumOf { it.fuel }
    val nowYm = YearMonth.now().toString() // "2026-05"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Monthly fuel spend",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Last 12 mo  ·  $%,.0f".format(total),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SpendBars(
                months = recent,
                currentMonthPrefix = nowYm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                accent = MaterialTheme.colorScheme.primary,
                surfaceVariant = MaterialTheme.colorScheme.surfaceVariant,
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
                onSurface = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                recent.forEach { p ->
                    Text(
                        text = formatMonthShort(p.month),
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
private fun SpendBars(
    months: List<MonthlySpendPointDto>,
    currentMonthPrefix: String,
    modifier: Modifier,
    accent: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    onSurface: Color,
) {
    val maxV = months.maxOf { it.fuel }.coerceAtLeast(0.0001)
    var selected by remember { mutableIntStateOf(-1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(months) {
                    detectTapGestures(
                        onTap = { offset ->
                            val slotW = size.width.toFloat() / months.size
                            val idx = (offset.x / slotW).toInt().coerceIn(0, months.size - 1)
                            selected = if (selected == idx) -1 else idx
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val padTop = 14f
            val padBot = 2f
            val plotH = h - padTop - padBot
            val slotW = w / months.size
            val barW = slotW * 0.6f
            months.forEachIndexed { i, p ->
                val v = p.fuel
                val barH = (v / maxV).toFloat() * plotH
                val x = i * slotW + (slotW - barW) / 2f
                val y = padTop + plotH - barH
                val isCurrent = p.month == currentMonthPrefix
                val tint = when {
                    i == selected -> accent
                    isCurrent -> accent.copy(alpha = 0.85f)
                    else -> surfaceVariant
                }
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                )
            }
            if (selected in months.indices) {
                val p = months[selected]
                val barH = (p.fuel / maxV).toFloat() * plotH
                val x = selected * slotW + slotW / 2f
                val y = padTop + plotH - barH - 4f
                val paint = android.graphics.Paint().apply {
                    this.color = onSurface.toArgb()
                    this.textSize = labelTextSizePx
                    this.isAntiAlias = true
                    this.textAlign = android.graphics.Paint.Align.CENTER
                    this.typeface = android.graphics.Typeface.MONOSPACE
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "$%.0f".format(p.fuel),
                    x,
                    y,
                    paint,
                )
            }
        }
    }
}

private fun formatMonthShort(period: String): String {
    return runCatching {
        YearMonth.parse(period).format(DateTimeFormatter.ofPattern("MMM"))
    }.getOrDefault(period.takeLast(2))
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
