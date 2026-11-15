package com.phoboslt.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoboslt.race.Lap
import com.phoboslt.race.RacePhase
import com.phoboslt.race.RaceStats
import com.phoboslt.race.consecutiveTime
import com.phoboslt.race.formatClock
import com.phoboslt.race.formatSeconds

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun RaceScreen(vm: MainViewModel, reconnecting: Boolean) {
    KeepScreenOn()
    val race by vm.race.state.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(race.phase) {
        while (race.phase == RacePhase.RUNNING) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val elapsed = when {
        race.phase == RacePhase.RUNNING && race.startedAtElapsed != null -> now - race.startedAtElapsed!!
        else -> race.finalElapsedMs ?: 0L
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reconnecting) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Connection lost, reconnecting… Laps crossed meanwhile are still timed by the timer but may not be shown.",
                    Modifier.padding(12.dp),
                )
            }
        }
        race.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f))
                    TextButton(onClick = vm.race::dismissError) { Text("OK") }
                }
            }
        }

        Text(
            formatClock(elapsed),
            style = MaterialTheme.typography.displayLarge,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (race.phase == RacePhase.COUNTDOWN) {
            Text("Get ready… start on the tone", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = vm.race::start,
                enabled = race.phase == RacePhase.IDLE && !reconnecting,
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
            Button(onClick = vm.race::stop, enabled = race.phase != RacePhase.IDLE, modifier = Modifier.weight(1f)) { Text("Stop") }
            OutlinedButton(onClick = vm.race::clearLaps, enabled = race.laps.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
        }

        StatsRow(RaceStats.of(race.laps))
        LazyColumn(Modifier.fillMaxWidth()) {
            lapTable(race.laps, newestFirst = true)
        }
    }
}

@Composable
fun StatsRow(stats: RaceStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Laps", stats.lapCount.toString())
        Stat("Best", stats.bestMs?.let { formatSeconds(it) } ?: "–")
        Stat("Average", stats.averageMs?.let { formatSeconds(it) } ?: "–")
        Stat("Best 3", stats.bestThreeMs?.let { formatSeconds(it) } ?: "–")
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

fun LazyListScope.lapTable(laps: List<Lap>, newestFirst: Boolean) {
    val best = laps.filter { !it.isHoleShot }.minOfOrNull { it.timeMs }
    item {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf("Lap", "Time", "2 laps", "3 laps").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()
    }
    items(if (newestFirst) laps.reversed() else laps, key = { it.index }) { lap ->
        val isBest = !lap.isHoleShot && lap.timeMs == best
        Row(
            Modifier.fillMaxWidth()
                .then(if (isBest) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                .padding(vertical = 8.dp),
        ) {
            val cell = Modifier.weight(1f)
            Text(if (lap.isHoleShot) "HS" else lap.index.toString(), cell)
            Text(formatSeconds(lap.timeMs), cell, fontFamily = FontFamily.Monospace, fontWeight = if (isBest) FontWeight.Bold else null)
            Text(consecutiveTime(laps, lap, 2)?.let { formatSeconds(it) } ?: "", cell, fontFamily = FontFamily.Monospace)
            Text(consecutiveTime(laps, lap, 3)?.let { formatSeconds(it) } ?: "", cell, fontFamily = FontFamily.Monospace)
        }
    }
}
