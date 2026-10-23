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

data class InsightsUi(
    /** The latest response; null until the first load lands. */
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
 * slow first build mid-flight. Home's entry card, Car's entry card and the
 * screen all read the same response. Each card picks its own data window on
 * the server, so there is one response per vehicle.
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

    private var loadedAtMs = 0L
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            var last: String? = null
            activeVehicle.slug.collect { slug ->
                val switched = last != null && last != slug
                last = slug
                if (switched) {
                    loadedAtMs = 0L
                    _ui.update { it.copy(data = null, error = null) }
                }
                load(force = false)
            }
        }
    }

    /** Reload when the result is older than the server's cache. */
    fun refreshIfStale() {
        if (loadJob?.isActive == true) return
        val data = _ui.value.data
        val age = System.currentTimeMillis() - loadedAtMs
        val pending = (data?.pendingTrips ?: 0.0) > 0
        when {
            data == null || age > STALE_MS -> load(force = false)
            // Trips were still being summarised: rebuild past the server's
            // 10-minute cache so the cards that were waiting fill in.
            pending && age > PENDING_STALE_MS -> load(force = true)
        }
    }

    /** The top bar's refresh: the server rebuilds instead of serving its cache. */
    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        loadJob?.cancel()
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
                    tz = ZoneId.systemDefault().id,
                    refresh = if (force) true else null,
                    cacheControl = cc,
                )
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val body = result.getOrNull()
            if (body != null) {
                loadedAtMs = System.currentTimeMillis()
                _ui.update { it.copy(data = body, loading = false, error = null) }
            } else {
                val e = result.exceptionOrNull()
                logs.warn("insights: load failed", mapOf("err" to (e?.message ?: "?")))
                val msg = when {
                    e is NoVehicle -> e.message
                    e is HttpException && e.code() == 404 -> "This server doesn't have Insights yet. Update the server to see them."
                    else -> "Couldn't reach the server"
                }
                _ui.update { it.copy(loading = false, error = msg) }
            }
        }
    }

    private class NoVehicle(override val message: String) : Exception(message)

    private companion object {
        /** Matches the server's 10-minute cache. */
        const val STALE_MS = 10 * 60_000L

        /** Re-ask sooner while the server reports trips still being analysed. */
        const val PENDING_STALE_MS = 2 * 60_000L
    }
}
