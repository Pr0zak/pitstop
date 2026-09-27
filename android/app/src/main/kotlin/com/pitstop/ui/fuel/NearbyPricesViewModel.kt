package com.pitstop.ui.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.ActiveVehicle
import com.pitstop.data.SettingsRepository
import com.pitstop.data.VehicleDirectory
import com.pitstop.domain.FuelGrade
import com.pitstop.http.NearbyPricesDto
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** What the Fuel tab's "Nearby prices" card renders. */
data class NearbyPricesUi(
    val grade: FuelGrade = FuelGrade.Regular,
    val loading: Boolean = false,
    /** Last answer for [grade]; kept on screen through a refresh. */
    val result: NearbyPricesDto? = null,
    /** A transport failure (the server never answered with a `status`). */
    val error: String? = null,
    val expanded: Boolean = false,
    /** Whether the app may read location — shapes the no_location copy. */
    val hasLocationPermission: Boolean = true,
    /** Wall-clock time of the last completed load, for [NearbyPricesViewModel.refreshIfStale]. */
    val loadedAtMs: Long? = null,
)

/**
 * Backs the "Nearby prices" card (ADR-026). Activity-scoped by its caller:
 * the Fuel page is disposed on every tab switch, and a lookup in flight
 * must not die with it.
 *
 * The phone sends its own fix when it has one; without a permission or a
 * fix it sends no lat/lon and the server falls back to the car's last GPS
 * point, then home. A fix is reused for a few minutes so flipping grade
 * chips doesn't wake the GPS each time; an explicit refresh takes a new one.
 */
@HiltViewModel
class NearbyPricesViewModel @Inject constructor(
    private val api: PitstopApi,
    private val settings: SettingsRepository,
    private val activeVehicle: ActiveVehicle,
    private val directory: VehicleDirectory,
    private val locationProvider: LocationProvider,
    private val logBuffer: LogBuffer,
) : ViewModel() {

    private val _ui = MutableStateFlow(NearbyPricesUi())
    val ui: StateFlow<NearbyPricesUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var lastFix: GpsFix? = null
    private var lastFixAtMs = 0L
    private var loadedSlug: String? = null

    init {
        // A top-bar vehicle switch changes the history join and the
        // vehicle-GPS fallback — reload, but only once the card has been
        // looked at (no lookup for a tab the user never opened).
        viewModelScope.launch {
            activeVehicle.slug.collect { slug ->
                if (loadedSlug != null && slug != loadedSlug) refresh()
            }
        }
    }

    /** Load when the card is first shown, or when the last load is stale. */
    fun refreshIfStale() {
        val s = _ui.value
        val at = s.loadedAtMs
        if (s.loading) return
        if (s.result != null && at != null && System.currentTimeMillis() - at < STALE_AFTER_MS) return
        refresh()
    }

    /**
     * [forceNetwork] = an explicit gesture: take a new fix and send
     * `Cache-Control: no-cache`, or OkHttp's 60 s max-age would replay
     * the previous answer and the refresh would look dead.
     */
    fun refresh(forceNetwork: Boolean = false) {
        job?.cancel()
        _ui.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            if (settings.current().settings.apiBaseUrl.isBlank()) {
                _ui.update { it.copy(loading = false, error = "Set up the server in Settings") }
                return@launch
            }
            val slug = activeVehicle.current()
            val vehicleId = directory.bySlug(slug)?.id
                ?: runCatching { directory.refresh() }.getOrNull()?.firstOrNull { it.slug == slug }?.id
            val permitted = locationProvider.hasPermission()
            val fix = if (permitted) currentFix(fresh = forceNetwork) else null
            val grade = _ui.value.grade
            val result = runCatching {
                api.getNearbyFuelPrices(
                    vehicleId = vehicleId,
                    lat = fix?.lat,
                    lon = fix?.lon,
                    grade = grade.apiValue,
                    cacheControl = if (forceNetwork) "no-cache" else null,
                )
            }
            loadedSlug = slug
            result.onSuccess { dto ->
                _ui.update {
                    // A chip tapped mid-flight already started its own load.
                    if (it.grade != grade) {
                        it
                    } else {
                        it.copy(
                            loading = false,
                            result = dto,
                            error = null,
                            hasLocationPermission = permitted,
                            loadedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logBuffer.warn(
                    "nearby prices: fetch failed",
                    mapOf("err" to (e.message ?: e::class.java.simpleName)),
                )
                _ui.update {
                    it.copy(
                        loading = false,
                        error = failureMessage(e),
                        hasLocationPermission = permitted,
                    )
                }
            }
        }
    }

    fun setGrade(grade: FuelGrade) {
        if (grade == _ui.value.grade) return
        // Drop the other grade's rows: its prices must never sit under this chip.
        _ui.update { it.copy(grade = grade, result = null, expanded = false) }
        refresh()
    }

    fun toggleExpanded() {
        _ui.update { it.copy(expanded = !it.expanded) }
    }

    private suspend fun currentFix(fresh: Boolean): GpsFix? {
        val now = System.currentTimeMillis()
        lastFix?.let { if (!fresh && now - lastFixAtMs < FIX_REUSE_MS) return it }
        // getCurrentLocation can sit for a long time indoors; past this the
        // server's own fallback (car GPS / home) is the better answer.
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { locationProvider.fix() }
        if (fix != null) {
            lastFix = fix
            lastFixAtMs = now
        }
        return fix ?: lastFix?.takeIf { now - lastFixAtMs < FIX_REUSE_MS }
    }

    private fun failureMessage(e: Throwable): String = when {
        e is HttpException && e.code() == 404 -> "This server doesn't have live prices yet — update pitstop"
        e is HttpException && (e.code() == 401 || e.code() == 403) -> "The server rejected the Query token"
        e is HttpException -> "Server error ${e.code()}"
        e is IOException -> "Couldn't reach the server"
        else -> "Couldn't load prices"
    }

    private companion object {
        const val STALE_AFTER_MS = 5 * 60_000L
        const val FIX_REUSE_MS = 5 * 60_000L
        const val FIX_TIMEOUT_MS = 6_000L
    }
}
