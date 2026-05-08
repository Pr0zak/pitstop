package com.pitstop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
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
class WiCanBleManager(context: Context) : BleManager(context) {

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

    override fun getMinLogPriority(): Int = Log.WARN

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
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
                return true
            }
        }
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

    override fun onServicesInvalidated() {
        rx = null
        tx = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        stateCallback?.onConnectionStateChange(ConnectionState.CONNECTING)
        connect(device)
            .retry(3, 250)
            .useAutoConnect(false)
            .timeout(15_000)
            .done { stateCallback?.onConnectionStateChange(ConnectionState.CONNECTED) }
            .fail { _, _ -> stateCallback?.onConnectionStateChange(ConnectionState.FAILED) }
            .enqueue()
    }

    fun disconnectDevice() {
        disconnect().enqueue()
        stateCallback?.onConnectionStateChange(ConnectionState.DISCONNECTED)
    }

    fun writeCommand(ascii: String) {
        val target = rx ?: return
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
            // Nordic UART (NUS) — what most ESP32-based WiCAN firmwares expose
            UartProfile(
                serviceUuid = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                rxUuid = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                txUuid = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            ),
            // 0xFFE0 SPP-style profile — fallback for older WiCAN firmware
            UartProfile(
                serviceUuid = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
                rxUuid = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
                txUuid = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
            ),
        )
    }
}
