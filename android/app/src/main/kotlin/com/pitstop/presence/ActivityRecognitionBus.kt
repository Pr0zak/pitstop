package com.pitstop.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny in-process bus connecting [ActivityRecognitionReceiver] (which
 * runs on the main thread when GMS dispatches a transition) to
 * [InCarDetector] (which collects the flow on its own scope).
 *
 * Kept as a separate Hilt singleton so the receiver can grab it via
 * `EntryPoints.get(...)` without pulling InCarDetector itself into the
 * receiver's static-injection graph (receivers can't constructor-inject
 * non-application graph members without `@AndroidEntryPoint`, which adds
 * runtime overhead we don't need for a 10-line receiver).
 *
 * The flow is hot but emits only on transitions. Initial value is false
 * — on process boot, [InCarDetector] re-seeds this from the persisted
 * AR-state flag in DataStore (see `SettingsRepository.readInVehicleState`).
 */
@Singleton
class ActivityRecognitionBus @Inject constructor() {

    private val _inVehicle = MutableStateFlow(false)

    /** True while the device is currently believed to be IN_VEHICLE,
     *  based on the latest ENTER/EXIT transition from GMS. */
    val inVehicle: StateFlow<Boolean> = _inVehicle.asStateFlow()

    /** Called by the receiver (main thread) on every IN_VEHICLE transition. */
    fun setInVehicle(value: Boolean) {
        _inVehicle.value = value
    }
}
