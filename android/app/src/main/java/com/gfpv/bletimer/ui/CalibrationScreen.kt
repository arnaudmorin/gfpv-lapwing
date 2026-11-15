package com.gfpv.bletimer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfpv.bletimer.ble.TimerConfig
import kotlin.math.roundToInt

@Composable
fun CalibrationScreen(vm: MainViewModel) {
    KeepScreenOn()
    val config by vm.connection.config.collectAsStateWithLifecycle()
    val samples by vm.rssiSamples.collectAsStateWithLifecycle()
    val cfg = config ?: TimerConfig()

    var enter by remember(cfg) { mutableIntStateOf(cfg.enterRssi) }
    var exit by remember(cfg) { mutableIntStateOf(cfg.exitRssi) }

    DisposableEffect(Unit) {
        vm.setRssiStreaming(true)
        onDispose { vm.setRssiStreaming(false) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("RSSI ", style = MaterialTheme.typography.titleMedium)
            Text(samples.lastOrNull()?.toString() ?: "–", style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
        }
        RssiChart(samples, enter, exit, Modifier.fillMaxWidth().height(260.dp))
        Text("Blue: RSSI · green: crossing · red: enter · orange: exit", style = MaterialTheme.typography.bodySmall)

        ThresholdSlider("Enter RSSI", enter) {
            enter = it
            if (exit >= enter) exit = enter - 1
        }
        ThresholdSlider("Exit RSSI", exit) {
            exit = it
            if (enter <= exit) enter = exit + 1
        }
        Button(
            onClick = { vm.saveConfig(cfg.copy(enterRssi = enter, exitRssi = exit)) },
            enabled = enter != cfg.enterRssi || exit != cfg.exitRssi,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save RSSI thresholds") }
        Text(
            "Place the drone slightly further than one gate away from the timer: its RSSI minus 2-5 is a good " +
                "enter value, and 8-10 below that a good exit value.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ThresholdSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(value.toString(), fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(MIN_RSSI, MAX_RSSI)) },
            valueRange = MIN_RSSI.toFloat()..MAX_RSSI.toFloat(),
            steps = MAX_RSSI - MIN_RSSI - 1,
        )
    }
}

private const val MIN_RSSI = 50
private const val MAX_RSSI = 255

@Composable
fun RssiChart(samples: List<Int>, enter: Int, exit: Int, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val lo = minOf(samples.minOrNull() ?: exit, exit - 10).coerceAtLeast(0).toFloat()
        val hi = maxOf(samples.maxOrNull() ?: enter, enter + 10).toFloat()
        fun y(v: Number) = size.height - (v.toFloat() - lo) / (hi - lo) * size.height
        val step = size.width / (MainViewModel.RSSI_HISTORY - 1)
        val x0 = size.width - (samples.size - 1) * step // newest sample on the right edge

        drawRect(grid, style = Stroke(1f))

        // Crossing shading with the same enter/exit hysteresis as the timer
        var crossing = false
        samples.forEachIndexed { i, v ->
            if (crossing && v < exit) crossing = false else if (!crossing && v > enter) crossing = true
            if (crossing) drawRect(ChartColors.crossing, Offset(x0 + i * step - step / 2, 0f), Size(step, size.height))
        }

        drawLine(ChartColors.enter, Offset(0f, y(enter)), Offset(size.width, y(enter)), strokeWidth = 4f)
        drawLine(ChartColors.exit, Offset(0f, y(exit)), Offset(size.width, y(exit)), strokeWidth = 4f)

        if (samples.size >= 2) {
            val path = Path()
            samples.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(x0, y(v)) else path.lineTo(x0 + i * step, y(v))
            }
            drawPath(path, ChartColors.rssi, style = Stroke(4f))
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(x0, size.height)
                close()
            }
            drawPath(fill, ChartColors.rssi.copy(alpha = 0.25f))
        }
        if (samples.isEmpty()) drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
    }
}
