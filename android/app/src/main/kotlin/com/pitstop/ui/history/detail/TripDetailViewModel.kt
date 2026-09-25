package com.pitstop.ui.history.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.http.PitstopApi
import com.pitstop.http.RoutePointDto
import com.pitstop.http.TripDetailDto
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persisted timeline series selection. [loaded] false means DataStore has
 * not emitted yet; [metrics] null means the user has never chosen. Only
 * `loaded && metrics != null` may override the per-metric defaults.
 */
data class StoredSeries(
    val loaded: Boolean,
    val metrics: Set<String>?,
)

data class TripDetailUi(
    val loading: Boolean = true,
    val error: String? = null,
    val trip: TripDetailDto? = null,
    val route: List<RoutePointDto> = emptyList(),
    /** Same-distance-bucket averages; null until loaded or when the bucket
     *  has too few trips to compare against (the card hides then). */
    val baseline: com.pitstop.http.TripBaselineDto? = null,
    /** Set once DELETE /trips/{id} succeeded — the screen pops back. */
    val deleted: Boolean = false,
    val deleting: Boolean = false,
    /** A user-facing failure for the snackbar (save / delete). */
    val message: String? = null,
)

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: PitstopApi,
    private val logBuffer: LogBuffer,
    private val settingsRepository: com.pitstop.data.SettingsRepository,
) : ViewModel() {

    private val tripId: String = savedStateHandle.get<String>("id")
        ?: error("trip id missing from navigation args")

    private val _ui = MutableStateFlow(TripDetailUi(loading = true))
    val ui: StateFlow<TripDetailUi> = _ui.asStateFlow()

    /**
     * Imperial / metric preference — the timeline chart converts its
     * series and labels it with this (same mechanism as LiveScreen).
     */
    val unitSystem: StateFlow<String> = settingsRepository.settings
        .map { it.unitSystem }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            "imperial",
        )

    /**
     * Series the user last had on the timeline, or null while DataStore is
     * still loading / has never been written. The screen must distinguish
     * those two from an empty set — see [SettingsRepository.tripSeriesMetrics]
     * — so this is wrapped rather than defaulted to emptySet().
     */
    val storedSeries: StateFlow<StoredSeries> = settingsRepository.tripSeriesMetrics
        .map { StoredSeries(loaded = true, metrics = it) }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            StoredSeries(loaded = false, metrics = null),
        )

    /**
     * Save the Edit sheet — towing, category and notes in ONE PATCH, only
     * the fields that changed. Optimistic like the toggles above. A cleared
     * text field is sent as JSON null (the backend PATCH honours explicit
     * nulls via exclude_unset); the shared Json omits nulls from data
     * classes, so this goes out as a raw object.
     */
    fun saveDetails(isTowing: Boolean, category: String?, notes: String?) {
        val current = _ui.value.trip ?: return
        val cat = category?.trim()?.ifBlank { null }
        val note = notes?.trim()?.ifBlank { null }
        val body = kotlinx.serialization.json.buildJsonObject {
            if (isTowing != current.isTowing) put("is_towing", kotlinx.serialization.json.JsonPrimitive(isTowing))
            if (cat != current.category?.ifBlank { null }) put("category", kotlinx.serialization.json.JsonPrimitive(cat))
            if (note != current.notes?.ifBlank { null }) put("notes", kotlinx.serialization.json.JsonPrimitive(note))
        }
        if (body.isEmpty()) return
        _ui.update { it.copy(trip = current.copy(isTowing = isTowing, category = cat, notes = note)) }
        viewModelScope.launch {
            runCatching { api.patchTrip(current.id, body) }.onFailure { e ->
                _ui.update { it.copy(trip = current, message = "Couldn't save the trip details") }
                logBuffer.warn(
                    "trip-detail: details save failed",
                    mapOf("trip_id" to current.id, "err" to (e.message ?: e::class.java.simpleName)),
                )
            }
        }
    }

    /** DELETE the trip (after the screen's confirm dialog). */
    fun delete() {
        val current = _ui.value.trip ?: return
        if (_ui.value.deleting) return
        _ui.update { it.copy(deleting = true) }
        viewModelScope.launch {
            runCatching { api.deleteTrip(current.id) }
                .onSuccess {
                    logBuffer.info("trip-detail: deleted", mapOf("trip_id" to current.id))
                    _ui.update { it.copy(deleting = false, deleted = true) }
                }
                .onFailure { e ->
                    logBuffer.warn(
                        "trip-detail: delete failed",
                        mapOf("trip_id" to current.id, "err" to (e.message ?: e::class.java.simpleName)),
                    )
                    _ui.update { it.copy(deleting = false, message = "Couldn't delete this trip") }
                }
        }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }

    /** Persist an explicit user toggle. Never called for a fallback. */
    fun setSeries(metrics: Set<String>) {
        viewModelScope.launch { settingsRepository.setTripSeriesMetrics(metrics) }
    }

    init {
        load()
    }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val tripDeferred = async {
                runCatching { api.getTripDetail(tripId) }
            }
            val routeDeferred = async {
                // Route is best-effort; pre-GPS trips return an empty
                // list and the screen falls back to no-map.
                runCatching { api.getTripRoute(tripId) }
            }
            val tripResult = tripDeferred.await()
            val routeResult = routeDeferred.await()
            val trip = tripResult.getOrNull()
            val route = routeResult.getOrNull()
            val err = tripResult.exceptionOrNull()
            if (trip == null) {
                logBuffer.warn(
                    "trip-detail: load failed",
                    mapOf(
                        "trip_id" to tripId,
                        "err" to (err?.message ?: err?.javaClass?.simpleName ?: "unknown"),
                    ),
                )
                _ui.update {
                    it.copy(
                        loading = false,
                        error = err?.message ?: "Couldn't load trip",
                    )
                }
                return@launch
            }
            _ui.update {
                TripDetailUi(
                    loading = false,
                    error = null,
                    trip = trip,
                    route = route?.points ?: emptyList(),
                )
            }
            // Baseline is decoration: fetched after the trip renders, and a
            // failure (or an older backend) just leaves the card hidden.
            val km = trip.distanceKm
            if (km != null && km > 0.0) {
                val baseline = runCatching { api.getTripBaseline(trip.vehicleId, km) }.getOrNull()
                if (baseline?.sufficient == true) _ui.update { it.copy(baseline = baseline) }
            }
        }
    }
}
