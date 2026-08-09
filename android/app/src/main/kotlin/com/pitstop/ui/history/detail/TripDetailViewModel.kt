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
     * Flag or unflag this trip as towing.
     *
     * Optimistic: the switch moves immediately and reverts if the server
     * rejects it. A toggle that waits on a round trip reads as broken on a
     * phone that may be on cellular, and the cost of being wrong here is one
     * boolean the user can flip again.
     */
    fun setTowing(value: Boolean) {
        val current = _ui.value.trip ?: return
        _ui.update { it.copy(trip = current.copy(isTowing = value)) }
        viewModelScope.launch {
            runCatching { api.updateTrip(current.id, com.pitstop.http.TripUpdateRequest(isTowing = value)) }
                .onFailure { e ->
                    _ui.update { it.copy(trip = current) }
                    logBuffer.warn(
                        "trip-detail: towing toggle failed",
                        mapOf("trip_id" to current.id, "err" to (e.message ?: e::class.java.simpleName)),
                    )
                }
        }
    }

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
        }
    }
}
