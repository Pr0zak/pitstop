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
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.CornerRadius
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
    /** When true, fuelLevelPct came from the backend's persisted
     *  hybrid estimator (vehicles.fuel_level_estimate_l). Live BLE
     *  samples should NOT overlay this — the estimator is intentionally
     *  stable between fillups + trips + sensor-snaps. When false, the
     *  hero is showing the legacy smoothed sensor and live BLE overlay
     *  is welcome. */
    val fuelLevelIsEstimate: Boolean = false,
    /** FUEL-CONFIDENCE: true when the fuel-level estimate's
     *  updated-at is older than 7 days, so the gauge shows a warn-tone
     *  "stale" badge (web parity). */
    val fuelLevelStale: Boolean = false,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fuel level",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariantColor,
                )
                if (data.fuelLevelStale) {
                    Spacer(Modifier.size(6.dp))
                    Box(
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = Color(0xFFFFB020),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "STALE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color(0xFFFFB020),
                        )
                    }
                }
            }
            Spacer(Modifier.size(4.dp))

            // Linear bar, not an arc. The semicircle version had four
            // separate geometry faults and every one of them came from
            // positions being derived independently instead of from one
            // source: StrokeCap.Round extended the stroke strokeW/2 PAST
            // each endpoint (at E and F the tangent is vertical, so both
            // caps hung below the baseline — the visible misalignment);
            // ticks were placed at R - strokeW - 1dp while the arc's inner
            // edge is R - strokeW/2, leaving them floating in the gap; the
            // E/F labels were Box-aligned to the card rather than to the
            // arc's ends, so they drifted with card width; and the value
            // sat at BottomCenter on top of the ticks.
            //
            // A bar has one axis. Track, fill, ticks and both labels are
            // all placed off the same left/right edges, so they cannot
            // disagree — and the readout gets to be the biggest thing on
            // the card, which is what a glance actually wants.
            Text(
                pct?.let { "${it.roundToInt()}%" } ?: "—",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.displaySmall.copy(
                    letterSpacing = (-1.5).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = onSurfaceColor,
            )

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
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariantColor,
            )

            Spacer(Modifier.size(10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            ) {
                val barH = 14.dp.toPx()
                val r = barH / 2f
                val left = 0f
                val right = size.width
                val w = right - left

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(w, barH),
                    cornerRadius = CornerRadius(r, r),
                )
                // Only draw the fill when it is at least as wide as the
                // rounded end, otherwise the corner radius renders a lozenge
                // that reads as more fuel than there is at 1-2 %.
                val fillW = w * pctF / 100f
                if (fillW >= barH) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(left, 0f),
                        size = Size(fillW, barH),
                        cornerRadius = CornerRadius(r, r),
                    )
                } else if (fillW > 0f) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(left, 0f),
                        size = Size(barH, barH),
                        cornerRadius = CornerRadius(r, r),
                    )
                }

                // Quarter ticks, hung off the SAME left/right the bar uses.
                for (i in 0..4) {
                    val x = left + w * i / 4f
                    // Inset the outermost ticks by the corner radius so they
                    // sit under the bar's flat edge rather than its curve.
                    val tx = when (i) {
                        0 -> x + r
                        4 -> x - r
                        else -> x
                    }
                    drawLine(
                        color = tickColor.copy(alpha = 0.7f),
                        start = Offset(tx, barH + 3.dp.toPx()),
                        end = Offset(tx, barH + 8.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "E",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = onSurfaceVariantColor,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "F",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = onSurfaceVariantColor,
                )
            }
        }
    }
}
