package com.pitstop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phone-side mirror of the web Overview hero strip (frontend OverviewView.vue).
 * Same four metrics, derived from the same /fillups + /analytics/mpg payloads.
 *
 * On the phone we render a 2×2 grid; the Fuel level cell is an analog-style arc
 * gauge (tick marks, E/F endpoints, color-coded fill) so it reads at a glance
 * like a dashboard fuel gauge and stands out against the text-only siblings.
 */

data class HeroCardData(
    val avgConsumptionMpg: Double?,
    val latestPpg: Double?,
    val ppgDeltaPct: Double?,
    val monthCost: Double,
    val monthCount: Int,
    val fuelLevelPct: Double?,
    val fuelGallons: Double?,
    val fuelLevelAge: String?,
    val fuelLevelTimestampMs: Long? = null,
    val tank1CapacityGal: Double? = null,
    val mpgSeries: List<Double>,
)

@Composable
fun FuelHeroCards(
    data: HeroCardData,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
                        delta > 0.5 -> Color(0xFFFF3A2E)
                        delta < -0.5 -> Color(0xFF4ADE80)
                        else -> null
                    }
                },
            )
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = "This month",
                value = "$%.2f".format(data.monthCost),
                unit = "",
                sub = "${data.monthCount} fillup${if (data.monthCount == 1) "" else "s"}",
            )
            FuelGaugeCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                data = data,
            )
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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        letterSpacing = (-1.2).sp,
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

@Composable
private fun FuelGaugeCard(
    modifier: Modifier = Modifier,
    data: HeroCardData,
) {
    val pct = data.fuelLevelPct
    val pctF = (pct ?: 0.0).coerceIn(0.0, 100.0).toFloat()
    val fillColor = when {
        pct == null -> MaterialTheme.colorScheme.outline
        pct < 15 -> Color(0xFFFF3A2E)
        pct < 35 -> Color(0xFFFFB020)
        else -> Color(0xFF4ADE80)
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = fillColor.copy(alpha = 0.55f)

    Card(
        modifier = modifier.border(
            width = 1.5.dp,
            color = borderColor,
            shape = RoundedCornerShape(12.dp),
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Fuel level",
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariantColor,
            )
            Spacer(Modifier.size(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeW = 8.dp.toPx()
                    val pad = strokeW / 2f + 4.dp.toPx()
                    val maxDiamByWidth = size.width - pad * 2
                    val maxDiamByHeight = (size.height - pad) * 2f
                    val diameter = minOf(maxDiamByWidth, maxDiamByHeight)
                    val cx = size.width / 2f
                    val cy = size.height - pad
                    val topLeft = Offset(cx - diameter / 2f, cy - diameter / 2f)
                    val arcSize = Size(diameter, diameter)

                    val sweep = 180f
                    val start = 180f

                    drawArc(
                        color = trackColor,
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = fillColor,
                        startAngle = start,
                        sweepAngle = sweep * pctF / 100f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round),
                    )

                    val rOuter = diameter / 2f - strokeW - 1.dp.toPx()
                    val rInner = rOuter - 4.dp.toPx()
                    for (i in 0..4) {
                        val ang = (start + sweep * i / 4f) * (PI.toFloat() / 180f)
                        val s = sin(ang.toDouble()).toFloat()
                        val c = cos(ang.toDouble()).toFloat()
                        drawLine(
                            color = tickColor,
                            start = Offset(cx + rOuter * c, cy + rOuter * s),
                            end = Offset(cx + rInner * c, cy + rInner * s),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        pct?.let { "${it.toInt()}%" } ?: "—",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            letterSpacing = (-1.0).sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = onSurfaceColor,
                    )
                }

                Text(
                    "E",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = onSurfaceVariantColor,
                )
                Text(
                    "F",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = onSurfaceVariantColor,
                )
            }

            Text(
                run {
                    val gal = data.fuelGallons?.let { "%.1f gal".format(it) }
                    val age = data.fuelLevelAge
                    when {
                        gal != null && age != null -> "$gal · $age"
                        gal != null -> gal
                        age != null -> age
                        else -> "—"
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariantColor,
            )
        }
    }
}
