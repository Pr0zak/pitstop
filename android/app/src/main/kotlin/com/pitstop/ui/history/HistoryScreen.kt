package com.pitstop.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pitstop.drive.UploadProgress
import com.pitstop.http.DtcDto
import com.pitstop.http.FillupDto
import com.pitstop.http.TripDto
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.LoadErrorState
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.rememberPitstopListState
import com.pitstop.ui.components.UploadStatusCard
import com.pitstop.ui.fuel.FuelAddScreen
import com.pitstop.ui.history.detail.DtcDetailScreen
import com.pitstop.ui.history.detail.FillupDetailScreen
import com.pitstop.ui.history.detail.TripDetailScreen
import com.pitstop.ui.history.detail.TripMapScreen
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import com.pitstop.util.requireActivity
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History tab — root surface owns its own NavHost so drilling into a
 * detail screen doesn't break the bottom NavigationBar. Routes:
 *   list                  – the four subtabs (Trips/Fillups/DTCs/Map)
 *   trip/{id}             – TripDetailScreen
 *   trip/{id}/map         – full-screen, interactive route map
 *   fillup/{id}           – FillupDetailScreen
 *   fillup/{editId}/edit  – the Fuel form, prefilled, in edit mode
 *   dtc/{code}?vehicleId= – DtcDetailScreen
 *
 * Every route owns its own Scaffold + top bar (brand bar on the list,
 * DetailTopAppBar on the rest), so a detail can title itself with the
 * thing it shows. The bottom NavigationBar is owned by MainActivity's
 * Scaffold so it stays put across detail transitions.
 *
 * Deep links (Home → a DTC / a trip) arrive through the Activity-scoped
 * [HistoryViewModel.pendingLink] and are pushed here once composed.
 */
@Composable
fun HistoryScreen(
    // Scoped to the Activity, not to a NavBackStackEntry. MainActivity's
    // pager keeps beyondViewportPageCount at 0, so leaving History disposes
    // this whole NavHost — with an entry-scoped ViewModel that meant a
    // brand-new instance (and a fresh five-endpoint fan-out) every time the
    // tab came back. Activity scope keeps one instance, one list, one
    // refresh policy — and is what lets Home deep-link in.
    viewModel: HistoryViewModel = hiltViewModel(LocalContext.current.requireActivity()),
) {
    val nav = rememberNavController()
    val openDtc: (String, String) -> Unit = { code, vehicleId ->
        nav.navigate("dtc/${URLEncoder.encode(code, "UTF-8")}?vehicleId=$vehicleId")
    }

    val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        when (val link = pendingLink) {
            null -> return@LaunchedEffect
            is HistoryDeepLink.Dtc -> openDtc(link.code, link.vehicleId)
            is HistoryDeepLink.Trip -> nav.navigate("trip/${link.id}")
        }
        viewModel.consumeLink()
    }

    NavHost(
        navController = nav,
        startDestination = ROUTE_LIST,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(ROUTE_LIST) {
            HistoryListRoute(
                viewModel = viewModel,
                onOpenTrip = { id -> nav.navigate("trip/$id") },
                onOpenFillup = { id -> nav.navigate("fillup/$id") },
                onOpenDtc = openDtc,
            )
        }
        composable(
            route = "trip/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TripDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenDtc = openDtc,
                onOpenMap = { nav.navigate("trip/$id/map") },
                onDeleted = {
                    nav.popBackStack()
                    viewModel.refresh(forceNetwork = true)
                },
            )
        }
        composable(
            route = "trip/{id}/map",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            TripMapScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "fillup/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            FillupDetailScreen(
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("fillup/$id/edit") },
                onDeleted = {
                    nav.popBackStack()
                    viewModel.refresh(forceNetwork = true)
                },
            )
        }
        composable(
            route = "fillup/{editId}/edit",
            arguments = listOf(navArgument("editId") { type = NavType.StringType }),
        ) {
            FuelAddScreen(
                onBack = { nav.popBackStack() },
                onSaved = {
                    nav.popBackStack()
                    viewModel.refresh(forceNetwork = true)
                },
            )
        }
        composable(
            route = "dtc/{code}?vehicleId={vehicleId}",
            arguments = listOf(
                navArgument("code") { type = NavType.StringType },
                navArgument("vehicleId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            DtcDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenTrip = { id -> nav.navigate("trip/$id") },
            )
        }
    }
}

private const val ROUTE_LIST = "list"

/** Collects [HistoryViewModel] state and hands it to [HistoryListContent]. */
@Composable
private fun HistoryListRoute(
    viewModel: HistoryViewModel,
    onOpenTrip: (String) -> Unit,
    onOpenFillup: (String) -> Unit,
    onOpenDtc: (code: String, vehicleId: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val subTab by viewModel.subTab.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val syncConfirm by viewModel.syncConfirm.collectAsStateWithLifecycle()
    val selection by viewModel.tripSelection.collectAsStateWithLifecycle()
    val mergeState by viewModel.mergeState.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val tripSort by viewModel.tripSort.collectAsStateWithLifecycle()
    val tripFilter by viewModel.tripSourceFilter.collectAsStateWithLifecycle()
    val towingOnly by viewModel.towingOnly.collectAsStateWithLifecycle()
    val fillupSort by viewModel.fillupSort.collectAsStateWithLifecycle()
    val fillupFilter by viewModel.fillupFilter.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Returning to the tab re-fetches only when the page has gone stale.
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    // Undo window for a trip delete. The snackbar is the timer: dismissing
    // it (or letting it time out) commits, Undo restores. The ViewModel has
    // its own backstop timer for when this screen is gone before either.
    LaunchedEffect(pendingDelete) {
        val pending = pendingDelete ?: return@LaunchedEffect
        val n = pending.ids.size
        val result = snackbar.showSnackbar(
            message = if (n == 1) "Trip deleted" else "$n trips deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.commitPendingDelete()
        }
    }

    syncConfirm?.let { prompt ->
        SyncConfirmDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmSync,
            onDismiss = viewModel::cancelSyncConfirm,
        )
    }

    HistoryListContent(
        subTab = subTab,
        onSubTab = viewModel::selectSubTab,
        ui = ui,
        pendingCount = pendingCount,
        uploadProgress = uploadProgress,
        onSync = viewModel::syncNow,
        onCancelSync = viewModel::cancelSync,
        onRefresh = { viewModel.refresh(forceNetwork = true) },
        selection = selection,
        mergeState = mergeState,
        hiddenTripIds = pendingDelete?.ids.orEmpty(),
        tripSort = tripSort,
        tripFilter = tripFilter,
        towingOnly = towingOnly,
        onTripSort = viewModel::setTripSort,
        onTripFilter = viewModel::setTripSourceFilter,
        onTowingOnly = viewModel::setTowingOnly,
        onToggleSelect = viewModel::toggleTripSelection,
        onLongPress = viewModel::longPressTrip,
        onCancelSelection = viewModel::exitTripSelection,
        onMerge = viewModel::mergeSelectedTrips,
        onDelete = viewModel::deleteSelection,
        fillupSort = fillupSort,
        fillupFilter = fillupFilter,
        onFillupSort = viewModel::setFillupSort,
        onFillupFilter = viewModel::setFillupFilter,
        onOpenTrip = onOpenTrip,
        onOpenFillup = onOpenFillup,
        onOpenDtc = onOpenDtc,
        snackbarHostState = snackbar,
        mapContent = { com.pitstop.ui.history.heatmap.HeatmapTab() },
    )
}

/**
 * Stateless body of the History list — the four sub-tabs under one top
 * bar. Split out so screenshot tests can render it from fixtures; the Map
 * sub-tab is a slot because MapLibre needs a live Android view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryListContent(
    subTab: HistorySubTab,
    onSubTab: (HistorySubTab) -> Unit,
    ui: HistoryUiState,
    pendingCount: Int,
    uploadProgress: UploadProgress,
    onSync: () -> Unit,
    onCancelSync: () -> Unit,
    onRefresh: () -> Unit,
    selection: TripSelection,
    mergeState: MergeState,
    hiddenTripIds: Set<String>,
    tripSort: TripSortOrder,
    tripFilter: TripSourceFilter,
    towingOnly: Boolean,
    onTripSort: (TripSortOrder) -> Unit,
    onTripFilter: (TripSourceFilter) -> Unit,
    onTowingOnly: (Boolean) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onCancelSelection: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    fillupSort: FillupSortOrder,
    fillupFilter: FillupFilter,
    onFillupSort: (FillupSortOrder) -> Unit,
    onFillupFilter: (FillupFilter) -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenFillup: (String) -> Unit,
    onOpenDtc: (code: String, vehicleId: String) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    mapContent: @Composable () -> Unit = {},
) {
    val selecting = subTab == HistorySubTab.Trips &&
        (selection.mode || mergeState is MergeState.InProgress)
    Scaffold(
        // MainActivity's outer Scaffold already consumed the system-bar
        // insets for the whole pager; re-applying them here leaves an empty
        // status-bar-tall band above the content.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = selection.ids.size,
                    merging = mergeState is MergeState.InProgress,
                    onCancel = onCancelSelection,
                    onMerge = onMerge,
                    onDelete = onDelete,
                )
            } else {
                PitstopTopAppBar()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            UploadStatusCard(
                progress = uploadProgress,
                pendingCount = pendingCount,
                onSync = onSync,
                onCancel = onCancelSync,
                onlyActiveOrFailed = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            SecondaryTabRow(selectedTabIndex = subTab.ordinal) {
                for (t in HistorySubTab.entries) {
                    Tab(
                        selected = subTab == t,
                        onClick = { onSubTab(t) },
                        text = { Text(t.label) },
                    )
                }
            }
            val header: @Composable () -> Unit = {
                ListHeaderLine(
                    info = ui.lastRefresh,
                    loading = when (subTab) {
                        HistorySubTab.Trips -> ui.trips.loading
                        HistorySubTab.Fillups -> ui.fillups.loading
                        else -> ui.dtcs.loading
                    },
                    failed = when (subTab) {
                        HistorySubTab.Trips -> ui.trips.error
                        HistorySubTab.Fillups -> ui.fillups.error
                        else -> ui.dtcs.error
                    } != null,
                    itemCount = when (subTab) {
                        HistorySubTab.Trips -> ui.trips.data.size
                        HistorySubTab.Fillups -> ui.fillups.data.size
                        else -> ui.dtcs.data.size
                    },
                    noun = when (subTab) {
                        HistorySubTab.Trips -> "trip"
                        HistorySubTab.Fillups -> "fillup"
                        else -> "code"
                    },
                    newCount = if (subTab == HistorySubTab.Trips) ui.lastRefresh?.newTrips ?: 0 else 0,
                    pendingDrives = if (subTab == HistorySubTab.Trips) pendingCount else 0,
                    syncing = uploadProgress is UploadProgress.Running,
                    onSync = onSync,
                )
            }
            when (subTab) {
                HistorySubTab.Trips -> TripsTab(
                    state = ui.trips,
                    header = header,
                    onRefresh = onRefresh,
                    selection = selection,
                    hidden = hiddenTripIds,
                    sort = tripSort,
                    filter = tripFilter,
                    towingOnly = towingOnly,
                    onSort = onTripSort,
                    onFilter = onTripFilter,
                    onTowingOnly = onTowingOnly,
                    onOpen = onOpenTrip,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onLongPress,
                )
                HistorySubTab.Fillups -> FillupsTab(
                    ui = ui,
                    header = header,
                    onRefresh = onRefresh,
                    sort = fillupSort,
                    filter = fillupFilter,
                    onSort = onFillupSort,
                    onFilter = onFillupFilter,
                    onOpen = onOpenFillup,
                )
                HistorySubTab.Dtcs -> DtcsTab(
                    state = ui.dtcs,
                    header = header,
                    onRefresh = onRefresh,
                    onOpen = onOpenDtc,
                )
                HistorySubTab.Map -> mapContent()
            }
        }
    }
}

/** Contextual top bar for multi-select: count as the title, Merge / Delete
 *  as actions, close to leave the mode. Replaces the old in-list banner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    merging: Boolean,
    onCancel: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
        },
        title = {
            Text(
                if (merging) "Merging…" else "$count selected",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        actions = {
            if (merging) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                // Merge needs two legs; the icon stays visible but disabled
                // at one so the affordance is learnable.
                IconButton(onClick = onMerge, enabled = count >= 2) {
                    Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = "Merge trips")
                }
                IconButton(onClick = onDelete, enabled = count >= 1) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete trips")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun SyncConfirmDialog(
    prompt: SyncConfirmPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isOffline = prompt.reason == "offline"
    val drives = "${prompt.pendingCount} drive${if (prompt.pendingCount == 1) "" else "s"}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isOffline) "No network" else "Cellular upload?") },
        text = {
            Text(
                if (isOffline) {
                    "There's no network connection right now. $drives will stay " +
                        "queued until you're back online."
                } else {
                    "You're on cellular. $drives queued — large payloads (~MB each) " +
                        "will be sent over your mobile data. Wait for WiFi, or sync " +
                        "now over cellular?"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isOffline) {
                Text(if (isOffline) "OK" else "Sync over cellular")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isOffline) "Close" else "Wait for WiFi") }
        },
    )
}

/**
 * One labelSmall line at the top of each list saying when it last came
 * back from the server, how much it holds, and whether anything arrived.
 * Pull-to-refresh is the refresh gesture; this is the receipt that it did
 * something — the explicit "up to date" is the answer to "did that work?".
 * On Trips it also carries the queued-drive count with a Sync action,
 * since the upload card only appears here while a pass runs or failed.
 */
@Composable
private fun ListHeaderLine(
    info: RefreshInfo?,
    loading: Boolean,
    failed: Boolean,
    itemCount: Int,
    noun: String,
    newCount: Int,
    pendingDrives: Int,
    syncing: Boolean,
    onSync: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
        }
        val text = when {
            loading -> "Refreshing…"
            failed -> "Couldn't refresh — pull down to retry"
            info == null -> ""
            else -> buildString {
                // "Checked" not "Updated" when the server never answered
                // and the disk cache filled in — the timestamp then
                // describes the attempt, not the data.
                append(if (info.fromCache) "Offline · saved data, checked " else "Updated ")
                append(
                    DateTimeFormatter.ofPattern("HH:mm").format(
                        java.time.Instant.ofEpochMilli(info.atMs).atZone(ZoneId.systemDefault()),
                    ),
                )
                append(" · ${UnitFormat.count(itemCount.toLong())} $noun${if (itemCount == 1) "" else "s"}")
                if (!info.fromCache && newCount > 0) append(" · $newCount new")
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (pendingDrives > 0 && !syncing) {
            TextButton(onClick = onSync, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Sync $pendingDrives queued", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ── Trips ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TripsTab(
    state: HistoryListState<TripDto>,
    header: @Composable () -> Unit,
    onRefresh: () -> Unit,
    selection: TripSelection,
    hidden: Set<String>,
    sort: TripSortOrder,
    filter: TripSourceFilter,
    towingOnly: Boolean,
    onSort: (TripSortOrder) -> Unit,
    onFilter: (TripSourceFilter) -> Unit,
    onTowingOnly: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val groups = remember(state.data, sort, filter, towingOnly, hidden) {
        groupAndSortTrips(state.data, sort, filter, towingOnly, hidden)
    }
    PullToRefreshBox(
        isRefreshing = state.loading && state.data.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberPitstopListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The stat header scrolls with the list — pinned, it cost a
            // third of the screen before the first trip.
            if (!selection.mode) {
                item(key = "stats") { TripStatsHeader(trips = state.data) }
            }
            item(key = "controls") {
                ListControls(
                    chips = {
                        for (f in TripSourceFilter.entries) {
                            item(key = f.name) {
                                FilterChip(
                                    selected = f == filter,
                                    onClick = { onFilter(f) },
                                    label = { Text(f.label) },
                                )
                            }
                        }
                        item(key = "tow") {
                            FilterChip(
                                selected = towingOnly,
                                onClick = { onTowingOnly(!towingOnly) },
                                label = { Text("Towing") },
                            )
                        }
                    },
                    sortOptions = TripSortOrder.entries.map { it.label },
                    selectedSort = sort.ordinal,
                    onSort = { onSort(TripSortOrder.entries[it]) },
                )
            }
            item(key = "header") { header() }
            listStates(
                loading = state.loading,
                error = state.error,
                empty = state.data.isEmpty(),
                filteredEmpty = groups.isEmpty(),
                what = "trips",
                emptyIcon = Icons.Outlined.DirectionsCar,
                emptyTitle = "No trips yet",
                emptyBody = "Drives appear here after the bridge uploads them.",
                onRetry = onRefresh,
            )
            for ((key, items) in groups) {
                stickyHeader(key = "header-${key.name}") {
                    GroupHeader(label = key.label, count = items.size)
                }
                items(items, key = { it.id }) { trip ->
                    TripCard(trip, selection, onOpen, onToggleSelect, onLongPress)
                }
            }
        }
    }
}

/**
 * Loading / error / empty rows shared by the three server-backed lists.
 * Rendered as LazyColumn items rather than instead of the list, so the
 * header and pull-to-refresh stay available in every state.
 */
private fun LazyListScope.listStates(
    loading: Boolean,
    error: String?,
    empty: Boolean,
    filteredEmpty: Boolean,
    what: String,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyBody: String,
    onRetry: () -> Unit,
) {
    when {
        loading && empty -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null && empty -> item(key = "error") {
            LoadErrorState(what = what, onRetry = onRetry)
        }
        empty -> item(key = "empty") {
            EmptyState(icon = emptyIcon, title = emptyTitle, body = emptyBody)
        }
        filteredEmpty -> item(key = "filtered") {
            EmptyState(
                icon = Icons.Outlined.FilterAltOff,
                title = "Nothing matches",
                body = "No $what match these filters.",
            )
        }
    }
}

/**
 * Filter chips (horizontally scrollable — the old fixed row clipped the
 * last chip on a 360 dp phone) plus the sort control as an icon button
 * whose menu ticks the active order.
 */
@Composable
private fun ListControls(
    chips: LazyListScope.() -> Unit,
    sortOptions: List<String>,
    selectedSort: Int,
    onSort: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = rememberPitstopListState(),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = chips,
        )
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort: ${sortOptions[selectedSort]}",
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                sortOptions.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = {
                            if (i == selectedSort) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected")
                            }
                        },
                        onClick = {
                            onSort(i)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TripCard(
    trip: TripDto,
    selection: TripSelection,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val system = LocalUnitSystem.current
    val isSelected = trip.id in selection.ids
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = if (selection.mode) "Toggle selection" else "Open trip",
                onLongClickLabel = "Select",
                onClick = {
                    if (selection.mode) onToggleSelect(trip.id) else onOpen(trip.id)
                },
                onLongClick = {
                    // In selection mode, long-press still toggles to
                    // preserve the fast-select flow. Out of selection
                    // mode, long-press enters multi-select seeded with
                    // this trip; the contextual bar then offers Merge
                    // (≥2) or Delete (≥1).
                    if (selection.mode) onToggleSelect(trip.id) else onLongPress(trip.id)
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (selection.mode) 4.dp else 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selection.mode) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect(trip.id) })
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTripDate(trip.startedAt),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = UnitFormat.distanceKm(trip.distanceKm, system),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val parts = buildList {
                    trip.durationS?.let {
                        add(if (it >= 60) "${it / 60}m ${it % 60}s" else "${it}s")
                    }
                    trip.maxSpeedKph?.let {
                        add("max ${UnitFormat.Quantity.SpeedKph.format(it, system, 0)}")
                    }
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
                // Tags last, on their own line: they explain a number above
                // (why this tank looks bad) rather than competing with it.
                val category = trip.category?.takeIf { it.isNotBlank() }
                if (category != null || trip.gpsOnly || trip.isTowing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        category?.let {
                            TagChip(it, MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                        }
                        if (trip.gpsOnly) {
                            TagChip("GPS only", MaterialTheme.ext.gps, MaterialTheme.ext.infoContainer)
                        }
                        if (trip.isTowing) {
                            TagChip("TOW", MaterialTheme.ext.tow, MaterialTheme.ext.warnContainer)
                        }
                    }
                }
            }
        }
    }
}

/** Small non-interactive label chip; colours from the theme. */
@Composable
internal fun TagChip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "Tag: $text" },
    )
}

// ── Fillups ─────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FillupsTab(
    ui: HistoryUiState,
    header: @Composable () -> Unit,
    onRefresh: () -> Unit,
    sort: FillupSortOrder,
    filter: FillupFilter,
    onSort: (FillupSortOrder) -> Unit,
    onFilter: (FillupFilter) -> Unit,
    onOpen: (String) -> Unit,
) {
    val state = ui.fillups
    val groups = remember(state.data, sort, filter) { groupAndSortFillups(state.data, sort, filter) }
    PullToRefreshBox(
        isRefreshing = state.loading && state.data.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberPitstopListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            item(key = "header") { header() }
            listStates(
                loading = state.loading,
                error = state.error,
                empty = state.data.isEmpty(),
                filteredEmpty = groups.isEmpty(),
                what = "fillups",
                emptyIcon = Icons.Outlined.LocalGasStation,
                emptyTitle = "No fillups yet",
                emptyBody = "Log one from the Fuel tab at the pump.",
                onRetry = onRefresh,
            )
            for ((key, items) in groups) {
                stickyHeader(key = "fillup-header-${key.name}") {
                    GroupHeader(label = key.label, count = items.size)
                }
                items(items, key = { it.id }) { f -> FillupCard(f, onOpen) }
            }
        }
    }
}

@Composable
private fun FillupCard(f: FillupDto, onOpen: (String) -> Unit) {
    val system = LocalUnitSystem.current
    Card(
        onClick = { onOpen(f.id) },
        modifier = Modifier.fillMaxWidth(),
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
                    text = UnitFormat.money(f.priceTotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val parts = buildList {
                f.fuelVolume?.let { add(UnitFormat.volumeGal(it, system)) }
                f.pricePerUnit?.let { add(UnitFormat.pricePerVolume(it, system)) }
                f.mpg?.let { add(UnitFormat.economy(it, system)) }
                if (!f.isFull) add("partial")
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

// ── DTCs ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DtcsTab(
    state: HistoryListState<DtcDto>,
    header: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (code: String, vehicleId: String) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.loading && state.data.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberPitstopListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") { header() }
            listStates(
                loading = state.loading,
                error = state.error,
                empty = state.data.isEmpty(),
                filteredEmpty = false,
                what = "trouble codes",
                emptyIcon = Icons.Outlined.CheckCircle,
                emptyTitle = "No trouble codes",
                emptyBody = "Nothing logged in the last year — clean bill of health.",
                onRetry = onRefresh,
            )
            items(state.data, key = { it.id }) { dtc ->
                Card(
                    onClick = { onOpen(dtc.code, dtc.vehicleId) },
                    modifier = Modifier.fillMaxWidth(),
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
                            if (dtc.clearedAt == null) {
                                TagChip(
                                    "ACTIVE",
                                    MaterialTheme.colorScheme.onErrorContainer,
                                    MaterialTheme.colorScheme.errorContainer,
                                )
                                Spacer(Modifier.size(8.dp))
                            }
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
    }
}

// Backend serves timestamps in UTC; convert to the device's local zone
// before formatting so a trip at 20:32Z renders as "3:32PM" in CDT, not
// "8:32PM". Without the withZoneSameInstant() step OffsetDateTime keeps
// its parsed offset and formats the raw UTC fields.
private val LOCAL_ZONE: ZoneId = ZoneId.systemDefault()

internal fun formatTripDate(iso: String): String =
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
