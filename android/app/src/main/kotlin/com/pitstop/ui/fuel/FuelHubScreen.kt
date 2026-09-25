package com.pitstop.ui.fuel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.domain.FuelField
import com.pitstop.domain.RangeEstimate
import com.pitstop.http.FillupDto
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.RangeFormat
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.components.rememberPitstopListState
import com.pitstop.ui.history.FillupCard
import com.pitstop.ui.history.FillupFilter
import com.pitstop.ui.history.FillupSortOrder
import com.pitstop.ui.history.FillupStatsHeader
import com.pitstop.ui.history.GroupHeader
import com.pitstop.ui.history.HistoryUiState
import com.pitstop.ui.history.HistoryViewModel
import com.pitstop.ui.history.ListControls
import com.pitstop.ui.history.ListHeaderLine
import com.pitstop.ui.history.SectionHost
import com.pitstop.ui.history.SectionHostId
import com.pitstop.ui.history.groupAndSortFillups
import com.pitstop.ui.history.listStates
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import com.pitstop.util.requireActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Fuel tab: a hub rather than a form. Range + last fill up top, the
 * fillup stats strip, the fillup list (moved here from History), and a
 * "Log fillup" button that opens the quick sheet. Fillup detail / edit are
 * pushed on this tab's own stack via [SectionHost].
 *
 * [pendingLogSheet] is the launcher shortcut's "add fillup" request.
 */
@Composable
fun FuelScreen(
    pendingLogSheet: MutableStateFlow<Boolean>? = null,
    historyVm: HistoryViewModel = hiltViewModel(LocalContext.current.requireActivity()),
) {
    SectionHost(SectionHostId.Fuel, historyVm) { nav ->
        FuelHubRoute(historyVm = historyVm, onOpenFillup = nav.openFillup, pendingLogSheet = pendingLogSheet)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelHubRoute(
    historyVm: HistoryViewModel,
    onOpenFillup: (String) -> Unit,
    pendingLogSheet: MutableStateFlow<Boolean>?,
) {
    val activity = LocalContext.current.requireActivity()
    // Activity-scoped: a save in flight must survive a tab switch (the pager
    // disposes this page, and with it any entry-scoped ViewModel's scope).
    val fuelVm: FuelAddViewModel = hiltViewModel(activity)
    val ui by historyVm.ui.collectAsStateWithLifecycle()
    val sort by historyVm.fillupSort.collectAsStateWithLifecycle()
    val filter by historyVm.fillupFilter.collectAsStateWithLifecycle()
    val form by fuelVm.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }

    val pending = pendingLogSheet?.collectAsStateWithLifecycle()
    LaunchedEffect(pending?.value) {
        if (pending?.value == true) {
            sheetOpen = true
            pendingLogSheet.value = false
        }
    }
    LaunchedEffect(sheetOpen) { if (sheetOpen) fuelVm.onSheetOpened() }
    LaunchedEffect(Unit) { historyVm.refreshIfStale() }
    LaunchedEffect(form.submittedId) {
        if (form.submittedId != null && form.editingId == null) {
            sheetOpen = false
            more = false
            fuelVm.acknowledgeSubmitted()
            historyVm.refresh(forceNetwork = true)
            snackbar.showSnackbar("Fillup saved")
        }
    }
    LaunchedEffect(form.errorMessage) {
        form.errorMessage?.let { if (!sheetOpen) snackbar.showSnackbar(it) }
    }

    FuelHubContent(
        ui = ui,
        sort = sort,
        filter = filter,
        onSort = historyVm::setFillupSort,
        onFilter = historyVm::setFillupFilter,
        onRefresh = { historyVm.refresh(forceNetwork = true) },
        onOpenFillup = onOpenFillup,
        onLog = { sheetOpen = true },
        snackbarHostState = snackbar,
    )

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                sheetOpen = false
                more = false
            },
            sheetState = sheetState,
        ) {
            LogFillupSheetContent(
                form = form,
                more = more,
                onToggleMore = { more = !more },
                onQuick = fuelVm::setQuickField,
                onOdometer = { v -> fuelVm.update { it.copy(odometer = v, odometerAutoFilled = false) } },
                onPartial = { p -> fuelVm.update { it.copy(partial = p) } },
                onStation = { st -> fuelVm.update { it.copy(stationName = st) } },
                onUsePriceHint = fuelVm::useStationPrice,
                onFuelType = fuelVm::selectFuelType,
                onDateTime = fuelVm::setDateTime,
                onMissed = { m -> fuelVm.update { it.copy(isMissed = m) } },
                onNotes = { n -> fuelVm.update { it.copy(notes = n) } },
                onRefreshGps = fuelVm::refreshGps,
                onSubmit = fuelVm::submit,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}

/** Stateless Fuel hub, rendered by screenshot tests. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FuelHubContent(
    ui: HistoryUiState,
    sort: FillupSortOrder,
    filter: FillupFilter,
    onSort: (FillupSortOrder) -> Unit = {},
    onFilter: (FillupFilter) -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenFillup: (String) -> Unit = {},
    onLog: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state = ui.fillups
    val groups = remember(state.data, sort, filter) { groupAndSortFillups(state.data, sort, filter) }
    val is24h = is24HourClock()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PitstopTopAppBar() },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onLog,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Log fillup") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.data.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = rememberPitstopListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "hub-header") {
                    FuelHubHeader(range = ui.range, lastFill = state.data.maxByOrNull { it.fillupDate })
                }
                item(key = "stats") {
                    FillupStatsHeader(
                        fillups = state.data,
                        costPerMile = ui.costPerMile,
                        monthlySpend = ui.monthlySpend,
                    )
                }
                item(key = "controls") {
                    ListControls(
                        chips = {
                            for (f in FillupFilter.entries) {
                                item(key = f.name) {
                                    FilterChip(
                                        selected = f == filter,
                                        onClick = { onFilter(f) },
                                        label = { Text(f.name) },
                                    )
                                }
                            }
                        },
                        sortOptions = FillupSortOrder.entries.map { it.label },
                        selectedSort = sort.ordinal,
                        onSort = { onSort(FillupSortOrder.entries[it]) },
                    )
                }
                item(key = "header") {
                    ListHeaderLine(
                        info = ui.lastRefresh,
                        loading = state.loading,
                        failed = state.error != null,
                        itemCount = state.data.size,
                        noun = "fillup",
                        newCount = 0,
                        pendingDrives = 0,
                        syncing = false,
                        onSync = {},
                    )
                }
                listStates(
                    loading = state.loading,
                    error = state.error,
                    empty = state.data.isEmpty(),
                    filteredEmpty = groups.isEmpty(),
                    what = "fillups",
                    emptyIcon = Icons.Outlined.LocalGasStation,
                    emptyTitle = "No fillups yet",
                    emptyBody = "Tap Log fillup at the pump — two numbers and you're done.",
                    onRetry = onRefresh,
                )
                for ((key, items) in groups) {
                    stickyHeader(key = "fillup-header-${key.name}") {
                        GroupHeader(
                            label = key.label,
                            summary = "${items.size} fillup${if (items.size == 1) "" else "s"}",
                        )
                    }
                    items(items, key = { it.id }) { f -> FillupCard(f, is24h, onOpenFillup) }
                }
            }
        }
    }
}

/** Range to empty and the last fill, side by side. */
@Composable
private fun FuelHubHeader(range: RangeEstimate?, lastFill: FillupDto?) {
    val system = LocalUnitSystem.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Range", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val low = range?.low == true
                Text(
                    if (range?.rangeMi != null) RangeFormat.range(range.rangeMi, system) else "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (low) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    range?.let { r ->
                        listOfNotNull(RangeFormat.fuelLine(r, system).takeIf { it != "—" }, RangeFormat.basisLine(r.basis, system))
                            .joinToString(" · ")
                    }.orEmpty().ifEmpty { "No fuel reading yet" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("Last fill", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    UnitFormat.money(lastFill?.priceTotal),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    lastFill?.let { RangeFormat.daysAgo(it.fillupDate) } ?: "none yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The quick-log sheet body: total and price (volume derived, or type the
 * volume instead — any two of three give the third), a prefilled odometer,
 * Full tank on, the nearest known station, the price you paid there last,
 * and "More" for everything the full form has. Saving is FuelAddViewModel's
 * existing submit — the same POST phone/fillups path as always.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LogFillupSheetContent(
    form: FuelFormState,
    more: Boolean,
    onToggleMore: () -> Unit = {},
    onQuick: (FuelField, String) -> Unit = { _, _ -> },
    onOdometer: (String) -> Unit = {},
    onPartial: (Boolean) -> Unit = {},
    onStation: (String) -> Unit = {},
    onUsePriceHint: () -> Unit = {},
    onFuelType: (Int) -> Unit = {},
    onDateTime: (java.time.LocalDateTime?) -> Unit = {},
    onMissed: (Boolean) -> Unit = {},
    onNotes: (String) -> Unit = {},
    onRefreshGps: () -> Unit = {},
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val system = LocalUnitSystem.current
    val volUnit = UnitFormat.Quantity.VolumeGal.unit(system)
    val distUnit = UnitFormat.Quantity.DistanceMi.unit(system)
    val errors = validateFuelForm(form, distUnit, volUnit)
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
    var typeVolume by rememberSaveable { mutableStateOf(false) }
    val volumeTyped = typeVolume || FuelField.Volume in form.quickPinned
    val vehicle = form.vehicles.firstOrNull { it.slug == form.selectedVehicleSlug }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Log fillup",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            vehicle?.let {
                Text(it.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.totalPrice,
                onValueChange = { onQuick(FuelField.Total, it) },
                label = { Text("Total") },
                leadingIcon = { Icon(Icons.Filled.AttachMoney, contentDescription = null) },
                singleLine = true,
                keyboardOptions = numeric,
                modifier = Modifier.weight(1f),
                colors = darkTextFieldColors(),
            )
            OutlinedTextField(
                value = form.pricePerVolume,
                onValueChange = { onQuick(FuelField.Price, it) },
                label = { Text("Price${UnitFormat.perVolumeUnit(system)}") },
                singleLine = true,
                keyboardOptions = numeric,
                modifier = Modifier.weight(1f),
                colors = darkTextFieldColors(),
            )
        }

        if (volumeTyped) {
            OutlinedTextField(
                value = form.volume,
                onValueChange = { onQuick(FuelField.Volume, it) },
                label = { Text("Fuel ($volUnit)") },
                leadingIcon = { Icon(Icons.Filled.LocalGasStation, contentDescription = null) },
                singleLine = true,
                keyboardOptions = numeric,
                modifier = Modifier.fillMaxWidth(),
                colors = darkTextFieldColors(),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocalGasStation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (form.volume.isNotBlank()) "= ${form.volume} $volUnit" else "Fuel is worked out from the two above",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (form.volume.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { typeVolume = true }) { Text("Enter $volUnit") }
            }
        }

        form.stationPriceHint?.let { hint ->
            val price = UnitFormat.pricePerVolume(hint.perGal, system)
            val date = hint.dateIso?.let { " · ${RangeFormat.daysAgo(it)}" }.orEmpty()
            AssistChip(
                onClick = onUsePriceHint,
                label = { Text("Last price here $price$date") },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }

        val odoErr = errors.odometer?.takeIf { form.showAllErrors || form.odometer.isNotBlank() }
        val sinceLast = form.odometer.toDoubleOrNull()?.let { o -> form.lastOdometer?.let { o - it } }
        OutlinedTextField(
            value = form.odometer,
            onValueChange = onOdometer,
            label = { Text("Odometer ($distUnit)") },
            leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
            singleLine = true,
            isError = odoErr != null,
            supportingText = {
                when {
                    odoErr != null -> Text(odoErr)
                    sinceLast != null && sinceLast >= 0 -> Text("+${"%,.0f".format(sinceLast)} $distUnit since last fillup")
                }
            },
            keyboardOptions = numeric,
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors(),
        )

        ToggleRow(label = "Full tank", checked = !form.partial, onCheckedChange = { onPartial(!it) })

        form.nearestPriorStation?.let { near ->
            FilterChip(
                selected = form.stationName == near,
                onClick = { onStation(if (form.stationName == near) "" else near) },
                label = { Text(near) },
                leadingIcon = { Icon(Icons.Outlined.NearMe, contentDescription = "Nearby", modifier = Modifier.size(16.dp)) },
            )
        }

        TextButton(onClick = onToggleMore, modifier = Modifier.padding(start = 0.dp)) {
            Text(if (more) "Less" else "More")
            Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }

        if (more) {
            FuelTypeDropdown(selected = form.fuelType, onSelect = onFuelType, modifier = Modifier.fillMaxWidth())
            DateTimeRow(value = form.dateTime, onChange = onDateTime)
            ToggleRow(label = "Missed previous fillup", checked = form.isMissed, onCheckedChange = onMissed)
            OutlinedTextField(
                value = form.notes,
                onValueChange = onNotes,
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = darkTextFieldColors(),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StationLocationRow(
                coords = form.gps?.let { "%.4f, %.4f".format(it.lat, it.lon) }
                    ?: if (form.gpsRefreshing) "Locating…" else "No location fix yet",
                onRefresh = onRefreshGps,
            )
            OutlinedTextField(
                value = form.stationName,
                onValueChange = onStation,
                label = { Text("Station") },
                leadingIcon = { Icon(Icons.Filled.Storefront, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = darkTextFieldColors(),
            )
            val chips = form.stationSuggestions.filter { it != form.stationName && it != form.nearestPriorStation }
            if (chips.isNotEmpty()) {
                LazyRow(state = rememberPitstopListState(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chips) { st -> SuggestionChip(onClick = { onStation(st) }, label = { Text(st, maxLines = 1) }) }
                }
            }
        }

        Button(
            onClick = onSubmit,
            enabled = !errors.any && !form.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (form.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text("Saving…")
            } else {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Save fillup")
            }
        }
        form.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
