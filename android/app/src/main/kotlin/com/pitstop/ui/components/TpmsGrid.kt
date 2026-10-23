package com.pitstop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pitstop.domain.VehicleSignals
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat

/**
 * Four-corner tire pressures: FL FR / RL RR around a simple car outline.
 * Each corner = muted label, big whole psi (always psi, as on the dash), muted
 * tire temp. A tire ≥ 2 psi below the highest of the four gets an amber
 * border, faint amber fill, amber number and a "LOW" tag. A corner with no
 * reading still renders, reading "—". Matches the web's TpmsGrid.vue.
 */
@Composable
fun TpmsGrid(
    tires: List<VehicleSignals.Tire>,
    system: String,
    modifier: Modifier = Modifier,
    /** Door-placard cold pressures (kPa); null hides the "of 35 · −4" line. */
    placardFrontKpa: Double? = null,
    placardRearKpa: Double? = null,
) {
    val placard = { c: VehicleSignals.Corner -> VehicleSignals.placardFor(c, placardFrontKpa, placardRearKpa) }
    val by = tires.associateBy { it.corner }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics { contentDescription = "Tire pressures" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TireCell(by[VehicleSignals.Corner.FL], VehicleSignals.Corner.FL, system, end = false, placard(VehicleSignals.Corner.FL))
            TireCell(by[VehicleSignals.Corner.RL], VehicleSignals.Corner.RL, system, end = false, placard(VehicleSignals.Corner.RL))
        }
        // The car body, spanning both rows.
        Box(
            Modifier
                .width(44.dp)
                .fillMaxHeight()
                .padding(vertical = 3.dp)
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TireCell(by[VehicleSignals.Corner.FR], VehicleSignals.Corner.FR, system, end = true, placard(VehicleSignals.Corner.FR))
            TireCell(by[VehicleSignals.Corner.RR], VehicleSignals.Corner.RR, system, end = true, placard(VehicleSignals.Corner.RR))
        }
    }
}

@Composable
private fun TireCell(
    tire: VehicleSignals.Tire?,
    corner: VehicleSignals.Corner,
    system: String,
    end: Boolean,
    placardKpa: Double? = null,
) {
    val low = tire?.low == true
    val verdict = VehicleSignals.placardVerdict(tire?.pressureKpa, placardKpa)
    val shape = RoundedCornerShape(10.dp)
    // Whole psi in both unit systems (dash parity); only the temp converts.
    val pressure = VehicleSignals.tirePressure(tire?.pressureKpa)
    val unit = VehicleSignals.TIRE_PRESSURE_UNIT
    val a11y = buildString {
        append(corner.label).append(' ')
        append(if (pressure == "—") "no reading" else "$pressure $unit")
        if (low) append(", low")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    1.dp,
                    if (low) MaterialTheme.ext.warn.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                shape,
            )
            .then(
                if (low) Modifier.background(MaterialTheme.ext.warnContainer.copy(alpha = 0.18f), shape)
                else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                corner.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (low) {
                Text(
                    "LOW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ext.warn,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                pressure,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (low) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurface,
            )
            if (pressure != "—") {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            UnitFormat.Quantity.TempC.format(tire?.tempC?.takeIf { VehicleSignals.validTireTempC(it) }, system, 0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (end) TextAlign.End else TextAlign.Start,
        )
        // "of 35 · −4": amber when ≥ 3 psi under placard, red "under" when
        // ≥ 25 % under (same thresholds as the server's trip verdict).
        verdict?.let { v ->
            Text(
                "of ${v.placardPsi} · " + (if (v.underInflated) "under ${v.deltaLabel}" else v.deltaLabel),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (v.belowSpec) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    v.underInflated -> MaterialTheme.ext.bad
                    v.belowSpec -> MaterialTheme.ext.warn
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Home "Tires" card: the grid plus how old the newest pressure is ("live"
 * under 90 s, then Nm/Nh/Nd ago) — it only updates while the WiCAN or the
 * phone is reporting. Hidden by the caller only when no corner ever reported.
 */
@Composable
fun TiresCard(
    tires: List<VehicleSignals.Tire>,
    newestMs: Long?,
    nowMs: Long,
    system: String,
    modifier: Modifier = Modifier,
    placardFrontKpa: Double? = null,
    placardRearKpa: Double? = null,
    title: String = "Tires",
    /** Home: opens the Tire detail screen (history + charts); null = not tappable. */
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                val stale = newestMs != null && nowMs - newestMs > 24L * 3_600_000L
                Text(
                    newestMs?.let { "updated ${VehicleSignals.ageLabel(it, nowMs)}" } ?: "no readings yet",
                    style = MaterialTheme.typography.labelSmall,
                    // Over a day old: still shown, flagged amber, values dimmed.
                    color = if (stale) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            TpmsGrid(
                tires, system,
                Modifier
                    .padding(top = 10.dp)
                    .alpha(if (newestMs != null && nowMs - newestMs > 24L * 3_600_000L) 0.6f else 1f),
                placardFrontKpa, placardRearKpa,
            )
            if (tires.any { it.low }) {
                Text(
                    "Highlighted: 2 psi or more below the highest tire",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (onClick != null) {
                Text(
                    "Tap for pressure history and charts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
        ) { body() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors) { body() }
    }
}

/**
 * Six small boxes labelled 1–6 with whole misfire counts, amber when > 0.
 * Caption "per-cylinder counters". Web parity with LiveView's misfire row.
 */
@Composable
fun MisfireRow(counts: Map<Int, Double?>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "Misfires",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (cyl in VehicleSignals.MISFIRE_CYLINDERS) {
                val v = counts[cyl]
                val hot = v != null && v > 0.0
                val shape = RoundedCornerShape(8.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            BorderStroke(
                                1.dp,
                                if (hot) MaterialTheme.ext.warn.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            shape,
                        )
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Cylinder $cyl misfires ${v?.toLong() ?: "no reading"}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$cyl",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        v?.let { "%.0f".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (hot) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            "per-cylinder counters",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
