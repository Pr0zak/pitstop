package com.pitstop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.domain.VehicleSignals
import com.pitstop.ui.theme.ext
import kotlin.math.roundToInt

/** Newest `oil_life_pct` reading (Maintenance Minder, cluster DID 7068). */
data class OilLife(val pct: Double, val atMs: Long)

/**
 * Oil life card: big percentage, a bar, the Minder's own wording, and the
 * reading's age. Shown on Home and Car → Service once the car has reported.
 * Web parity: OilLifeCard.vue; thresholds in [VehicleSignals.oilLifeStatus].
 */
@Composable
fun OilLifeCard(oil: OilLife, nowMs: Long, modifier: Modifier = Modifier) {
    val status = VehicleSignals.oilLifeStatus(oil.pct)
    val color = when (status) {
        VehicleSignals.OilStatus.DUE_NOW -> MaterialTheme.ext.bad
        VehicleSignals.OilStatus.DUE_SOON -> MaterialTheme.ext.warn
        VehicleSignals.OilStatus.GOOD -> MaterialTheme.ext.good
        VehicleSignals.OilStatus.NONE -> MaterialTheme.colorScheme.outline
    }
    val pct = oil.pct.roundToInt()
    val headline = VehicleSignals.oilLifeHeadline(oil.pct)
    val stale = nowMs - oil.atMs > 7L * 86_400_000L
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Oil life $pct percent. $headline" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Oil life",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(
                    "updated ${VehicleSignals.ageLabel(oil.atMs, nowMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stale) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "$pct%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (status == VehicleSignals.OilStatus.GOOD) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (status == VehicleSignals.OilStatus.GOOD) MaterialTheme.colorScheme.onSurfaceVariant else color,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((oil.pct / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .fillMaxHeight()
                        .background(color),
                )
            }
            Text(
                "From the car's Maintenance Minder. Due soon at 15%, due now at 5%.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
