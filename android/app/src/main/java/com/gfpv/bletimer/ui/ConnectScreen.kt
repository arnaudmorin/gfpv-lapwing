package com.gfpv.bletimer.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfpv.bletimer.ble.ConnectionState

private fun blePermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Notifications are optional: they only show the foreground service status. */
private fun requestedPermissions(): Array<String> =
    (blePermissions() + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        .toTypedArray()

private fun hasBlePermissions(ctx: Context) =
    blePermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private fun bluetoothOn(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

@Composable
fun ConnectScreen(vm: MainViewModel, state: ConnectionState) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasBlePermissions(ctx)) }
    var btOn by remember { mutableStateOf(bluetoothOn(ctx)) }
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBlePermissions(ctx)
        btOn = bluetoothOn(ctx)
        if (granted && btOn) vm.startScan()
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = bluetoothOn(ctx)
        if (btOn) vm.startScan()
    }

    LaunchedEffect(granted, btOn) {
        if (granted && btOn && state is ConnectionState.Disconnected) vm.startScan()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state is ConnectionState.Connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Connecting to ${state.name}…", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = vm::disconnect) { Text("Cancel") }
            return@Column
        }

        Text("Connect to your timer", style = MaterialTheme.typography.headlineSmall)
        when {
            !granted -> {
                Text("GFPV Bluetooth Timer needs Bluetooth access to find and talk to the timer.")
                Button(onClick = { permissionLauncher.launch(requestedPermissions()) }) { Text("Grant Bluetooth access") }
                return@Column
            }
            !btOn -> {
                Text("Bluetooth is turned off.")
                Button(onClick = { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
                return@Column
            }
        }

        vm.lastDevice?.let { (address, name) ->
            OutlinedButton(onClick = { vm.connect(address, name) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text("Reconnect to $name")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) {
                Text(if (scanning) "Stop scanning" else "Scan")
            }
            if (scanning) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }

        if (found.isEmpty() && !scanning) {
            Text(
                "No timer found. Make sure it is powered on and nearby." +
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " On this Android version, location services must be enabled to scan." else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(found, key = { it.address }) { t ->
                Card(onClick = { vm.connect(t.address, t.name) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bluetooth, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(t.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Filled.SignalCellularAlt, null)
                        Text(" ${t.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
