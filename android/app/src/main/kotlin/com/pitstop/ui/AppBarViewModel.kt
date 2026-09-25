package com.pitstop.ui

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.companion.WicanCompanionManager
import com.pitstop.data.ActiveVehicle
import com.pitstop.data.SettingsRepository
import com.pitstop.data.VehicleDirectory
import com.pitstop.data.switchableVehicles
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.service.PitstopBridgeService
import com.pitstop.ui.components.AppBarState
import com.pitstop.ui.components.AppBarVehicle
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.components.bridgePillOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The bridge sheet's inputs (the old Home bridge card, now behind the top-bar chip). */
data class BridgeSheetState(
    val status: BridgeStatus = BridgeStatus(),
    val needsPairing: Boolean = false,
    val configured: Boolean = false,
)

/**
 * Activity-scoped state for the chrome every tab shares: the vehicle
 * switcher and the logging-status chip, plus the bridge sheet the chip
 * opens. Activity scope because the tab pages come and go (see
 * project memory: pager pages are disposed on swipe).
 */
@HiltViewModel
class AppBarViewModel @Inject constructor(
    application: Application,
    settingsRepository: SettingsRepository,
    stateBus: BridgeStateBus,
    directory: VehicleDirectory,
    private val activeVehicle: ActiveVehicle,
    private val companionManager: WicanCompanionManager,
    private val mqttPublisher: MqttPublisher,
) : AndroidViewModel(application) {

    val bridge: StateFlow<BridgeSheetState> = combine(stateBus.status, settingsRepository.settings) { status, s ->
        BridgeSheetState(
            status = status.copy(brokerConnected = mqttPublisher.isConnected()),
            needsPairing = s.bridgeAutoTrigger &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !companionManager.hasAssociation(),
            configured = s.apiBaseUrl.isNotBlank() && s.vehicleSlug.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BridgeSheetState())

    val state: StateFlow<AppBarState> = combine(
        directory.vehicles,
        activeVehicle.slug,
        bridge,
        settingsRepository.settings,
    ) { vehicles, slug, b, s ->
        val (label, tone) = loggingChip(b, s.manualSyncOnly)
        AppBarState(
            vehicleName = vehicles.firstOrNull { it.slug == slug }?.name ?: slug.ifBlank { null },
            vehicles = switchableVehicles(vehicles, slug).map { AppBarVehicle(it.slug, it.name) },
            selectedSlug = slug,
            loggingLabel = label,
            loggingTone = tone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppBarState())

    fun selectVehicle(slug: String) {
        viewModelScope.launch { activeVehicle.select(slug) }
    }

    fun startBridge() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, PitstopBridgeService.startIntent(ctx))
    }

    fun stopBridge() {
        val ctx = getApplication<Application>()
        ctx.startService(PitstopBridgeService.stopIntent(ctx))
    }
}

/** Chip text + tone: setup and pairing problems outrank the bridge phase. */
fun loggingChip(b: BridgeSheetState, manualSyncOnly: Boolean): Pair<String, PillTone> = when {
    !b.configured -> "Set up" to PillTone.Neutral
    b.needsPairing -> "Pair WiCAN" to PillTone.Degraded
    else -> bridgePillOf(b.status.phase, b.status.brokerConnected, b.status.engineState, manualSyncOnly)
}
