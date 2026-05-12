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
import com.pitstop.http.CostPerMilePointDto

/**
 * $/mi card. Lifetime number on the left, last-12-month bar chart on
 * the right. Lifetime is computed correctly from sum(total_cost) /
 * sum(miles) across every period — averaging the monthly cost_per_mi
 * values directly would over-weight low-miles months.
 *
 * Months with miles == 0 (the backend returns cost_per_mi=null on those)
 * are still summed into the lifetime $ total — the user paid for that
 * fuel even if no driving happened that month — but skipped from the
 * chart and from the miles total so they don't draw a zero-height bar.
 */
@Composable
fun CostPerMileCard(
    points: List<CostPerMilePointDto>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val totalCost = points.sumOf { it.totalCost }
    val totalMiles = points.sumOf { it.miles }
    val lifetime = if (totalMiles > 0) totalCost / totalMiles else null
    val last12 = points.takeLast(12)
    val barable = last12.filter { (it.costPerMi ?: 0.0) > 0 }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Cost per mile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = lifetime?.let { "$%.3f".format(it) } ?: "—",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                letterSpacing = (-1.2).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "/mi",
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Lifetime  ·  ${points.count { (it.costPerMi ?: 0.0) > 0 }} months tracked",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (barable.size >= 2) {
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .height(60.dp),
                    ) {
                        CostBars(
                            points = barable,
                            modifier = Modifier.matchParentSize(),
                            accent = MaterialTheme.colorScheme.primary,
                            surfaceVariant = MaterialTheme.colorScheme.surfaceVariant,
                            onSurface = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CostBars(
    points: List<CostPerMilePointDto>,
    modifier: Modifier,
    accent: Color,
    surfaceVariant: Color,
    onSurface: Color,
) {
    val maxV = points.maxOf { it.costPerMi ?: 0.0 }
    var selected by remember { mutableIntStateOf(-1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelTextSizePx = with(density) { 9.sp.toPx() }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(points) {
                    detectTapGestures(
                        onTap = { offset ->
                            val slotW = size.width.toFloat() / points.size
                            val idx = (offset.x / slotW).toInt().coerceIn(0, points.size - 1)
                            selected = if (selected == idx) -1 else idx
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val padTop = 12f
            val padBot = 2f
            val plotH = h - padTop - padBot
            val slotW = w / points.size
            val barW = slotW * 0.55f
            points.forEachIndexed { i, p ->
                val v = p.costPerMi ?: return@forEachIndexed
                val barH = (v / maxV).toFloat() * plotH
                val x = i * slotW + (slotW - barW) / 2f
                val y = padTop + plotH - barH
                val tint = if (i == selected) accent else accent.copy(alpha = 0.55f)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                )
            }
            if (selected in points.indices) {
                val p = points[selected]
                val v = p.costPerMi
                if (v != null) {
                    val barH = (v / maxV).toFloat() * plotH
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
                        "$%.3f".format(v),
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
