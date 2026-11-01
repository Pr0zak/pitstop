package com.pitstop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.pitstop.domain.VehicleSignals
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat

/**
 * Four-corner tire pressures, "dash view" (picked 2026-10-31): a one-line
 * verdict strip, then a top-down car whose four tires are coloured by status
 * with the big whole-psi number beside each wheel, its temp and the delta to
 * the door placard. Status (VehicleSignals.tireStatus): LOW (≥ 2 psi below the
 * highest tire) full amber, only under the placard dim amber, under-inflated
 * red, fine green, no reading grey "—". Web parity: TpmsGrid.vue.
 */
@Composable
fun TpmsGrid(
    tires: List<VehicleSignals.Tire>,
    system: String,
    modifier: Modifier = Modifier,
    /** Door-placard cold pressures (kPa); null hides the delta and the placard line. */
    placardFrontKpa: Double? = null,
    placardRearKpa: Double? = null,
) {
    val by = tires.associateBy { it.corner }
    val placard = { c: VehicleSignals.Corner -> VehicleSignals.placardFor(c, placardFrontKpa, placardRearKpa) }
    val status = VehicleSignals.Corner.entries.associateWith { VehicleSignals.tireStatus(by[it], placard(it)) }
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Tire pressures" }) {
        VehicleSignals.tireHeadline(tires, placardFrontKpa, placardRearKpa)?.let { (st, text) ->
            val c = statusColor(st, strong = true)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(c, CircleShape))
                Text(text, style = MaterialTheme.typography.labelLarge, color = c, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                for (c in listOf(VehicleSignals.Corner.FL, VehicleSignals.Corner.RL)) {
                    TireReadout(by[c], c, status.getValue(c), placard(c), system, end = true)
                }
            }
            CarTopDown(status, Modifier.padding(horizontal = 12.dp).size(84.dp, 172.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                for (c in listOf(VehicleSignals.Corner.FR, VehicleSignals.Corner.RR)) {
                    TireReadout(by[c], c, status.getValue(c), placard(c), system, end = false)
                }
            }
        }
        val f = placardFrontKpa?.let { (it * VehicleSignals.PSI_PER_KPA).roundToInt() }
        val r = placardRearKpa?.let { (it * VehicleSignals.PSI_PER_KPA).roundToInt() }
        if (f != null || r != null) {
            Text(
                if (f == r || r == null) "Door placard $f psi cold"
                else if (f == null) "Door placard $r psi cold (rear)"
                else "Door placard $f front / $r rear psi cold",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

/** Status colour. [strong] = the verdict strip: under-placard uses full amber there. */
@Composable
private fun statusColor(s: VehicleSignals.TireStatus, strong: Boolean = false): Color = when (s) {
    VehicleSignals.TireStatus.GOOD -> MaterialTheme.ext.good
    VehicleSignals.TireStatus.LOW -> MaterialTheme.ext.warn
    VehicleSignals.TireStatus.UNDER_PLACARD -> if (strong) MaterialTheme.ext.warn else MaterialTheme.ext.warn.copy(alpha = 0.45f)
    VehicleSignals.TireStatus.UNDER_INFLATED -> MaterialTheme.ext.bad
    VehicleSignals.TireStatus.NONE -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun TireReadout(
    tire: VehicleSignals.Tire?,
    corner: VehicleSignals.Corner,
    st: VehicleSignals.TireStatus,
    placardKpa: Double?,
    system: String,
    end: Boolean,
) {
    // Whole psi in both unit systems (dash parity); only the temp converts.
    val pressure = VehicleSignals.tirePressure(tire?.pressureKpa)
    val unit = VehicleSignals.TIRE_PRESSURE_UNIT
    val verdict = VehicleSignals.placardVerdict(tire?.pressureKpa, placardKpa)
    val alarm = st == VehicleSignals.TireStatus.LOW || st == VehicleSignals.TireStatus.UNDER_INFLATED
    val a11y = buildString {
        append(corner.label).append(' ')
        append(if (pressure == "—") "no reading" else "$pressure $unit")
        when (st) {
            VehicleSignals.TireStatus.UNDER_INFLATED -> append(", under-inflated")
            VehicleSignals.TireStatus.LOW -> append(", low")
            VehicleSignals.TireStatus.UNDER_PLACARD -> append(", below placard")
            else -> {}
        }
    }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = a11y },
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        Text(
            corner.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                pressure,
                fontSize = 32.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (alarm) statusColor(st) else MaterialTheme.colorScheme.onSurface,
            )
            if (pressure != "—") {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        Text(
            UnitFormat.Quantity.TempC.format(tire?.tempC?.takeIf { VehicleSignals.validTireTempC(it) }, system, 0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        verdict?.let { v ->
            Text(
                "${v.deltaLabel} vs spec",
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

/** Top-down car outline with the four tires filled in their status colours. */
@Composable
private fun CarTopDown(status: Map<VehicleSignals.Corner, VehicleSignals.TireStatus>, modifier: Modifier) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val glass = MaterialTheme.colorScheme.outlineVariant
    val colors = VehicleSignals.Corner.entries.associateWith { statusColor(status.getValue(it)) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tw = w * 0.2f
        val th = h * 0.2f
        fun tire(x: Float, y: Float, c: VehicleSignals.Corner) =
            drawRoundRect(colors.getValue(c), Offset(x, y), Size(tw, th), CornerRadius(tw * 0.35f))
        // Tires first; the body covers their inner half.
        tire(0f, h * 0.14f, VehicleSignals.Corner.FL)
        tire(w - tw, h * 0.14f, VehicleSignals.Corner.FR)
        tire(0f, h * 0.66f, VehicleSignals.Corner.RL)
        tire(w - tw, h * 0.66f, VehicleSignals.Corner.RR)
        val bx = tw * 0.55f
        val bodySize = Size(w - 2 * bx, h)
        val radius = CornerRadius(w * 0.28f, w * 0.3f)
        drawRoundRect(body, Offset(bx, 0f), bodySize, radius)
        drawRoundRect(outline, Offset(bx, 0f), bodySize, radius, style = Stroke(1.5.dp.toPx()))
        // Windshield and rear glass.
        drawRoundRect(glass, Offset(bx + w * 0.1f, h * 0.24f), Size(w - 2 * bx - w * 0.2f, h * 0.13f), CornerRadius(8f))
        drawRoundRect(glass, Offset(bx + w * 0.12f, h * 0.78f), Size(w - 2 * bx - w * 0.24f, h * 0.08f), CornerRadius(8f))
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
