package com.pitstop.ui.history.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.http.FillupDto
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FillupDetailUi(
    val loading: Boolean = true,
    val error: String? = null,
    val fillup: FillupDto? = null,
    /** Other fillups for the same vehicle, most-recent-first, used
     *  for the MPG trend chart and "cost per mile since previous"
     *  derived metrics. Capped at ~20 entries. */
    val context: List<FillupDto> = emptyList(),
)

@HiltViewModel
class FillupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: PitstopApi,
    private val logBuffer: LogBuffer,
) : ViewModel() {

    private val fillupId: String = savedStateHandle.get<String>("id")
        ?: error("fillup id missing from navigation args")

    private val _ui = MutableStateFlow(FillupDetailUi(loading = true))
    val ui: StateFlow<FillupDetailUi> = _ui.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val detailDeferred = async {
                runCatching { api.getFillupDetail(fillupId) }
            }
            val detail = detailDeferred.await().getOrNull()
            val err = detailDeferred.await().exceptionOrNull()
            if (detail == null) {
                logBuffer.warn(
                    "fillup-detail: load failed",
                    mapOf(
                        "fillup_id" to fillupId,
                        "err" to (err?.message ?: err?.javaClass?.simpleName ?: "unknown"),
                    ),
                )
                _ui.update {
                    it.copy(
                        loading = false,
                        error = err?.message ?: "Couldn't load fillup",
                    )
                }
                return@launch
            }
            // Fetch context for the MPG trend chart — last 20 fills
            // for this vehicle. Best-effort; empty on failure.
            val context = runCatching {
                api.getFillups(vehicleId = detail.vehicleId, limit = 20)
            }.getOrElse { emptyList() }

            _ui.update {
                FillupDetailUi(
                    loading = false,
                    error = null,
                    fillup = detail,
                    context = context,
                )
            }
        }
    }
}
