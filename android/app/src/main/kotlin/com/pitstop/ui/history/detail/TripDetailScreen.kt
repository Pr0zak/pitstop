package com.pitstop.ui.history.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.LaunchedEffect
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
    val storedSeries by viewModel.storedSeries.collectAsStateWithLifecycle()
    when {
        ui.loading && ui.trip == null -> CenteredSpinner(modifier)
        ui.error != null && ui.trip == null -> CenteredError(ui.error ?: "Unknown error", modifier)
        ui.trip == null -> CenteredError("Trip not found", modifier)
        else -> Loaded(
            trip = ui.trip!!,
            route = ui.route,
            unitSystem = unitSystem,
            storedSeries = storedSeries,
            onPersistSeries = viewModel::setSeries,
            onTowingChange = viewModel::setTowing,
            onCategoryChange = viewModel::setCategory,
            onOpenDtc = onOpenDtc,
            modifier = modifier,
        )
    }
}

/**
 * Visible to the debug design gallery (src/debug) so the REAL screen can
 * be rendered against synthetic data on an emulator — verifying the
 * shipping composable rather than a mock of it. `internal`, so this is
 * still module-private in a release build.
 */
@Composable
internal fun Loaded(
    trip: TripDetailDto,
    route: List<com.pitstop.http.RoutePointDto>,
    unitSystem: String,
    storedSeries: StoredSeries,
    onPersistSeries: (Set<String>) -> Unit,
    onTowingChange: (Boolean) -> Unit,
    onCategoryChange: (String?) -> Unit,
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
    // Speed + RPM are the only default-on series. Fall back to the first
    // available metric (rather than "all of them") when neither is
    // present — with 18 chartable metrics, showing everything on a
    // partially-instrumented trip would render an unreadable chart.
    val defaultMetrics = remember(availableMetrics) {
        val defaults = availableMetrics.filter { it.defaultVisible }.map { it.metric }
        when {
            defaults.isNotEmpty() -> defaults.toSet()
            availableMetrics.isNotEmpty() -> setOf(availableMetrics.first().metric)
            else -> emptySet()
        }
    }
    // Null until we've decided what to show, which needs BOTH the trip's
    // available metrics and the persisted choice. Seeding straight from
    // `defaultMetrics` would flash Speed+RPM and then swap once DataStore
    // arrives a frame later.
    var visibleMetrics by remember(availableMetrics) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(availableMetrics, storedSeries) {
        if (visibleMetrics != null || !storedSeries.loaded) return@LaunchedEffect
        val stored = storedSeries.metrics
        visibleMetrics = when {
            stored == null -> defaultMetrics
            // Intersect with what this trip actually has: a stored choice
            // of "Fuel rate" means nothing on a cellular trip that never
            // captured it. Falling back to defaults there is better than
            // an empty chart -- and this fallback is NOT persisted, so the
            // real choice survives for trips that do have the data.
            else -> stored.intersect(availableMetrics.map { it.metric }.toSet())
                .ifEmpty { defaultMetrics }
        }
    }
    val shown = visibleMetrics ?: emptySet()
    // Only an explicit tap writes back. Anything derived above is a
    // display fallback and must not overwrite what the user picked.
    val toggle: (String) -> Unit = { m ->
        val next = if (m in shown) shown - m else shown + m
        visibleMetrics = next
        onPersistSeries(next)
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

        HeroStatsCard(trip, unitSystem)

        TowingCard(trip.isTowing, onTowingChange)

        TagCard(trip.gpsOnly, trip.category, onCategoryChange)

        SecondaryStatsCard(trip, unitSystem)

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
                    var pickerOpen by remember { mutableStateOf(false) }
                    TimelineControls(
                        selectedCount = shown.size,
                        smoothLevel = smoothLevel,
                        onCycleSmooth = { smoothLevel = smoothLevel.next() },
                        onOpenPicker = { pickerOpen = true },
                    )
                    ActiveSeriesRow(
                        available = availableMetrics,
                        visible = shown,
                        unitSystem = unitSystem,
                        onToggle = toggle,
                    )
                    if (pickerOpen) {
                        SeriesPickerSheet(
                            available = availableMetrics,
                            visible = shown,
                            unitSystem = unitSystem,
                            onToggle = toggle,
                            onReset = {
                                visibleMetrics = defaultMetrics
                                onPersistSeries(defaultMetrics)
                            },
                            onDismiss = { pickerOpen = false },
                        )
                    }
                    // Convert every plotted point into the active unit
                    // system here, once, so the chart body stays unit-
                    // agnostic and the axis ticks always agree with the
                    // chip label.
                    val display = remember(shown, seriesMap, unitSystem) {
                        availableMetrics
                            .filter { it.metric in shown }
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
                    // Taller than the old 220 dp: variant B's single
                    // legend row freed ~300 dp, and the chart is what the
                    // card exists to show.
                    LineChart(series = display, height = 260.dp)
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

/**
 * Towing flag. Sits directly under the hero stats because that is where the
 * MPG number is — the flag exists to explain a figure that would otherwise
 * look like a bad tank.
 */
/**
 * Purpose tag, plus the provenance note when there was no engine data.
 *
 * The two are separate on purpose: `gps_only` is DERIVED and not editable —
 * whether OBD samples existed is a fact, and letting a user assert otherwise
 * would only produce a wrong answer. What the user knows and the system does
 * not is what the journey WAS, which is what `category` carries.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
)
@Composable
private fun TagCard(
    gpsOnly: Boolean,
    category: String?,
    onCategoryChange: (String?) -> Unit,
) {
    var draft by remember(category) { mutableStateOf(category.orEmpty()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Tag", style = MaterialTheme.typography.titleSmall)
            if (gpsOnly) {
                Text(
                    "No engine data — the phone recorded this on its own, so " +
                        "it may not have been this vehicle. Tag it so that's " +
                        "obvious later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Category") },
                placeholder = { Text("Boat, Commute, Road trip, …") },
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (suggestion in listOf("Boat", "Commute", "Road trip", "Errands", "Work")) {
                    AssistChip(
                        onClick = { draft = suggestion; onCategoryChange(suggestion) },
                        label = {
                            Text(suggestion, style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { onCategoryChange(draft.trim().ifBlank { null }) },
                ) { Text("Save tag") }
                if (!category.isNullOrBlank()) {
                    androidx.compose.material3.TextButton(
                        onClick = { draft = ""; onCategoryChange(null) },
                    ) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun TowingCard(isTowing: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Towing", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Fuel economy under tow isn't comparable to a normal trip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = isTowing, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun HeroStatsCard(trip: TripDetailDto, unitSystem: String) {
    val dist = UnitFormat.Quantity.DistanceKm
    val speed = UnitFormat.Quantity.SpeedKph
    // MPG stays MPG in both unit systems, deliberately. Unlike the values
    // around it, it is not a quantity in a convertible unit — it is a
    // named figure of merit, and the metric equivalent (L/100km) inverts
    // the scale, so "higher is better" would silently flip. Converting it
    // needs its own label and its own decision; it is not a units bug.
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
                    dist.format(trip.distanceKm, unitSystem, 1),
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
                    speed.format(trip.maxSpeedKph, unitSystem, 0),
                    Modifier.weight(1f),
                )
                StatCell(
                    "Max RPM",
                    trip.maxRpm?.let { "${it.roundToInt()}" } ?: "—",
                    Modifier.weight(1f),
                )
                StatCell(
                    "Avg speed",
                    speed.format(trip.avgSpeedKph, unitSystem, 0),
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SecondaryStatsCard(trip: TripDetailDto, unitSystem: String) {
    val dist = UnitFormat.Quantity.DistanceKm
    val rows = buildList<Pair<String, String>> {
        // Avg speed moved up into the 3×2 hero grid — don't duplicate.
        trip.idleS?.let {
            val m = it / 60
            val s = it % 60
            add("Idle time" to if (m > 0) "${m}m ${s}s" else "${s}s")
        }
        if (trip.dtcCount > 0) add("DTCs fired" to trip.dtcCount.toString())
        // Odometer start → end on ONE row. This was three rows ("Odo
        // start", "Odo end", "Distance (odo Δ)") for a single fact, and
        // the delta duplicates the hero card's Distance.
        //
        // Server-side these are already offset-corrected against the
        // vehicle's odometer_offset_km, so they read the same as the dash.
        if (trip.odoStartKm != null && trip.odoEndKm != null) {
            val u = dist.unit(unitSystem)
            add(
                "Odometer" to "%,.0f → %,.0f %s".format(
                    dist.convert(trip.odoStartKm, unitSystem),
                    dist.convert(trip.odoEndKm, unitSystem),
                    u,
                ),
            )
        }
        // Fuel level start/end (already calibration-normalized server-side).
        if (trip.fuelLevelStartPct != null && trip.fuelLevelEndPct != null) {
            add("Fuel level" to "${trip.fuelLevelStartPct.roundToInt()}% → ${trip.fuelLevelEndPct.roundToInt()}%")
        }
        // Gas-used estimate — computed by trip_stats from the ECU fuel
        // rate (preferred) or a MAF integral. Flagged "(est.)" since both
        // carry sensor noise; most useful on long trips.
        trip.fuelUsedL?.takeIf { it > 0.01 }?.let { lit ->
            add("Gas used (est.)" to UnitFormat.Quantity.VolumeL.format(lit, unitSystem, 2))
        }
        trip.avgCoolantC?.let {
            add("Avg coolant" to UnitFormat.Quantity.TempC.format(it, unitSystem, 0))
        }
        if (trip.weatherTempC != null) {
            val t = UnitFormat.Quantity.TempC.format(trip.weatherTempC, unitSystem, 0)
            val wmo = wmoLabel(trip.weatherCode)
            add("Weather" to "$t${wmo?.let { ", $it" } ?: ""}")
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
            // Spacing, not a rule between every pair. Eight hairlines in a
            // nine-row list is what made this read as a dense table rather
            // than a summary; the label/value contrast already separates
            // the rows.
            for ((i, kv) in rows.withIndex()) {
                if (i > 0) Spacer(Modifier.height(10.dp))
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

/**
 * Timeline header: title, plus the two chart controls. Both live on the
 * title row rather than owning rows of their own — Smooth used to sit
 * alone on a right-aligned line, which cost a full row to show one chip.
 */
@Composable
private fun TimelineControls(
    selectedCount: Int,
    smoothLevel: SmoothLevel,
    onCycleSmooth: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Timeline",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        val smoothOn = smoothLevel != SmoothLevel.Off
        AssistChip(
            onClick = onCycleSmooth,
            label = {
                Text(
                    if (smoothOn) "Smooth (${smoothLevel.label})" else "Smooth",
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
        Spacer(Modifier.width(6.dp))
        AssistChip(
            onClick = onOpenPicker,
            label = {
                Text("Series ($selectedCount)", style = MaterialTheme.typography.labelMedium)
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )
    }
}

/**
 * The plotted series, as a single scrolling legend. Only what's ON is
 * here — the full list lives in [SeriesPickerSheet]. This is the whole
 * point of the layout: the old wrapping FlowRow of every metric grew a
 * row each time a metric was added and pushed the chart off-screen.
 *
 * Tapping a chip removes that series, which is also what makes the
 * colour dot load-bearing: it maps the chip to its line on the chart.
 */
@Composable
private fun ActiveSeriesRow(
    available: List<TripMetricDef>,
    visible: Set<String>,
    unitSystem: String,
    onToggle: (String) -> Unit,
) {
    val active = available.filter { it.metric in visible }
    if (active.isEmpty()) {
        Text(
            "No series selected — tap Series to add one",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (def in active) {
            MetricChip(def, on = true, unitSystem = unitSystem, onToggle = onToggle)
        }
    }
}

/**
 * Full metric list, grouped by [MetricGroup]. A sheet rather than an
 * inline expander so the picker gets the height to show all 18 at once,
 * wrapped and fully readable — no horizontal clipping, which is what
 * made the always-visible scrolling-rail alternatives worse.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
)
@Composable
private fun SeriesPickerSheet(
    available: List<TripMetricDef>,
    visible: Set<String>,
    unitSystem: String,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Series",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onReset) { Text("Reset") }
            }
            // Only groups with data on THIS trip get a header — an
            // "Emissions" heading over nothing reads as a broken capture.
            for (group in MetricGroup.entries) {
                val inGroup = available.filter { it.group == group }
                if (inGroup.isEmpty()) continue
                Text(
                    group.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (def in inGroup) {
                        MetricChip(
                            def,
                            on = def.metric in visible,
                            unitSystem = unitSystem,
                            onToggle = onToggle,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One metric toggle. The leading dot carries the series' chart colour so
 * the legend row and the chart lines can be matched by eye.
 */
@Composable
private fun MetricChip(
    def: TripMetricDef,
    on: Boolean,
    unitSystem: String,
    onToggle: (String) -> Unit,
) {
    AssistChip(
        onClick = { onToggle(def.metric) },
        leadingIcon = {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        color = if (on) def.color else MaterialTheme.colorScheme.outlineVariant,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            )
        },
        label = {
            Text(def.chipLabel(unitSystem), style = MaterialTheme.typography.labelMedium)
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (on) {
                def.color.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            labelColor = if (on) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    )
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
/**
 * Category a metric is filed under in the series picker. With 17 series
 * a flat list is a wall of chips; the groups are what make it skimmable
 * — and they match the section comments in the backend allowlist
 * (`_TRIP_SAMPLE_METRICS`) so the two lists stay legible side by side.
 */
internal enum class MetricGroup(val title: String) {
    Core("Core drive"),
    FuelExhaust("Fuel & exhaust"),
    Emissions("Emissions"),
    Distance("Distance"),
}

internal data class TripMetricDef(
    val metric: String,
    /** Bare name; the unit is appended per unit-system at render time. */
    val label: String,
    val color: Color,
    val quantity: UnitFormat.Quantity,
    /** Decimals for the single-series Y-axis ticks. */
    val digits: Int = 1,
    /**
     * Section in the series picker. Declared after [digits] on purpose:
     * the table below passes digits positionally, so inserting a param
     * ahead of it would silently re-bind every one of those literals.
     */
    val group: MetricGroup = MetricGroup.Core,
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
        UnitFormat.Quantity.FuelRateGramsPerSec, 2, group = MetricGroup.FuelExhaust,
    ),
    // kg/h in both unit systems on purpose — see the Quantity docs.
    TripMetricDef(
        "engine_exhaust_flow", "Exhaust", Color(0xFFA3E635),
        UnitFormat.Quantity.MassFlowKgPerHour, 1, group = MetricGroup.FuelExhaust,
    ),
    // ── Emissions ─────────────────────────────────────────────────
    // Both cat banks: they normally track within a degree or two, so
    // the divergence is the diagnostic. Same hue family for that reason.
    TripMetricDef(
        "catalyst_temp_b1", "Cat B1", Color(0xFFFB7185),
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Emissions,
    ),
    TripMetricDef(
        "catalyst_temp_b2", "Cat B2", Color(0xFFE879F9),
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Emissions,
    ),
    // Commanded vs measured equivalence ratio — the PAIR is the signal
    // (fuel-control error); either alone is a flat line near 1.000,
    // hence 3 decimals on the ticks.
    TripMetricDef(
        "commanded_afr_ratio", "Cmd AFR", Color(0xFF38BDF8),
        UnitFormat.Quantity.Lambda, 3, group = MetricGroup.Emissions,
    ),
    TripMetricDef(
        "o2_s1_lambda", "O2 S1", Color(0xFF818CF8),
        UnitFormat.Quantity.Lambda, 3, group = MetricGroup.Emissions,
    ),
    TripMetricDef(
        "fuel_rail_pressure", "Fuel rail", Color(0xFF34D399),
        UnitFormat.Quantity.PressureKpa, 0, group = MetricGroup.Emissions,
    ),
    // ── Distance ──────────────────────────────────────────────────
    // Absolute odometer. The API already subtracted the vehicle's
    // odometer_offset_km, so this line reads the same as the dash and
    // as the fillup form — do NOT re-apply the offset here.
    //
    // LineChart auto-fits each series to its own Y range, which is what
    // makes a ~200 000 km value chartable next to a 0–120 km/h one at
    // all: on a shared axis it would flatten every other series.
    // WiCAN-only metric, so it is simply absent on a cellular trip.
    TripMetricDef(
        "odometer", "Odometer", Color(0xFF8B949E),
        UnitFormat.Quantity.DistanceKm, 0, group = MetricGroup.Distance,
    ),
)
