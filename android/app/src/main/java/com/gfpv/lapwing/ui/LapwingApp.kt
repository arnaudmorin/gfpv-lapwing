package com.gfpv.lapwing.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfpv.lapwing.R
import com.gfpv.lapwing.ble.ConnectionState
import java.util.Locale

enum class Tab(val label: String, val icon: ImageVector) {
    RACE("Race", Icons.Filled.Timer),
    CALIBRATE("Calibrate", Icons.Filled.GraphicEq),
    SETTINGS("Settings", Icons.Filled.Tune),
    STATS("Stats", Icons.Filled.BarChart),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LapwingApp(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.RACE) }
    val conn by vm.connection.state.collectAsStateWithLifecycle()
    val status by vm.connection.status.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val firmwarePrompt by vm.firmwarePrompt.collectAsStateWithLifecycle()
    firmwarePrompt?.let { offer ->
        AlertDialog(
            onDismissRequest = { vm.dismissFirmwarePrompt(offer) },
            title = { Text("Timer firmware update") },
            text = {
                Text(
                    "This app includes firmware ${offer.version} for your ${offer.image.chipName} timer, " +
                        "which runs ${offer.current}. Keep the phone close to the timer until the update finishes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.flashFirmware(offer.image)
                    tab = Tab.SETTINGS // the progress is shown there
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissFirmwarePrompt(offer) }) { Text("Later") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    status?.let {
                        AssistChip(
                            onClick = {},
                            label = { Text(String.format(Locale.US, "%.1f V", it.batteryVolts)) },
                            leadingIcon = { Icon(Icons.Filled.BatteryStd, null) },
                        )
                    }
                    if (conn !is ConnectionState.Disconnected) {
                        IconButton(onClick = vm::disconnect) { Icon(Icons.Filled.BluetoothDisabled, "Disconnect") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val c = conn
            when {
                tab == Tab.STATS -> StatsScreen(vm)
                c is ConnectionState.Disconnected || c is ConnectionState.Connecting -> ConnectScreen(vm, c)
                tab == Tab.RACE -> RaceScreen(vm, reconnecting = c is ConnectionState.Reconnecting)
                c is ConnectionState.Reconnecting -> Waiting("Waiting for ${c.name} to come back in range…")
                tab == Tab.CALIBRATE -> CalibrationScreen(vm)
                tab == Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun Waiting(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Icon(Icons.AutoMirrored.Filled.BluetoothSearching, null)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
