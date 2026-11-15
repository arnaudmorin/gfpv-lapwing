package com.phoboslt.race

import android.os.SystemClock
import android.util.Log
import com.phoboslt.audio.Announcer
import com.phoboslt.ble.AnnouncerType
import com.phoboslt.ble.TimerConfig
import com.phoboslt.ble.TimerConnection
import com.phoboslt.data.LapEntity
import com.phoboslt.data.RaceDao
import com.phoboslt.data.RaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class RacePhase { IDLE, COUNTDOWN, RUNNING }

data class RaceState(
    val phase: RacePhase = RacePhase.IDLE,
    val laps: List<Lap> = emptyList(),
    /** SystemClock.elapsedRealtime() when the start tone sounded. */
    val startedAtElapsed: Long? = null,
    /** Frozen race duration once stopped. */
    val finalElapsedMs: Long? = null,
    val error: String? = null,
)

/**
 * Race flow of the web UI: spoken countdown, random 1-5 s delay, start tone, then tell the timer to start.
 * Laps notified by the timer are announced and saved to the history database.
 */
class RaceController(
    private val scope: CoroutineScope,
    private val connection: TimerConnection,
    private val announcer: Announcer,
    private val dao: RaceDao,
) {
    private val _state = MutableStateFlow(RaceState())
    val state: StateFlow<RaceState> = _state.asStateFlow()

    private var countdown: Job? = null
    private var raceId: Long? = null

    private val config: TimerConfig get() = connection.config.value ?: TimerConfig()

    init {
        scope.launch { connection.laps.collect { onLap(Lap(it.index, it.timeMs)) } }
    }

    fun start() {
        if (_state.value.phase != RacePhase.IDLE) return
        _state.value = RaceState(phase = RacePhase.COUNTDOWN)
        countdown = scope.launch {
            val cfg = config
            announcer.rate = cfg.announcerRate
            val voice = cfg.announcerType != AnnouncerType.NONE
            if (voice) {
                announcer.speakAndWait("Arm your quad")
                announcer.speakAndWait("Starting on the tone in less than five")
            }
            delay(Random.nextLong(1_000, 5_000))
            try {
                connection.startTimer()
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
                _state.value = RaceState(error = "Could not start the timer: ${e.message}")
                return@launch
            }
            launch { announcer.tone(880, 500) }
            raceId = dao.insertRace(RaceEntity(startedAt = System.currentTimeMillis(), pilot = cfg.pilotName, frequency = cfg.frequency))
            _state.update { it.copy(phase = RacePhase.RUNNING, startedAtElapsed = SystemClock.elapsedRealtime()) }
        }
    }

    fun stop() {
        val wasCountingDown = _state.value.phase == RacePhase.COUNTDOWN
        countdown?.cancel()
        announcer.stopSpeaking()
        _state.update {
            val elapsed = it.startedAtElapsed?.let { s -> SystemClock.elapsedRealtime() - s }
            it.copy(phase = RacePhase.IDLE, finalElapsedMs = elapsed)
        }
        scope.launch {
            runCatching { connection.stopTimer() }.onFailure { Log.w(TAG, "stop failed", it) }
            if (!wasCountingDown && config.announcerType != AnnouncerType.NONE) announcer.speak("Race stopped")
            raceId?.let { id ->
                if (dao.lapCount(id) == 0) dao.deleteRace(id) else dao.finishRace(id, System.currentTimeMillis())
            }
            raceId = null
        }
    }

    fun clearLaps() {
        _state.update { it.copy(laps = emptyList(), error = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun onLap(lap: Lap) {
        if (_state.value.phase != RacePhase.RUNNING) return
        if (_state.value.laps.any { it.index == lap.index }) return // duplicate after a reconnect
        _state.update { it.copy(laps = it.laps + lap) }
        raceId?.let { dao.insertLap(LapEntity(raceId = it, lapIndex = lap.index, timeMs = lap.timeMs)) }

        val cfg = config
        announcer.rate = cfg.announcerRate
        when (cfg.announcerType) {
            AnnouncerType.NONE -> Unit
            AnnouncerType.BEEP -> scope.launch { announcer.tone(330, 100) }
            else -> calloutFor(cfg.announcerType, cfg.pilotName, _state.value.laps, lap)?.let { announcer.speak(it) }
        }
    }

    companion object {
        private const val TAG = "RaceController"
    }
}
