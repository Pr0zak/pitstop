package com.pitstop.ui

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.companion.WicanCompanionManager
import com.pitstop.companion.WicanSetupTracker
import com.pitstop.domain.WicanSetup
import com.pitstop.service.BridgePhase
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
    /** The unified WiCAN setup state (Bluetooth bond + auto-start). */
    val wican: WicanSetup.State = WicanSetup.State.Ready,
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
    appPrefs: com.pitstop.data.AppPrefs,
    private val activeVehicle: ActiveVehicle,
    private val companionManager: WicanCompanionManager,
    private val mqttPublisher: MqttPublisher,
    wicanSetup: WicanSetupTracker,
) : AndroidViewModel(application) {

    val bridge: StateFlow<BridgeSheetState> = combine(
        stateBus.status,
        settingsRepository.settings,
        wicanSetup.state,
    ) { status, s, wican ->
        BridgeSheetState(
            status = status.copy(brokerConnected = mqttPublisher.isConnected()),
            wican = wican,
            configured = s.apiBaseUrl.isNotBlank() && s.vehicleSlug.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BridgeSheetState())

    val state: StateFlow<AppBarState> = combine(
        directory.vehicles,
        activeVehicle.slug,
        bridge,
        settingsRepository.settings,
        appPrefs.vehicleNames,
    ) { vehicles, slug, b, s, cachedNames ->
        val (label, tone) = loggingChip(b, s.manualSyncOnly)
        AppBarState(
            // Live list, then last launch's cached name; the raw slug only
            // on the very first load after install.
            vehicleName = vehicles.firstOrNull { it.slug == slug }?.name
                ?: cachedNames[slug]
                ?: slug.ifBlank { null },
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

/**
 * Chip text + tone. Server setup first, then a WiCAN that can't link
 * ("Connect WiCAN"), then the bridge phase. A missing auto-start link only
 * shows as the soft "Auto-start off" while the bridge isn't actively
 * logging — a running capture is the more useful thing to say then.
 */
fun loggingChip(b: BridgeSheetState, manualSyncOnly: Boolean): Pair<String, PillTone> {
    if (!b.configured) return "Set up" to PillTone.Neutral
    val wicanPill = WicanSetup.pill(b.wican)
    if (wicanPill != null && WicanSetup.blocksLink(b.wican)) return wicanPill.first to com.pitstop.ui.components.toneOf(wicanPill.second)
    if (b.wican == WicanSetup.State.BluetoothInProgress) return wicanPill!!.first to PillTone.Connecting
    val phase = b.status.phase
    if (wicanPill != null && (phase == BridgePhase.Idle || phase == BridgePhase.Error)) {
        return wicanPill.first to com.pitstop.ui.components.toneOf(wicanPill.second)
    }
    return bridgePillOf(b.status.phase, b.status.brokerConnected, b.status.engineState, manualSyncOnly)
}
