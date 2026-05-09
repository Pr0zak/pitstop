package com.pitstop.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.pitstop.service.BridgeStateBus
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/**
 * One [Session] per car connection. We hand the [BridgeStateBus] through
 * to the screen; the screen invalidates on each state-bus emission so the
 * head-unit redraws whenever the foreground bridge service publishes a
 * new metric. Latency end-to-end is sub-second.
 */
class PitstopSession(
    private val stateBus: BridgeStateBus,
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen =
        LiveCarScreen(carContext, stateBus)
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
) {
    fun create(): Session = PitstopSession(stateBus)
}
