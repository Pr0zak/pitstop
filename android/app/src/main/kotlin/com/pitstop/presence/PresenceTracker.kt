package com.pitstop.presence

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks two phone-side "user is in the car" signals and combines them
 * into a single [inCar] StateFlow:
 *
 *   - Android Auto active connection ([CarConnection.type] LiveData,
 *     emits [CarConnection.CONNECTION_TYPE_PROJECTION] when AA is up).
 *     Sticky — first emission on subscribe is the current state.
 *   - The car's classic-Bluetooth (HFP) profile connection. The user
 *     pairs their phone with the head unit; whenever HFP is up to that
 *     specific MAC (saved as [com.pitstop.data.Settings.pairedCarBtMac]),
 *     the user is in the car.
 *
 * The bridge service consumes [inCar] for adaptive BLE backoff (Task #77)
 * and as a third trip-boundary signal alongside engine_state events.
 *
 * Implementation notes (researched):
 *   - We don't migrate the bridge service to LifecycleService.
 *     [androidx.lifecycle.asFlow] uses observeForever internally and
 *     cancels the observer when the collecting coroutine cancels, so
 *     no LifecycleOwner is needed.
 *   - HFP `ACTION_CONNECTION_STATE_CHANGED` is a system-protected
 *     broadcast → register with `RECEIVER_NOT_EXPORTED`.
 *   - All `BluetoothDevice` / `connectedDevices` reads require
 *     `BLUETOOTH_CONNECT` granted at runtime, not just declared.
 *   - HFP and AA can race ~3s on disconnect. False transitions are
 *     debounced by 30s before being emitted.
 */
@Singleton
class PresenceTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val logBuffer: LogBuffer,
) {

    private val ownScope = CoroutineScope(SupervisorJob())

    // Live HFP-connected MAC set; updated by the receiver + profile proxy.
    private val _hfpConnected = MutableStateFlow<Set<String>>(emptySet())

    // Live Android Auto state. Initialised on start() with the sticky
    // CarConnection LiveData; stays at its last value until cancelled.
    private val _aaType = MutableStateFlow(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)

    /** Combined signal: true when the user is detected as in the car. */
    val inCar: StateFlow<Boolean> by lazy {
        combine(_aaType, _hfpConnected, settings.settings) { aa, macs, s ->
            val aaUp = aa == CarConnection.CONNECTION_TYPE_PROJECTION
            val btUp = s.pairedCarBtMac?.let { it in macs } == true
            aaUp || btUp
        }
            .distinctUntilChanged()
            .debounceFalse(30_000L)
            .stateIn(ownScope, SharingStarted.Eagerly, false)
    }

    private var headsetProxy: BluetoothHeadset? = null
    private var btReceiver: BroadcastReceiver? = null
    private var aaCollectJob: kotlinx.coroutines.Job? = null

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            headsetProxy = proxy as? BluetoothHeadset
            recomputeHfp()
        }
        override fun onServiceDisconnected(profile: Int) {
            headsetProxy = null
            recomputeHfp()
        }
    }

    /** Begin observing presence signals. Idempotent. */
    fun start() {
        if (aaCollectJob != null) return
        // Subscribe AA LiveData → MutableStateFlow on a long-lived job.
        // .asFlow() uses observeForever; cancelling this job removes it.
        aaCollectJob = ownScope.launch {
            CarConnection(context).type.asFlow().collect { value ->
                _aaType.value = value
            }
        }
        // Bluetooth profile proxy + state-change receiver.
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter != null) {
            adapter.getProfileProxy(context, proxyListener, BluetoothProfile.HEADSET)
        } else {
            logBuffer.warn("presence: bluetooth adapter unavailable")
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = recomputeHfp()
        }
        btReceiver = rx
        ContextCompat.registerReceiver(
            context,
            rx,
            IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Stop observing. Releases the profile proxy + receiver. */
    fun stop() {
        aaCollectJob?.cancel()
        aaCollectJob = null
        btReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        btReceiver = null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        headsetProxy = null
    }

    @SuppressLint("MissingPermission")
    private fun recomputeHfp() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _hfpConnected.value = emptySet()
            return
        }
        val proxy = headsetProxy ?: run {
            _hfpConnected.value = emptySet()
            return
        }
        val macs = runCatching {
            proxy.connectedDevices.mapNotNull { it.address }.toSet()
        }.getOrDefault(emptySet())
        _hfpConnected.value = macs
    }

    /** Quickly suspend until [inCar] is true, or [timeoutMs] elapses. */
    suspend fun awaitInCar(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            inCar.first { it }
        } != null

    companion object {
        // Helper extension: only delay a transition to false; if the
        // value flips back to true within the window, the false is
        // suppressed entirely. Smoothes the AA-vs-HFP race on
        // disconnect (~3s skew) without delaying transitions to true.
        private fun Flow<Boolean>.debounceFalse(ms: Long): Flow<Boolean> =
            transformLatest { v ->
                if (v) emit(true) else {
                    delay(ms)
                    emit(false)
                }
            }
    }
}
