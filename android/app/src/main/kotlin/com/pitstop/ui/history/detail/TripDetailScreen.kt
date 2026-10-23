package com.pitstop.ui.history.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.RoutePointDto
import com.pitstop.http.TripBaselineDto
import com.pitstop.http.TripDetailDto
import com.pitstop.http.TripDtcDto
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.components.LoadErrorState
import com.pitstop.ui.components.OverflowAction
import com.pitstop.ui.theme.ChartPalette
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import kotlin.math.roundToInt

/**
 * Trip detail. Mirrors the depth of the web TripDetailView but tailored to
 * a single-column phone layout, ordered by what a driver asks first:
 *
 *   1. Narrative sentence (skipped when empty)
 *   2. Hero stats (duration / distance / economy / speeds / RPM)
 *   3. Baseline — "this trip vs your usual" (only with enough history)
 *   4. Route map — non-interactive preview; Expand opens trip/{id}/map
 *   5. Timeline chart + series controls
 *   6. DTCs during the trip (tap → DTC detail)
 *   7. Secondary stats (idle, odometer, fuel level, weather, …)
 *   8. Details — category / towing / notes, edited in a bottom sheet
 *
 * The date is the top-bar title; Edit and Delete live in its overflow.
 */
@Composable
fun TripDetailScreen(
    onBack: () -> Unit,
    onOpenDtc: (code: String, vehicleId: String) -> Unit,
    onOpenMap: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    /** Open with the details (tag) sheet up — the drive-summary "Tag as…" action. */
    startEditing: Boolean = false,
    viewModel: TripDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val storedSeries by viewModel.storedSeries.collectAsStateWithLifecycle()
    val unitSystem = LocalUnitSystem.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.deleted) { if (ui.deleted) onDeleted() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val trip = ui.trip
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(
                title = trip?.let { DateLabel.detailTitle(it.startedAt, is24HourClock()) } ?: "Trip",
                onBack = onBack,
                overflow = if (trip == null) {
                    emptyList()
                } else {
                    listOf(
                        OverflowAction("Edit details", Icons.Filled.Edit) { editing = true },
                        OverflowAction("Delete trip", Icons.Filled.Delete, destructive = true) {
                            confirmDelete = true
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier,
    ) { padding ->
        val inner = Modifier.padding(padding)
        when {
            ui.loading && trip == null -> Box(inner.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            trip == null -> LoadErrorState(what = "this trip", onRetry = viewModel::refresh, modifier = inner)
            else -> TripDetailContent(
                trip = trip,
                route = ui.route,
                baseline = ui.baseline,
                unitSystem = unitSystem,
                storedSeries = storedSeries,
                onPersistSeries = viewModel::setSeries,
                onOpenDtc = onOpenDtc,
                onOpenMap = onOpenMap,
                onEdit = { editing = true },
                modifier = inner,
            )
        }
    }

    if (confirmDelete && trip != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trip?") },
            text = {
                Text("It's removed from History, analytics and the map. This can't be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (editing && trip != null) {
        TripEditSheet(
            trip = trip,
            onSave = { towing, category, notes ->
                viewModel.saveDetails(towing, category, notes)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * Stateless body of [TripDetailScreen]. Visible to the debug design
 * gallery (src/debug) and the screenshot tests so the REAL screen renders
 * against synthetic data — verifying the shipping composable rather than
 * a mock of it. `internal`, so still module-private in a release build.
 */
@Composable
internal fun TripDetailContent(
    trip: TripDetailDto,
    route: List<RoutePointDto>,
    baseline: TripBaselineDto?,
    unitSystem: String,
    storedSeries: StoredSeries,
    onPersistSeries: (Set<String>) -> Unit,
    onOpenDtc: (code: String, vehicleId: String) -> Unit,
    onOpenMap: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
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
        pivotSamples(
            trip.samples,
            smoothWindow = smoothLevel.windowSize,
            discreteMetrics = DISCRETE_TRIP_METRICS,
            deriveAcceleration = true,
        )
    }
    val availableMetrics = remember(seriesMap) {
        TRIP_METRICS.filter { seriesMap[it.metric]?.points?.isNotEmpty() == true }
    }
    // Speed + RPM are the only default-on series. Fall back to the first
    // available metric (rather than "all of them") when neither is
    // present — with ~35 chartable metrics, showing everything on a
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
        val narrative = remember(trip, unitSystem) { tripNarrative(trip, unitSystem) }
        if (narrative.isNotBlank()) {
            Text(
                text = narrative,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HeroStatsCard(trip, unitSystem, usualMpg = baseline?.takeIf { it.sufficient }?.avgMpg)

        baseline?.let { BaselineCard(trip, it, unitSystem) }

        if (route.isNotEmpty()) {
            SectionCard(title = "Route") {
                // Non-interactive preview: inside a scrolling column a live
                // map fights the scroll for every drag. The overlay takes the
                // tap and Expand opens the full-screen interactive map.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                        ),
                ) {
                    MapLibreRouteView(points = route, interactive = false)
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(onClickLabel = "Expand map", onClick = onOpenMap),
                    )
                    FilledTonalIconButton(
                        onClick = onOpenMap,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    ) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = "Expand map")
                    }
                }
                SpeedLegendRow()
            }
        }

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
                        val plotted = availableMetrics
                            .filter { it.metric in shown }
                            .mapNotNull { def ->
                                seriesMap[def.metric]?.let { s ->
                                    def to s.points.map { p ->
                                        p.copy(value = def.display(p.value, unitSystem))
                                    }
                                }
                            }
                        val ranges = sharedYRanges(plotted)
                        plotted.map { (def, points) ->
                            DisplaySeries(
                                series = MetricSeries(def.metric, points),
                                color = def.color,
                                unitLabel = def.quantity.unit(unitSystem),
                                digits = def.digits,
                                step = def.step,
                                carryMs = def.carryMs,
                                yRange = ranges[def.metric],
                                tickFormat = def.tickFormat,
                            )
                        }
                    }
                    LineChart(series = display, height = 260.dp)
                }
            }
        }

        // Tires: hidden only when the trip has no tire data at all.
        if (tripHasTires(trip)) {
            TripTiresCard(samples = trip.samples, tires = trip.tires, system = unitSystem)
        }

        if (trip.dtcs.isNotEmpty()) {
            SectionCard(title = "DTCs during trip") {
                for ((index, dtc) in trip.dtcs.withIndex()) {
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DtcRow(dtc = dtc, onClick = { onOpenDtc(dtc.code, trip.vehicleId) })
                }
            }
        }

        SecondaryStatsCard(trip, unitSystem)

        DetailsCard(trip, onEdit)

        Spacer(Modifier.height(24.dp))
    }
}

/** Titled surface card used by every section below the hero. */
@Composable
private fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun DtcRow(dtc: TripDtcDto, onClick: () -> Unit) {
    val is24h = is24HourClock()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Open ${dtc.code}", onClick = onClick)
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
            fmtClockLocal(dtc.seenAt, is24h),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "This trip vs your usual": the same-distance-bucket averages from
 * /analytics/trip-baseline, one line per figure — "Avg speed 34 vs 31 mph
 * (+10%)". Only rendered when the server says the bucket is big enough.
 */
@Composable
private fun BaselineCard(trip: TripDetailDto, b: TripBaselineDto, system: String) {
    val speed = UnitFormat.Quantity.SpeedKph
    val tripMpg = UnitFormat.mpgFrom(trip.distanceKm, trip.fuelUsedL?.takeIf { it > 0.4 })
    val rows = buildList {
        comparison("Avg speed", trip.avgSpeedKph, b.avgSpeedKph, higherIsBetter = null) {
            speed.number(it, system, 0) to speed.unit(system)
        }?.let(::add)
        comparison("Top speed", trip.maxSpeedKph, b.avgMaxSpeedKph, higherIsBetter = null) {
            speed.number(it, system, 0) to speed.unit(system)
        }?.let(::add)
        comparison("Economy", tripMpg, b.avgMpg, higherIsBetter = true) {
            UnitFormat.economyNumber(it, system) to UnitFormat.economyUnit(system)
        }?.let(::add)
        comparison("Duration", trip.durationS?.toDouble(), b.avgDurationS, higherIsBetter = null) {
            fmtDuration(it.roundToInt()) to ""
        }?.let(::add)
    }
    if (rows.isEmpty()) return
    SectionCard(title = "vs your usual") {
        Text(
            "Compared with ${b.sampleSize} trips of similar length" +
                (b.bucketLabel?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (r in rows) BaselineRow(r)
    }
}

private data class Comparison(
    val label: String,
    val text: String,
    val deltaPct: Double,
    /** null = neutral (speed is not "better" when higher). */
    val better: Boolean?,
)

private fun comparison(
    label: String,
    value: Double?,
    usual: Double?,
    higherIsBetter: Boolean?,
    fmt: (Double) -> Pair<String, String>,
): Comparison? {
    if (value == null || usual == null || usual <= 0.0) return null
    val (v, unit) = fmt(value)
    val (u, _) = fmt(usual)
    val pct = (value - usual) / usual * 100.0
    val sign = if (pct >= 0) "+" else "−"
    val text = "$v vs $u${if (unit.isBlank()) "" else " $unit"} ($sign${kotlin.math.abs(pct).roundToInt()}%)"
    val better = higherIsBetter?.let { hib -> if (kotlin.math.abs(pct) < 2) null else (pct > 0) == hib }
    return Comparison(label, text, pct, better)
}

@Composable
private fun BaselineRow(c: Comparison) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            c.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            c.text,
            style = MaterialTheme.typography.titleSmall,
            color = when (c.better) {
                true -> MaterialTheme.ext.good
                false -> MaterialTheme.ext.bad
                null -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Category, towing and notes — what the USER knows about the trip that the
 * system doesn't. Read-only here; the Edit button (and the overflow's
 * "Edit details") open [TripEditSheet]. `gps_only` is shown but never
 * editable: whether OBD samples existed is a fact.
 */
@Composable
private fun DetailsCard(trip: TripDetailDto, onEdit: () -> Unit) {
    SectionCard(
        title = "Details",
        action = { TextButton(onClick = onEdit) { Text("Edit") } },
    ) {
        DetailRow("Category", trip.category?.takeIf { it.isNotBlank() } ?: "None")
        DetailRow("Towing", if (trip.isTowing) "Yes — economy not comparable" else "No")
        if (trip.gpsOnly) {
            DetailRow("Source", "Phone GPS only — no engine data")
        }
        Text(
            trip.notes?.takeIf { it.isNotBlank() } ?: "No notes",
            style = MaterialTheme.typography.bodyMedium,
            color = if (trip.notes.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Edit sheet for the user-owned fields. One Save → one PATCH with only the
 * changed fields (TripDetailViewModel.saveDetails).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TripEditSheet(
    trip: TripDetailDto,
    onSave: (towing: Boolean, category: String?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var towing by rememberSaveable { mutableStateOf(trip.isTowing) }
    var category by rememberSaveable { mutableStateOf(trip.category.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(trip.notes.orEmpty()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Trip details", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Towing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Fuel economy under tow isn't comparable to a normal trip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = towing, onCheckedChange = { towing = it })
            }
            if (trip.gpsOnly) {
                Text(
                    "No engine data — the phone recorded this on its own, so it may " +
                        "not have been this vehicle. Tag it so that's obvious later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                singleLine = true,
                label = { Text("Category") },
                placeholder = { Text("Commute, Road trip, …") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (suggestion in listOf("Boat", "Commute", "Road trip", "Errands", "Work")) {
                    SuggestionChip(onClick = { category = suggestion }, label = { Text(suggestion) })
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(towing, category, notes) }) { Text("Save") }
            }
        }
    }
}

/**
 * The six headline figures on a plain surface. Only the economy value
 * carries colour, and only with something to compare it to: better than
 * [usualMpg] (the user's average for trips of this length) reads good ▲,
 * worse reads bad ▼, within 2 % — or no comparison — stays neutral.
 */
@Composable
private fun HeroStatsCard(trip: TripDetailDto, unitSystem: String, usualMpg: Double?) {
    val dist = UnitFormat.Quantity.DistanceKm
    val speed = UnitFormat.Quantity.SpeedKph
    // Economy follows the unit toggle (mpg ↔ L/100 km). Below 0.4 L of
    // estimated fuel the ratio is sensor noise, so the cell reads "—".
    val mpg = UnitFormat.mpgFrom(trip.distanceKm, trip.fuelUsedL?.takeIf { it > 0.4 })
    val verdict = economyVerdict(mpg, usualMpg)
    val economyText = UnitFormat.economyNumber(mpg, unitSystem) + when (verdict) {
        // The arrow follows the displayed number (L/100 km falls when
        // economy improves); the colour follows better / worse.
        true -> if (UnitFormat.economyHigherIsBetter(unitSystem)) " ▲" else " ▼"
        false -> if (UnitFormat.economyHigherIsBetter(unitSystem)) " ▼" else " ▲"
        null -> ""
    }
    val economyColor = when (verdict) {
        true -> MaterialTheme.ext.good
        false -> MaterialTheme.ext.bad
        null -> MaterialTheme.colorScheme.onSurface
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 3-col × 2-row grid — keeps every cell the same width so the
            // value column lines up vertically.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell("Duration", fmtDuration(trip.durationS), Modifier.weight(1f))
                StatCell("Distance", dist.format(trip.distanceKm, unitSystem, 1), Modifier.weight(1f))
                StatCell(
                    if (unitSystem == "imperial") "MPG" else "L/100 km",
                    economyText,
                    Modifier.weight(1f),
                    valueColor = economyColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell("Max speed", speed.format(trip.maxSpeedKph, unitSystem, 0), Modifier.weight(1f))
                StatCell("Max RPM", trip.maxRpm?.let { "${it.roundToInt()}" } ?: "—", Modifier.weight(1f))
                StatCell("Avg speed", speed.format(trip.avgSpeedKph, unitSystem, 0), Modifier.weight(1f))
            }
        }
    }
}

/** true = better than usual, false = worse, null = no comparison or within 2 %. */
internal fun economyVerdict(mpg: Double?, usualMpg: Double?): Boolean? {
    if (mpg == null || usualMpg == null || usualMpg <= 0.0) return null
    val pct = (mpg - usualMpg) / usualMpg * 100.0
    return if (kotlin.math.abs(pct) < 2.0) null else pct > 0
}

@Composable
private fun SecondaryStatsCard(trip: TripDetailDto, unitSystem: String) {
    val dist = UnitFormat.Quantity.DistanceKm
    val is24h = is24HourClock()
    val rows = buildList<Pair<String, String>> {
        trip.idleS?.let {
            val m = it / 60
            val s = it % 60
            add("Idle time" to if (m > 0) "${m}m ${s}s" else "${s}s")
        }
        if (trip.dtcCount > 0) add("DTCs fired" to trip.dtcCount.toString())
        // Odometer start → end on ONE row. Server-side these are already
        // offset-corrected against the vehicle's odometer_offset_km, so
        // they read the same as the dash.
        if (trip.odoStartKm != null && trip.odoEndKm != null) {
            add(
                "Odometer" to "%,.0f → %,.0f %s".format(
                    dist.convert(trip.odoStartKm, unitSystem),
                    dist.convert(trip.odoEndKm, unitSystem),
                    dist.unit(unitSystem),
                ),
            )
        }
        // Fuel level start/end (already calibration-normalized server-side).
        if (trip.fuelLevelStartPct != null && trip.fuelLevelEndPct != null) {
            add("Fuel level" to "${trip.fuelLevelStartPct.roundToInt()}% → ${trip.fuelLevelEndPct.roundToInt()}%")
        }
        // Gas-used estimate — from the ECU fuel rate (preferred) or a MAF
        // integral. Flagged "(est.)" since both carry sensor noise.
        trip.fuelUsedL?.takeIf { it > 0.01 }?.let { lit ->
            add("Gas used (est.)" to UnitFormat.volumeL(lit, unitSystem))
        }
        trip.avgCoolantC?.let {
            add("Avg coolant" to UnitFormat.Quantity.TempC.format(it, unitSystem, 0))
        }
        // Braking (brake_pressure). The backend sends all four as null when
        // the trip has no brake samples, so nothing shows rather than a
        // misleading "0 presses".
        trip.brakeEvents?.let { add("Braking" to brakingLine(trip)) }
        if (trip.weatherTempC != null) {
            val t = UnitFormat.Quantity.TempC.format(trip.weatherTempC, unitSystem, 0)
            val wmo = wmoLabel(trip.weatherCode)
            add("Weather" to "$t${wmo?.let { ", $it" } ?: ""}")
        }
        trip.endedAt?.let { add("Ended" to fmtClockLocal(it, is24h)) }
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
            // Spacing, not a rule between every pair: the label/value
            // contrast already separates the rows.
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
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
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
 * inline expander so the picker gets the height to show every series at once,
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
        SeriesPickerContent(
            available = available,
            visible = visible,
            unitSystem = unitSystem,
            onToggle = onToggle,
            onReset = onReset,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * Body of [SeriesPickerSheet]: one labelled chip row per [MetricGroup] with
 * data on this trip. Split out so the screenshot tests can render it (a
 * ModalBottomSheet is a popup window layoutlib can't draw).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesPickerContent(
    available: List<TripMetricDef>,
    visible: Set<String>,
    unitSystem: String,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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
internal fun SpeedLegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "Stop" to ChartPalette.speedStop,
            "City" to ChartPalette.speedCity,
            "Suburb" to ChartPalette.speedSuburb,
            "Hwy" to ChartPalette.speedHighway,
        ).forEach { (label, c) ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = c, shape = RoundedCornerShape(2.dp)),
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

/**
 * Definitions of the trip-detail timeline series we know how to
 * render. Adding a new metric is a one-line append; the chart picks up
 * the color, the unit label and the value conversion automatically.
 *
 * The list mirrors the backend's `_TRIP_SAMPLE_METRICS` whitelist in
 * `api/trips.py` (minus the tire corners, which the Tires card charts)
 * plus the client-derived `acceleration` — and the web's `TRIP_SERIES`
 * in `TripDetailView.vue`, which charts the same set (web/phone parity,
 * ADR-013). Anything listed here but missing from a given trip is
 * filtered out by `availableMetrics` before the chips are drawn.
 *
 * Units: a def carries a [UnitFormat.Quantity], never a unit string.
 * The chip label gets the unit appended for the ACTIVE unit system, and
 * the series values are converted with the same Quantity, so the axis
 * ticks and the label can't disagree.
 *
 * Order here is the chip order within a [MetricGroup]; speed and RPM
 * come first (the two most-asked-about series and the only two on by
 * default).
 */
/**
 * Category a metric is filed under in the series picker. Same six groups,
 * in the same order, as the web picker's `SERIES_GROUPS`.
 */
internal enum class MetricGroup(val title: String) {
    Drive("Drive"),
    Transmission("Transmission"),
    Engine("Engine"),
    Fuel("Fuel"),
    Air("Air"),
    Emissions("Emissions"),
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
    val group: MetricGroup = MetricGroup.Drive,
    /**
     * Whether the series starts visible. Only speed + RPM do: with ~35
     * chartable metrics, "show everything that has data" would draw an
     * unreadable chart on a phone.
     */
    val defaultVisible: Boolean = false,
    /**
     * Carry a value at most this long, then break the line (and draw steps).
     * Brake pressure only: carried across a dropout, one press would read as
     * a long brake hold. Null = the old always-connected line.
     */
    val carryMs: Long? = null,
    /**
     * Discrete code or counter (gear, cylinder count, misfire counters):
     * drawn as steps, never smoothed, and downsampled by the last value in
     * a bucket — an average reads "gear 4.5".
     */
    val discrete: Boolean = false,
    /**
     * Series sharing a key share ONE y range, so a bank-to-bank gap (fuel
     * trims) or a step on one misfire counter isn't auto-scaled away.
     */
    val rangeKey: String? = null,
    /** Fixed y range in display units (overrides auto-fit). */
    val fixedRange: Pair<Double, Double>? = null,
    /**
     * Value remap applied AFTER unit conversion — gear only: Reverse (code
     * 15) is drawn at -1 so the axis reads R · P/N · D1…D9 in order.
     */
    val lane: ((Double) -> Double)? = null,
    /** Tick text for one display value (gear "D4", cylinder "6"). */
    val tickFormat: ((Double) -> String)? = null,
) {
    /** "Speed (mph)" / "Speed (km/h)" — unit resolved at render time. */
    fun chipLabel(system: String): String {
        val unit = quantity.unit(system)
        return if (unit.isBlank()) label else "$label ($unit)"
    }

    /** Raw API value → the value the chart plots, in [system] units. */
    fun display(value: Double, system: String): Double {
        val v = quantity.convert(value, system)
        return lane?.invoke(v) ?: v
    }

    /** Draw as steps: discrete states, or a carry-limited series. */
    val step: Boolean get() = discrete || carryMs != null
}

/** gear_position code for Reverse, and the lane it is drawn on. */
private const val GEAR_R_CODE = 15.0
private const val GEAR_R_LANE = -1.0

/** Lane value → gear text; blank between integer ticks (web `gearText`). */
internal fun gearTick(v: Double): String {
    if (v != kotlin.math.floor(v)) return ""
    return com.pitstop.domain.VehicleSignals.gearLabel(if (v == GEAR_R_LANE) GEAR_R_CODE else v)
}

private fun intTick(v: Double): String =
    if (v != kotlin.math.floor(v)) "" else v.toLong().toString()

internal val TRIP_METRICS: List<TripMetricDef> = listOf(
    // ── Drive ─────────────────────────────────────────────────────
    TripMetricDef(
        "vehicle_speed", "Speed", ChartPalette.speed,
        UnitFormat.Quantity.SpeedKph, digits = 0, defaultVisible = true,
    ),
    TripMetricDef(
        "engine_rpm", "RPM", ChartPalette.rpm,
        UnitFormat.Quantity.None, digits = 0, defaultVisible = true,
    ),
    TripMetricDef(
        "throttle_position", "Throttle", ChartPalette.throttle,
        UnitFormat.Quantity.Percent, 0,
    ),
    TripMetricDef("engine_load", "Load", ChartPalette.load, UnitFormat.Quantity.Percent, 0),
    // Absolute odometer. The API already subtracted the vehicle's
    // odometer_offset_km, so this line reads the same as the dash and
    // as the fillup form — do NOT re-apply the offset here. WiCAN-only,
    // so it is simply absent on a cellular trip.
    TripMetricDef(
        "odometer", "Odometer", ChartPalette.odometer,
        UnitFormat.Quantity.DistanceKm, 0,
    ),
    // Brake pressure (VSA, Mode 22 224005): bar on the wire, "Brake (psi)"
    // on the chart in both systems. Carried 3 s at most.
    TripMetricDef(
        "brake_pressure", "Brake", ChartPalette.brake,
        UnitFormat.Quantity.BrakeBar, 0, carryMs = 3_000L,
    ),
    // Derived on the phone from successive speed points (dv/dt, in g),
    // like the web's Accel lane — not a pid_readings metric.
    TripMetricDef(
        ACCELERATION_METRIC, "Accel", ChartPalette.accel,
        UnitFormat.Quantity.GForce, 2,
    ),
    // ── Transmission (Honda Mode 22) ──────────────────────────────
    TripMetricDef(
        "atf_temp", "ATF", ChartPalette.atf,
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Transmission,
    ),
    // 0 = P/N, 1..9 forward, 15 = R (drawn at -1).
    TripMetricDef(
        "gear_position", "Gear", ChartPalette.gear,
        UnitFormat.Quantity.None, 0, group = MetricGroup.Transmission,
        discrete = true,
        lane = { v -> if (kotlin.math.round(v) == GEAR_R_CODE) GEAR_R_LANE else v },
        tickFormat = ::gearTick,
    ),
    // ── Engine ────────────────────────────────────────────────────
    TripMetricDef(
        "coolant_temp", "Coolant", ChartPalette.coolant,
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Engine,
    ),
    TripMetricDef(
        "control_module_voltage", "Battery", ChartPalette.battery,
        UnitFormat.Quantity.Volt, 1, group = MetricGroup.Engine,
    ),
    TripMetricDef(
        "fuel_rail_pressure", "Fuel rail", ChartPalette.fuelRail,
        UnitFormat.Quantity.PressureKpa, 0, group = MetricGroup.Engine,
    ),
    // 6, or 3 while VCM has a bank off. Pinned to 0..6 so a trip that
    // never drops to 3 doesn't float a flat 6 mid-chart.
    TripMetricDef(
        "active_cylinders", "Cylinders", ChartPalette.cylinders,
        UnitFormat.Quantity.None, 0, group = MetricGroup.Engine,
        discrete = true, fixedRange = 0.0 to 6.0, tickFormat = ::intTick,
    ),
) + com.pitstop.domain.VehicleSignals.MISFIRE_CYLINDERS.mapIndexed { i, n ->
    // Cumulative per-cylinder counters on one shared range: a step on
    // one cylinder only stands out against the other five's scale.
    TripMetricDef(
        com.pitstop.domain.VehicleSignals.misfireMetric(n), "Misfire cyl $n",
        ChartPalette.misfire[i], UnitFormat.Quantity.None, 0,
        group = MetricGroup.Engine, discrete = true, rangeKey = "misfire",
        tickFormat = ::intTick,
    )
} + listOf(
    // ── Fuel ──────────────────────────────────────────────────────
    TripMetricDef(
        "fuel_level", "Fuel", ChartPalette.fuel,
        UnitFormat.Quantity.Percent, 0, group = MetricGroup.Fuel,
    ),
    // The cluster's own damped litres, next to the raw % above — the gap
    // between them is tank slosh.
    TripMetricDef(
        "fuel_level_l", "Dash fuel", ChartPalette.dashFuel,
        UnitFormat.Quantity.VolumeL, 1, group = MetricGroup.Fuel,
    ),
    // g/s on the wire → L/h or gph depending on the toggle, matching
    // the Live tile and the web's fuel-rate lane.
    TripMetricDef(
        "engine_fuel_rate", "Fuel rate", ChartPalette.fuelRate,
        UnitFormat.Quantity.FuelRateGramsPerSec, 2, group = MetricGroup.Fuel,
    ),
    // Commanded vs measured equivalence ratio — the PAIR is the signal
    // (fuel-control error); either alone is a flat line near 1.000,
    // hence 3 decimals on the ticks.
    TripMetricDef(
        "commanded_afr_ratio", "Cmd AFR", ChartPalette.cmdAfr,
        UnitFormat.Quantity.Lambda, 3, group = MetricGroup.Fuel,
    ),
    TripMetricDef(
        "o2_s1_lambda", "O2 S1", ChartPalette.o2,
        UnitFormat.Quantity.Lambda, 3, group = MetricGroup.Fuel,
    ),
    // Fuel trims on one shared range: the bank-to-bank gap is the
    // diagnostic, which per-series auto-fit would hide.
    TripMetricDef(
        "stft_b1", "STFT B1", ChartPalette.stftB1,
        UnitFormat.Quantity.Percent, 1, group = MetricGroup.Fuel, rangeKey = "trim",
    ),
    TripMetricDef(
        "stft_b2", "STFT B2", ChartPalette.stftB2,
        UnitFormat.Quantity.Percent, 1, group = MetricGroup.Fuel, rangeKey = "trim",
    ),
    TripMetricDef(
        "ltft_b1", "LTFT B1", ChartPalette.ltftB1,
        UnitFormat.Quantity.Percent, 1, group = MetricGroup.Fuel, rangeKey = "trim",
    ),
    TripMetricDef(
        "ltft_b2", "LTFT B2", ChartPalette.ltftB2,
        UnitFormat.Quantity.Percent, 1, group = MetricGroup.Fuel, rangeKey = "trim",
    ),
    // ── Air ───────────────────────────────────────────────────────
    TripMetricDef(
        "intake_air_temp", "Intake", ChartPalette.intake,
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Air,
    ),
    TripMetricDef(
        "manifold_pressure", "MAP", ChartPalette.map,
        UnitFormat.Quantity.PressureKpa, 0, group = MetricGroup.Air,
    ),
    TripMetricDef(
        "maf_air_flow", "MAF", ChartPalette.maf,
        UnitFormat.Quantity.MassFlowGramsPerSec, 1, group = MetricGroup.Air,
    ),
    // The cluster publishes outside air in °F (DID 7028).
    TripMetricDef(
        "ambient_air_temp_f", "Outside", ChartPalette.outside,
        UnitFormat.Quantity.TempF, 0, group = MetricGroup.Air,
    ),
    // ── Emissions ─────────────────────────────────────────────────
    // Both cat banks: they normally track within a degree or two, so
    // the divergence is the diagnostic. Same hue family for that reason.
    TripMetricDef(
        "catalyst_temp_b1", "Cat B1", ChartPalette.catB1,
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Emissions,
    ),
    TripMetricDef(
        "catalyst_temp_b2", "Cat B2", ChartPalette.catB2,
        UnitFormat.Quantity.TempC, 0, group = MetricGroup.Emissions,
    ),
    // kg/h in both unit systems on purpose — see the Quantity docs.
    TripMetricDef(
        "engine_exhaust_flow", "Exhaust", ChartPalette.exhaust,
        UnitFormat.Quantity.MassFlowKgPerHour, 1, group = MetricGroup.Emissions,
    ),
    // EVAP purge duty. commanded_egr stays off (constant 0 on this PCM).
    TripMetricDef(
        "commanded_evap_purge", "EVAP purge", ChartPalette.evapPurge,
        UnitFormat.Quantity.Percent, 0, group = MetricGroup.Emissions,
    ),
)

/** Metrics drawn as discrete steps (never smoothed or averaged). */
internal val DISCRETE_TRIP_METRICS: Set<String> =
    TRIP_METRICS.filter { it.discrete }.map { it.metric }.toSet()

/**
 * Y range per plotted series, in display units: a [TripMetricDef.fixedRange]
 * wins; otherwise series sharing a [TripMetricDef.rangeKey] get the union of
 * their points' bounds (over the series being plotted); null = auto-fit.
 */
internal fun sharedYRanges(
    plotted: List<Pair<TripMetricDef, List<TimedPoint>>>,
): Map<String, Pair<Double, Double>> {
    val out = mutableMapOf<String, Pair<Double, Double>>()
    plotted.filter { it.first.rangeKey != null && it.first.fixedRange == null }
        .groupBy { it.first.rangeKey!! }
        .forEach { (_, members) ->
            val b = members.flatMap { it.second }.bounds() ?: return@forEach
            for ((def, _) in members) out[def.metric] = b
        }
    for ((def, _) in plotted) def.fixedRange?.let { out[def.metric] = it }
    return out
}

/**
 * "12 presses · 1 hard · max 1276 psi · 42s on pedal" — web parity. Only
 * called when brake_events is non-null (null = not measured).
 */
internal fun brakingLine(trip: TripDetailDto): String = buildList {
    val n = trip.brakeEvents ?: 0
    add("$n press${if (n == 1) "" else "es"}")
    // Hard = a press peaking at or above the server's 60 bar ≈ 870 psi.
    trip.hardBrakeEvents?.let { add("$it hard (≥$HARD_BRAKE_PSI psi)") }
    // max_brake_bar arrives in bar; shown in whole psi like the tiles.
    trip.maxBrakeBar?.let { add("max ${UnitFormat.Quantity.BrakeBar.number(it, "imperial", 0)} psi") }
    trip.brakingS?.let { add("${it.roundToInt()}s on pedal") }
}.joinToString(" · ")

/** Backend _HARD_BRAKE_BAR (60 bar) in whole psi, for the braking label. */
internal const val HARD_BRAKE_PSI = 870
