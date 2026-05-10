package com.pitstop.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.http.DtcDto
import com.pitstop.http.FillupDto
import com.pitstop.http.PitstopApi
import com.pitstop.http.TripDto
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    init {
        refresh()
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
