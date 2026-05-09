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

    override fun onServicesInvalidated() {
        rx = null
        tx = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        stateCallback?.onConnectionStateChange(ConnectionState.CONNECTING)
        logBuffer?.info("ble connect", mapOf("mac" to maskMac(device.address)))
        connect(device)
            .retry(3, 250)
            .useAutoConnect(false)
            .timeout(15_000)
            .done {
                logBuffer?.info("ble connected", mapOf("mac" to maskMac(device.address)))
                stateCallback?.onConnectionStateChange(ConnectionState.CONNECTED)
            }
            .fail { _, status ->
                logBuffer?.warn(
                    "ble connect failed",
                    mapOf("mac" to maskMac(device.address), "status" to status),
                )
                stateCallback?.onConnectionStateChange(ConnectionState.FAILED)
            }
            .enqueue()
    }

    fun disconnectDevice() {
        logBuffer?.info("ble disconnect")
        disconnect().enqueue()
        stateCallback?.onConnectionStateChange(ConnectionState.DISCONNECTED)
    }

    fun writeCommand(ascii: String) {
        val target = rx ?: run {
            logBuffer?.warn("ble write skipped: no rx characteristic")
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
