package com.pitstop.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pitstop.http.DtcDto
import com.pitstop.http.FillupDto
import com.pitstop.http.TripDto
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.history.detail.DtcDetailScreen
import com.pitstop.ui.history.detail.FillupDetailScreen
import com.pitstop.ui.history.detail.TripDetailScreen
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History tab — root surface owns its own NavHost so drilling into a
 * detail screen doesn't break the bottom NavigationBar. Routes:
 *   list                  – the three subtabs (Trips/Fillups/DTCs)
 *   trip/{id}             – TripDetailScreen
 *   fillup/{id}           – FillupDetailScreen
 *   dtc/{code}?vehicleId= – DTCDetailScreen
 *
 * The TopAppBar swaps between the brand title (on list) and a back
 * arrow + section title (on detail). The bottom NavigationBar is
 * owned by MainActivity's Scaffold so it stays put across detail
 * transitions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value
    val onListRoute = current?.destination?.route == ROUTE_LIST ||
        current?.destination?.route == null
    val titleForRoute = when (current?.destination?.route?.substringBefore('/')) {
        "trip" -> "Trip"
        "fillup" -> "Fillup"
        "dtc" -> "DTC"
        else -> null
    }

    Scaffold(
        topBar = {
            if (onListRoute) {
                PitstopTopAppBar()
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            titleForRoute ?: "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_LIST,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(ROUTE_LIST) {
                HistoryListScreen(
                    onOpenTrip = { id -> nav.navigate("trip/$id") },
                    onOpenFillup = { id -> nav.navigate("fillup/$id") },
                    onOpenDtcCode = { code, vehicleId ->
                        val encoded = URLEncoder.encode(code, "UTF-8")
                        nav.navigate("dtc/$encoded?vehicleId=$vehicleId")
                    },
                )
            }
            composable(
                route = "trip/{id}",
                arguments = listOf(
                    androidx.navigation.navArgument("id") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) {
                TripDetailScreen(
                    onOpenDtc = { code, vehicleId ->
                        val encoded = URLEncoder.encode(code, "UTF-8")
                        nav.navigate("dtc/$encoded?vehicleId=$vehicleId")
                    },
                )
            }
            composable(
                route = "fillup/{id}",
                arguments = listOf(
                    androidx.navigation.navArgument("id") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) {
                FillupDetailScreen()
            }
            composable(
                route = "dtc/{code}?vehicleId={vehicleId}",
                arguments = listOf(
                    androidx.navigation.navArgument("code") {
                        type = androidx.navigation.NavType.StringType
                    },
                    androidx.navigation.navArgument("vehicleId") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) {
                DtcDetailScreen()
            }
        }
    }
}

private const val ROUTE_LIST = "list"

/**
 * The original three-subtab list view, now lifted into its own
 * composable so the History root can host both it and the detail
 * destinations under a single NavHost. The viewModel is hoisted via
 * Hilt — it's still tied to the History root entry, so switching to
 * a detail and back doesn't reload the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryListScreen(
    onOpenTrip: (id: String) -> Unit,
    onOpenFillup: (id: String) -> Unit,
    onOpenDtcCode: (code: String, vehicleId: String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (pendingCount > 0 || syncState !is SyncState.Idle) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText = when (val s = syncState) {
                    SyncState.Idle ->
                        "$pendingCount drive${if (pendingCount == 1) "" else "s"} pending upload"
                    SyncState.InProgress -> "Syncing…"
                    is SyncState.Done ->
                        if (s.uploaded == 0 && s.remaining == 0) "All up to date"
                        else "Synced ${s.uploaded} drive${if (s.uploaded == 1) "" else "s"}" +
                            if (s.remaining > 0) " · ${s.remaining} left" else ""
                    is SyncState.Failed -> "Sync failed: ${s.message}"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                )
                val inProgress = syncState is SyncState.InProgress
                AssistChip(
                    onClick = { if (!inProgress) viewModel.syncNow() },
                    enabled = !inProgress,
                    label = {
                        if (inProgress) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Syncing…")
                            }
                        } else {
                            Text("Sync now")
                        }
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
        SecondaryTabRow(selectedTabIndex = selectedTab) {
            listOf("Trips", "Fillups", "DTCs", "Heatmap").forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = { Text(label) },
                )
            }
        }
        when (selectedTab) {
            0 -> {
                val tripSelection by viewModel.tripSelection.collectAsStateWithLifecycle()
                val mergeState by viewModel.mergeState.collectAsStateWithLifecycle()
                TripsList(
                    state = ui.trips,
                    onRefresh = viewModel::refresh,
                    onOpen = onOpenTrip,
                    selection = tripSelection,
                    mergeState = mergeState,
                    onToggleSelect = viewModel::toggleTripSelection,
                    onCancelSelection = viewModel::exitTripSelection,
                    onMerge = viewModel::mergeSelectedTrips,
                )
            }
            1 -> FillupsList(state = ui.fillups, onRefresh = viewModel::refresh, onOpen = onOpenFillup)
            2 -> DtcsList(state = ui.dtcs, onRefresh = viewModel::refresh, onOpen = onOpenDtcCode)
            3 -> com.pitstop.ui.history.heatmap.HeatmapTab()
        }
    }
}

@Composable
private fun <T> ListSurface(
    state: HistoryListState<T>,
    onRefresh: () -> Unit,
    emptyMessage: String,
    item: @Composable (T) -> Unit,
) {
    when {
        state.loading && state.data.isEmpty() -> CenteredSpinner()
        state.error != null && state.data.isEmpty() ->
            CenteredText("Couldn't load: ${state.error}")
        state.data.isEmpty() -> CenteredText(emptyMessage)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.data, key = { keyOf(it) }) { item(it) }
        }
    }
}

private fun <T> keyOf(value: T): Any = when (value) {
    is TripDto -> value.id
    is FillupDto -> value.id
    is DtcDto -> value.id
    else -> value as Any
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TripsList(
    state: HistoryListState<TripDto>,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    selection: TripSelection,
    mergeState: MergeState,
    onToggleSelect: (String) -> Unit,
    onCancelSelection: () -> Unit,
    onMerge: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Action bar: shown when selection mode is active OR a merge
        // toast is in flight. Long-press a trip card to enter selection
        // mode, then tap a second one and hit Merge.
        if (selection.mode || mergeState !is MergeState.Idle) {
            TripSelectionBar(
                selection = selection,
                mergeState = mergeState,
                onCancel = onCancelSelection,
                onMerge = onMerge,
            )
        }
        ListSurface(
            state = state,
            onRefresh = onRefresh,
            emptyMessage = "No trips yet — take a drive.",
        ) { trip ->
            val isSelected = trip.id in selection.ids
            Card(
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (selection.mode) onToggleSelect(trip.id) else onOpen(trip.id)
                        },
                        onLongClick = { onToggleSelect(trip.id) },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selection.mode) {
                            Icon(
                                imageVector = if (isSelected) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = if (isSelected) "Selected" else "Tap to select",
                                tint = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .size(20.dp),
                            )
                        }
                        Text(
                            text = formatTripDate(trip.startedAt),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val mi = trip.distanceKm?.let { it * 0.621371 }
                        Text(
                            text = mi?.let { "%.1f mi".format(it) } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val parts = buildList {
                        trip.durationS?.let {
                            add(if (it >= 60) "${it / 60}m ${it % 60}s" else "${it}s")
                        }
                        trip.maxSpeedKph?.let { add("max %.0f mph".format(it * 0.621371)) }
                        trip.maxRpm?.let { add("%.0f rpm".format(it)) }
                        if (trip.dtcCount > 0) add("${trip.dtcCount} DTC")
                    }
                    if (parts.isNotEmpty()) {
                        Text(
                            text = parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TripSelectionBar(
    selection: TripSelection,
    mergeState: MergeState,
    onCancel: () -> Unit,
    onMerge: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = when (mergeState) {
            MergeState.Idle -> when (selection.ids.size) {
                0 -> "Long-press a trip to select"
                1 -> "1 selected · pick one more to merge"
                else -> "${selection.ids.size} selected"
            }
            MergeState.InProgress -> "Merging…"
            is MergeState.Done -> "Trips merged"
            is MergeState.Failed -> "Merge failed: ${mergeState.message}"
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (selection.mode && mergeState is MergeState.Idle) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                onClick = onMerge,
                enabled = selection.ids.size == 2,
            ) { Text("Merge") }
        }
        if (mergeState is MergeState.InProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun FillupsList(
    state: HistoryListState<FillupDto>,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    ListSurface(state = state, onRefresh = onRefresh, emptyMessage = "No fillups yet.") { f ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(f.id) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatFillupDate(f.fillupDate),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = f.priceTotal?.let { "$%.2f".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val parts = buildList {
                    f.fuelVolume?.let { add("%.2f gal".format(it)) }
                    f.pricePerUnit?.let { add("$%.3f/gal".format(it)) }
                    f.mpg?.let { add("%.1f mpg".format(it)) }
                    f.city?.let { add(it) }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DtcsList(
    state: HistoryListState<DtcDto>,
    onRefresh: () -> Unit,
    onOpen: (code: String, vehicleId: String) -> Unit,
) {
    ListSurface(state = state, onRefresh = onRefresh, emptyMessage = "No active DTCs — clean bill of health.") { dtc ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(dtc.code, dtc.vehicleId) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dtc.code,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatTripDate(dtc.seenAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                dtc.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(48.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredText(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Backend serves timestamps in UTC; convert to the device's local zone
// before formatting so a trip at 20:32Z renders as "3:32PM" in CDT, not
// "8:32PM". Without the withZoneSameInstant() step OffsetDateTime keeps
// its parsed offset and formats the raw UTC fields.
private val LOCAL_ZONE: ZoneId = ZoneId.systemDefault()

private fun formatTripDate(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(LOCAL_ZONE)
            .format(DateTimeFormatter.ofPattern("MMM d, h:mma"))
    }.getOrDefault(iso.take(16))

private fun formatFillupDate(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(LOCAL_ZONE)
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }.getOrElse { iso.take(10) }
