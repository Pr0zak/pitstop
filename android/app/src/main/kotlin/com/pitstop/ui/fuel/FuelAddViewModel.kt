package com.pitstop.ui.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.http.FillupRequest
import com.pitstop.http.PitstopApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

data class FuelFormState(
    val gallons: String = "",
    val pricePerGallon: String = "",
    val totalPrice: String = "",
    val odometer: String = "",
    val partial: Boolean = false,
    val stationName: String = "",
    val notes: String = "",
    val photoUri: String? = null,
    val gps: GpsFix? = null,
    val gpsRefreshing: Boolean = false,
    val stationSuggestions: List<String> = emptyList(),
    val submitting: Boolean = false,
    val submittedId: String? = null,
    val errorMessage: String? = null,
    val nearestPriorStation: String? = null,
    val lastOdometer: Double? = null,
)

@HiltViewModel
class FuelAddViewModel @Inject constructor(
    private val locationProvider: LocationProvider,
    private val historyStore: FuelHistoryStore,
    private val settingsRepository: SettingsRepository,
    private val api: PitstopApi,
) : ViewModel() {

    private val _form = MutableStateFlow(FuelFormState())
    val form: StateFlow<FuelFormState> = _form.asStateFlow()

    private var allHistory: List<HistoricFillup> = emptyList()

    init {
        viewModelScope.launch {
            allHistory = historyStore.all()
            // Surface the most recent odometer reading from history so the
            // "Last value: 76,304 mi" hint matches the design's reference.
            val lastOdo = allHistory.firstOrNull()?.let { null } // history doesn't carry odo today
            _form.value = _form.value.copy(lastOdometer = lastOdo)
            refreshGps()
        }
    }

    fun update(transform: (FuelFormState) -> FuelFormState) {
        val next = transform(_form.value).copy(
            errorMessage = null,
            submittedId = null,
        )
        _form.value = next
        // Recompute station suggestions live.
        val q = _form.value.stationName
        _form.value = _form.value.copy(
            stationSuggestions = historyStore.stationSuggestions(allHistory, q),
        )
    }

    /**
     * Update gallons and propagate the totalPrice = gallons × pricePerGallon
     * relationship — whichever two fields the user has filled, the third
     * derives. Mirrors the auto-fill behaviour Fuelio's refuelling screen
     * has where editing any of {gal, price/gal, total} updates the others.
     */
    fun setGallons(value: String) {
        val gal = value.toDoubleOrNull()
        val ppg = _form.value.pricePerGallon.toDoubleOrNull()
        val derivedTotal =
            if (gal != null && gal > 0 && ppg != null && ppg > 0)
                "%.2f".format(gal * ppg)
            else _form.value.totalPrice
        update { it.copy(gallons = value, totalPrice = derivedTotal) }
    }

    fun setPricePerGallon(value: String) {
        val ppg = value.toDoubleOrNull()
        val gal = _form.value.gallons.toDoubleOrNull()
        val derivedTotal =
            if (gal != null && gal > 0 && ppg != null && ppg > 0)
                "%.2f".format(gal * ppg)
            else _form.value.totalPrice
        update { it.copy(pricePerGallon = value, totalPrice = derivedTotal) }
    }

    fun setTotalPrice(value: String) {
        val total = value.toDoubleOrNull()
        val gal = _form.value.gallons.toDoubleOrNull()
        val derivedPpg =
            if (total != null && total > 0 && gal != null && gal > 0)
                "%.3f".format(total / gal)
            else _form.value.pricePerGallon
        update { it.copy(totalPrice = value, pricePerGallon = derivedPpg) }
    }

    fun refreshGps() {
        viewModelScope.launch {
            _form.value = _form.value.copy(gpsRefreshing = true)
            val fix = locationProvider.fix()
            val nearest = fix?.let { historyStore.nearestRecent(it.lat, it.lon) }
            _form.value = _form.value.copy(
                gps = fix,
                gpsRefreshing = false,
                nearestPriorStation = nearest?.stationName,
            )
        }
    }

    fun applyNearestStation() {
        val current = _form.value
        val name = current.nearestPriorStation ?: return
        _form.value = current.copy(stationName = name)
    }

    fun submit() {
        viewModelScope.launch {
            val f = _form.value
            val gallons = f.gallons.toDoubleOrNull()
            val totalPrice = f.totalPrice.toDoubleOrNull()
            if (gallons == null || gallons <= 0) {
                _form.value = f.copy(errorMessage = "Enter gallons")
                return@launch
            }
            if (totalPrice == null || totalPrice < 0) {
                _form.value = f.copy(errorMessage = "Enter total price")
                return@launch
            }
            val settings = settingsRepository.current().settings
            if (settings.vehicleSlug.isBlank() || settings.apiBaseUrl.isBlank()) {
                _form.value = f.copy(errorMessage = "Configure vehicle slug + API URL first")
                return@launch
            }

            // Auto-refresh GPS at submit time so the saved fillup has the
            // most recent fix available. Bound at 4 s — phone hasn't moved
            // since the user opened the form, so a stale fix is fine if
            // the GPS provider is slow. We don't fail the save on a
            // missing fix; the existing f.gps falls through.
            val freshGps = withTimeoutOrNull(4_000) { locationProvider.fix() }
            val gpsForRequest = freshGps ?: f.gps

            val request = FillupRequest(
                vehicleSlug = settings.vehicleSlug,
                timestampIso = Instant.now().toString(),
                gallons = gallons,
                totalPrice = totalPrice,
                odometerMi = f.odometer.toDoubleOrNull(),
                partial = f.partial,
                lat = gpsForRequest?.lat,
                lon = gpsForRequest?.lon,
                stationName = f.stationName.ifBlank { null },
                notes = f.notes.ifBlank { null },
            )
            // Reflect the fresh fix back into the form so the user sees
            // the coords that landed on the server.
            if (freshGps != null) {
                _form.value = _form.value.copy(gps = freshGps)
            }
            // After a successful submit we want the form to come back fresh
            // for the next visit, but keep the last-odo hint and station
            // suggestions populated.
            _form.value = f.copy(submitting = true, errorMessage = null)
            try {
                val response = api.postFillup(request)
                historyStore.add(
                    HistoricFillup(
                        tsMs = System.currentTimeMillis(),
                        stationName = f.stationName.ifBlank { null },
                        lat = f.gps?.lat,
                        lon = f.gps?.lon,
                    ),
                )
                allHistory = historyStore.all()
                _form.value = f.copy(submitting = false, submittedId = response.id)
            } catch (e: Exception) {
                _form.value = f.copy(submitting = false, errorMessage = e.message ?: "Submit failed")
            }
        }
    }
}
