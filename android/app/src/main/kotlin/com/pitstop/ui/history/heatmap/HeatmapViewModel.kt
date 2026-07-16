package com.pitstop.ui.history.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HeatmapUiState(
    val loading: Boolean = false,
    val error: String? = null,
    /** Raw route-trace points: [lat, lon, speed_mps, epoch_seconds]. */
    val points: List<List<Double>> = emptyList(),
    val total: Int = 0,
    val stride: Int = 1,
    /** Fillup markers — [lat, lon] for each historical fillup that
     *  carries a non-null GPS pair. Rendered as a CircleLayer on top
     *  of the route polylines when the Stations chip is on. */
    val stations: List<Pair<Double, Double>> = emptyList(),
)

@HiltViewModel
class HeatmapViewModel @Inject constructor(
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
) : ViewModel() {

    private val _state = MutableStateFlow(HeatmapUiState())
    val state: StateFlow<HeatmapUiState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(HeatmapMode.Density)
    val mode: StateFlow<HeatmapMode> = _mode.asStateFlow()

    private val _showStations = MutableStateFlow(false)
    val showStations: StateFlow<Boolean> = _showStations.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val secrets = runCatching { settings.current() }.getOrNull() ?: run {
                _state.update { it.copy(loading = false, error = "settings unavailable") }
                return@launch
            }
            val slug = secrets.settings.vehicleSlug.trim()
            if (slug.isEmpty() || secrets.settings.apiBaseUrl.isBlank()) {
                // Clear loading too — otherwise a refresh fired while a prior
                // load is still in flight strands the pull-to-refresh spinner.
                _state.update { it.copy(loading = false, error = "Set vehicle + server in Settings") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val vehicles = api.getVehicles()
                val v = vehicles.firstOrNull { it.slug == slug }
                    ?: error("vehicle slug '$slug' not found on server")
                val trace = api.getRouteTrace(v.id, maxPoints = 25_000)
                // Pull every fillup (capped at 500 — even heavy users
                // are well under that) and keep only those with a real
                // GPS pair. Fast; lives in the same load pass so the
                // Stations toggle responds instantly.
                val fillups = runCatching { api.getFillups(v.id, limit = 500) }
                    .getOrDefault(emptyList())
                val stations = fillups.mapNotNull { f ->
                    val lat = f.lat
                    val lon = f.lon
                    if (lat != null && lon != null) lat to lon else null
                }
                trace to stations
            }.onSuccess { (resp, stations) ->
                _state.update {
                    HeatmapUiState(
                        loading = false,
                        points = resp.points,
                        total = resp.total,
                        stride = resp.stride,
                        stations = stations,
                    )
                }
                logBuffer.info(
                    "heatmap loaded",
                    mapOf(
                        "count" to resp.count,
                        "total" to resp.total,
                        "stride" to resp.stride,
                        "stations" to stations.size,
                    ),
                )
            }.onFailure { t ->
                val msg = t.message ?: t::class.java.simpleName
                logBuffer.warn("heatmap fetch failed", mapOf("err" to msg))
                _state.update { it.copy(loading = false, error = msg) }
            }
        }
    }

    fun setMode(m: HeatmapMode) {
        _mode.value = m
    }

    fun toggleStations() {
        _showStations.value = !_showStations.value
    }
}
