package com.pitstop.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.pitstop.data.SettingsRepository
import com.pitstop.service.BridgeStateBus
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/**
 * One [Session] per car connection. We hand the [BridgeStateBus] through
 * to the screen, which samples it on a fixed 2 s tick and repaints only
 * when the rendered text would change (LiveCarScreen.renderSignature) —
 * NOT on every bus emission: mid-drive that is many updates a second, and
 * the host rate-limits template updates. So a value on the head unit is
 * at most ~2 s behind the phone.
 */
class PitstopSession(
    private val stateBus: BridgeStateBus,
    private val settingsRepository: SettingsRepository,
    private val rangeRepository: com.pitstop.data.RangeRepository,
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen =
        LiveCarScreen(carContext, stateBus, settingsRepository, rangeRepository)
}

/**
 * Hilt-friendly factory: PitstopCarAppService injects this and uses it to
 * produce sessions. CarAppService can't hold a singleton state-bus directly
 * because the framework instantiates it via reflection — the factory
 * indirection lets us bridge that.
 */
@ServiceScoped
class PitstopSessionFactory @Inject constructor(
    private val stateBus: BridgeStateBus,
    private val settingsRepository: SettingsRepository,
    private val rangeRepository: com.pitstop.data.RangeRepository,
) {
    fun create(): Session = PitstopSession(stateBus, settingsRepository, rangeRepository)
}
