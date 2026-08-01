package com.pitstop.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.http.CostPerMilePointDto
import com.pitstop.http.FillupDto
import com.pitstop.http.MonthlySpendPointDto
import kotlin.math.abs

private val ACCENT = Color(0xFFF97316)
private val UP = Color(0xFF22C55E)
private val DOWN = Color(0xFFEF4444)

/** How many recent fillups the sparkline plots. */
private const val SPARK_TANKS = 12

/**
 * Stat strip above the Fillups list: last-tank MPG, $/mile and this
 * month's fuel spend, over a sparkline of recent per-tank MPG.
 *
 * MPG stats deliberately skip `is_missed` fillups — a missed fill
 * breaks the odometer chain, so its computed MPG is meaningless. This
 * mirrors the backend rule that any MPG aggregation must read
 * `is_missed` (see CLAUDE.md; several /analytics endpoints once
 * produced bogus MPG by omitting it).
 */
@Composable
fun FillupStatsHeader(
    fillups: List<FillupDto>,
    costPerMile: List<CostPerMilePointDto>,
    monthlySpend: List<MonthlySpendPointDto>,
    modifier: Modifier = Modifier,
) {
    val stats = remember(fillups, costPerMile, monthlySpend) {
        computeFillupStats(fillups, costPerMile, monthlySpend)
    }
    if (stats.isEmpty) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(
                    value = stats.lastMpg?.let { fmt1(it) } ?: "—",
                    unit = "MPG",
                    caption = stats.mpgDelta?.let { deltaText(it, "vs avg", 1) },
                    captionColor = stats.mpgDelta?.let { if (it >= 0) UP else DOWN },
                )
                StatTile(
                    value = stats.costPerMi?.let { "$" + fmt2(it) } ?: "—",
                    unit = "/mi",
                    caption = stats.costPerMiDelta?.let { deltaText(it, "", 2, "$") },
                    // Cheaper is better, so the colour logic inverts.
                    captionColor = stats.costPerMiDelta?.let { if (it <= 0) UP else DOWN },
                )
                StatTile(
                    value = stats.monthSpend?.let { "$" + fmt0(it) } ?: "—",
                    unit = stats.monthLabel ?: "",
                    caption = stats.monthSpendDelta?.let { deltaText(it, "", 0, "$") },
                    captionColor = stats.monthSpendDelta?.let { if (it <= 0) UP else DOWN },
                )
            }

            if (stats.spark.size >= 2) {
                Text(
                    "MPG last ${stats.spark.size} tanks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Sparkline(
                    values = stats.spark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    unit: String,
    caption: String?,
    captionColor: Color?,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Text(
            caption ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = captionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Minimal line sparkline — no axes, no grid. Flat series (max == min)
 *  render as a centred line rather than dividing by zero. */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxV = values.max()
        val minV = values.min()
        val span = (maxV - minV).takeIf { it > 1e-9 }
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = stepX * i
            val norm = if (span == null) 0.5f else ((v - minV) / span).toFloat()
            // Inset by the stroke width so peaks aren't clipped.
            val y = size.height - 3f - norm * (size.height - 6f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = ACCENT, style = Stroke(width = 3f))
        // Emphasise the latest tank.
        val lastNorm = if (span == null) 0.5f else ((values.last() - minV) / span).toFloat()
        drawCircle(
            color = ACCENT,
            radius = 5f,
            center = Offset(size.width, size.height - 3f - lastNorm * (size.height - 6f)),
        )
    }
}

// ── Stat computation (pure, unit-tested via FillupStatsTest) ──────────

data class FillupStats(
    val lastMpg: Double? = null,
    val mpgDelta: Double? = null,
    val costPerMi: Double? = null,
    val costPerMiDelta: Double? = null,
    val monthSpend: Double? = null,
    val monthSpendDelta: Double? = null,
    val monthLabel: String? = null,
    val spark: List<Double> = emptyList(),
) {
    val isEmpty: Boolean
        get() = lastMpg == null && costPerMi == null && monthSpend == null
}

fun computeFillupStats(
    fillups: List<FillupDto>,
    costPerMile: List<CostPerMilePointDto>,
    monthlySpend: List<MonthlySpendPointDto>,
): FillupStats {
    // `fillups` arrives newest-first from the API.
    val valid = fillups.filter { !it.isMissed && it.mpg != null && it.mpg > 0 }
    val lastMpg = valid.firstOrNull()?.mpg
    val avg = valid.mapNotNull { it.mpg }.takeIf { it.isNotEmpty() }?.average()
    val mpgDelta = if (lastMpg != null && avg != null) lastMpg - avg else null

    // Oldest-first for the sparkline so it reads left→right in time.
    val spark = valid.take(SPARK_TANKS).mapNotNull { it.mpg }.reversed()

    // Analytics endpoints return points oldest-first; the last entry
    // with a non-null value is the most recent real month.
    val cpmPoints = costPerMile.filter { it.costPerMi != null }
    val costPerMi = cpmPoints.lastOrNull()?.costPerMi
    val costPerMiPrev = cpmPoints.dropLast(1).lastOrNull()?.costPerMi
    val costPerMiDelta = if (costPerMi != null && costPerMiPrev != null) costPerMi - costPerMiPrev else null

    val spendPoints = monthlySpend.filter { it.fuel > 0 }
    val monthSpend = spendPoints.lastOrNull()?.fuel
    val monthPrev = spendPoints.dropLast(1).lastOrNull()?.fuel
    val monthSpendDelta = if (monthSpend != null && monthPrev != null) monthSpend - monthPrev else null
    val monthLabel = spendPoints.lastOrNull()?.month?.let { monthShortLabel(it) }

    return FillupStats(
        lastMpg = lastMpg,
        mpgDelta = mpgDelta,
        costPerMi = costPerMi,
        costPerMiDelta = costPerMiDelta,
        monthSpend = monthSpend,
        monthSpendDelta = monthSpendDelta,
        monthLabel = monthLabel,
        spark = spark,
    )
}

/** "2026-07" → "Jul". Returns the raw string if it isn't the expected
 *  shape, so a backend format change degrades to a label, not a crash. */
internal fun monthShortLabel(month: String): String {
    val parts = month.split("-")
    if (parts.size < 2) return month
    val idx = parts[1].toIntOrNull() ?: return month
    val names = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    return names.getOrNull(idx - 1) ?: month
}

private fun deltaText(delta: Double, suffix: String, decimals: Int, prefix: String = ""): String {
    val arrow = if (delta >= 0) "▲" else "▼"
    val mag = abs(delta)
    val num = when (decimals) {
        0 -> fmt0(mag)
        2 -> fmt2(mag)
        else -> fmt1(mag)
    }
    return listOf("$arrow $prefix$num", suffix).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun fmt0(v: Double): String = String.format("%.0f", v)
private fun fmt1(v: Double): String = String.format("%.1f", v)
private fun fmt2(v: Double): String = String.format("%.2f", v)
