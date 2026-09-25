package com.pitstop.data

import com.pitstop.http.PitstopApi
import com.pitstop.http.VehicleDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which vehicle the app is LOOKING AT — Home, Trips, Fuel and Car all read
 * this. It is deliberately separate from [Settings.vehicleSlug], which is the
 * vehicle the BRIDGE publishes as: switching the top-bar picker to the truck
 * to check its fillups must not start tagging the Pilot's live telemetry
 * with the truck's slug.
 *
 * Picking the configured vehicle clears the override, so a later change of
 * the configured slug in Settings is followed rather than shadowed.
 */
@Singleton
class ActiveVehicle @Inject constructor(
    private val settings: SettingsRepository,
    private val prefs: AppPrefs,
) {
    val slug: Flow<String> = combine(settings.settings.map { it.vehicleSlug }, prefs.viewVehicleSlug) { cfg, view ->
        effectiveVehicleSlug(view, cfg)
    }.distinctUntilChanged()

    suspend fun current(): String = slug.first()

    suspend fun select(slug: String) {
        val configured = settings.settings.first().vehicleSlug.trim()
        prefs.setViewVehicleSlug(if (slug == configured) "" else slug)
    }
}

/**
 * Process-wide cache of `/vehicles`, so the top bar can name the vehicle and
 * offer the switcher without its own fetch. Whoever already loads the list
 * (Home, History) publishes through [refresh]; [vehicles] is empty until then.
 */
@Singleton
class VehicleDirectory @Inject constructor(
    private val api: PitstopApi,
) {
    private val _vehicles = MutableStateFlow<List<VehicleDto>>(emptyList())
    val vehicles: StateFlow<List<VehicleDto>> = _vehicles.asStateFlow()

    /** Fetch, publish and return the list. Throws on failure, like the API. */
    suspend fun refresh(cacheControl: String? = null): List<VehicleDto> {
        val list = api.getVehicles(cacheControl)
        _vehicles.value = list
        return list
    }

    fun bySlug(slug: String): VehicleDto? = _vehicles.value.firstOrNull { it.slug == slug }
}

/**
 * Vehicles offered by the top-bar switcher: every non-archived vehicle, plus
 * the current one even if archived (so the menu can never lose the vehicle
 * on screen). `active == false` is the backend's "archived".
 */
fun switchableVehicles(all: List<VehicleDto>, currentSlug: String): List<VehicleDto> =
    all.filter { it.active != false || it.slug == currentSlug }
