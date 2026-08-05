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
    settingsRepository: com.pitstop.data.SettingsRepository,
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
