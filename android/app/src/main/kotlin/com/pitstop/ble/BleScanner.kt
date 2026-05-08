package com.pitstop.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ScannedDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val seenAt: Long = System.currentTimeMillis(),
)

@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun isAvailable(): Boolean = adapter != null && adapter?.isEnabled == true

    fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Cold flow that emits the rolling map of discovered peripherals while it is
     * collected. Filtering is done downstream — we don't want to miss devices that
     * advertise without a name on the first packet.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<ScannedDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth not available"))
            return@callbackFlow
        }
        val seen = mutableMapOf<String, ScannedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device?.address ?: return
                val name = try {
                    result.scanRecord?.deviceName ?: result.device.name
                } catch (e: SecurityException) {
                    null
                }
                seen[mac] = ScannedDevice(mac = mac, name = name, rssi = result.rssi)
                trySend(seen.values.sortedByDescending { it.rssi })
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = emptyList<ScanFilter>()
        try {
            scanner.startScan(filters, settings, callback)
            _isScanning.value = true
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            }
            _isScanning.value = false
        }
    }
}
