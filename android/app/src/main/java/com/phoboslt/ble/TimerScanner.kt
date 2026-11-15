package com.phoboslt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundTimer(val address: String, val name: String, val rssi: Int)

/** Scans for timers advertising the PhobosLT service while the flow is collected. */
@SuppressLint("MissingPermission")
fun scanTimers(context: Context): Flow<FoundTimer> = callbackFlow {
    val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
    if (scanner == null) {
        close(IllegalStateException("Bluetooth is off"))
        return@callbackFlow
    }
    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: result.device.address
            trySend(FoundTimer(result.device.address, name, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            close(IllegalStateException("Scan failed ($errorCode)"))
        }
    }
    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(PhobosUuids.SERVICE)).build())
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(filters, settings, cb)
    awaitClose { runCatching { scanner.stopScan(cb) } }
}
