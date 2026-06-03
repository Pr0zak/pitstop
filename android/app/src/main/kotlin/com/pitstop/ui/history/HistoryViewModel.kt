package com.pitstop.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.drive.DriveUploader
import com.pitstop.drive.PendingDriveDao
import com.pitstop.http.DtcDto
import com.pitstop.http.FillupDto
import com.pitstop.http.PitstopApi
import com.pitstop.http.TripDto
import com.pitstop.http.TripMergeRequest
import com.pitstop.log.LogBuffer
import com.pitstop.net.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class HistoryUiState(
    val trips: HistoryListState<TripDto> = HistoryListState(),
    val fillups: HistoryListState<FillupDto> = HistoryListState(),
    val dtcs: HistoryListState<DtcDto> = HistoryListState(),
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

/** Surfaced to the History header so the Sync-now chip can show progress
 *  instead of disappearing into a fire-and-forget kick. */
sealed class SyncState {
    data object Idle : SyncState()
    data object InProgress : SyncState()
    data class Done(val uploaded: Int, val remaining: Int) : SyncState()
    data class Failed(val message: String) : SyncState()
}

/** Cellular-confirm prompt before draining the queue on a metered
 *  network. Carries the queue depth so the dialog can show "Sync N
 *  drive(s) over cellular?" without an extra round-trip. */
data class SyncConfirmPrompt(
    val pendingCount: Int,
    val reason: String,
)

/** Multi-select state for the Trips list — drives the merge-two-trips
 *  UX. When `mode` is true the trip cards toggle on tap instead of
 *  opening detail, and the selection action bar appears at the top of
 *  the list. Capped at 2 selections because merge takes exactly two. */
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

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
    private val pendingDao: PendingDriveDao,
    private val driveUploader: DriveUploader,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _syncConfirm = MutableStateFlow<SyncConfirmPrompt?>(null)
    val syncConfirm: StateFlow<SyncConfirmPrompt?> = _syncConfirm.asStateFlow()

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _tripSelection = MutableStateFlow(TripSelection())
    val tripSelection: StateFlow<TripSelection> = _tripSelection.asStateFlow()

    private val _mergeState = MutableStateFlow<MergeState>(MergeState.Idle)
    val mergeState: StateFlow<MergeState> = _mergeState.asStateFlow()

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
    }

    /**
     * User tapped "Sync now". Runs the drain on [viewModelScope] so the
     * UI can show progress (chip → "Syncing…" → "Synced N drive(s)"),
     * rather than the previous fire-and-forget kickWorker call which
     * left the user with no visual feedback that anything had happened.
     * This path explicitly represents user intent, so it ignores
     * manual-sync mode.
     */
    fun syncNow() {
        // Default entry point — gate on network class. Cellular gets a
        // confirmation dialog; WiFi / Ethernet go straight through.
        // The user's explicit "Sync anyway" from the dialog calls
        // confirmSync() which bypasses this check.
        if (_syncState.value is SyncState.InProgress) return
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

    private fun doDrain() {
        if (_syncState.value is SyncState.InProgress) return
        val startUnacked = pendingCount.value
        _syncState.value = SyncState.InProgress
        logBuffer.info(
            "history: sync-now requested",
            mapOf("pending" to startUnacked),
        )
        viewModelScope.launch {
            runCatching { driveUploader.drain("history-sync-now") }
                .onSuccess { uploaded ->
                    val remaining = runCatching { pendingDao.unackedCount() }.getOrDefault(0)
                    _syncState.value = SyncState.Done(uploaded = uploaded, remaining = remaining)
                    delay(SYNC_DISMISS_MS)
                    if (_syncState.value is SyncState.Done) _syncState.value = SyncState.Idle
                }
                .onFailure { t ->
                    val msg = t.message ?: t::class.java.simpleName
                    logBuffer.warn("history: sync-now drain failed", mapOf("err" to msg))
                    _syncState.value = SyncState.Failed(msg)
                    delay(SYNC_DISMISS_MS)
                    if (_syncState.value is SyncState.Failed) _syncState.value = SyncState.Idle
                }
        }
    }

    /** Toggle a trip in/out of the merge selection. First selection
     *  flips the list into selection mode. Capped at 2; further taps
     *  on unselected trips are ignored until the user clears one. */
    fun toggleTripSelection(tripId: String) {
        _tripSelection.update { sel ->
            if (tripId in sel.ids) {
                val newIds = sel.ids - tripId
                TripSelection(mode = newIds.isNotEmpty(), ids = newIds)
            } else if (sel.ids.size >= 2) {
                sel
            } else {
                sel.copy(mode = true, ids = sel.ids + tripId)
            }
        }
    }

    fun exitTripSelection() {
        _tripSelection.value = TripSelection()
    }

    /** Fire POST /trips/{kept}/merge for the two selected trips.
     *  Successful merge refreshes the list, drops selection mode, and
     *  briefly shows a Done banner. */
    fun mergeSelectedTrips() {
        val sel = _tripSelection.value
        if (sel.ids.size != 2) return
        if (_mergeState.value is MergeState.InProgress) return
        val ids = sel.ids.toList()
        val keep = ids[0]
        val other = ids[1]
        _mergeState.value = MergeState.InProgress
        logBuffer.info(
            "trip merge requested",
            mapOf("a" to keep, "b" to other),
        )
        viewModelScope.launch {
            runCatching { api.mergeTrips(keep, TripMergeRequest(otherTripId = other)) }
                .onSuccess { merged ->
                    logBuffer.info("trip merge accepted", mapOf("kept" to merged.id))
                    _mergeState.value = MergeState.Done(merged.id)
                    _tripSelection.value = TripSelection()
                    refresh()
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
        const val SYNC_DISMISS_MS = 4_000L
        const val MERGE_DISMISS_MS = 3_000L
    }

    /** Reload all three lists in parallel. Each list manages its own
     *  loading + error state so a single failure doesn't blank the
     *  others. */
    fun refresh() {
        viewModelScope.launch {
            val secrets = settings.current()
            val slug = secrets.settings.vehicleSlug.trim()
            val apiBaseUrl = secrets.settings.apiBaseUrl.trim()
            if (slug.isEmpty() || apiBaseUrl.isEmpty()) {
                _ui.update {
                    it.copy(
                        trips = it.trips.copy(loading = false, error = "Set vehicle + server in Settings"),
                        fillups = it.fillups.copy(loading = false, error = "Set vehicle + server in Settings"),
                        dtcs = it.dtcs.copy(loading = false, error = null),
                    )
                }
                return@launch
            }
            // Resolve slug → vehicle UUID once.
            val vehicles = runCatching { api.getVehicles() }.getOrElse { exc ->
                logBuffer.warn(
                    "history: vehicles fetch failed",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
                )
                _ui.update {
                    it.copy(
                        trips = it.trips.copy(loading = false, error = "vehicles fetch failed"),
                        fillups = it.fillups.copy(loading = false, error = "vehicles fetch failed"),
                        dtcs = it.dtcs.copy(loading = false, error = null),
                    )
                }
                return@launch
            }
            val vehicleId = vehicles.firstOrNull { it.slug == slug }?.id ?: run {
                logBuffer.warn("history: configured slug not found", mapOf("slug" to slug))
                return@launch
            }

            _ui.update {
                it.copy(
                    trips = it.trips.copy(loading = true, error = null),
                    fillups = it.fillups.copy(loading = true, error = null),
                    dtcs = it.dtcs.copy(loading = true, error = null),
                )
            }

            // Fan out — three independent fetches.
            val tripsDeferred = async {
                runCatching { api.getTrips(vehicleId, limit = 30) }
            }
            val fillupsDeferred = async {
                runCatching { api.getFillups(vehicleId, limit = 30) }
            }
            val dtcsDeferred = async {
                runCatching { api.getDtcs(vehicleId, activeOnly = false) }
            }
            val (tripsResult, fillupsResult, dtcsResult) = awaitAll(
                tripsDeferred, fillupsDeferred, dtcsDeferred,
            )

            _ui.update { current ->
                @Suppress("UNCHECKED_CAST")
                val trips = (tripsResult as Result<List<TripDto>>)
                @Suppress("UNCHECKED_CAST")
                val fillups = (fillupsResult as Result<List<FillupDto>>)
                @Suppress("UNCHECKED_CAST")
                val dtcs = (dtcsResult as Result<List<DtcDto>>)
                current.copy(
                    trips = HistoryListState(
                        data = trips.getOrNull() ?: emptyList(),
                        loading = false,
                        error = trips.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    ),
                    fillups = HistoryListState(
                        data = fillups.getOrNull() ?: emptyList(),
                        loading = false,
                        error = fillups.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    ),
                    dtcs = HistoryListState(
                        data = dtcs.getOrNull() ?: emptyList(),
                        loading = false,
                        error = dtcs.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    ),
                )
            }
        }
    }
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
