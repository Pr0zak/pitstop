package com.pitstop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Phone-side mirror of the web Overview hero strip (frontend OverviewView.vue).
 * Same four metrics, same instrument-cluster type treatment, derived from the
 * same /fillups + /analytics/mpg payloads.
 *
 * On the phone we render a 2×2 grid (two rows of two cards) instead of the web
 * single-row strip — fits the 412 dp Pixel viewport without horizontal scroll.
 */

data class HeroCardData(
    val avgConsumptionMpg: Double?,
    val latestPpg: Double?,
    val ppgDeltaPct: Double?,
    val monthCost: Double,
    val monthCount: Int,
    /** Current fuel level as a percentage (0-100) from the latest
     *  pid_readings sample. Null when no reading is available. */
    val fuelLevelPct: Double?,
    /** Estimated gallons remaining = tank capacity × (fuel_level / 100).
     *  Null when either tank capacity or fuel_level is unknown. */
    val fuelGallons: Double?,
    /** Relative-time text for the fuel_level reading age (e.g. "live",
     *  "3h ago"). Empty/null when no reading. */
    val fuelLevelAge: String?,
    val mpgSeries: List<Double>,
)

@Composable
fun FuelHeroCards(
    data: HeroCardData,
    modifier: Modifier = Modifier,
) {
    // No internal horizontal padding — the host screen owns padding.
    // (Earlier this card hard-coded `.padding(horizontal = 16.dp)` which
    // forced StatusScreen to apply a negative compensating pad. Removed.)
    Column(
        modifier = modifier
            .fillMaxWidth(),
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
                        delta > 0.5 -> Color(0xFFFF3A2E) // danger when current is more expensive
                        delta < -0.5 -> Color(0xFF4ADE80) // success when cheaper
                        else -> null
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCard(
                modifier = Modifier.weight(1f),
                title = "This month",
                value = "$%.2f".format(data.monthCost),
                unit = "",
                sub = "${data.monthCount} fillup${if (data.monthCount == 1) "" else "s"}",
            )
            HeroCard(
                modifier = Modifier.weight(1f),
                title = "Fuel level",
                value = data.fuelLevelPct?.let { "%.0f".format(it) } ?: "—",
                unit = "%",
                sub = run {
                    val gal = data.fuelGallons?.let { "%.1f gal".format(it) }
                    val age = data.fuelLevelAge
                    when {
                        gal != null && age != null -> "$gal · $age"
                        gal != null -> gal
                        age != null -> age
                        else -> "—"
                    }
                },
            )
        }

        // The old "MPG (year)" sparkline was pulled out of this card —
        // it lives in MpgYearChart with proper axis labels + tooltip
        // and is wired in StatusScreen below the 2×2 grid.
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

