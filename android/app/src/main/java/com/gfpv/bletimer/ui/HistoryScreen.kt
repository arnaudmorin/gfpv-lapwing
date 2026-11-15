package com.gfpv.bletimer.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfpv.bletimer.ble.Frequencies
import com.gfpv.bletimer.data.RaceWithLaps
import com.gfpv.bletimer.race.Lap
import com.gfpv.bletimer.race.RaceStats
import com.gfpv.bletimer.race.consecutiveTime
import com.gfpv.bletimer.race.formatSeconds

private fun RaceWithLaps.toLaps() = laps.map { Lap(it.lapIndex, it.timeMs) }.sortedBy { it.index }

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val races by vm.races.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    val selected = races.firstOrNull { it.race.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null }
        RaceDetail(vm, selected, onBack = { selectedId = null })
        return
    }

    if (races.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No races yet. Races with at least one lap are saved here automatically.")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        items(races, key = { it.race.id }) { r ->
            val stats = remember(r) { RaceStats.of(r.toLaps()) }
            Card(onClick = { selectedId = r.race.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(raceTitle(r), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${stats.lapCount} laps · best ${stats.bestMs?.let { formatSeconds(it) } ?: "–"} · " +
                            Frequencies.label(r.race.frequency),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun raceTitle(r: RaceWithLaps): String {
    val ctx = LocalContext.current
    val date = DateUtils.formatDateTime(
        ctx, r.race.startedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
    return if (r.race.pilot.isBlank()) date else "$date · ${r.race.pilot}"
}

@Composable
private fun RaceDetail(vm: MainViewModel, r: RaceWithLaps, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val laps = remember(r) { r.toLaps() }
    val stats = remember(laps) { RaceStats.of(laps) }
    var confirmDelete by remember { mutableStateOf(false) }
    val title = raceTitle(r)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this race?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteRace(r.race.id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(title, laps, stats))
                    ctx.startActivity(Intent.createChooser(send, null))
                }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            }
            StatsRow(stats)
            Text(
                "Hole shot ${stats.holeShotMs?.let { formatSeconds(it) } ?: "–"} · " +
                    "consistency ±${stats.stdDevMs?.let { formatSeconds(it) } ?: "–"} s",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        lapTable(laps, newestFirst = false)
    }
}

private fun shareText(title: String, laps: List<Lap>, stats: RaceStats): String = buildString {
    appendLine("GFPV Bluetooth Timer race $title")
    stats.bestMs?.let { appendLine("Best lap ${formatSeconds(it)} s") }
    stats.bestThreeMs?.let { appendLine("Best 3 consecutive ${formatSeconds(it)} s") }
    laps.forEach { lap ->
        val name = if (lap.isHoleShot) "Hole shot" else "Lap ${lap.index}"
        append("$name: ${formatSeconds(lap.timeMs)} s")
        consecutiveTime(laps, lap, 3)?.let { append(" (3 laps ${formatSeconds(it)} s)") }
        appendLine()
    }
}
