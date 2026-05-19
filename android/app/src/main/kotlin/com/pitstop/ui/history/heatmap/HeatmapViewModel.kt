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

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val secrets = runCatching { settings.current() }.getOrNull() ?: run {
                _state.update { it.copy(loading = false, error = "settings unavailable") }
                return@launch
            }
            val slug = secrets.settings.vehicleSlug.trim()
            if (slug.isEmpty() || secrets.settings.apiBaseUrl.isBlank()) {
                _state.update { it.copy(error = "Set vehicle + server in Settings") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val vehicles = api.getVehicles()
                val v = vehicles.firstOrNull { it.slug == slug }
                    ?: error("vehicle slug '$slug' not found on server")
                api.getRouteTrace(v.id, maxPoints = 25_000)
            }.onSuccess { resp ->
                _state.update {
                    HeatmapUiState(
                        loading = false,
                        points = resp.points,
                        total = resp.total,
                        stride = resp.stride,
                    )
                }
                logBuffer.info(
                    "heatmap loaded",
                    mapOf("count" to resp.count, "total" to resp.total, "stride" to resp.stride),
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
}
