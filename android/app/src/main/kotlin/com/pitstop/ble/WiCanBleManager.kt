package com.pitstop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.pitstop.log.LogBuffer
import com.pitstop.log.maskMac
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.BondingObserver
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

/**
 * Wraps Nordic's [BleManager] for the WiCAN Pro device. WiCAN exposes a Nordic-UART-style
 * service: one writable RX characteristic (host -> device, ELM commands) and one notify TX
 * characteristic (device -> host, OBD responses).
 *
 * Service / characteristic UUIDs we try, in order:
 *   1. Nordic UART Service (NUS)   — 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   2. SPP-style 0xFFE0 / 0xFFE1   — older WiCAN firmware
 *
 * Future: surface the UUIDs in the config UI so a user with custom firmware can override.
 *
 * Battery-aware reconnect: the foreground service handles the backoff. This manager just
 * reports state changes through [stateCallback].
 */
class WiCanBleManager(
    context: Context,
    private val logBuffer: LogBuffer? = null,
) : BleManager(context) {

    interface UartStateCallback {
        fun onConnectionStateChange(state: ConnectionState)
        fun onDataReceived(bytes: ByteArray)
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, READY, FAILED }

    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var stateCallback: UartStateCallback? = null

    fun setStateCallback(cb: UartStateCallback?) {
        stateCallback = cb
    }

    init {
        // Observe bonding so the logs show definitively whether the WiCAN
        // demands a bond and whether re-bonding succeeds — the 2026-06-23
        // "needs pairing before each drive" symptom was BLE auth failures
        // (connect status 5 = HCI Authentication Failure), i.e. a stale/missing
        // bond, NOT the GATT cache (the v0.1.195 cache-clear logged
        // "Refreshing failed" — blocked hidden API — and changed nothing).
        setBondingObserver(object : BondingObserver {
            override fun onBondingRequired(device: BluetoothDevice) {
                logBuffer?.info("ble bonding required", mapOf("mac" to maskMac(device.address)))
            }

            override fun onBonded(device: BluetoothDevice) {
                logBuffer?.info("ble bonded", mapOf("mac" to maskMac(device.address)))
            }

            override fun onBondingFailed(device: BluetoothDevice) {
                logBuffer?.warn("ble bonding failed", mapOf("mac" to maskMac(device.address)))
            }
        })

        // Genuine-auth recovery lives here, NOT in the connect() .fail{} handler.
        // The .fail{} callback only exposes Nordic's negative FailCallback.REASON_*
        // constants (REASON_TIMEOUT = -5 is the WiCAN's dominant failure — the
        // dongle simply wasn't in range/advertising), never the raw HCI status.
        // A real bond-key mismatch (the "needs pairing before each drive" symptom,
        // HCI Authentication Failure / GATT 0x89) is surfaced by the OS as a
        // connection FAILURE with ConnectionObserver.REASON_UNKNOWN while the
        // device is still BONDED on our side — the bond exists but the peer
        // rejected it. removeBond() ONLY for that case so the next connect
        // re-pairs fresh. We deliberately do NOT remove on REASON_LINK_LOSS /
        // REASON_TIMEOUT / REASON_CANCELLED — those are ordinary flaps, and
        // re-pairing on a flap is exactly the regression we're avoiding.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = Unit

            @SuppressLint("MissingPermission")
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                if (reason == ConnectionObserver.REASON_UNKNOWN && bonded) {
                    logBuffer?.warn(
                        "ble: auth failure (bonded + REASON_UNKNOWN) — removing stale bond so the next connect re-pairs",
                        mapOf("mac" to maskMac(device.address)),
                    )
                    removeBond().enqueue()
                }
            }
        })
    }

    override fun getMinLogPriority(): Int = Log.WARN

    /**
     * Mirror Nordic BLE library log lines into our [LogBuffer]. We keep `Log.println` too
     * so logcat still works during development. Messages are short and pre-redacted by the
     * library — no MACs in there in practice.
     */
    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
        when {
            priority >= Log.ERROR -> logBuffer?.error("ble: $message")
            priority >= Log.WARN -> logBuffer?.warn("ble: $message")
            priority >= Log.INFO -> logBuffer?.info("ble: $message")
            else -> logBuffer?.debug("ble: $message")
        }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Diagnostic: dump every advertised service + each characteristic's
        // properties bitmap. When the static UUID list misses, we want
        // ground-truth so we can patch the candidate list rather than
        // guess from the firmware source.
        val discoveredSummary = gatt.services.joinToString("; ") { svc ->
            val chars = svc.characteristics.joinToString(",") { c ->
                "${c.uuid}/0x${Integer.toHexString(c.properties)}"
            }
            "${svc.uuid}[${chars}]"
        }
        logBuffer?.info(
            "ble services discovered",
            mapOf(
                "count" to gatt.services.size,
                "services" to discoveredSummary.take(800),
            ),
        )

        for (uuids in CANDIDATE_PROFILES) {
            val service = gatt.getService(uuids.serviceUuid) ?: continue
            val rxChar = service.getCharacteristic(uuids.rxUuid) ?: continue
            val txChar = service.getCharacteristic(uuids.txUuid) ?: continue
            // RX must support write; TX must support notify
            val rxOk = (rxChar.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            val txOk = (txChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (rxOk && txOk) {
                rx = rxChar
                tx = txChar
                logBuffer?.info(
                    "ble service matched",
                    mapOf("service" to uuids.serviceUuid.toString()),
                )
                return true
            }
        }
        logBuffer?.warn("ble required UART service not found")
        return false
    }

    override fun initialize() {
        // Best-effort MTU bump; the WiCAN can fragment, so even MTU=23 works. Larger MTU
        // means fewer notify packets per OBD response.
        requestMtu(247).enqueue()
        setNotificationCallback(tx).with { _, data: Data ->
            val raw = data.value ?: return@with
            stateCallback?.onDataReceived(raw)
        }
        enableNotifications(tx)
            .done { stateCallback?.onConnectionStateChange(ConnectionState.READY) }
            .fail { _, _ -> stateCallback?.onConnectionStateChange(ConnectionState.FAILED) }
            .enqueue()
    }

    // The WiCAN's GATT attribute table isn't stable across reconnects and
    // Android aggressively caches it, producing the 0x85 (GATT_ERROR / 133) +
    // "services invalidated" flap that previously only cleared by forgetting +
    // re-pairing the device in system Bluetooth (the 2026-06-21 incident;
    // BLE-2). Telling the Nordic library to clear the service cache on every
    // disconnect makes each reconnect rediscover fresh services — the same
    // effect as the manual forget, done automatically. Costs one extra
    // service-discovery round-trip per reconnect; cheap vs. a dead link.
    override fun shouldClearCacheWhenDisconnected(): Boolean = true

    override fun onServicesInvalidated() {
        rx = null
        tx = null
        // Tell the foreground service the link is gone so it can flip phase
        // back to Disconnected and schedule a reconnect with backoff. Without
        // this, the service stays in Connected phase, the poll loop keeps
        // queueing writes that all silently drop, and we get hundreds of
        // "ble write skipped" lines without ever triggering recovery.
        logBuffer?.info("ble services invalidated")
        stateCallback?.onConnectionStateChange(ConnectionState.DISCONNECTED)
    }

    /**
     * @param reconnect true when we're re-attaching to a device we've already
     *   talked to this session (the service-level backoff loop's steady state).
     *   That path uses `useAutoConnect(true)` so the OS reconnects
     *   opportunistically the moment the WiCAN re-advertises — cheap on battery
     *   and it dodges the ~15 s active-scan radio timeout that produced 626/685
     *   REASON_TIMEOUT (-5) failures over 7 days. The first attempt of a session
     *   stays on `useAutoConnect(false)` (direct connect) for a fast first
     *   frame; autoConnect's first attempt can lag until the next advertising
     *   interval, which we don't want on engine-start.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, reconnect: Boolean = false) {
        stateCallback?.onConnectionStateChange(ConnectionState.CONNECTING)
        logBuffer?.info(
            "ble connect",
            mapOf(
                "mac" to maskMac(device.address),
                "bonded" to (device.bondState == BluetoothDevice.BOND_BONDED),
                "reconnect" to reconnect,
            ),
        )
        // No inner .retry(): one connect() = one attempt = one "ble connect
        // failed" log line, so the service-level backoff owns retry cadence
        // (adaptive on inCar / engine state) instead of the Nordic library
        // silently burning three 15 s timeouts per scheduled attempt. The old
        // .retry(3, 250) inflated a single logical failure into a ~45 s stall
        // and one opaque log line.
        connect(device)
            .useAutoConnect(reconnect)
            .timeout(15_000)
            .done {
                logBuffer?.info("ble connected", mapOf("mac" to maskMac(device.address)))
                stateCallback?.onConnectionStateChange(ConnectionState.CONNECTED)
            }
            .fail { failedDevice, status ->
                val bonded = failedDevice.bondState == BluetoothDevice.BOND_BONDED
                logBuffer?.warn(
                    "ble connect failed",
                    mapOf("mac" to maskMac(failedDevice.address), "status" to status, "bonded" to bonded),
                )
                // The .fail{} handler on a connect() request delivers Nordic's
                // FailCallback.REASON_* constants (all NEGATIVE), NOT a raw HCI
                // status. REASON_TIMEOUT (-5) is the dominant WiCAN failure
                // (626/685 attempts over 7 days): the dongle simply wasn't
                // advertising / in range. That is NOT an auth problem — removing
                // the bond on a timeout would force a needless re-pair every
                // time the car is asleep. So we just report FAILED and let the
                // service backoff handle it.
                //
                // A genuine HCI Authentication Failure (GATT status 5 / 0x89 =
                // 137) does NOT arrive here — Nordic surfaces it through the
                // ConnectionObserver.onDeviceFailedToConnect path (registered in
                // init), which is the ONLY place we removeBond().
                stateCallback?.onConnectionStateChange(ConnectionState.FAILED)
            }
            .enqueue()
    }

    fun disconnectDevice() {
        logBuffer?.info("ble disconnect")
        disconnect().enqueue()
        stateCallback?.onConnectionStateChange(ConnectionState.DISCONNECTED)
    }

    /** Throttle for the "no rx characteristic" warn — log at most once per
     * 5 s while the rx target is null, so a transient disconnect doesn't
     * spam hundreds of identical lines into the depot. */
    private var lastSkippedWarnAt: Long = 0

    fun writeCommand(ascii: String) {
        val target = rx ?: run {
            val now = System.currentTimeMillis()
            if (now - lastSkippedWarnAt > 5_000L) {
                lastSkippedWarnAt = now
                logBuffer?.warn("ble write skipped: no rx characteristic (will reconnect)")
            }
            return
        }
        logBuffer?.debug("ble write", mapOf("cmd" to ascii.trim()))
        writeCharacteristic(
            target,
            ascii.toByteArray(Charsets.US_ASCII),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ).enqueue()
    }

    private data class UartProfile(
        val serviceUuid: UUID,
        val rxUuid: UUID,
        val txUuid: UUID,
    )

    companion object {
        private const val TAG = "WiCanBleManager"

        private val CANDIDATE_PROFILES = listOf(
            // WiCAN-PRO v4.49 BLE channel: 0xFFF0 service.
            // Confirmed against meatpiHQ/wican-fw main/ble.c (line 1090
            // is where fff0_attr_db gets registered) AND from a runtime
            // service-discovery dump from the device itself:
            //
            //   0000fff0-..  [
            //     0000fff1-../0x30 (NOTIFY|INDICATE) — device→phone
            //     0000fff2-../0x0c (WRITE|WRITE_NO_RESPONSE) — phone→device
            //     ...CMD_IN/OUT custom UUIDs (CLI service)
            //   ]
            //
            // Naming convention: rxUuid is from the *device's* perspective —
            // it's where the device receives writes from us, so phone writes
            // here (must have WRITE property). txUuid is the char the device
            // transmits on, so phone notifies here (must have NOTIFY).
            UartProfile(
                serviceUuid = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),
                rxUuid = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB"),  // WRITE
                txUuid = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"),  // NOTIFY
            ),
            // Nordic UART (NUS) — older / community wican-fw builds may expose this.
            UartProfile(
                serviceUuid = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                rxUuid = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                txUuid = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            ),
            // 0xFFE0 HM-10 style — long-shot fallback for clones.
            UartProfile(
                serviceUuid = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
                rxUuid = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
                txUuid = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
            ),
        )
    }
}
