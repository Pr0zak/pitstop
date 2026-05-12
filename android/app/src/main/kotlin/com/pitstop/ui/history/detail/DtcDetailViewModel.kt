package com.pitstop.ui.history.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.http.DtcTimelineCode
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DtcDetailUi(
    val loading: Boolean = true,
    val error: String? = null,
    val code: String = "",
    val vehicleId: String = "",
    val entry: DtcTimelineCode? = null,
)

@HiltViewModel
class DtcDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: PitstopApi,
    private val logBuffer: LogBuffer,
) : ViewModel() {

    private val code: String = savedStateHandle.get<String>("code")
        ?: error("dtc code missing from navigation args")
    private val vehicleId: String = savedStateHandle.get<String>("vehicleId").orEmpty()

    private val _ui = MutableStateFlow(
        DtcDetailUi(loading = true, code = code, vehicleId = vehicleId),
    )
    val ui: StateFlow<DtcDetailUi> = _ui.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            // Without a vehicle context we can't query the timeline
            // (the endpoint requires vehicle_id). Surface a friendly
            // error so the user can scrub back.
            if (vehicleId.isBlank()) {
                _ui.update {
                    it.copy(
                        loading = false,
                        error = "Need a vehicle context to load timeline",
                    )
                }
                return@launch
            }
            val result = runCatching {
                api.getDtcTimeline(vehicleId = vehicleId, days = 365)
            }
            val response = result.getOrNull()
            if (response == null) {
                val err = result.exceptionOrNull()
                logBuffer.warn(
                    "dtc-detail: load failed",
                    mapOf(
                        "code" to code,
                        "vehicle_id" to vehicleId,
                        "err" to (err?.message ?: err?.javaClass?.simpleName ?: "unknown"),
                    ),
                )
                _ui.update {
                    it.copy(
                        loading = false,
                        error = err?.message ?: "Couldn't load DTC history",
                    )
                }
                return@launch
            }
            val entry = response.codes.firstOrNull { it.code == code }
            _ui.update {
                it.copy(
                    loading = false,
                    error = null,
                    entry = entry,
                )
            }
        }
    }
}
