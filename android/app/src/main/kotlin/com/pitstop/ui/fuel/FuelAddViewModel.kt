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
import java.time.Instant
import javax.inject.Inject

data class FuelFormState(
    val gallons: String = "",
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
            refreshGps()
        }
    }

    fun update(transform: (FuelFormState) -> FuelFormState) {
        _form.value = transform(_form.value).copy(
            errorMessage = null,
            submittedId = null,
        )
        // Recompute station suggestions live.
        val q = _form.value.stationName
        _form.value = _form.value.copy(
            stationSuggestions = historyStore.stationSuggestions(allHistory, q),
        )
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
            val request = FillupRequest(
                vehicleSlug = settings.vehicleSlug,
                timestampIso = Instant.now().toString(),
                gallons = gallons,
                totalPrice = totalPrice,
                odometerMi = f.odometer.toDoubleOrNull(),
                partial = f.partial,
                lat = f.gps?.lat,
                lon = f.gps?.lon,
                stationName = f.stationName.ifBlank { null },
                notes = f.notes.ifBlank { null },
            )
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
