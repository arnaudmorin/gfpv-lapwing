package com.gfpv.lapwing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gfpv.lapwing.race.Progression
import com.gfpv.lapwing.race.formatSeconds

private fun lapText(ms: Long?) = if (ms == null) "–" else "${formatSeconds(ms)} s"

/** Records and progression charts at the top of the Stats tab (ported from fpv-android-lap-timer). */
@Composable
fun HistorySummary(progression: Progression, raceCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Best lap", lapText(progression.bestLapMs), Modifier.weight(1f), valueColor = ChartColors.bestLap)
            StatTile(
                "Best 3 consecutive",
                lapText(progression.bestThreeMs),
                Modifier.weight(1f),
                detail = progression.bestThreeLaps?.joinToString(" · ") { formatSeconds(it) },
                valueColor = ChartColors.bestThree,
            )
        }
        if (raceCount >= 2) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Progression", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "One point per race; lower is faster.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LineChart("Best lap", progression.bestLaps, ChartColors.bestLap)
                    Spacer(Modifier.height(12.dp))
                    LineChart("Best 3 consecutive", progression.bestThrees, ChartColors.bestThree)
                }
            }
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Minimal line chart: one point per race, scaled between the series' min and max. */
@Composable
fun LineChart(title: String, values: List<Long?>, color: Color) {
    val points = values.withIndex().mapNotNull { (i, v) -> v?.let { i to it } }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f))
            if (points.isNotEmpty()) {
                Text(
                    "${lapText(points.minOf { it.second })} – ${lapText(points.maxOf { it.second })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val axisColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
            drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            if (points.isEmpty()) return@Canvas
            val min = points.minOf { it.second }
            val max = points.maxOf { it.second }
            val span = (max - min).coerceAtLeast(1L).toFloat()
            val pad = 6.dp.toPx()
            fun x(i: Int) = if (values.size == 1) size.width / 2 else pad + i * (size.width - 2 * pad) / (values.size - 1)
            fun y(v: Long) = size.height - pad - (v - min) / span * (size.height - 2 * pad)

            val path = Path()
            points.forEachIndexed { k, (i, v) ->
                if (k == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            points.forEach { (i, v) -> drawCircle(color, 3.5.dp.toPx(), Offset(x(i), y(v))) }
        }
    }
}
