package com.pitstop.ui.tires

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.ActiveVehicle
import com.pitstop.data.VehicleDirectory
import com.pitstop.domain.TireHistory
import com.pitstop.http.PitstopApi
import com.pitstop.http.TireAnalyticsDto
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

data class TireDetailUi(
    /** The selected range in days (one of [TireHistory.RANGES]). */
    val days: Int = TireHistory.DEFAULT_DAYS,
    /** The response for [days]; the previous range's stays up while a new one loads. */
    val data: TireAnalyticsDto? = null,
    val loading: Boolean = false,
    /** Plain-language failure. */
    val error: String? = null,
)

/**
 * Home → Tires detail. One GET /analytics/tires per range for the active
 * vehicle; the server does every computation (ADR-013), the screen formats.
 */
@HiltViewModel
class TireDetailViewModel @Inject constructor(
    private val api: PitstopApi,
    private val activeVehicle: ActiveVehicle,
    private val directory: VehicleDirectory,
    private val logs: LogBuffer,
) : ViewModel() {

    private val _ui = MutableStateFlow(TireDetailUi())
    val ui: StateFlow<TireDetailUi> = _ui.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(force = false)
    }

    fun setDays(days: Int) {
        if (days == _ui.value.days && _ui.value.data != null) return
        _ui.update { it.copy(days = days) }
        load(force = false)
    }

    /** Pull-to-refresh / top-bar refresh: skip OkHttp's 60 s cache. */
    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val days = _ui.value.days
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val result = runCatching {
                val slug = activeVehicle.current()
                val cc = if (force) "no-cache" else null
                val vehicles = directory.vehicles.value.takeIf { v -> v.any { it.slug == slug } }
                    ?: directory.refresh(cc)
                val v = vehicles.firstOrNull { it.slug == slug }
                    ?: throw NoVehicle(if (slug.isBlank()) "Pick a vehicle in Settings" else "Vehicle \"$slug\" not on server")
                api.getTireAnalytics(vehicleId = v.id, days = days, cacheControl = cc)
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val body = result.getOrNull()
            if (body != null) {
                _ui.update { it.copy(data = body, loading = false, error = null) }
            } else {
                val e = result.exceptionOrNull()
                logs.warn("tires: load failed", mapOf("err" to (e?.message ?: "?"), "days" to days.toString()))
                val msg = when {
                    e is NoVehicle -> e.message
                    e is HttpException && e.code() == 404 -> "This server doesn't have tire history yet. Update the server to see it."
                    else -> "Couldn't reach the server"
                }
                _ui.update { it.copy(loading = false, error = msg) }
            }
        }
    }

    private class NoVehicle(override val message: String) : Exception(message)
}
