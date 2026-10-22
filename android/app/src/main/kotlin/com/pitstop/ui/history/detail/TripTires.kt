package com.pitstop.ui.history.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.domain.VehicleSignals
import com.pitstop.http.TripSampleDto
import com.pitstop.http.TripTiresDto
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import kotlin.math.roundToInt

/** Corner colours — the web's Tires chart uses the same four. */
private val CORNER_COLORS = mapOf(
    VehicleSignals.Corner.FL to Color(0xFF2F81F7),
    VehicleSignals.Corner.FR to Color(0xFFF97316),
    VehicleSignals.Corner.RL to Color(0xFF3FB950),
    VehicleSignals.Corner.RR to Color(0xFFD946EF),
)

/** One summary row per corner, already formatted; pure so it is unit-tested. */
internal data class TireSummaryRow(
    val label: String,
    /** "30 → 32 psi" — whole psi in every unit system (dash parity). */
    val pressure: String,
    /** "18 → 29 °C" warm-up; null when the trip has no temps. */
    val warmUp: String?,
    val low: Boolean,
    /** "of 35 · −4" from the server's start-pressure verdict; null = no placard. */
    val placard: String? = null,
    val belowSpec: Boolean = false,
    val underInflated: Boolean = false,
)

/**
 * Rows for [TripTiresSummary] in FL, FR, RL, RR order. LOW comes from the
 * server's `low_corners` (end-of-trip, 2-psi rule); when that list is absent
 * the same [VehicleSignals.tpms] rule runs on the end pressures. A corner
 * with no reading says "—", never 0.
 */
internal fun tireSummaryRows(tires: TripTiresDto, system: String): List<TireSummaryRow> {
    val byCorner = tires.corners.associateBy { it.corner.lowercase() }
    val localLow = VehicleSignals.tpms(
        VehicleSignals.Corner.entries.associateWith { byCorner[it.name.lowercase()]?.pressureEndKpa },
    ).filter { it.low }.map { it.corner.name.lowercase() }.toSet()
    val low = tires.lowCorners.map { it.lowercase() }.toSet().ifEmpty { localLow }
    return VehicleSignals.Corner.entries.map { c ->
        val k = c.name.lowercase()
        val row = byCorner[k]
        val start = VehicleSignals.tirePressure(row?.pressureStartKpa)
        val end = VehicleSignals.tirePressure(row?.pressureEndKpa)
        val pressure = when {
            start == "—" && end == "—" -> "—"
            start == end || start == "—" -> "$end psi"
            end == "—" -> "$start psi"
            else -> "$start → $end psi"
        }
        val t0 = row?.tempStartC
        val t1 = row?.tempEndC
        val warm = when {
            t0 == null && t1 == null -> null
            t0 == null || t1 == null || t0.roundToInt() == t1.roundToInt() ->
                UnitFormat.Quantity.TempC.format(t1 ?: t0, system, 0)
            else -> "${UnitFormat.Quantity.TempC.number(t0, system, 0)} → " +
                UnitFormat.Quantity.TempC.format(t1, system, 0)
        }
        val plc = row?.placardKpa?.let { (it * VehicleSignals.PSI_PER_KPA).roundToInt() }
        val placard = if (plc != null && row.belowPlacardPsi != null) {
            val d = -Math.round(row.belowPlacardPsi).toInt()
            val delta = when {
                d < 0 -> "−${-d}"
                d > 0 -> "+$d"
                else -> "±0"
            }
            "of $plc · " + (if (row.underInflated) "under $delta" else delta)
        } else {
            null
        }
        TireSummaryRow(
            c.label, pressure, warm, k in low,
            placard = placard,
            belowSpec = row?.belowSpec == true,
            underInflated = row?.underInflated == true,
        )
    }
}

/**
 * Compact per-trip Tires summary: start → end pressure per corner, the
 * warm-up, and LOW on any corner 2 psi or more below the highest at the
 * end of the trip. Mirrors the web trip detail's tires block.
 */
@Composable
internal fun TripTiresSummary(tires: TripTiresDto, system: String, modifier: Modifier = Modifier) {
    val rows = remember(tires, system) { tireSummaryRows(tires, system) }
    // 2×2 like the car (and the web side card): FL FR / RL RR.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in rows.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in pair) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (r.low) {
                                Text(
                                    "LOW",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.ext.warn,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        Text(
                            r.pressure,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = if (r.low) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            r.warmUp ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        r.placard?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (r.belowSpec) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    r.underInflated -> MaterialTheme.ext.bad
                                    r.belowSpec -> MaterialTheme.ext.warn
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        if (rows.any { it.low }) {
            Text(
                "Low: 2 psi or more below the highest tire at trip end.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The trip has a tire reading in either the samples or the summary. */
internal fun tripHasTires(trip: com.pitstop.http.TripDetailDto): Boolean =
    trip.tires != null || trip.samples.any { it.metric.startsWith("tire_pressure_") && it.valueNum != null }

/**
 * Tires card for trip detail: a chart of the four pressures (whole psi) with
 * a Temps toggle for the four tire temperatures, then the summary. Hidden by
 * the caller only when the trip has no tire data. Corners with no samples
 * simply have no line — gaps are never drawn as zero.
 */
@Composable
internal fun TripTiresCard(
    samples: List<TripSampleDto>,
    tires: TripTiresDto?,
    system: String,
    modifier: Modifier = Modifier,
) {
    var temps by rememberSaveable { mutableStateOf(false) }
    val seriesMap = remember(samples) {
        // Same sentinels the backend drops: 0 kPa = a sensor that didn't
        // answer, -40 °C = the floor of the temp byte.
        pivotSamples(
            samples.filter { s ->
                val v = s.valueNum
                when {
                    v == null -> false
                    s.metric.startsWith("tire_pressure_") -> v > 0
                    s.metric.startsWith("tire_temp_") -> VehicleSignals.validTireTempC(v)
                    else -> false
                }
            },
        )
    }
    val display = remember(seriesMap, temps, system) {
        VehicleSignals.Corner.entries.mapNotNull { c ->
            val key = if (temps) c.tempMetric else c.pressureMetric
            val s = seriesMap[key]?.takeIf { it.points.isNotEmpty() } ?: return@mapNotNull null
            DisplaySeries(
                series = s.copy(
                    points = s.points.map { p ->
                        p.copy(
                            value = if (temps) {
                                UnitFormat.Quantity.TempC.convert(p.value, system)
                            } else {
                                p.value * VehicleSignals.PSI_PER_KPA
                            },
                        )
                    },
                ),
                color = CORNER_COLORS.getValue(c),
                unitLabel = if (temps) UnitFormat.Quantity.TempC.unit(system) else VehicleSignals.TIRE_PRESSURE_UNIT,
                digits = 0,
                // Web parity: step lines, carried ≤90 s, then a gap.
                step = true,
                carryMs = 90_000L,
            )
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tires",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                FilterChip(
                    selected = !temps,
                    onClick = { temps = false },
                    label = { Text("Pressure") },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = temps,
                    onClick = { temps = true },
                    label = { Text("Temp") },
                )
            }
            if (display.isNotEmpty()) {
                // Legend: corner colours.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (c in VehicleSignals.Corner.entries) {
                        Text(
                            c.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = CORNER_COLORS.getValue(c),
                        )
                    }
                }
                // One axis for all four: the spread is the story. ~30 s
                // cadence, so a gap over 3 min is a dropout, not data.
                // Dashed placard baseline(s) in pressure mode; hidden with no
                // placard. Front and rear only differ on some vehicles.
                val refs = if (temps) emptyList() else listOfNotNull(
                    tires?.placardFrontKpa, tires?.placardRearKpa,
                ).distinct().map { kpa ->
                    val psi = kpa * VehicleSignals.PSI_PER_KPA
                    ReferenceLine(psi, "placard ${psi.roundToInt()} psi")
                }
                LineChart(series = display, height = 200.dp, sharedYAxis = true, referenceLines = refs)
            } else {
                Text(
                    if (temps) "No tire temperatures on this trip" else "No tire pressure samples on this trip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tires?.let { TripTiresSummary(it, system) }
        }
    }
}
