package com.pitstop.ui.fuel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.http.FillupRequest
import com.pitstop.http.FillupUpdateRequest
import com.pitstop.http.PitstopApi
import com.pitstop.domain.FuelField
import com.pitstop.domain.FuelTriple
import com.pitstop.http.StationPriceDto
import com.pitstop.util.UnitFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/**
 * The fillup form. Every numeric field holds text in the user's DISPLAY
 * units — litres / km / price-per-litre for a metric user — and is only
 * converted at the edges: prefill converts stored values in, submit
 * converts back out to what each endpoint wants (the phone POST alias
 * takes US gal + mi; the PATCH takes the vehicle's stored units).
 */
data class FuelFormState(
    val volume: String = "",
    val pricePerVolume: String = "",
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
    /** Server / network failure on submit — the snackbar. Field problems
     *  are NOT here; they are [FuelFormErrors], shown on the field. */
    val errorMessage: String? = null,
    val nearestPriorStation: String? = null,
    /** Last recorded fillup odometer, in DISPLAY units. */
    val lastOdometer: Double? = null,
    /** Local date-time of the fillup; null = "now" (resolved at submit). */
    val dateTime: LocalDateTime? = null,
    /** Show field errors on untouched fields too — set by a Save attempt. */
    val showAllErrors: Boolean = false,
    /** Non-null in edit mode: the fillup being corrected. */
    val editingId: String? = null,
    val loadingEdit: Boolean = false,

    // Vehicle selection
    val vehicles: List<VehicleOption> = emptyList(),
    val selectedVehicleSlug: String = "",

    // Gas type — Fuelio's enum-ish:
    //   100 = Regular 87, 101 = Mid 89, 102 = Premium 91, 103 = Premium 93,
    //   200 = Diesel, 300 = E85, 400 = LPG / propane
    val fuelType: Int = 100,

    /** Quick-log sheet: which two of {total, price, volume} the user typed,
     *  most recent last — the third is derived (see [FuelTriple]). */
    val quickPinned: List<FuelField> = listOf(FuelField.Total, FuelField.Price),
    /** "Last price here $3.299" — the user's own last fill at the station
     *  nearest the GPS fix, from /analytics/station-prices. */
    val stationPriceHint: StationPriceHint? = null,
)

/** A price the user paid before at (about) this spot. [perGal] is USD per US gal. */
data class StationPriceHint(val perGal: Double, val dateIso: String?)

/** Field-level problems; null = fine. Pure so it is unit-testable. */
data class FuelFormErrors(
    val volume: String? = null,
    val total: String? = null,
    val odometer: String? = null,
) {
    val any: Boolean get() = volume != null || total != null || odometer != null
}

/**
 * Validate the form. [volumeUnit] / [distanceUnit] are the display unit
 * labels, used in the messages. The odometer may be blank (the server
 * falls back to the last reading) but, when given, must not go backwards.
 */
fun validateFuelForm(f: FuelFormState, distanceUnit: String, volumeUnit: String): FuelFormErrors {
    val vol = f.volume.toDoubleOrNull()
    val total = f.totalPrice.toDoubleOrNull()
    val odo = f.odometer.toDoubleOrNull()
    val last = f.lastOdometer
    return FuelFormErrors(
        volume = when {
            f.volume.isBlank() -> "Enter the amount in $volumeUnit"
            vol == null || vol <= 0.0 -> "Must be a number above 0"
            else -> null
        },
        total = when {
            f.totalPrice.isBlank() -> "Enter the total cost"
            total == null || total < 0.0 -> "Must be a number"
            else -> null
        },
        odometer = when {
            f.odometer.isBlank() -> null
            odo == null -> "Must be a number"
            last != null && odo < last - 0.5 ->
                "Below the last fillup (${"%,.0f".format(last)} $distanceUnit)"
            else -> null
        },
    )
}

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
    /** Fuelio storage units (dist 0=km 1=mi, fuel 0=L 1=US gal), for PATCH. */
    val distUnit: Int? = null,
    val fuelUnit: Int? = null,
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

private const val MI_TO_KM = 1.609344
private const val GAL_TO_L = 3.785411784

@HiltViewModel
class FuelAddViewModel @Inject constructor(
    private val locationProvider: LocationProvider,
    private val historyStore: FuelHistoryStore,
    private val settingsRepository: SettingsRepository,
    private val api: PitstopApi,
    private val stateBus: com.pitstop.service.BridgeStateBus,
    private val logBuffer: com.pitstop.log.LogBuffer,
    private val activeVehicle: com.pitstop.data.ActiveVehicle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Set when opened from Fillup detail's Edit (History route
     *  fillup/{editId}/edit); null on the Fuel tab. */
    private val editId: String? = savedStateHandle.get<String>("editId")

    private val _form = MutableStateFlow(FuelFormState(editingId = editId, loadingEdit = editId != null))
    val form: StateFlow<FuelFormState> = _form.asStateFlow()

    private var allHistory: List<HistoricFillup> = emptyList()

    /** Unit system at open time. The form's text is in these units; a
     *  toggle mid-entry would silently re-mean the typed numbers, so it is
     *  deliberately read once rather than observed. */
    private var system: String = "imperial"
    private val metric: Boolean get() = system != "imperial"

    init {
        viewModelScope.launch {
            system = settingsRepository.settings.first().unitSystem
            allHistory = historyStore.all()
            loadVehicles()
            if (editId != null) {
                loadForEdit(editId)
            } else {
                // Auto-prefill odometer from the live OBD bridge if a reading
                // is available. The user can override by editing the field.
                autoFillOdometer()
                refreshGps()
            }
        }
    }

    // ── Unit edges ──────────────────────────────────────────────────
    private fun miToDisplay(mi: Double) = if (metric) mi * MI_TO_KM else mi
    private fun displayToMi(v: Double) = if (metric) v / MI_TO_KM else v
    private fun galToDisplay(gal: Double) = if (metric) gal * GAL_TO_L else gal
    private fun displayToGal(v: Double) = if (metric) v / GAL_TO_L else v

    private suspend fun loadVehicles() {
        val current = settingsRepository.current()
        // The vehicle the app is showing (top-bar switcher), which is the
        // bridge's configured one unless the user switched.
        val defaultSlug = runCatching { activeVehicle.current() }.getOrDefault(current.settings.vehicleSlug)
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
                    distUnit = it.distUnit,
                    fuelUnit = it.fuelUnit,
                )
            },
            selectedVehicleSlug = resolvedSlug,
        )
        if (editId != null) return
        // Once the vehicle list is known, fetch the latest fillup for the
        // active selection so the "Last value" hint and odometer prefill
        // reflect the per-vehicle history (not just the live OBD reading).
        if (resolvedSlug.isNotBlank()) {
            loadLatestOdoForSlug(resolvedSlug)
            loadStationPrices(resolvedSlug)
        }
        // Retry the autoFill now that we have the vehicle's
        // backend-persisted latest_odo as a fallback for an empty
        // in-process BridgeStateBus (cold app start).
        val selected = vs.firstOrNull { it.slug == resolvedSlug }
        val latestOdoKm = selected?.latest?.get("odometer")?.valueNum
        autoFillOdometer(vehicleLatestOdoKm = latestOdoKm)
    }

    /**
     * Edit mode: prefill from the stored row. Stored values are in the
     * vehicle's units; the form shows display units, so both conversions
     * run here (stored → US → display).
     */
    private suspend fun loadForEdit(id: String) {
        val f = runCatching { api.getFillupDetail(id) }.getOrElse { e ->
            logBuffer.warn("fuel: edit load failed", mapOf("id" to id, "err" to (e.message ?: "")))
            _form.value = _form.value.copy(loadingEdit = false, errorMessage = "Couldn't load that fillup")
            return
        }
        val vehicle = _form.value.vehicles.firstOrNull { it.id == f.vehicleId }
        val odoMi = if (vehicle?.distUnit == 0) f.odo / MI_TO_KM else f.odo
        val gal = f.fuelVolume?.let { if (vehicle?.fuelUnit == 0) it / GAL_TO_L else it }
        val vol = gal?.let { galToDisplay(it) }
        val local = runCatching {
            OffsetDateTime.parse(f.fillupDate).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }.getOrNull()
        _form.value = _form.value.copy(
            loadingEdit = false,
            selectedVehicleSlug = vehicle?.slug ?: _form.value.selectedVehicleSlug,
            odometer = String.format(Locale.US, "%.0f", miToDisplay(odoMi)),
            volume = vol?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') }.orEmpty(),
            totalPrice = f.priceTotal?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            pricePerVolume = if (vol != null && vol > 0 && f.priceTotal != null) {
                String.format(Locale.US, "%.3f", f.priceTotal / vol)
            } else "",
            partial = !f.isFull,
            isMissed = f.isMissed,
            notes = f.notes.orEmpty(),
            dateTime = local,
        )
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
        val odo = miToDisplay(latest.odo)
        val current = _form.value
        _form.value = current.copy(
            lastOdometer = odo,
            // Prefill if the field is empty or carries an auto-filled
            // value from a previous vehicle's bridge — fresh fillup wins.
            odometer = if (current.odometer.isBlank() || current.odometerAutoFilled) {
                String.format(Locale.US, "%.0f", odo)
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
        // OBD reports km; the form is in display units.
        val value0 = UnitFormat.Quantity.DistanceKm.convert(km, system)
        // Sanity-guard the live reading: an odometer only ever increases,
        // so a value below the last recorded fillup — or implausibly far
        // above it — is a corrupt OBD frame or a stale/glitched bus entry,
        // not a real reading (the 2026-06-20 "mileage nowhere near correct"
        // report). Don't prefill garbage: fall back to the last fillup odo
        // as a safe floor the user can nudge up from, and log it so a
        // recurrence is one query away.
        val last = _form.value.lastOdometer
        val maxJump = miToDisplay(MAX_MI_SINCE_LAST)
        val value = if (last == null || (value0 >= last - 1.0 && value0 <= last + maxJump)) {
            value0
        } else {
            logBuffer.warn(
                "fuel: implausible auto-fill odometer rejected",
                mapOf("computed" to value0, "last_fillup" to last, "system" to system),
            )
            last
        }
        _form.value = _form.value.copy(
            odometer = String.format(Locale.US, "%.0f", value),
            odometerAutoFilled = true,
        )
    }

    fun selectVehicle(slug: String) {
        _form.value = _form.value.copy(selectedVehicleSlug = slug, stationPriceHint = null)
        // Re-fetch latest odo for the newly-picked vehicle so the
        // hint + prefill update right away.
        viewModelScope.launch {
            loadLatestOdoForSlug(slug)
            loadStationPrices(slug)
        }
    }

    // ── Quick-log sheet ─────────────────────────────────────────────

    private var stationPrices: List<StationPriceDto> = emptyList()
    private var stationPricesSlug: String? = null

    /**
     * The Fuel hub's quick sheet opened. This ViewModel is Activity-scoped
     * there (a save must survive a tab switch), so its init ran long ago:
     * follow the top-bar vehicle, and re-take the GPS fix and the odometer
     * the way a fresh form would.
     */
    fun onSheetOpened() {
        viewModelScope.launch {
            val slug = runCatching { activeVehicle.current() }.getOrNull()
            if (_form.value.vehicles.isEmpty()) loadVehicles()
            if (slug != null && slug.isNotBlank() && slug != _form.value.selectedVehicleSlug) {
                _form.value = _form.value.copy(selectedVehicleSlug = slug, stationPriceHint = null)
            }
            val sel = _form.value.selectedVehicleSlug
            if (sel.isNotBlank()) {
                loadLatestOdoForSlug(sel)
                loadStationPrices(sel)
            }
            autoFillOdometer()
        }
        refreshGps()
    }

    /** Type into one of total / price / volume; the untouched third follows. */
    fun setQuickField(field: FuelField, value: String) {
        val f = _form.value
        val next = FuelTriple(f.totalPrice, f.pricePerVolume, f.volume, f.quickPinned).edit(field, value)
        update {
            it.copy(
                totalPrice = next.total,
                pricePerVolume = next.price,
                volume = next.volume,
                quickPinned = next.pinned,
            )
        }
    }

    /** The hub showed its "saved" snackbar; don't replay it on the next visit. */
    fun acknowledgeSubmitted() {
        _form.value = _form.value.copy(submittedId = null)
    }

    /** Tap on "last price here": use it as the price. */
    fun useStationPrice() {
        val hint = _form.value.stationPriceHint ?: return
        val display = UnitFormat.pricePerVolumeValue(hint.perGal, system) ?: return
        setQuickField(FuelField.Price, String.format(Locale.US, "%.3f", display))
    }

    private suspend fun loadStationPrices(slug: String) {
        if (stationPricesSlug == slug && stationPrices.isNotEmpty()) {
            recomputePriceHint()
            return
        }
        val v = _form.value.vehicles.firstOrNull { it.slug == slug } ?: return
        stationPrices = runCatching { api.getStationPrices(v.id) }.getOrElse { emptyList() }
        stationPricesSlug = slug
        recomputePriceHint()
    }

    private fun recomputePriceHint() {
        val fix = _form.value.gps ?: return
        val v = _form.value.vehicles.firstOrNull { it.slug == _form.value.selectedVehicleSlug }
        val near = nearestStationPrice(stationPrices, fix.lat, fix.lon) ?: run {
            _form.value = _form.value.copy(stationPriceHint = null)
            return
        }
        val price = near.latestPrice ?: return
        // Stored in the vehicle's fuel unit; the hint is per US gallon.
        val perGal = if (v?.fuelUnit == 0) price * GAL_TO_L else price
        _form.value = _form.value.copy(stationPriceHint = StationPriceHint(perGal, near.latestDate))
    }

    fun selectFuelType(code: Int) {
        _form.value = _form.value.copy(fuelType = code)
    }

    /** Date / time pickers. Null resets to "now". */
    fun setDateTime(value: LocalDateTime?) {
        update { it.copy(dateTime = value) }
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
     * Update the volume and propagate total = volume × price — whichever
     * two fields the user has filled, the third derives. Mirrors Fuelio's
     * refuelling screen where editing any of {volume, price, total}
     * updates the others. Unit-free: all three are in display units.
     */
    fun setVolume(value: String) {
        val vol = value.toDoubleOrNull()
        val ppv = _form.value.pricePerVolume.toDoubleOrNull()
        val derivedTotal =
            if (vol != null && vol > 0 && ppv != null && ppv > 0)
                String.format(Locale.US, "%.2f", vol * ppv)
            else _form.value.totalPrice
        update { it.copy(volume = value, totalPrice = derivedTotal) }
    }

    fun setPricePerVolume(value: String) {
        val ppv = value.toDoubleOrNull()
        val vol = _form.value.volume.toDoubleOrNull()
        val derivedTotal =
            if (vol != null && vol > 0 && ppv != null && ppv > 0)
                String.format(Locale.US, "%.2f", vol * ppv)
            else _form.value.totalPrice
        update { it.copy(pricePerVolume = value, totalPrice = derivedTotal) }
    }

    fun setTotalPrice(value: String) {
        val total = value.toDoubleOrNull()
        val vol = _form.value.volume.toDoubleOrNull()
        val derivedPpv =
            if (total != null && total > 0 && vol != null && vol > 0)
                String.format(Locale.US, "%.3f", total / vol)
            else _form.value.pricePerVolume
        update { it.copy(totalPrice = value, pricePerVolume = derivedPpv) }
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
            recomputePriceHint()
        }
    }

    fun submit() {
        // Synchronous re-entry guard. Without this, three rapid taps fire
        // three viewModelScope.launch{} coroutines before any of them can
        // flip the submitting flag — and we once shipped 3 duplicate
        // fillups to the user's drive in production. Set the flag on the
        // calling thread so a second tap inside the same frame sees it
        // already true and bails.
        if (_form.value.submitting) return
        val volUnit = UnitFormat.Quantity.VolumeGal.unit(system)
        val distUnit = UnitFormat.Quantity.DistanceMi.unit(system)
        if (validateFuelForm(_form.value, distUnit, volUnit).any) {
            _form.value = _form.value.copy(showAllErrors = true)
            return
        }
        _form.value = _form.value.copy(submitting = true, errorMessage = null)

        viewModelScope.launch {
            val f = _form.value
            val volume = f.volume.toDouble()
            val totalPrice = f.totalPrice.toDouble()
            val gallons = displayToGal(volume)
            val odoMi = f.odometer.toDoubleOrNull()?.let { displayToMi(it) }
            val whenIso = (f.dateTime ?: LocalDateTime.now())
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime()
                .toString()
            if (f.editingId != null) {
                submitEdit(f, f.editingId, gallons, totalPrice, odoMi, whenIso)
                return@launch
            }
            val settings = settingsRepository.current().settings
            // Use the form's selected vehicle if set; fall back to the
            // bridge's configured vehicle (matches pre-picker behaviour).
            val targetSlug = f.selectedVehicleSlug.ifBlank { settings.vehicleSlug }
            if (targetSlug.isBlank() || settings.apiBaseUrl.isBlank()) {
                _form.value = f.copy(
                    submitting = false,
                    errorMessage = "Pick a vehicle and set up the server in Settings first",
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

            val request = FillupRequest(
                vehicleSlug = targetSlug,
                timestampIso = whenIso,
                gallons = gallons,
                totalPrice = totalPrice,
                odometerMi = odoMi,
                partial = f.partial,
                lat = gpsForRequest?.lat,
                lon = gpsForRequest?.lon,
                stationName = f.stationName.ifBlank { null },
                notes = composeNotes(f),
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
                    nearestPriorStation = f.nearestPriorStation,
                )
                // A new fillup moves the fuel estimate; don't wait 30 min.
                com.pitstop.widget.FuelWidgetProvider.refreshWidgets(appContext)
            } catch (e: Exception) {
                logBuffer.warn("fuel: submit failed", mapOf("err" to (e.message ?: e::class.java.simpleName)))
                _form.value = f.copy(
                    submitting = false,
                    errorMessage = "Couldn't save — check the connection and try again",
                )
            }
        }
    }

    /** PATCH in the vehicle's STORED units (the PATCH does no conversion). */
    private suspend fun submitEdit(
        f: FuelFormState,
        id: String,
        gallons: Double,
        totalPrice: Double,
        odoMi: Double?,
        whenIso: String,
    ) {
        val vehicle = f.vehicles.firstOrNull { it.slug == f.selectedVehicleSlug }
        val storedVolume = if (vehicle?.fuelUnit == 0) gallons * GAL_TO_L else gallons
        val storedOdo = odoMi?.let { if (vehicle?.distUnit == 0) it * MI_TO_KM else it }
        val body = FillupUpdateRequest(
            fillupDate = whenIso,
            odo = storedOdo,
            fuelVolume = storedVolume,
            isFull = !f.partial,
            isMissed = f.isMissed,
            priceTotal = totalPrice,
            pricePerUnit = if (storedVolume > 0) totalPrice / storedVolume else null,
            notes = f.notes.trim().ifBlank { null },
        )
        runCatching { api.updateFillup(id, body) }
            .onSuccess { _form.value = f.copy(submitting = false, submittedId = it.id) }
            .onFailure { e ->
                logBuffer.warn("fuel: edit failed", mapOf("id" to id, "err" to (e.message ?: "")))
                _form.value = f.copy(submitting = false, errorMessage = "Couldn't save the changes")
            }
    }

    // Compose the notes field with structured suffixes (gas type, missed
    // flag) appended so the backend captures the extras even though the
    // FillupRequest schema doesn't carry them directly. The alias passes
    // notes through verbatim.
    private fun composeNotes(f: FuelFormState): String? {
        val notesParts = mutableListOf<String>()
        if (f.notes.isNotBlank()) notesParts.add(f.notes.trim())
        FUEL_TYPES.firstOrNull { it.code == f.fuelType }?.let {
            if (f.fuelType != 100) notesParts.add("[fuel:${it.label}]")
        }
        if (f.isMissed) notesParts.add("[missed-fillup]")
        return notesParts.joinToString("\n").ifBlank { null }
    }

    private companion object {
        /** Max plausible miles between two fillups. A live/backend odometer
         *  prefill more than this above the last recorded fillup is treated
         *  as a bad reading and rejected. Generous — covers missed fillups
         *  and long road trips without admitting garbage frames. */
        const val MAX_MI_SINCE_LAST = 10_000.0
    }
}

/** Station cluster within [radiusM] of the fix with a known price, nearest first. */
fun nearestStationPrice(
    stations: List<StationPriceDto>,
    lat: Double,
    lon: Double,
    radiusM: Double = 150.0,
): StationPriceDto? = stations
    .filter { it.lat != null && it.lon != null && it.latestPrice != null }
    .map { it to haversineM(lat, lon, it.lat!!, it.lon!!) }
    .filter { it.second <= radiusM }
    .minByOrNull { it.second }
    ?.first

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).let { it * it }
    return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}
