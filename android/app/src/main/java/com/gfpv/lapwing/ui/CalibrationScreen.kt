package com.gfpv.lapwing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfpv.lapwing.ble.TimerConfig
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CalibrationScreen(vm: MainViewModel) {
    KeepScreenOn()
    val samples by vm.rssiSamples.collectAsStateWithLifecycle()
    val cfg = vm.config ?: TimerConfig()

    DisposableEffect(Unit) {
        vm.setRssiStreaming(true)
        onDispose { vm.setRssiStreaming(false) }
    }

    // Both read the latest config so a drag never works from a stale copy; enter always stays above exit.
    fun setEnter(value: Int) {
        val c = vm.config ?: return
        val enter = value.coerceIn(MIN_RSSI + 1, MAX_RSSI)
        vm.updateConfig(c.copy(enterRssi = enter, exitRssi = minOf(c.exitRssi, enter - 1)))
    }
    fun setExit(value: Int) {
        val c = vm.config ?: return
        val exit = value.coerceIn(MIN_RSSI, MAX_RSSI - 1)
        vm.updateConfig(c.copy(exitRssi = exit, enterRssi = maxOf(c.enterRssi, exit + 1)))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("RSSI ", style = MaterialTheme.typography.titleMedium)
            Text(samples.lastOrNull()?.toString() ?: "–", style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
        }
        RssiChart(
            samples, cfg.enterRssi, cfg.exitRssi,
            onEnterChange = ::setEnter, onExitChange = ::setExit,
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
        Text(
            "Blue: RSSI · green: crossing · red: enter · orange: exit. Drag the red and orange lines, " +
                "or use the sliders. Changes are saved to the timer automatically.",
            style = MaterialTheme.typography.bodySmall,
        )

        ThresholdSlider("Enter RSSI", cfg.enterRssi, ::setEnter)
        ThresholdSlider("Exit RSSI", cfg.exitRssi, ::setExit)
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

private enum class Threshold { ENTER, EXIT }

/** RSSI range shown by the chart: the samples, with room around both thresholds. */
private fun chartRange(samples: List<Int>, enter: Int, exit: Int): ClosedFloatingPointRange<Float> {
    val lo = minOf(samples.minOrNull() ?: exit, exit - 10).coerceAtLeast(0).toFloat()
    val hi = maxOf(samples.maxOrNull() ?: enter, enter + 10).toFloat()
    return lo..hi
}

/**
 * Live RSSI graph with the enter (red) and exit (orange) thresholds. A finger on or near a
 * threshold line drags it; touches elsewhere are left to the page so it still scrolls.
 */
@Composable
fun RssiChart(
    samples: List<Int>,
    enter: Int,
    exit: Int,
    onEnterChange: (Int) -> Unit,
    onExitChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Color.White, fontFamily = FontFamily.Monospace)

    var dragging by remember { mutableStateOf<Threshold?>(null) }
    // The scale is frozen while dragging, otherwise it follows the line and the line runs away from the finger.
    var frozenRange by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val range = frozenRange ?: chartRange(samples, enter, exit)

    val current by rememberUpdatedState(Triple(enter, exit, range))
    val onEnter by rememberUpdatedState(onEnterChange)
    val onExit by rememberUpdatedState(onExitChange)

    val touch = Modifier.pointerInput(Unit) {
        val grab = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val (e, x, r) = current
            val h = size.height.toFloat()
            fun yOf(v: Int) = h - (v - r.start) / (r.endInclusive - r.start) * h
            val dEnter = abs(down.position.y - yOf(e))
            val dExit = abs(down.position.y - yOf(x))
            val target = when {
                dEnter > grab && dExit > grab -> return@awaitEachGesture
                // Lines close together: the upper half picks enter (always the higher line), the lower half exit.
                dEnter <= grab && dExit <= grab ->
                    if (down.position.y < (yOf(e) + yOf(x)) / 2) Threshold.ENTER else Threshold.EXIT
                dEnter <= grab -> Threshold.ENTER
                else -> Threshold.EXIT
            }
            down.consume()
            dragging = target
            frozenRange = r
            try {
                drag(down.id) { change ->
                    val y = change.position.y.coerceIn(0f, h)
                    val v = (r.start + (h - y) / h * (r.endInclusive - r.start)).roundToInt()
                    if (target == Threshold.ENTER) onEnter(v) else onExit(v)
                    change.consume()
                }
            } finally {
                dragging = null
                frozenRange = null
            }
        }
    }

    Canvas(modifier.clipToBounds().then(touch)) {
        val lo = range.start
        val hi = range.endInclusive
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

        val enterWidth = if (dragging == Threshold.ENTER) 4.dp.toPx() else 2.dp.toPx()
        val exitWidth = if (dragging == Threshold.EXIT) 4.dp.toPx() else 2.dp.toPx()
        drawLine(ChartColors.enter, Offset(0f, y(enter)), Offset(size.width, y(enter)), strokeWidth = enterWidth)
        drawLine(ChartColors.exit, Offset(0f, y(exit)), Offset(size.width, y(exit)), strokeWidth = exitWidth)

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

        // Value tags at the right end, enter above its line and exit below, so they never overlap.
        fun DrawScope.tag(value: Int, lineY: Float, above: Boolean, color: Color) {
            val text = textMeasurer.measure(value.toString(), labelStyle)
            val padH = 6.dp.toPx()
            val padV = 2.dp.toPx()
            val w = text.size.width + 2 * padH
            val h = text.size.height + 2 * padV
            val left = size.width - w - 4.dp.toPx()
            val top = (if (above) lineY - h else lineY).coerceIn(0f, size.height - h)
            drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(4.dp.toPx()))
            drawText(text, topLeft = Offset(left + padH, top + padV))
        }
        tag(enter, y(enter), above = true, color = ChartColors.enter)
        tag(exit, y(exit), above = false, color = ChartColors.exit)
    }
}
