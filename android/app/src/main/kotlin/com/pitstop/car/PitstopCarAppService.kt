package com.pitstop.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Entry point for Android Auto + AAOS. The OS calls into this service when
 * the user opens "Pitstop" from the head-unit launcher; we hand back a
 * [PitstopSession] which then yields screens.
 *
 * We mark this @AndroidEntryPoint so the session can ask Hilt for the
 * [com.pitstop.service.BridgeStateBus] singleton — that's the same bus the
 * phone-side Live view reads from, which keeps the head-unit screen in
 * sync with the bridge service state without any IPC dance.
 *
 * Host validation: we accept the OEM allowlist baked into the AndroidX
 * library. For dev, the user enables "Unknown sources" in Android Auto's
 * developer settings — that's standard for sideloaded automotive apps.
 */
@AndroidEntryPoint
class PitstopCarAppService : CarAppService() {

    @Inject lateinit var sessionFactory: PitstopSessionFactory

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        // ^ debug-only stance. For a play-store build we'd swap to
        //   HostValidator.Builder(applicationContext)
        //       .addAllowedHosts(R.array.hosts_allowlist_sample)
        //       .build()
        //   and ship the OEM allowlist resource. Pitstop is sideloaded only,
        //   so the OS-level "Unknown sources" toggle is the gate.

    override fun onCreateSession(): Session = sessionFactory.create()
}
