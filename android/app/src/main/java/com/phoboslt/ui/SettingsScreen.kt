package com.phoboslt.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoboslt.ble.AnnouncerType
import com.phoboslt.ble.Frequencies
import com.phoboslt.ble.TimerConfig
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val config by vm.connection.config.collectAsStateWithLifecycle()
    val cfg = config ?: run {
        Waiting("Reading configuration…")
        return
    }
    var draft by remember(cfg) { mutableStateOf(cfg) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Video channel")
        val bc = Frequencies.find(draft.frequency)
        Text("Band")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Frequencies.bands.forEachIndexed { b, name ->
                FilterChip(
                    selected = bc?.first == b,
                    onClick = { draft = draft.copy(frequency = Frequencies.table[b][bc?.second ?: 0]) },
                    label = { Text(name) },
                )
            }
        }
        Text("Channel")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in 0 until 8) {
                FilterChip(
                    selected = bc?.second == c,
                    onClick = { draft = draft.copy(frequency = Frequencies.table[bc?.first ?: 0][c]) },
                    label = { Text("${c + 1}") },
                )
            }
        }
        Text(Frequencies.label(draft.frequency), style = MaterialTheme.typography.titleMedium)

        Section("Race")
        LabeledSlider(
            "Minimum lap time", String.format(Locale.US, "%.1f s", draft.minLapDs / 10f),
            draft.minLapDs, 10, 200, 5,
        ) { draft = draft.copy(minLapDs = it) }

        Section("Timer battery")
        LabeledSlider(
            "Low battery alarm", if (draft.alarmDv == 0) "Off" else String.format(Locale.US, "%.1f V", draft.alarmDv / 10f),
            draft.alarmDv, 0, 36, 1,
        ) { draft = draft.copy(alarmDv = it) }

        Section("Announcer")
        AnnouncerType.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = draft.announcerType == t, onClick = { draft = draft.copy(announcerType = t) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = draft.announcerType == t, onClick = null)
                Text(t.label, Modifier.padding(start = 8.dp))
            }
        }
        LabeledSlider(
            "Voice speed", String.format(Locale.US, "%.1f×", draft.announcerRate),
            draft.announcerRateX10, 1, 20, 1,
        ) { draft = draft.copy(announcerRateX10 = it) }
        OutlinedTextField(
            value = draft.pilotName,
            onValueChange = { draft = draft.copy(pilotName = it.take(TimerConfig.MAX_NAME_LENGTH)) },
            label = { Text("Pilot name (spoken with lap times)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.testVoice(draft) }) { Text("Test voice") }

        Button(onClick = { vm.saveConfig(draft) }, enabled = draft != cfg, modifier = Modifier.fillMaxWidth()) {
            Text("Save configuration")
        }

        Section("Timer")
        TimerInfo(vm)
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Integer slider in firmware units, e.g. tenths of a second. */
@Composable
private fun LabeledSlider(label: String, valueText: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(valueText, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onChange(((it - min) / step).roundToInt() * step + min) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / step - 1,
        )
    }
}

@Composable
private fun TimerInfo(vm: MainViewModel) {
    val info by vm.connection.info.collectAsStateWithLifecycle()
    val ota by vm.ota.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadFirmware)
    }

    info?.let {
        Text("${it.name} · ${it.chip} · firmware ${it.firmware}", style = MaterialTheme.typography.bodyMedium)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Firmware update", style = MaterialTheme.typography.titleSmall)
            when (val s = ota) {
                OtaState.Idle -> {
                    Text("Select a firmware.bin built with PlatformIO for this timer (.pio/build/<target>/firmware.bin).", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Choose firmware file") }
                }
                is OtaState.Ready -> {
                    Text("${s.fileName}: ${s.image.projectName} ${s.image.version}, ${s.image.chipName}, ${s.image.bytes.size / 1024} KB")
                    s.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.flashFirmware(s.image) }) { Text("Update timer") }
                        TextButton(onClick = vm::resetOta) { Text("Cancel") }
                    }
                }
                is OtaState.Running -> {
                    Text("Sending… ${(s.progress * 100).roundToInt()}%  Keep the phone close to the timer.")
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                OtaState.Done -> {
                    Text("Update installed. The timer is rebooting and will reconnect automatically.")
                    TextButton(onClick = vm::resetOta) { Text("OK") }
                }
                is OtaState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::resetOta) { Text("OK") }
                }
            }
        }
    }
}
