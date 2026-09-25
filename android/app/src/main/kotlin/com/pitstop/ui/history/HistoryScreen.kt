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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.pitstop.ui.components.is24HourClock
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
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import com.pitstop.util.requireActivity
import java.net.URLEncoder
import java.time.ZoneId

/**
 * Navigation for one tab's detail stack. Trips, Fuel and Car each own a
 * NavHost (so drilling into a detail keeps the bottom bar), and all three
 * register the same detail routes — a trip opened from a DTC under Car
 * stays under Car, and Back returns there.
 *
 *   list                        – the tab's root content
 *   trip/{id}?edit=             – TripDetailScreen (edit = tag sheet open)
 *   trip/{id}/map               – full-screen, interactive route map
 *   fillup/{id}                 – FillupDetailScreen
 *   fillup/{editId}/edit        – the Fuel form, prefilled, in edit mode
 *   dtc/{code}?vehicleId=       – DtcDetailScreen
 */
class SectionNav(
    val openTrip: (String) -> Unit,
    val openFillup: (String) -> Unit,
    val openDtc: (code: String, vehicleId: String) -> Unit,
)

/**
 * One tab's NavHost. [viewModel] is the Activity-scoped [HistoryViewModel]
 * (the pager disposes a tab's NavHost when it scrolls away, so an
 * entry-scoped ViewModel would be rebuilt — and refetch — on every visit).
 * Deep links addressed to [host] are pushed once this host is composed.
 */
@Composable
fun SectionHost(
    host: SectionHostId,
    viewModel: HistoryViewModel,
    root: @Composable (SectionNav) -> Unit,
) {
    val nav = rememberNavController()
    val openDtc: (String, String) -> Unit = { code, vehicleId ->
        nav.navigate("dtc/${URLEncoder.encode(code, "UTF-8")}?vehicleId=$vehicleId")
    }
    val sectionNav = remember(nav) {
        SectionNav(
            openTrip = { id -> nav.navigate("trip/$id") },
            openFillup = { id -> nav.navigate("fillup/$id") },
            openDtc = openDtc,
        )
    }

    val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        if (link.host != host) return@LaunchedEffect
        // A notification tapped while a detail is already open: start from
        // the tab root so Back behaves.
        nav.popBackStack(ROUTE_LIST, inclusive = false)
        when (link) {
            is HistoryDeepLink.Dtc -> openDtc(link.code, link.vehicleId)
            is HistoryDeepLink.Trip -> nav.navigate("trip/${link.id}?edit=${link.edit}")
            is HistoryDeepLink.Fillup -> nav.navigate("fillup/${link.id}")
        }
        viewModel.consumeLink()
    }

    NavHost(
        navController = nav,
        startDestination = ROUTE_LIST,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(ROUTE_LIST) { root(sectionNav) }
        composable(
            route = "trip/{id}?edit={edit}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("edit") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
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
                startEditing = entry.arguments?.getBoolean("edit") == true,
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

/** The Trips tab: Trips | Map under the shared top bar. */
@Composable
fun TripsScreen(
    viewModel: HistoryViewModel = hiltViewModel(LocalContext.current.requireActivity()),
) {
    SectionHost(SectionHostId.Trips, viewModel) { nav ->
        TripsListRoute(viewModel = viewModel, onOpenTrip = nav.openTrip)
    }
}

/** Collects [HistoryViewModel] state and hands it to [TripsListContent]. */
@Composable
private fun TripsListRoute(
    viewModel: HistoryViewModel,
    onOpenTrip: (String) -> Unit,
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
    val tagging by viewModel.taggingTripId.collectAsStateWithLifecycle()
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

    TripsListContent(
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
        onOpenTrip = onOpenTrip,
        taggingTripId = tagging,
        onToggleTagging = viewModel::toggleTagging,
        onTag = { id, category -> viewModel.tagTrip(id, category = category) },
        onToggleTowing = { id -> viewModel.tagTrip(id, toggleTowing = true) },
        snackbarHostState = snackbar,
        mapContent = { com.pitstop.ui.history.heatmap.HeatmapTab() },
    )
}

/**
 * Stateless body of the Trips tab — Trips | Map under one top bar. Split
 * out so screenshot tests can render it from fixtures; the Map sub-tab is a
 * slot because MapLibre needs a live Android view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripsListContent(
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
    onOpenTrip: (String) -> Unit,
    taggingTripId: String? = null,
    onToggleTagging: (String) -> Unit = {},
    onTag: (id: String, category: String) -> Unit = { _, _ -> },
    onToggleTowing: (String) -> Unit = {},
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
            when (subTab) {
                HistorySubTab.Trips -> TripsTab(
                    state = ui.trips,
                    header = {
                        ListHeaderLine(
                            info = ui.lastRefresh,
                            loading = ui.trips.loading,
                            failed = ui.trips.error != null,
                            itemCount = ui.trips.data.size,
                            noun = "trip",
                            newCount = ui.lastRefresh?.newTrips ?: 0,
                            pendingDrives = pendingCount,
                            syncing = uploadProgress is UploadProgress.Running,
                            onSync = onSync,
                        )
                    },
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
                    taggingTripId = taggingTripId,
                    onToggleTagging = onToggleTagging,
                    onTag = onTag,
                    onToggleTowing = onToggleTowing,
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
internal fun SyncConfirmDialog(
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
internal fun ListHeaderLine(
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
    val is24h = is24HourClock()
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
                    DateLabel.time(
                        java.time.Instant.ofEpochMilli(info.atMs).atZone(ZoneId.systemDefault()),
                        is24h,
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
    taggingTripId: String? = null,
    onToggleTagging: (String) -> Unit = {},
    onTag: (id: String, category: String) -> Unit = { _, _ -> },
    onToggleTowing: (String) -> Unit = {},
) {
    val groups = remember(state.data, sort, filter, towingOnly, hidden) {
        groupAndSortTrips(state.data, sort, filter, towingOnly, hidden)
    }
    val system = LocalUnitSystem.current
    val is24h = is24HourClock()
    // Which short-hop runs are unfolded. A List, not a Set, so it saves.
    var expandedHops by rememberSaveable { mutableStateOf(listOf<String>()) }
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
                    GroupHeader(
                        label = key.label,
                        summary = tripGroupSummary(tripGroupTotals(items), system),
                    )
                }
                // Short hops fold only on the chronological list (on a
                // "longest first" list they are not consecutive in time)
                // and never while selecting, where every trip must be
                // tappable on its own.
                val rows = if (sort == TripSortOrder.RecentFirst && !selection.mode) {
                    foldShortHops(items)
                } else {
                    items.map { TripRow.Single(it) }
                }
                for (row in rows) {
                    when (row) {
                        is TripRow.Single -> item(key = row.key) {
                            TaggableTripRow(
                                row.trip, is24h, selection, onOpen, onToggleSelect, onLongPress,
                                tagging = taggingTripId == row.trip.id,
                                onToggleTagging = onToggleTagging,
                                onTag = onTag,
                                onToggleTowing = onToggleTowing,
                            )
                        }
                        is TripRow.ShortHops -> {
                            val open = row.key in expandedHops
                            item(key = row.key) {
                                ShortHopsRow(
                                    hops = row,
                                    expanded = open,
                                    onToggle = {
                                        expandedHops = if (open) expandedHops - row.key else expandedHops + row.key
                                    },
                                )
                            }
                            if (open) {
                                items(row.trips, key = { it.id }) { trip ->
                                    TaggableTripRow(
                                        trip, is24h, selection, onOpen, onToggleSelect, onLongPress,
                                        tagging = taggingTripId == trip.id,
                                        onToggleTagging = onToggleTagging,
                                        onTag = onTag,
                                        onToggleTowing = onToggleTowing,
                                    )
                                }
                            }
                        }
                    }
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
internal fun LazyListScope.listStates(
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
 * whose menu ticks the active order. Whichever edge has more chips past
 * it fades out, so a chip cut at the edge reads as "scroll for more"
 * rather than as a truncated label.
 */
@Composable
internal fun ListControls(
    chips: LazyListScope.() -> Unit,
    sortOptions: List<String>,
    selectedSort: Int,
    onSort: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val rowState = rememberPitstopListState()
        LazyRow(
            state = rowState,
            modifier = Modifier
                .weight(1f)
                .edgeFade(
                    start = rowState.canScrollBackward,
                    end = rowState.canScrollForward,
                ),
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

/** Fades the [start] / [end] edge of a horizontally scrolling row to transparent. */
private fun Modifier.edgeFade(start: Boolean, end: Boolean, width: Dp = 32.dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width / 2)
            if (start) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
                    size = Size(w, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (end) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startX = size.width - w,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - w, 0f),
                    size = Size(w, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

@Composable
internal fun GroupHeader(label: String, summary: String) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TripCard(
    trip: TripDto,
    is24h: Boolean,
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
                // Economy first: what the drive covered and what it cost
                // in fuel. The date is context — the group header already
                // says which day.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${UnitFormat.distanceKm(trip.distanceKm, system)} · " +
                            "${UnitFormat.economyNumber(tripMpg(trip), system)} ${UnitFormat.economyUnit(system)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = DateLabel.list(trip.startedAt, withTime = true, grouped = true, is24h = is24h),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val parts = buildList {
                    trip.durationS?.let { add(fmtTripDuration(it)) }
                    trip.maxSpeedKph?.let {
                        add("max ${UnitFormat.Quantity.SpeedKph.format(it, system, 0)}")
                    }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Chips only when set, on their own line: they explain a
                // number above (why this trip's economy looks bad) rather
                // than competing with it.
                val category = trip.category?.takeIf { it.isNotBlank() }
                if (category != null || trip.gpsOnly || trip.isTowing || trip.dtcCount > 0) {
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
                        if (trip.dtcCount > 0) {
                            TagChip(
                                "${trip.dtcCount} DTC",
                                MaterialTheme.colorScheme.onErrorContainer,
                                MaterialTheme.colorScheme.errorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A trip card that reveals quick-tag chips when swiped either way. Swipe,
 * not long-press: long-press already enters multi-select. The swipe never
 * dismisses — it snaps back and toggles the chip row under the card. While
 * selecting, rows don't swipe (every gesture there means "select").
 * TalkBack users get the same toggle as a custom action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaggableTripRow(
    trip: TripDto,
    is24h: Boolean,
    selection: TripSelection,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    tagging: Boolean,
    onToggleTagging: (String) -> Unit,
    onTag: (id: String, category: String) -> Unit,
    onToggleTowing: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selection.mode) {
            TripCard(trip, is24h, selection, onOpen, onToggleSelect, onLongPress)
        } else {
            // confirmValueChange can fire more than once for one gesture on
            // this material3 version; the time gate makes one swipe = one toggle.
            val lastToggle = remember { longArrayOf(0L) }
            val state = rememberSwipeToDismissBoxState(
                confirmValueChange = { v ->
                    if (v != SwipeToDismissBoxValue.Settled) {
                        val now = System.currentTimeMillis()
                        if (now - lastToggle[0] > 500L) {
                            lastToggle[0] = now
                            onToggleTagging(trip.id)
                        }
                    }
                    false
                },
                positionalThreshold = { it * 0.25f },
            )
            SwipeToDismissBox(
                state = state,
                backgroundContent = { TagSwipeBackground() },
                modifier = Modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (tagging) "Hide tags" else "Tag trip") {
                            onToggleTagging(trip.id)
                            true
                        },
                    )
                },
            ) {
                TripCard(trip, is24h, selection, onOpen, onToggleSelect, onLongPress)
            }
        }
        if (tagging && !selection.mode) {
            TripTagChips(trip = trip, onTag = onTag, onToggleTowing = onToggleTowing, onDone = { onToggleTagging(trip.id) })
        }
    }
}

@Composable
private fun TagSwipeBackground() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (i in 0..1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Outlined.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.size(6.dp))
                Text("Tag", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Commute / Errands / Road trip (one category) + Towing (a flag). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripTagChips(
    trip: TripDto,
    onTag: (id: String, category: String) -> Unit,
    onToggleTowing: (String) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
    LazyRow(
        state = rememberPitstopListState(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.weight(1f),
    ) {
        items(QUICK_TRIP_CATEGORIES) { c ->
            FilterChip(
                selected = trip.category.equals(c, ignoreCase = true),
                onClick = { onTag(trip.id, c) },
                label = { Text(c) },
            )
        }
        item {
            FilterChip(
                selected = trip.isTowing,
                onClick = { onToggleTowing(trip.id) },
                label = { Text("Towing") },
            )
        }
    }
    IconButton(onClick = onDone) {
        Icon(Icons.Filled.Close, contentDescription = "Close tags")
    }
    }
}

/** "31m" / "1h 30m" / "45s". */
private fun fmtTripDuration(s: Int): String = when {
    s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
    s >= 60 -> "${s / 60}m"
    else -> "${s}s"
}

/**
 * A run of consecutive short hops, folded: "▸ 3 short hops · 0.6 mi".
 * Tapping unfolds the trips beneath it. Deliberately quieter than a trip
 * card — these are the drives nobody wants to scroll past.
 */
@Composable
private fun ShortHopsRow(hops: TripRow.ShortHops, expanded: Boolean, onToggle: () -> Unit) {
    val system = LocalUnitSystem.current
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Text(
            text = "${if (expanded) "▾" else "▸"} ${hops.trips.size} short hops · " +
                UnitFormat.distanceKm(hops.distanceKm, system),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
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

// ── Fillups (rendered by the Fuel hub) ─────────────────────────────

@Composable
internal fun FillupCard(f: FillupDto, is24h: Boolean, onOpen: (String) -> Unit) {
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
                    text = DateLabel.list(f.fillupDate, withTime = false, grouped = true, is24h = is24h),
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
