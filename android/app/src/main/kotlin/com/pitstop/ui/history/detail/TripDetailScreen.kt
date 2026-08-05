package com.pitstop.ui.history.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.TripDetailDto
import com.pitstop.http.TripDtcDto
import com.pitstop.util.UnitFormat
import kotlin.math.roundToInt

/**
 * Trip detail surface. Mirrors the depth of the web TripDetailView
 * but tailored to a single-column phone layout. Renders, top to
 * bottom:
 *
 *   1. Auto-generated narrative sentence (skipped when empty)
 *   2. Hero stats card (duration / distance / MPG / max speed / max RPM)
 *   3. Secondary stats card (avg speed / idle / DTC count / odo Δ /
 *      avg coolant / weather snapshot)
 *   4. Series toggle chips + line chart
 *   5. Route map (when GPS points are present)
 *   6. DTC list (tap → DTCDetailScreen via the supplied callback)
 */
@Composable
fun TripDetailScreen(
    onOpenDtc: (code: String, vehicleId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val unitSystem by viewModel.unitSystem.collectAsStateWithLifecycle()
    when {
        ui.loading && ui.trip == null -> CenteredSpinner(modifier)
        ui.error != null && ui.trip == null -> CenteredError(ui.error ?: "Unknown error", modifier)
        ui.trip == null -> CenteredError("Trip not found", modifier)
        else -> Loaded(
            trip = ui.trip!!,
            route = ui.route,
            unitSystem = unitSystem,
            onOpenDtc = onOpenDtc,
            modifier = modifier,
        )
    }
}

@Composable
private fun Loaded(
    trip: TripDetailDto,
    route: List<com.pitstop.http.RoutePointDto>,
    unitSystem: String,
    onOpenDtc: (code: String, vehicleId: String) -> Unit,
    modifier: Modifier,
) {
    // Default the smoothing level once per trip: long captures
    // (> 300 samples in any series) get Medium out of the gate
    // because they're the ones that look noisy on a narrow screen.
    // Short trips render fine raw, so default to Off.
    val maxSeriesSize = remember(trip.samples) {
        trip.samples
            .groupingBy { it.metric }
            .eachCount()
            .values
            .maxOrNull() ?: 0
    }
    var smoothLevel by remember(trip.samples) {
        mutableStateOf(if (maxSeriesSize > 300) SmoothLevel.Medium else SmoothLevel.Off)
    }
    val seriesMap = remember(trip.samples, smoothLevel) {
        pivotSamples(trip.samples, smoothWindow = smoothLevel.windowSize)
    }
    val availableMetrics = remember(seriesMap) {
        TRIP_METRICS.filter { seriesMap[it.metric]?.points?.isNotEmpty() == true }
    }
    var visibleMetrics by remember(availableMetrics) {
        // Speed + RPM are the only default-on series. Fall back to the
        // first available metric (rather than "all of them") when
        // neither is present — with 17 chartable metrics, showing
        // everything on a partially-instrumented trip would render an
        // unreadable phone-width chart.
        val defaults = availableMetrics.filter { it.defaultVisible }.map { it.metric }
        mutableStateOf(
            when {
                defaults.isNotEmpty() -> defaults.toSet()
                availableMetrics.isNotEmpty() -> setOf(availableMetrics.first().metric)
                else -> emptySet()
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Headline timestamp.
        Text(
            text = fmtDateTimeLocal(trip.startedAt),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val narrative = remember(trip) { tripNarrative(trip) }
        if (narrative.isNotBlank()) {
            Text(
                text = narrative,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HeroStatsCard(trip)

        SecondaryStatsCard(trip)

        // Timeline chart + chip row, only when we have at least one
        // series with data.
        if (availableMetrics.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Timeline",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SeriesChipRow(
                        available = availableMetrics,
                        visible = visibleMetrics,
                        unitSystem = unitSystem,
                        onToggle = { m ->
                            visibleMetrics = if (m in visibleMetrics) {
                                visibleMetrics - m
                            } else {
                                visibleMetrics + m
                            }
                        },
                        smoothLevel = smoothLevel,
                        onCycleSmooth = { smoothLevel = smoothLevel.next() },
                    )
                    // Convert every plotted point into the active unit
                    // system here, once, so the chart body stays unit-
                    // agnostic and the axis ticks always agree with the
                    // chip label.
                    val display = remember(visibleMetrics, seriesMap, unitSystem) {
                        availableMetrics
                            .filter { it.metric in visibleMetrics }
                            .mapNotNull { def ->
                                seriesMap[def.metric]?.let { s ->
                                    DisplaySeries(
                                        series = s.copy(
                                            points = s.points.map { p ->
                                                p.copy(
                                                    value = def.quantity
                                                        .convert(p.value, unitSystem),
                                                )
                                            },
                                        ),
                                        color = def.color,
                                        unitLabel = def.quantity.unit(unitSystem),
                                        digits = def.digits,
                                    )
                                }
                            }
                    }
                    LineChart(series = display)
                }
            }
        }

        if (route.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Route",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp),
                            ),
                    ) {
                        MapLibreRouteView(points = route)
                    }
                    SpeedLegendRow()
                }
            }
        }

        if (trip.dtcs.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "DTCs during trip",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    for ((index, dtc) in trip.dtcs.withIndex()) {
                        if (index > 0) HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        DtcRow(
                            dtc = dtc,
                            onClick = { onOpenDtc(dtc.code, trip.vehicleId) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DtcRow(dtc: TripDtcDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                dtc.code,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            dtc.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            fmtClockLocal(dtc.seenAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeroStatsCard(trip: TripDetailDto) {
    val mi = trip.distanceKm?.let(::kmToMi)
    val mpg = if (trip.distanceKm != null && trip.fuelUsedL != null && trip.fuelUsedL > 0.4) {
        val gal = lToGal(trip.fuelUsedL)
        if (gal > 0) kmToMi(trip.distanceKm) / gal else null
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 3-col × 2-row grid — keeps every cell the same width so the
            // value column lines up vertically. Previously row 1 was 2
            // wide cells and row 2 was 3 narrower ones, which looked off.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell("Duration", fmtDuration(trip.durationS), Modifier.weight(1f))
                StatCell(
                    "Distance",
                    mi?.let { "%.1f mi".format(it) } ?: "—",
                    Modifier.weight(1f),
                )
                StatCell(
                    "MPG",
                    mpg?.let { "%.1f".format(it) } ?: "—",
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell(
                    "Max speed",
                    trip.maxSpeedKph?.let { "${kphToMph(it).roundToInt()} mph" } ?: "—",
                    Modifier.weight(1f),
                )
                StatCell(
                    "Max RPM",
                    trip.maxRpm?.let { "${it.roundToInt()}" } ?: "—",
                    Modifier.weight(1f),
                )
                StatCell(
                    "Avg speed",
                    trip.avgSpeedKph?.let { "${kphToMph(it).roundToInt()} mph" } ?: "—",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SecondaryStatsCard(trip: TripDetailDto) {
    val rows = buildList<Pair<String, String>> {
        // Avg speed moved up into the 3×2 hero grid — don't duplicate.
        trip.idleS?.let {
            val m = it / 60
            val s = it % 60
            add("Idle time" to if (m > 0) "${m}m ${s}s" else "${s}s")
        }
        if (trip.dtcCount > 0) add("DTCs fired" to trip.dtcCount.toString())
        // Odometer start/finish with delta, only when both endpoints
        // are known (WiCAN didn't always publish odometer in window).
        if (trip.odoStartKm != null && trip.odoEndKm != null) {
            val startMi = kmToMi(trip.odoStartKm)
            val endMi = kmToMi(trip.odoEndKm)
            val deltaMi = endMi - startMi
            add("Odo start" to "%,.0f mi".format(startMi))
            add("Odo end" to "%,.0f mi".format(endMi))
            add("Distance (odo Δ)" to "%.1f mi".format(deltaMi))
        }
        // Fuel level start/end (already calibration-normalized server-side).
        if (trip.fuelLevelStartPct != null && trip.fuelLevelEndPct != null) {
            add("Fuel level" to "${trip.fuelLevelStartPct.roundToInt()}% → ${trip.fuelLevelEndPct.roundToInt()}%")
        }
        // Gas-used estimate — computed by trip_detector from either MAF
        // integration (preferred) or fuel_level delta (OBD-5 fallback).
        // Flagged "(est.)" since both paths carry sensor noise — most
        // useful for long trips on partial tanks.
        trip.fuelUsedL?.takeIf { it > 0.01 }?.let { lit ->
            val gal = lit / 3.78541
            add("Gas used (est.)" to "%.2f gal".format(gal))
        }
        trip.avgCoolantC?.let {
            add("Avg coolant" to "${cToF(it).roundToInt()}°F")
        }
        if (trip.weatherTempC != null) {
            val f = cToF(trip.weatherTempC).roundToInt()
            val wmo = wmoLabel(trip.weatherCode)
            add("Weather" to "${f}°F${wmo?.let { ", $it" } ?: ""}")
        }
        trip.endedAt?.let {
            add("Ended" to fmtClockLocal(it))
        }
    }
    if (rows.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            for ((i, kv) in rows.withIndex()) {
                if (i > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        kv.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        kv.second,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeriesChipRow(
    available: List<TripMetricDef>,
    visible: Set<String>,
    unitSystem: String,
    onToggle: (String) -> Unit,
    smoothLevel: SmoothLevel,
    onCycleSmooth: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Series toggles — the metric selection. Wraps as needed.
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (def in available) {
                val on = def.metric in visible
                AssistChip(
                    onClick = { onToggle(def.metric) },
                    label = {
                        Text(
                            def.chipLabel(unitSystem),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (on) def.color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        // Smooth chip lives on its own line, right-aligned — it's a
        // chart control, not a series selector. Previously it sat at
        // the tail of the FlowRow which read as "another metric".
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            val smoothOn = smoothLevel != SmoothLevel.Off
            AssistChip(
                onClick = onCycleSmooth,
                label = {
                    Text(
                        "Smooth (${smoothLevel.label})",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (smoothOn) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    labelColor = if (smoothOn) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
        }
    }
}

/**
 * Chart smoothing level. Maps directly to a rolling-median window
 * size that `pivotSamples` applies before the existing downsample.
 * Stored in-memory per-trip; doesn't need to outlive the screen.
 */
internal enum class SmoothLevel(val label: String, val windowSize: Int) {
    Off("Off", 1),
    Light("Light", 3),
    Medium("Medium", 7),
    Heavy("Heavy", 15);

    fun next(): SmoothLevel {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

@Composable
private fun SpeedLegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "Stop" to 0xFFEF4444.toInt(),
            "City" to 0xFFF59E0B.toInt(),
            "Suburb" to 0xFF22C55E.toInt(),
            "Hwy" to 0xFF2F81F7.toInt(),
        ).forEach { (label, c) ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = Color(c), shape = RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
private fun CenteredSpinner(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredError(message: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Definitions of the trip-detail timeline series we know how to
 * render. Adding a new metric is a one-line append; the chart picks up
 * the color, the unit label and the value conversion automatically.
 *
 * The list mirrors the backend's `_TRIP_SAMPLE_METRICS` whitelist in
 * `api/trips.py` — that endpoint decides what a trip response may
 * contain, so anything not listed there can never have points, and
 * anything listed here but missing from a given trip is filtered out
 * by `availableMetrics` before the chips are drawn.
 *
 * Units: a def carries a [UnitFormat.Quantity], never a unit string.
 * The chip label gets the unit appended for the ACTIVE unit system, and
 * the series values are converted with the same Quantity, so the axis
 * ticks and the label can't disagree.
 *
 * Order here is the chip order — speed first, then RPM (the two
 * most-asked-about series and the only two on by default), then the
 * rest of the core drive trace, then fuel/exhaust, then emissions.
 */
internal data class TripMetricDef(
    val metric: String,
    /** Bare name; the unit is appended per unit-system at render time. */
    val label: String,
    val color: Color,
    val quantity: UnitFormat.Quantity,
    /** Decimals for the single-series Y-axis ticks. */
    val digits: Int = 1,
    /**
     * Whether the series starts visible. Only speed + RPM do: with 17
     * chartable metrics, "show everything that has data" would draw an
     * unreadable 17-line chart on a phone.
     */
    val defaultVisible: Boolean = false,
) {
    /** "Speed (mph)" / "Speed (km/h)" — unit resolved at render time. */
    fun chipLabel(system: String): String {
        val unit = quantity.unit(system)
        return if (unit.isBlank()) label else "$label ($unit)"
    }
}

internal val TRIP_METRICS: List<TripMetricDef> = listOf(
    // ── Core drive trace ──────────────────────────────────────────
    TripMetricDef(
        "vehicle_speed", "Speed", Color(0xFF2F81F7),
        UnitFormat.Quantity.SpeedKph, digits = 0, defaultVisible = true,
    ),
    TripMetricDef(
        "engine_rpm", "RPM", Color(0xFFF59E0B),
        UnitFormat.Quantity.None, digits = 0, defaultVisible = true,
    ),
    TripMetricDef("engine_load", "Load", Color(0xFFEAB308), UnitFormat.Quantity.Percent, 0),
    TripMetricDef("coolant_temp", "Coolant", Color(0xFFEF4444), UnitFormat.Quantity.TempC, 0),
    TripMetricDef("fuel_level", "Fuel", Color(0xFF22C55E), UnitFormat.Quantity.Percent, 0),
    TripMetricDef(
        "throttle_position", "Throttle", Color(0xFF14B8A6),
        UnitFormat.Quantity.Percent, 0,
    ),
    TripMetricDef("intake_air_temp", "Intake", Color(0xFF94A3B8), UnitFormat.Quantity.TempC, 0),
    TripMetricDef(
        "maf_air_flow", "MAF", Color(0xFF06B6D4),
        UnitFormat.Quantity.MassFlowGramsPerSec, 1,
    ),
    TripMetricDef(
        "manifold_pressure", "MAP", Color(0xFFA78BFA),
        UnitFormat.Quantity.PressureKpa, 0,
    ),
    TripMetricDef(
        "control_module_voltage", "Battery", Color(0xFFF472B6),
        UnitFormat.Quantity.Volt, 1,
    ),
    // ── Fuel + exhaust ────────────────────────────────────────────
    // g/s on the wire → L/h or gph depending on the toggle, matching
    // the Live tile and the web's fmtFuelRateLh().
    TripMetricDef(
        "engine_fuel_rate", "Fuel rate", Color(0xFFF97316),
        UnitFormat.Quantity.FuelRateGramsPerSec, 2,
    ),
    // kg/h in both unit systems on purpose — see the Quantity docs.
    TripMetricDef(
        "engine_exhaust_flow", "Exhaust", Color(0xFFA3E635),
        UnitFormat.Quantity.MassFlowKgPerHour, 1,
    ),
    // ── Emissions ─────────────────────────────────────────────────
    // Both cat banks: they normally track within a degree or two, so
    // the divergence is the diagnostic. Same hue family for that reason.
    TripMetricDef(
        "catalyst_temp_b1", "Cat B1", Color(0xFFFB7185),
        UnitFormat.Quantity.TempC, 0,
    ),
    TripMetricDef(
        "catalyst_temp_b2", "Cat B2", Color(0xFFE879F9),
        UnitFormat.Quantity.TempC, 0,
    ),
    // Commanded vs measured equivalence ratio — the PAIR is the signal
    // (fuel-control error); either alone is a flat line near 1.000,
    // hence 3 decimals on the ticks.
    TripMetricDef(
        "commanded_afr_ratio", "Cmd AFR", Color(0xFF38BDF8),
        UnitFormat.Quantity.Lambda, 3,
    ),
    TripMetricDef(
        "o2_s1_lambda", "O2 S1", Color(0xFF818CF8),
        UnitFormat.Quantity.Lambda, 3,
    ),
    TripMetricDef(
        "fuel_rail_pressure", "Fuel rail", Color(0xFF34D399),
        UnitFormat.Quantity.PressureKpa, 0,
    ),
)
