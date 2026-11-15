package com.gfpv.bletimer.race

import com.gfpv.bletimer.audio.VoiceLanguage
import com.gfpv.bletimer.ble.AnnouncerType
import java.util.Locale
import kotlin.math.sqrt

data class Lap(val index: Int, val timeMs: Long) {
    val isHoleShot get() = index == 0
}

fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.2f", ms / 1000.0)

fun formatClock(ms: Long): String {
    val m = ms / 60_000
    val s = (ms / 1000) % 60
    val cs = (ms / 10) % 100
    return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
}

/**
 * Sum of the last [n] laps ending at [lap], or null if any of them is missing (a hole shot is not a lap,
 * and a lap notification may have been lost while out of range).
 */
fun consecutiveTime(laps: List<Lap>, lap: Lap, n: Int): Long? {
    if (lap.index - n + 1 < 1) return null
    val byIndex = laps.associateBy { it.index }
    var total = 0L
    for (i in lap.index - n + 1..lap.index) total += byIndex[i]?.timeMs ?: return null
    return total
}

/** Text to speak for a new lap, or null if the announcer type has nothing to say (none, beep, not enough laps). */
fun calloutFor(type: AnnouncerType, lang: VoiceLanguage, pilot: String, laps: List<Lap>, lap: Lap): String? {
    if (lap.isHoleShot) {
        return if (type in listOf(AnnouncerType.LAP_TIME, AnnouncerType.TWO_LAPS, AnnouncerType.THREE_LAPS)) {
            lang.holeShot(lap.timeMs)
        } else null
    }
    return when (type) {
        AnnouncerType.LAP_TIME -> lang.lap(pilot, lap.index, lap.timeMs)
        AnnouncerType.TWO_LAPS -> consecutiveTime(laps, lap, 2)?.let { lang.laps(pilot, 2, it) }
        AnnouncerType.THREE_LAPS -> consecutiveTime(laps, lap, 3)?.let { lang.laps(pilot, 3, it) }
        AnnouncerType.NONE, AnnouncerType.BEEP -> null
    }
}

data class RaceStats(
    val lapCount: Int,
    val bestMs: Long?,
    val averageMs: Long?,
    /** Standard deviation of the lap times, lower is more consistent. */
    val stdDevMs: Long?,
    val bestThreeMs: Long?,
    val holeShotMs: Long?,
) {
    companion object {
        fun of(laps: List<Lap>): RaceStats {
            val real = laps.filter { !it.isHoleShot }.map { it.timeMs }
            val avg = if (real.isEmpty()) null else real.average()
            val std = if (real.size < 2 || avg == null) null else sqrt(real.sumOf { (it - avg) * (it - avg) } / real.size)
            val bestThree = laps.filter { !it.isHoleShot }.mapNotNull { consecutiveTime(laps, it, 3) }.minOrNull()
            return RaceStats(
                lapCount = real.size,
                bestMs = real.minOrNull(),
                averageMs = avg?.toLong(),
                stdDevMs = std?.toLong(),
                bestThreeMs = bestThree,
                holeShotMs = laps.firstOrNull { it.isHoleShot }?.timeMs,
            )
        }
    }
}

/** Fastest 3 consecutive laps of a race (hole shot excluded) and the laps that make it up. */
fun bestThreeConsecutive(laps: List<Lap>): Pair<Long, List<Lap>>? {
    val byIndex = laps.associateBy { it.index }
    return laps.filter { !it.isHoleShot }
        .mapNotNull { lap -> consecutiveTime(laps, lap, 3)?.let { it to lap } }
        .minByOrNull { it.first }
        ?.let { (total, last) -> total to (last.index - 2..last.index).map { byIndex.getValue(it) } }
}

/** All-time records and per-race series for the History charts, as in fpv-android-lap-timer's Stats screen. */
data class Progression(
    /** Best lap of each race, oldest race first (null when a race has no full lap). */
    val bestLaps: List<Long?>,
    /** Best 3 consecutive laps of each race, oldest race first. */
    val bestThrees: List<Long?>,
    val bestLapMs: Long?,
    val bestThreeMs: Long?,
    val bestThreeLaps: List<Long>?,
) {
    companion object {
        fun of(racesOldestFirst: List<List<Lap>>): Progression {
            val stats = racesOldestFirst.map { RaceStats.of(it) }
            val bestThree = racesOldestFirst.mapNotNull { bestThreeConsecutive(it) }.minByOrNull { it.first }
            return Progression(
                bestLaps = stats.map { it.bestMs },
                bestThrees = stats.map { it.bestThreeMs },
                bestLapMs = stats.mapNotNull { it.bestMs }.minOrNull(),
                bestThreeMs = bestThree?.first,
                bestThreeLaps = bestThree?.second?.map { it.timeMs },
            )
        }
    }
}
