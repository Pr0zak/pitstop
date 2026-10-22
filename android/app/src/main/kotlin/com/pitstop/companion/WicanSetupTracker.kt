package com.pitstop.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pitstop.data.AppPrefs
import com.pitstop.data.SettingsRepository
import com.pitstop.domain.WicanSetup
import com.pitstop.log.LogBuffer
import com.pitstop.service.BlePairing
import com.pitstop.service.BridgeStateBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifetime source of [WicanSetup.Inputs] for every surface (top-bar
 * pill, bridge sheet, Home, Settings, wizard) and the Bluetooth-bond half of
 * the primary "Connect WiCAN" action. A singleton so the bond receiver and
 * the 5 s association poll survive tab ViewModels being disposed on swipe.
 *
 * Bond state isn't observable as a flow, so it is re-read on every
 * `ACTION_BOND_STATE_CHANGED`, on [refresh] (Settings resume, after an
 * action) and on a 5 s tick while someone is collecting — the tick is also
 * what notices a CDM association dropped by "Forget" in Android settings.
 */
@Singleton
class WicanSetupTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val appPrefs: AppPrefs,
    private val stateBus: BridgeStateBus,
    private val companion: WicanCompanionManager,
    private val logBuffer: LogBuffer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Probe(val bond: WicanSetup.Bond, val cdm: Boolean)

    private val probe = MutableStateFlow(Probe(WicanSetup.Bond.Unknown, false))

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refresh()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // Once a bond exists (or the bridge saw the WiCAN ask for one), this
        // dongle needs the Bluetooth step for good — remember it.
        scope.launch {
            combine(probe, stateBus.status.map { it.blePairing }.distinctUntilChanged()) { p, pairing ->
                p.bond == WicanSetup.Bond.Bonded || pairing != BlePairing.None
            }.collect { learned ->
                if (learned && !appPrefs.wicanNeedsBond.first()) appPrefs.setWicanNeedsBond(true)
            }
        }
    }

    private val ticker: Flow<Unit> = flow {
        while (true) {
            refreshNow()
            emit(Unit)
            delay(5_000)
        }
    }

    /** Live inputs; collecting it keeps the 5 s re-probe running. */
    val inputs: Flow<WicanSetup.Inputs> = combine(
        settings.settings,
        appPrefs.wicanNeedsBond,
        stateBus.status.map { it.blePairing }.distinctUntilChanged(),
        probe,
        ticker,
    ) { s, needsBond, pairing, p, _ ->
        WicanSetup.Inputs(
            deviceKnown = !s.bleDeviceMac.isNullOrBlank() || p.cdm,
            bond = p.bond,
            bondRequired = needsBond,
            linkPairing = when (pairing) {
                BlePairing.None -> WicanSetup.LinkPairing.None
                BlePairing.InProgress -> WicanSetup.LinkPairing.InProgress
                BlePairing.Required -> WicanSetup.LinkPairing.Required
                BlePairing.Failed -> WicanSetup.LinkPairing.Failed
                BlePairing.Rejected -> WicanSetup.LinkPairing.Rejected
            },
            autoStartOn = s.bridgeAutoTrigger,
            cdmSupported = companion.presenceSupported,
            cdmAssociated = p.cdm,
            bleEnabled = s.bridgeBleEnabled,
        )
    }.distinctUntilChanged()

    val state: Flow<WicanSetup.State> = inputs.map { WicanSetup.evaluate(it) }.distinctUntilChanged()

    fun refresh() {
        scope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val mac = runCatching { settings.settings.first().bleDeviceMac }.getOrNull()
        probe.value = Probe(bondOf(mac), runCatching { companion.hasAssociation() }.getOrDefault(false))
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun deviceOf(mac: String?): BluetoothDevice? {
        if (mac.isNullOrBlank() || !hasConnectPermission()) return null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return null
        return runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun bondOf(mac: String?): WicanSetup.Bond {
        val device = deviceOf(mac) ?: return WicanSetup.Bond.Unknown
        return when (runCatching { device.bondState }.getOrNull()) {
            BluetoothDevice.BOND_BONDED -> WicanSetup.Bond.Bonded
            BluetoothDevice.BOND_BONDING -> WicanSetup.Bond.Bonding
            BluetoothDevice.BOND_NONE -> WicanSetup.Bond.None
            else -> WicanSetup.Bond.Unknown
        }
    }

    /**
     * Step 1 of "Connect WiCAN": create the Bluetooth bond. The system dialog
     * asks for the passkey on the WiCAN's Settings › Bluetooth page. With
     * [fresh], an existing bond is removed first — the "Reconnect WiCAN"
     * path for a WiCAN that was reset/reflashed and rejects the old keys.
     * Suspends until the bond settles (or 2 min); returns true when bonded.
     */
    @SuppressLint("MissingPermission")
    suspend fun bond(fresh: Boolean): Boolean {
        val mac = settings.settings.first().bleDeviceMac
        val device = deviceOf(mac) ?: return false
        appPrefs.setWicanNeedsBond(true)
        if (fresh && device.bondState != BluetoothDevice.BOND_NONE) {
            // removeBond() is a hidden API (Nordic uses the same reflection).
            val removed = runCatching {
                device.javaClass.getMethod("removeBond").invoke(device) as? Boolean
            }.getOrNull() == true
            logBuffer.info("wican setup: removeBond", mapOf("ok" to removed))
            withTimeoutOrNull(10_000) {
                while (device.bondState != BluetoothDevice.BOND_NONE) delay(200)
            }
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            refreshNow()
            return true
        }
        val started = runCatching { device.createBond() }.getOrDefault(false)
        logBuffer.info("wican setup: createBond", mapOf("started" to started))
        val result = withTimeoutOrNull(120_000) {
            delay(1_000)
            while (device.bondState == BluetoothDevice.BOND_BONDING) delay(500)
            device.bondState == BluetoothDevice.BOND_BONDED
        } ?: false
        // A fresh bond replaces whatever "rejected" verdict the bridge holds.
        if (result) stateBus.update { it.copy(blePairing = BlePairing.None) }
        refreshNow()
        return result
    }

    /** A different dongle was picked: forget what we learned about the old one. */
    fun onDevicePicked() {
        scope.launch {
            appPrefs.setWicanNeedsBond(false)
            stateBus.update { it.copy(blePairing = BlePairing.None) }
            refreshNow()
        }
    }
}
