package com.pitstop.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.ActiveVehicle
import com.pitstop.data.VehicleDirectory
import com.pitstop.http.InsightsResponseDto
import com.pitstop.http.PitstopApi
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
import java.time.ZoneId
import javax.inject.Inject

/** The look-back windows the Insights screen offers, in days. */
enum class InsightsWindow(val days: Int, val label: String) {
    ThreeMonths(90, "3 mo"),
    SixMonths(180, "6 mo"),
    OneYear(365, "1 yr"),
    All(3650, "All"),
}

data class InsightsUi(
    val window: InsightsWindow = InsightsWindow.OneYear,
    /** The response for [window]; null until the first load lands. */
    val data: InsightsResponseDto? = null,
    val loading: Boolean = false,
    /** Plain-language failure; shown over [data] when there is some. */
    val error: String? = null,
)

/**
 * Car → Insights, and the Car tab's entry card.
 *
 * Activity-scoped by both callers (`hiltViewModel(activity)`): a tab's
 * ViewModel is disposed when the pager swipes away, which would cancel a
 * slow first build mid-flight. One response is kept per window so the
 * chips switch instantly once each has loaded.
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val api: PitstopApi,
    private val activeVehicle: ActiveVehicle,
    private val directory: VehicleDirectory,
    private val logs: LogBuffer,
) : ViewModel() {

    private val _ui = MutableStateFlow(InsightsUi())
    val ui: StateFlow<InsightsUi> = _ui.asStateFlow()

    private val cache = mutableMapOf<InsightsWindow, Pair<Long, InsightsResponseDto>>()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            var last: String? = null
            activeVehicle.slug.collect { slug ->
                val switched = last != null && last != slug
                last = slug
                if (switched) {
                    cache.clear()
                    _ui.update { it.copy(data = null, error = null) }
                }
                load(force = false)
            }
        }
    }

    /** Reload when the shown window's result is older than the server's cache. */
    fun refreshIfStale() {
        if (loadJob?.isActive == true) return
        val hit = cache[_ui.value.window]
        if (hit == null || System.currentTimeMillis() - hit.first > STALE_MS) load(force = false)
    }

    /** The top bar's refresh: the server rebuilds instead of serving its cache. */
    fun refresh() = load(force = true)

    fun selectWindow(window: InsightsWindow) {
        if (window == _ui.value.window) return
        _ui.update { it.copy(window = window, data = cache[window]?.second, loading = false, error = null) }
        loadJob?.cancel()
        loadJob = null
        refreshIfStale()
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val window = _ui.value.window
        val cc = if (force) "no-cache" else null
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val result = runCatching {
                val slug = activeVehicle.current()
                val vehicles = directory.vehicles.value.takeIf { v -> v.any { it.slug == slug } }
                    ?: directory.refresh(cc)
                val v = vehicles.firstOrNull { it.slug == slug }
                    ?: throw NoVehicle(if (slug.isBlank()) "Pick a vehicle in Settings" else "Vehicle \"$slug\" not on server")
                api.getInsights(
                    vehicleId = v.id,
                    days = window.days,
                    tz = ZoneId.systemDefault().id,
                    refresh = if (force) true else null,
                    cacheControl = cc,
                )
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val body = result.getOrNull()
            if (body != null) {
                cache[window] = System.currentTimeMillis() to body
                // The user may have switched windows while this was in flight.
                _ui.update { s -> if (s.window == window) s.copy(data = body, loading = false, error = null) else s }
            } else {
                val e = result.exceptionOrNull()
                logs.warn("insights: load failed", mapOf("err" to (e?.message ?: "?"), "days" to window.days.toString()))
                val msg = when {
                    e is NoVehicle -> e.message
                    e is HttpException && e.code() == 404 -> "This server doesn't have Insights yet. Update the server to see them."
                    else -> "Couldn't reach the server"
                }
                _ui.update { s -> if (s.window == window) s.copy(loading = false, error = msg) else s }
            }
        }
    }

    private class NoVehicle(override val message: String) : Exception(message)

    private companion object {
        /** Matches the server's 10-minute cache. */
        const val STALE_MS = 10 * 60_000L
    }
}
