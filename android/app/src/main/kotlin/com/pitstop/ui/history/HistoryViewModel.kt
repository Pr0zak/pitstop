package com.pitstop.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.drive.DriveUploader
import com.pitstop.drive.PendingDriveDao
import com.pitstop.drive.UploadProgress
import com.pitstop.drive.UploadProgressBus
import com.pitstop.http.CostPerMilePointDto
import com.pitstop.http.CostPerMileResponse
import com.pitstop.http.DtcDto
import com.pitstop.http.FillupDto
import com.pitstop.http.MonthlySpendPointDto
import com.pitstop.http.MonthlySpendResponse
import com.pitstop.http.NetworkFreshness
import com.pitstop.http.PitstopApi
import com.pitstop.http.TripDto
import com.pitstop.http.TripMergeRequest
import com.pitstop.log.LogBuffer
import com.pitstop.net.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-list state — generic over T so each subtab carries its own
 * loading / error / data without leaking shapes across.
 */
data class HistoryListState<T>(
    val data: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Outcome of the most recent [HistoryViewModel.refresh] pass. Backs
 * the "Updated 14:03 · 187 trips · 2 new" line under the tab row —
 * without it a refresh that returned exactly what was already on
 * screen was indistinguishable from one that never ran.
 */
data class RefreshInfo(
    val atMs: Long,
    /** Trip ids present now that weren't in the previous page. The row
     *  counts themselves come from the live list state, not from here —
     *  this only carries what a snapshot can't reconstruct. */
    val newTrips: Int,
    /** At least one request in the pass was answered from the offline
     *  disk cache instead of the server, so the timestamp describes the
     *  attempt, not the data. */
    val fromCache: Boolean = false,
)

data class HistoryUiState(
    val trips: HistoryListState<TripDto> = HistoryListState(),
    val fillups: HistoryListState<FillupDto> = HistoryListState(),
    val dtcs: HistoryListState<DtcDto> = HistoryListState(),
    /** Backs the Fillups-tab stat strip. Both are best-effort — a
     *  failure leaves them null and the strip just hides those tiles
     *  rather than failing the whole tab. */
    val costPerMile: List<CostPerMilePointDto> = emptyList(),
    val monthlySpend: List<MonthlySpendPointDto> = emptyList(),
    /** Null until the first pass completes. */
    val lastRefresh: RefreshInfo? = null,
)

/** Sort orders for the Trips list (TRIPS-1). Default is RecentFirst —
 *  what the server returns. Other orders are computed client-side
 *  from the cached page. */
enum class TripSortOrder { RecentFirst, FurthestFirst, FastestFirst, LongestFirst }

/** Source-filter chips for the Trips list. */
enum class TripSourceFilter { All, Phone, ManualMerge, Other }

/** Sort orders for the Fillups list (FILLUPS-1). */
enum class FillupSortOrder { RecentFirst, HighestCost, MostFuel, BestMpg, HighestPpg }

/** Full / partial filter chip for fillups. */
enum class FillupFilter { All, Full, Partial }

/** Relative date bucket for group headers. */
enum class TripGroupKey(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    Past7Days("Past 7 days"),
    Past30Days("Past 30 days"),
    ThisYear("This year"),
    Older("Older"),
}

/** Cellular-confirm prompt before draining the queue on a metered
 *  network. Carries the queue depth so the dialog can show "Sync N
 *  drive(s) over cellular?" without an extra round-trip. */
data class SyncConfirmPrompt(
    val pendingCount: Int,
    val reason: String,
)

/** Multi-select state for the Trips list — drives the merge-N-trips
 *  UX. When `mode` is true the trip cards toggle on tap instead of
 *  opening detail, and the selection action bar appears at the top of
 *  the list. Any two or more selections can be merged into one (a long
 *  drive the phone fragmented across BLE dropouts often needs 3+). */
data class TripSelection(
    val mode: Boolean = false,
    val ids: Set<String> = emptySet(),
)

/** Surfaced to the Trips list action bar while a manual merge is in
 *  flight (and briefly after) so the user gets feedback. */
sealed class MergeState {
    data object Idle : MergeState()
    data object InProgress : MergeState()
    data class Done(val keptTripId: String) : MergeState()
    data class Failed(val message: String) : MergeState()
}

/** Mirrors [MergeState] for the multi-select delete flow. `Done`
 *  carries how many trips were removed so the banner can pluralize. */
sealed class DeleteState {
    data object Idle : DeleteState()
    data object InProgress : DeleteState()
    data class Done(val count: Int) : DeleteState()
    data class Failed(val message: String) : DeleteState()
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
    private val pendingDao: PendingDriveDao,
    private val driveUploader: DriveUploader,
    private val uploadProgressBus: UploadProgressBus,
    private val networkFreshness: NetworkFreshness,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _syncConfirm = MutableStateFlow<SyncConfirmPrompt?>(null)
    val syncConfirm: StateFlow<SyncConfirmPrompt?> = _syncConfirm.asStateFlow()

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    /**
     * Live upload state, straight off the process-wide bus. Not owned
     * by this ViewModel on purpose: a pass started here keeps reporting
     * after the History page is disposed, and a pass started anywhere
     * else (post-seal auto-kick, periodic worker, notification action)
     * shows up here without a second state machine to keep in step.
     */
    val uploadProgress: StateFlow<UploadProgress> = uploadProgressBus.state

    private val _tripSelection = MutableStateFlow(TripSelection())
    val tripSelection: StateFlow<TripSelection> = _tripSelection.asStateFlow()

    private val _mergeState = MutableStateFlow<MergeState>(MergeState.Idle)
    val mergeState: StateFlow<MergeState> = _mergeState.asStateFlow()

    /** True while the "Delete N trips?" confirm dialog is showing for
     *  the current multi-selection. */
    private val _deleteConfirm = MutableStateFlow(false)
    val deleteConfirm: StateFlow<Boolean> = _deleteConfirm.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    private val _tripSort = MutableStateFlow(TripSortOrder.RecentFirst)
    val tripSort: StateFlow<TripSortOrder> = _tripSort.asStateFlow()
    fun setTripSort(o: TripSortOrder) { _tripSort.value = o }

    private val _tripSourceFilter = MutableStateFlow(TripSourceFilter.All)
    val tripSourceFilter: StateFlow<TripSourceFilter> = _tripSourceFilter.asStateFlow()
    fun setTripSourceFilter(f: TripSourceFilter) { _tripSourceFilter.value = f }

    private val _fillupSort = MutableStateFlow(FillupSortOrder.RecentFirst)
    val fillupSort: StateFlow<FillupSortOrder> = _fillupSort.asStateFlow()
    fun setFillupSort(o: FillupSortOrder) { _fillupSort.value = o }

    private val _fillupFilter = MutableStateFlow(FillupFilter.All)
    val fillupFilter: StateFlow<FillupFilter> = _fillupFilter.asStateFlow()
    fun setFillupFilter(f: FillupFilter) { _fillupFilter.value = f }

    /**
     * Live count of unacked drives in the local queue (#117). Drives
     * accumulate when the phone can't reach the server; "Sync now"
     * kicks a worker. The badge surfaces in the History header so
     * the user always knows what's pending.
     */
    val pendingCount: StateFlow<Int> = pendingDao.observeUnackedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        refresh()
        observeUploads()
    }

    /**
     * Pull the trip list forward when an upload pass actually lands
     * something. The uploaded drive becomes a trip row server-side on
     * the spot (POST /ingest/drive returns its trip id), so leaving the
     * list untouched meant the user watched "Synced 3 drives" scroll by
     * above a list that still showed none of them.
     */
    private fun observeUploads() {
        viewModelScope.launch {
            uploadProgressBus.state.collect { p ->
                if (p is UploadProgress.Finished && p.uploaded > 0) {
                    refresh(forceNetwork = true)
                }
            }
        }
    }

    /**
     * User tapped "Sync now". Hands off to [DriveUploader.requestDrain],
     * which runs the pass on the uploader's own process-lifetime scope
     * and reports through [uploadProgress]. Running it on
     * [viewModelScope] (the previous behaviour) meant swiping off the
     * History tab cancelled the upload mid-flight — the pager disposes
     * this page, taking the scope with it.
     *
     * This path explicitly represents user intent, so it ignores
     * manual-sync mode.
     */
    fun syncNow() {
        // Default entry point — gate on network class. Cellular gets a
        // confirmation dialog; WiFi / Ethernet go straight through.
        // The user's explicit "Sync anyway" from the dialog calls
        // confirmSync() which bypasses this check.
        if (uploadProgress.value is UploadProgress.Running) return
        if (_syncConfirm.value != null) return
        val pending = pendingCount.value
        when (networkMonitor.classify()) {
            NetworkMonitor.NetworkClass.Unmetered -> doDrain()
            NetworkMonitor.NetworkClass.Metered -> {
                _syncConfirm.value = SyncConfirmPrompt(
                    pendingCount = pending,
                    reason = "cellular",
                )
                logBuffer.info(
                    "history: sync-now blocked, awaiting cellular confirm",
                    mapOf("pending" to pending),
                )
            }
            NetworkMonitor.NetworkClass.None -> {
                // No network at all. Still surface the prompt so the
                // user knows why nothing happened, but label it.
                _syncConfirm.value = SyncConfirmPrompt(
                    pendingCount = pending,
                    reason = "offline",
                )
                logBuffer.info(
                    "history: sync-now blocked, no network",
                    mapOf("pending" to pending),
                )
            }
        }
    }

    /** Called by the dialog's "Sync anyway" button — runs the drain
     *  regardless of network class. */
    fun confirmSync() {
        _syncConfirm.value = null
        doDrain()
    }

    /** Called by the dialog's Cancel / outside-tap. */
    fun cancelSyncConfirm() {
        _syncConfirm.value = null
    }

    /** Stop the pass in flight. The drive being sent stays queued —
     *  nothing is acked until the server answers — so cancelling costs
     *  only the bytes already on the wire. */
    fun cancelSync() {
        driveUploader.cancelDrain()
    }

    private fun doDrain() {
        if (uploadProgress.value is UploadProgress.Running) return
        logBuffer.info(
            "history: sync-now requested",
            mapOf("pending" to pendingCount.value),
        )
        driveUploader.requestDrain("history-sync-now")
    }

    /** Long-press on a trip card enters multi-select mode seeded with
     *  that trip (delegated to [toggleTripSelection]). From there the
     *  selection bar offers Delete (any ≥ 1) or Merge (any ≥ 2) — the
     *  same model as the web Trips view. */
    fun longPressTrip(tripId: String) = toggleTripSelection(tripId)

    /** Selection-bar Delete → open the "Delete N trips?" confirm. The
     *  API calls don't fire until the user confirms. */
    fun requestDeleteSelection() {
        if (_tripSelection.value.ids.isEmpty()) return
        if (_deleteState.value is DeleteState.InProgress) return
        _deleteConfirm.value = true
    }

    fun cancelDeleteSelection() {
        _deleteConfirm.value = false
    }

    /** Fire DELETE /trips/{id} for every selected trip. Deletes are
     *  independent, so a single failure doesn't abort the rest — the
     *  banner reports partial failure. Refreshes and drops selection
     *  mode when done. */
    fun confirmDeleteSelection() {
        if (_deleteState.value is DeleteState.InProgress) return
        val ids = _tripSelection.value.ids.toList()
        if (ids.isEmpty()) return
        _deleteConfirm.value = false
        _deleteState.value = DeleteState.InProgress
        logBuffer.info(
            "trips delete requested",
            mapOf("count" to ids.size, "ids" to ids.joinToString(",")),
        )
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            for (id in ids) {
                runCatching { api.deleteTrip(id) }.onFailure { t ->
                    failed.add(id)
                    logBuffer.warn(
                        "trip delete failed",
                        mapOf("err" to (t.message ?: t::class.java.simpleName), "id" to id),
                    )
                }
            }
            val deleted = ids.size - failed.size
            _deleteState.value = if (failed.isEmpty()) {
                logBuffer.info("trips delete accepted", mapOf("count" to deleted))
                DeleteState.Done(deleted)
            } else {
                DeleteState.Failed("$deleted of ${ids.size} deleted; ${failed.size} failed")
            }
            _tripSelection.value = TripSelection()
            refresh(forceNetwork = true)
            delay(DELETE_DISMISS_MS)
            if (_deleteState.value is DeleteState.Done || _deleteState.value is DeleteState.Failed) {
                _deleteState.value = DeleteState.Idle
            }
        }
    }

    /** Toggle a trip in/out of the merge selection. First selection
     *  flips the list into selection mode. No cap — a fragmented long
     *  drive can need many legs merged at once (MERGE-N). */
    fun toggleTripSelection(tripId: String) {
        _tripSelection.update { sel ->
            if (tripId in sel.ids) {
                val newIds = sel.ids - tripId
                TripSelection(mode = newIds.isNotEmpty(), ids = newIds)
            } else {
                sel.copy(mode = true, ids = sel.ids + tripId)
            }
        }
    }

    fun exitTripSelection() {
        _tripSelection.value = TripSelection()
    }

    /** Fire POST /trips/{kept}/merge for every selected trip (MERGE-N).
     *  The server picks the earliest leg as the survivor regardless of
     *  which id anchors the path, so we just pass the first selected id
     *  and fold the rest in. Successful merge refreshes the list, drops
     *  selection mode, and briefly shows a Done banner. */
    fun mergeSelectedTrips() {
        val sel = _tripSelection.value
        if (sel.ids.size < 2) return
        if (_mergeState.value is MergeState.InProgress) return
        val ids = sel.ids.toList()
        val keep = ids[0]
        val others = ids.drop(1)
        _mergeState.value = MergeState.InProgress
        logBuffer.info(
            "trip merge requested",
            mapOf("keep" to keep, "others" to others.joinToString(","), "count" to ids.size),
        )
        viewModelScope.launch {
            runCatching { api.mergeTrips(keep, TripMergeRequest(otherTripIds = others)) }
                .onSuccess { merged ->
                    logBuffer.info("trip merge accepted", mapOf("kept" to merged.id))
                    _mergeState.value = MergeState.Done(merged.id)
                    _tripSelection.value = TripSelection()
                    refresh(forceNetwork = true)
                    delay(MERGE_DISMISS_MS)
                    if (_mergeState.value is MergeState.Done) _mergeState.value = MergeState.Idle
                }
                .onFailure { t ->
                    val msg = t.message ?: t::class.java.simpleName
                    logBuffer.warn("trip merge failed", mapOf("err" to msg))
                    _mergeState.value = MergeState.Failed(msg)
                    delay(MERGE_DISMISS_MS)
                    if (_mergeState.value is MergeState.Failed) _mergeState.value = MergeState.Idle
                }
        }
    }

    private companion object {
        const val MERGE_DISMISS_MS = 3_000L
        const val DELETE_DISMISS_MS = 3_000L

        /** How old the loaded page may be before returning to the tab
         *  re-fetches it. Matches the OkHttp fresh window so a
         *  cache-served refresh can't be the thing that keeps firing. */
        const val STALE_AFTER_MS = 60_000L
    }

    /**
     * The in-flight refresh, if any. Pull-to-refresh, the post-upload
     * hook, merge/delete completion and the tab's on-appear staleness
     * check can all land at once; without this guard each one fanned
     * out five more requests and every completion flipped `loading`
     * independently, which read on screen as a spinner that restarted
     * itself indefinitely.
     */
    private var refreshJob: Job? = null

    /** Re-fetch only if the page is older than [STALE_AFTER_MS]. Called
     *  when the History tab becomes visible. */
    fun refreshIfStale() {
        val last = _ui.value.lastRefresh
        if (last != null && System.currentTimeMillis() - last.atMs < STALE_AFTER_MS) return
        refresh()
    }

    /**
     * Reload all three lists in parallel. Each list manages its own
     * loading + error state so a single failure doesn't blank the
     * others.
     *
     * [forceNetwork] adds `Cache-Control: no-cache`, which the explicit
     * user gestures and the post-upload hook need: OkHttp treats a GET
     * as fresh for 60 s, so a plain refetch right after an upload
     * replays the pre-upload list.
     */
    fun refresh(forceNetwork: Boolean = false) {
        val inFlight = refreshJob
        if (inFlight?.isActive == true) {
            if (!forceNetwork) {
                logBuffer.debug("history: refresh already in flight, ignoring")
                return
            }
            // An explicit gesture (or a just-completed upload / merge /
            // delete) supersedes a background pass rather than queuing
            // behind it. Cancel-and-replace keeps exactly one fan-out in
            // flight either way.
            inFlight.cancel()
        }
        val cacheControl = if (forceNetwork) "no-cache" else null
        refreshJob = viewModelScope.launch {
            // Flip loading up front, before the vehicles round-trip. It
            // used to be set only after that call returned, so a
            // pull-to-refresh released its spinner immediately and then
            // grew a second one seconds later.
            _ui.update {
                it.copy(
                    trips = it.trips.copy(loading = true, error = null),
                    fillups = it.fillups.copy(loading = true, error = null),
                    dtcs = it.dtcs.copy(loading = true, error = null),
                )
            }
            val secrets = settings.current()
            val slug = secrets.settings.vehicleSlug.trim()
            val apiBaseUrl = secrets.settings.apiBaseUrl.trim()
            if (slug.isEmpty() || apiBaseUrl.isEmpty()) {
                _ui.update {
                    it.copy(
                        trips = it.trips.copy(loading = false, error = "Set vehicle + server in Settings"),
                        fillups = it.fillups.copy(loading = false, error = "Set vehicle + server in Settings"),
                        dtcs = it.dtcs.copy(loading = false, error = null),
                        lastRefresh = failedRefresh(),
                    )
                }
                return@launch
            }
            // Resolve slug → vehicle UUID once.
            val vehicles = runCatching { api.getVehicles(cacheControl) }.getOrElse { exc ->
                logBuffer.warn(
                    "history: vehicles fetch failed",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
                )
                _ui.update {
                    it.copy(
                        trips = it.trips.copy(loading = false, error = "vehicles fetch failed"),
                        fillups = it.fillups.copy(loading = false, error = "vehicles fetch failed"),
                        dtcs = it.dtcs.copy(loading = false, error = null),
                        lastRefresh = failedRefresh(),
                    )
                }
                return@launch
            }
            val vehicleId = vehicles.firstOrNull { it.slug == slug }?.id ?: run {
                logBuffer.warn("history: configured slug not found", mapOf("slug" to slug))
                // Clear loading so the pull-to-refresh spinner can't strand.
                _ui.update {
                    it.copy(
                        trips = it.trips.copy(loading = false, error = "vehicle \"$slug\" not on server"),
                        fillups = it.fillups.copy(loading = false),
                        dtcs = it.dtcs.copy(loading = false),
                        lastRefresh = failedRefresh(),
                    )
                }
                return@launch
            }

            // Fan out — three independent fetches.
            val freshnessBefore = networkFreshness.snapshot()
            val tripsDeferred = async {
                // 200, not 30: the stat header aggregates a 14-day
                // window, and at ~9 trips/day 30 rows is barely three
                // days — the totals would silently under-report. Also
                // gives the list itself more history to scroll.
                runCatching { api.getTrips(vehicleId, limit = 200, cacheControl = cacheControl) }
            }
            val fillupsDeferred = async {
                runCatching { api.getFillups(vehicleId, limit = 30, cacheControl = cacheControl) }
            }
            val dtcsDeferred = async {
                runCatching {
                    api.getDtcs(vehicleId, activeOnly = false, cacheControl = cacheControl)
                }
            }
            // Stat-strip inputs for the Fillups tab. Deliberately
            // best-effort: these feed a decorative header, so a failure
            // must not surface as a list error.
            val cpmDeferred = async {
                runCatching { api.getCostPerMile(vehicleId, cacheControl) }
            }
            val spendDeferred = async {
                runCatching { api.getMonthlySpend(vehicleId, cacheControl) }
            }
            val (tripsResult, fillupsResult, dtcsResult, cpmResult, spendResult) = awaitAll(
                tripsDeferred, fillupsDeferred, dtcsDeferred, cpmDeferred, spendDeferred,
            )

            _ui.update { current ->
                @Suppress("UNCHECKED_CAST")
                val trips = (tripsResult as Result<List<TripDto>>)
                @Suppress("UNCHECKED_CAST")
                val fillups = (fillupsResult as Result<List<FillupDto>>)
                @Suppress("UNCHECKED_CAST")
                val dtcs = (dtcsResult as Result<List<DtcDto>>)
                @Suppress("UNCHECKED_CAST")
                val cpm = (cpmResult as Result<CostPerMileResponse>)
                @Suppress("UNCHECKED_CAST")
                val spend = (spendResult as Result<MonthlySpendResponse>)
                val tripRows = trips.getOrNull()
                // A failed fetch keeps the rows already on screen. The
                // old code substituted an empty list, so one flaky
                // request blanked a list the user was reading and left
                // only "Couldn't load" behind.
                val known = current.trips.data.mapTo(HashSet()) { it.id }
                current.copy(
                    costPerMile = cpm.getOrNull()?.points ?: current.costPerMile,
                    monthlySpend = spend.getOrNull()?.months ?: current.monthlySpend,
                    trips = HistoryListState(
                        data = tripRows ?: current.trips.data,
                        loading = false,
                        error = trips.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    ),
                    fillups = HistoryListState(
                        data = fillups.getOrNull() ?: current.fillups.data,
                        loading = false,
                        error = fillups.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    ),
                    dtcs = HistoryListState(
                        data = dtcs.getOrNull() ?: current.dtcs.data,
                        loading = false,
                        error = dtcs.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    ),
                    lastRefresh = RefreshInfo(
                        atMs = System.currentTimeMillis(),
                        // Only meaningful once there's a previous page to
                        // diff against — otherwise a cold start would
                        // announce every trip in history as "new".
                        newTrips = if (current.lastRefresh == null || tripRows == null) 0
                        else tripRows.count { it.id !in known },
                        fromCache = networkFreshness.snapshot() != freshnessBefore,
                    ),
                )
            }
        }
    }

    /** Stamp for a pass that bailed before fetching anything. The
     *  per-list `error` is what tells the status line the attempt
     *  failed; this just moves the clock so the staleness gate doesn't
     *  retry on every recompose. */
    private fun failedRefresh() = RefreshInfo(
        atMs = System.currentTimeMillis(),
        newTrips = 0,
    )
}

// ── Trip-grouping helpers (TRIPS-1) ─────────────────────────────────

/** Relative-date bucket label for a trip's started_at.
 *
 *  Converts the ISO-8601 timestamp (always UTC on the wire) to the phone's
 *  local timezone before taking the date. Without this, a 7 PM CDT drive
 *  whose UTC timestamp rolls into the next day (00:25Z) would bucket as
 *  "Today" the morning after — surprising users who think of trips in
 *  local wall-clock time. */
fun bucketFor(
    startedAtIso: String,
    now: java.time.LocalDate = java.time.LocalDate.now(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): TripGroupKey {
    val t = runCatching {
        java.time.OffsetDateTime.parse(startedAtIso)
            .atZoneSameInstant(zone)
            .toLocalDate()
    }.getOrNull() ?: return TripGroupKey.Older
    val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(t, now)
    return when {
        daysAgo <= 0L -> TripGroupKey.Today
        daysAgo == 1L -> TripGroupKey.Yesterday
        daysAgo <= 7L -> TripGroupKey.Past7Days
        daysAgo <= 30L -> TripGroupKey.Past30Days
        t.year == now.year -> TripGroupKey.ThisYear
        else -> TripGroupKey.Older
    }
}

/** Apply filter + sort, then group by relative-date bucket. Returns
 *  the buckets in display order (Today first), each with its
 *  already-sorted items. Empty buckets are omitted. */
fun groupAndSortTrips(
    trips: List<com.pitstop.http.TripDto>,
    sort: TripSortOrder,
    filter: TripSourceFilter,
): List<Pair<TripGroupKey, List<com.pitstop.http.TripDto>>> {
    val filtered = trips.filter { t ->
        when (filter) {
            TripSourceFilter.All -> true
            TripSourceFilter.Phone -> t.source == "phone_batch"
            TripSourceFilter.ManualMerge -> t.source == "manual_merge"
            TripSourceFilter.Other -> t.source != "phone_batch" && t.source != "manual_merge"
        }
    }
    val comparator: Comparator<com.pitstop.http.TripDto> = when (sort) {
        TripSortOrder.RecentFirst -> compareByDescending { it.startedAt }
        TripSortOrder.FurthestFirst -> compareByDescending { it.distanceKm ?: 0.0 }
        TripSortOrder.FastestFirst -> compareByDescending { it.maxSpeedKph ?: 0.0 }
        TripSortOrder.LongestFirst -> compareByDescending { it.durationS ?: 0 }
    }
    val byBucket = filtered.groupBy { bucketFor(it.startedAt) }
    return TripGroupKey.entries
        .mapNotNull { key ->
            val items = byBucket[key]?.sortedWith(comparator)
            if (items.isNullOrEmpty()) null else key to items
        }
}

// ── Fillup grouping helpers (FILLUPS-1) ─────────────────────────────

/** Reuses [TripGroupKey] buckets — both trips and fillups are
 *  relative-date-grouped the same way. */
fun groupAndSortFillups(
    fillups: List<com.pitstop.http.FillupDto>,
    sort: FillupSortOrder,
    filter: FillupFilter,
): List<Pair<TripGroupKey, List<com.pitstop.http.FillupDto>>> {
    val filtered = fillups.filter { f ->
        when (filter) {
            FillupFilter.All -> true
            FillupFilter.Full -> f.isFull
            FillupFilter.Partial -> !f.isFull
        }
    }
    val comparator: Comparator<com.pitstop.http.FillupDto> = when (sort) {
        FillupSortOrder.RecentFirst -> compareByDescending { it.fillupDate }
        FillupSortOrder.HighestCost -> compareByDescending { it.priceTotal ?: 0.0 }
        FillupSortOrder.MostFuel -> compareByDescending { it.fuelVolume ?: 0.0 }
        FillupSortOrder.BestMpg -> compareByDescending { it.mpg ?: 0.0 }
        FillupSortOrder.HighestPpg -> compareByDescending { it.pricePerUnit ?: 0.0 }
    }
    val byBucket = filtered.groupBy { bucketFor(it.fillupDate) }
    return TripGroupKey.entries
        .mapNotNull { key ->
            val items = byBucket[key]?.sortedWith(comparator)
            if (items.isNullOrEmpty()) null else key to items
        }
}
