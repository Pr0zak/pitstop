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
import java.util.Locale
import javax.inject.Inject

data class FuelFormState(
    val gallons: String = "",
    val pricePerGallon: String = "",
    val totalPrice: String = "",
    val odometer: String = "",
    val odometerAutoFilled: Boolean = false,
    val partial: Boolean = false,
    val isMissed: Boolean = false,
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

    // Vehicle selection
    val vehicles: List<VehicleOption> = emptyList(),
    val selectedVehicleSlug: String = "",

    // Gas type — Fuelio's enum-ish:
    //   100 = Regular 87, 101 = Mid 89, 102 = Premium 91, 103 = Premium 93,
    //   200 = Diesel, 300 = E85, 400 = LPG / propane
    val fuelType: Int = 100,
)

/** Lightweight vehicle row for the picker dropdown. */
data class VehicleOption(
    val id: String,
    val slug: String,
    val name: String,
    val active: Boolean,
    /** Measured (PCM − dash) odometer difference in km, or null when the
     *  vehicle has never been calibrated. Carried on the option so the
     *  odometer prefill can correct itself when the picker changes
     *  vehicles. See [FuelAddViewModel.autoFillOdometer]. */
    val odometerOffsetKm: Double? = null,
)

/** Fuelio's fuel-type enum + display label. */
data class FuelTypeOption(val code: Int, val label: String)
val FUEL_TYPES: List<FuelTypeOption> = listOf(
    FuelTypeOption(100, "Regular (87)"),
    FuelTypeOption(101, "Mid-grade (89)"),
    FuelTypeOption(102, "Premium (91)"),
    FuelTypeOption(103, "Premium (93)"),
    FuelTypeOption(200, "Diesel"),
    FuelTypeOption(300, "E85"),
    FuelTypeOption(400, "LPG"),
)

@HiltViewModel
class FuelAddViewModel @Inject constructor(
    private val locationProvider: LocationProvider,
    private val historyStore: FuelHistoryStore,
    private val settingsRepository: SettingsRepository,
    private val api: PitstopApi,
    private val stateBus: com.pitstop.service.BridgeStateBus,
    private val logBuffer: com.pitstop.log.LogBuffer,
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
            // Pre-load vehicle list + default selected slug.
            loadVehicles()
            // Auto-prefill odometer from the live OBD bridge if a reading is
            // available. The user can override by editing the field.
            autoFillOdometer()
            refreshGps()
        }
    }

    private suspend fun loadVehicles() {
        val current = settingsRepository.current()
        val defaultSlug = current.settings.vehicleSlug.ifBlank { "" }
        if (current.queryToken.isBlank()) {
            logBuffer.warn("fuel: QUERY token blank; vehicle picker will be empty")
        }
        if (current.settings.apiBaseUrl.isBlank()) {
            logBuffer.warn("fuel: API base URL blank; vehicle picker will be empty")
        }
        val vs = runCatching { api.getVehicles() }.getOrElse { exc ->
            logBuffer.warn(
                "fuel: /api/vehicles fetch failed",
                mapOf(
                    "err" to (exc.message ?: exc::class.java.simpleName),
                    "api_base" to current.settings.apiBaseUrl,
                ),
            )
            emptyList()
        }
        if (vs.isEmpty()) {
            logBuffer.warn(
                "fuel: vehicle list returned empty",
                mapOf("api_base" to current.settings.apiBaseUrl),
            )
        }
        val resolvedSlug = defaultSlug
            .ifBlank { vs.firstOrNull { it.active != false }?.slug ?: "" }
        _form.value = _form.value.copy(
            vehicles = vs.map {
                VehicleOption(
                    id = it.id,
                    slug = it.slug,
                    name = it.name,
                    active = it.active ?: true,
                    odometerOffsetKm = it.odometerOffsetKm,
                )
            },
            selectedVehicleSlug = resolvedSlug,
        )
        // Once the vehicle list is known, fetch the latest fillup for the
        // active selection so the "Last value: X mi" hint and odometer
        // auto-prefill reflect the per-vehicle history (not just the
        // bridge's live OBD reading).
        if (resolvedSlug.isNotBlank()) loadLatestOdoForSlug(resolvedSlug)
        // Retry the autoFill now that we have the vehicle's
        // backend-persisted latest_odo as a fallback for an empty
        // in-process BridgeStateBus (cold app start).
        val selected = vs.firstOrNull { it.slug == resolvedSlug }
        val latestOdoKm = selected?.latest?.get("odometer")?.valueNum
        autoFillOdometer(vehicleLatestOdoKm = latestOdoKm)
    }

    /**
     * Per-vehicle: pull the most-recent fillup row from /fillups, surface
     * its .odo as the lastOdometer hint AND auto-fill the form's odometer
     * if it's currently blank (or only carries an auto-filled value the
     * user hasn't touched). Live OBD odo from the bridge wins when newer
     * than the latest fillup — that's still handled in autoFillOdometer().
     */
    private suspend fun loadLatestOdoForSlug(slug: String) {
        val vehicleId = _form.value.vehicles.firstOrNull { it.slug == slug }?.id ?: return
        val latest = runCatching { api.getFillups(vehicleId, limit = 1) }
            .getOrNull()
            ?.firstOrNull()
            ?: return
        val odoMi = latest.odo
        val current = _form.value
        _form.value = current.copy(
            lastOdometer = odoMi,
            // Prefill if the field is empty or carries an auto-filled
            // value from a previous vehicle's bridge — fresh fillup
            // wins.
            odometer = if (current.odometer.isBlank() || current.odometerAutoFilled) {
                String.format(Locale.US, "%.0f", odoMi)
            } else current.odometer,
            odometerAutoFilled = current.odometer.isBlank() || current.odometerAutoFilled,
        )
    }

    private fun autoFillOdometer(vehicleLatestOdoKm: Double? = null) {
        // Don't clobber a user-edited value.
        if (_form.value.odometer.isNotBlank() && !_form.value.odometerAutoFilled) return
        // Prefer the in-process BridgeStateBus (freshest live reading),
        // fall back to the per-vehicle latest_odo_km the backend gives us
        // — the form was opening blank for users who hadn't driven since
        // the last app launch (in-memory bus was empty).
        val pcmKm = stateBus.latestByMetric.value["odometer"]?.value
            ?: vehicleLatestOdoKm
            ?: return
        // Both of those sources are PCM-sourced, and the number the user is
        // about to confirm is the one on the DASH. The PCM and the
        // instrument cluster are separate modules keeping separate counters
        // — on this Pilot the PCM runs ~51 km (~32 mi) AHEAD. Fillup odos
        // are dash-typed, so prefilling a raw PCM reading pushes the whole
        // offset into the Δodo that recomputed MPG divides by, corrupting
        // that interval AND the next one.
        //
        // `odometer_offset_km` (backend migration 0020) is the user's
        // measured (PCM − dash) difference, so a positive value means the
        // PCM reads high and we subtract it. Null = "not calibrated" ->
        // 0.0, i.e. exactly today's behaviour for every other vehicle.
        //
        // Subtracted BEFORE the plausibility guard below, deliberately: the
        // guard compares against lastOdometer, which comes off a fillup and
        // is therefore already dash-sourced. Guarding a raw PCM number
        // against a dash number would measure the offset, not a glitch.
        val offsetKm = _form.value.vehicles
            .firstOrNull { it.slug == _form.value.selectedVehicleSlug }
            ?.odometerOffsetKm
            ?: 0.0
        val km = pcmKm - offsetKm
        // OBD reports km; convert to miles for the form (matches the
        // "Odometer (mi)" label). User can override.
        val mi = km * 0.621371
        // Sanity-guard the live reading: an odometer only ever increases,
        // so a value below the last recorded fillup — or implausibly far
        // above it — is a corrupt OBD frame or a stale/glitched bus entry,
        // not a real reading (the 2026-06-20 "mileage nowhere near correct"
        // report). Don't prefill garbage: fall back to the last fillup odo
        // as a safe floor the user can nudge up from, and log it so a
        // recurrence is one query away.
        val lastMi = _form.value.lastOdometer
        val value = if (
            lastMi == null || (mi >= lastMi - 1.0 && mi <= lastMi + MAX_MI_SINCE_LAST)
        ) {
            mi
        } else {
            logBuffer.warn(
                "fuel: implausible auto-fill odometer rejected",
                mapOf("computed_mi" to mi, "last_fillup_mi" to lastMi),
            )
            lastMi
        }
        _form.value = _form.value.copy(
            odometer = String.format(Locale.US, "%.0f", value),
            odometerAutoFilled = true,
        )
    }

    fun selectVehicle(slug: String) {
        _form.value = _form.value.copy(selectedVehicleSlug = slug)
        // Re-fetch latest odo for the newly-picked vehicle so the
        // hint + prefill update right away.
        viewModelScope.launch { loadLatestOdoForSlug(slug) }
    }

    fun selectFuelType(code: Int) {
        _form.value = _form.value.copy(fuelType = code)
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
                String.format(Locale.US, "%.2f", gal * ppg)
            else _form.value.totalPrice
        update { it.copy(gallons = value, totalPrice = derivedTotal) }
    }

    fun setPricePerGallon(value: String) {
        val ppg = value.toDoubleOrNull()
        val gal = _form.value.gallons.toDoubleOrNull()
        val derivedTotal =
            if (gal != null && gal > 0 && ppg != null && ppg > 0)
                String.format(Locale.US, "%.2f", gal * ppg)
            else _form.value.totalPrice
        update { it.copy(pricePerGallon = value, totalPrice = derivedTotal) }
    }

    fun setTotalPrice(value: String) {
        val total = value.toDoubleOrNull()
        val gal = _form.value.gallons.toDoubleOrNull()
        val derivedPpg =
            if (total != null && total > 0 && gal != null && gal > 0)
                String.format(Locale.US, "%.3f", total / gal)
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
        // Synchronous re-entry guard. Without this, three rapid taps fire
        // three viewModelScope.launch{} coroutines before any of them can
        // flip the submitting flag — and we just shipped 3 duplicate
        // fillups to the user's drive in production. Set the flag on the
        // calling thread so a second tap inside the same frame sees it
        // already true and bails.
        if (_form.value.submitting) return
        _form.value = _form.value.copy(submitting = true, errorMessage = null)

        viewModelScope.launch {
            val f = _form.value
            val gallons = f.gallons.toDoubleOrNull()
            val totalPrice = f.totalPrice.toDoubleOrNull()
            if (gallons == null || gallons <= 0) {
                _form.value = f.copy(submitting = false, errorMessage = "Enter gallons")
                return@launch
            }
            if (totalPrice == null || totalPrice < 0) {
                _form.value = f.copy(submitting = false, errorMessage = "Enter total price")
                return@launch
            }
            val settings = settingsRepository.current().settings
            // Use the form's selected vehicle if set; fall back to the
            // bridge's configured vehicle (matches pre-picker behaviour).
            val targetSlug = f.selectedVehicleSlug.ifBlank { settings.vehicleSlug }
            if (targetSlug.isBlank() || settings.apiBaseUrl.isBlank()) {
                _form.value = f.copy(
                    submitting = false,
                    errorMessage = "Pick a vehicle and configure API URL first",
                )
                return@launch
            }

            // Auto-refresh GPS at submit time so the saved fillup has the
            // most recent fix available. Bound at 4 s — phone hasn't moved
            // since the user opened the form, so a stale fix is fine if
            // the GPS provider is slow. We don't fail the save on a
            // missing fix; the existing f.gps falls through.
            val freshGps = withTimeoutOrNull(4_000) { locationProvider.fix() }
            val gpsForRequest = freshGps ?: f.gps

            // Compose the notes field with structured suffixes (gas type,
            // missed flag) appended so the backend captures the extras
            // even though the FillupRequest schema doesn't carry them
            // directly. The /api/fillups alias passes notes through verbatim.
            val notesParts = mutableListOf<String>()
            if (f.notes.isNotBlank()) notesParts.add(f.notes.trim())
            FUEL_TYPES.firstOrNull { it.code == f.fuelType }?.let {
                if (f.fuelType != 100) notesParts.add("[fuel:${it.label}]")
            }
            if (f.isMissed) notesParts.add("[missed-fillup]")
            val notesPayload = notesParts.joinToString("\n").ifBlank { null }

            val request = FillupRequest(
                vehicleSlug = targetSlug,
                timestampIso = Instant.now().toString(),
                gallons = gallons,
                totalPrice = totalPrice,
                odometerMi = f.odometer.toDoubleOrNull(),
                partial = f.partial,
                lat = gpsForRequest?.lat,
                lon = gpsForRequest?.lon,
                stationName = f.stationName.ifBlank { null },
                notes = notesPayload,
            )
            // Reflect the fresh fix back into the form so the user sees
            // the coords that landed on the server.
            if (freshGps != null) {
                _form.value = _form.value.copy(gps = freshGps)
            }
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
                // Reset the form so the user knows the fillup landed and
                // can immediately enter a new one. Preserve metadata that
                // would still apply to the next entry: vehicle picker,
                // gas type, last-odo hint, station suggestions.
                _form.value = FuelFormState(
                    submitting = false,
                    submittedId = response.id,
                    vehicles = f.vehicles,
                    selectedVehicleSlug = f.selectedVehicleSlug,
                    fuelType = f.fuelType,
                    lastOdometer = f.odometer.toDoubleOrNull() ?: f.lastOdometer,
                    stationSuggestions = f.stationSuggestions,
                    gps = f.gps,
                )
            } catch (e: Exception) {
                _form.value = f.copy(
                    submitting = false,
                    errorMessage = e.message ?: "Submit failed",
                )
            }
        }
    }

    private companion object {
        /** Max plausible miles between two fillups. A live/backend odometer
         *  prefill more than this above the last recorded fillup is treated
         *  as a bad reading and rejected. Generous — covers missed fillups
         *  and long road trips without admitting garbage frames. */
        const val MAX_MI_SINCE_LAST = 10_000.0
    }
}
