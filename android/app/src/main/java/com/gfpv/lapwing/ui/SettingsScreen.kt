package com.gfpv.lapwing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfpv.lapwing.R
import com.gfpv.lapwing.audio.VoiceLanguage
import com.gfpv.lapwing.ble.AnnouncerType
import com.gfpv.lapwing.ble.Frequencies
import com.gfpv.lapwing.ble.TimerConfig
import java.util.Locale
import kotlin.math.roundToInt

/** Every change is sent to the timer automatically, see [MainViewModel.updateConfig]. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val cfg = vm.config ?: run {
        Waiting("Reading configuration…")
        return
    }
    val preferredBand by vm.preferredBand.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = cfg.pilotName,
            onValueChange = { vm.updateConfig(cfg.copy(pilotName = it.take(TimerConfig.MAX_NAME_LENGTH))) },
            label = { Text("Pilot name (spoken with lap times)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Video channel")
        val bc = Frequencies.find(cfg.frequency, preferredBand)
        Text("Band")
        ChoiceRow(Frequencies.bands, bc?.first) { b ->
            vm.setPreferredBand(b)
            vm.updateConfig(cfg.copy(frequency = Frequencies.table[b][bc?.second ?: 0]))
        }
        Text("Channel")
        ChoiceRow((1..8).map { "$it" }, bc?.second) { c ->
            val b = bc?.first ?: 0
            vm.setPreferredBand(b)
            vm.updateConfig(cfg.copy(frequency = Frequencies.table[b][c]))
        }
        Text(Frequencies.label(cfg.frequency, preferredBand), style = MaterialTheme.typography.titleMedium)

        Section("Race")
        LabeledSlider(
            "Minimum lap time", String.format(Locale.US, "%.1f s", cfg.minLapDs / 10f),
            cfg.minLapDs, 10, 200, 5,
        ) { vm.updateConfig(cfg.copy(minLapDs = it)) }

        Section("Timer battery")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Low battery alarm", Modifier.weight(1f))
            Switch(
                checked = cfg.alarmDv > 0,
                onCheckedChange = { on -> vm.updateConfig(cfg.copy(alarmDv = if (on) TimerConfig().alarmDv else 0)) },
            )
        }
        if (cfg.alarmDv > 0) {
            // A 1S cell: 4.0 V is nearly full, below 3.0 V it is already damaged. Default 3.6 V.
            LabeledSlider(
                "Beep below", String.format(Locale.US, "%.1f V", cfg.alarmDv / 10f),
                cfg.alarmDv, 30, 40, 1,
            ) { vm.updateConfig(cfg.copy(alarmDv = it)) }
        }

        Section("Announcer")
        AnnouncerType.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = cfg.announcerType == t, onClick = { vm.updateConfig(cfg.copy(announcerType = t)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = cfg.announcerType == t, onClick = null)
                Text(t.label, Modifier.padding(start = 8.dp))
            }
        }
        LabeledSlider(
            "Voice speed", String.format(Locale.US, "%.1f×", cfg.announcerRate),
            cfg.announcerRateX10, 1, 20, 1,
        ) { vm.updateConfig(cfg.copy(announcerRateX10 = it)) }
        VoiceLanguagePicker(vm)
        OutlinedButton(onClick = { vm.testVoice(cfg) }) { Text("Test voice") }

        Section("Timer")
        TimerInfo(vm)

        Section("About")
        About()
    }
}

@Composable
private fun About() {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text("Version $version", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        "GFPV Lapwing is made by GFPV Racing, a bunch of slightly crazy FPV drone pilots from Brittany, " +
            "France. We spend our evenings and weekends flying whoops around living rooms, racing " +
            "through gates in the rain (it is Brittany after all) and timing every lap to beat each other " +
            "by a few hundredths of a second.",
    )
    Text(
        "We built this timer so anyone can practice anywhere with just a small box and a phone. " +
            "If you are around, come fly with us!",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { uri.openUri("https://www.gfpv.fr") }) { Text("gfpv.fr") }
        OutlinedButton(onClick = { uri.openUri("https://github.com/arnaudmorin/fpv-android-ble-timer") }) { Text("Source code") }
    }
}

/** Single choice buttons sharing one line, small enough for the 8 channels on a phone. */
@Composable
private fun ChoiceRow(labels: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Applied immediately and stored on the phone: it is not part of the timer configuration. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceLanguagePicker(vm: MainViewModel) {
    val language by vm.voiceLanguage.collectAsStateWithLifecycle()
    var available by remember { mutableStateOf(true) }
    LaunchedEffect(language) { available = vm.isVoiceAvailable(language) }

    Text("Voice language")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VoiceLanguage.entries.forEach { lang ->
            FilterChip(
                selected = language == lang,
                onClick = { vm.setVoiceLanguage(lang) },
                label = { Text(lang.label) },
            )
        }
    }
    if (!available) {
        Text(
            "No ${language.label} voice is installed on this phone. Install it in Android settings › " +
                "Text-to-speech output, or the callouts may be silent or use another language.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
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
    val offer by vm.firmwareOffer.collectAsStateWithLifecycle()
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
                    offer?.let { o ->
                        Text(
                            if (o.newer) "Firmware ${o.version} is included in this app, the timer runs ${o.current}."
                            else "This app includes firmware ${o.version}, the timer runs a local build (${o.current}).",
                        )
                        Button(onClick = { vm.flashFirmware(o.image) }) { Text("Install firmware ${o.version}") }
                        Text("Or install another firmware file:", style = MaterialTheme.typography.bodySmall)
                    }
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
