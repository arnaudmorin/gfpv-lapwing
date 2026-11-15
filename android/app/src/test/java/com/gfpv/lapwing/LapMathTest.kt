package com.gfpv.lapwing

import com.gfpv.lapwing.audio.VoiceLanguage
import com.gfpv.lapwing.ble.AnnouncerType
import com.gfpv.lapwing.race.Lap
import com.gfpv.lapwing.race.Progression
import com.gfpv.lapwing.race.bestThreeConsecutive
import com.gfpv.lapwing.race.RaceStats
import com.gfpv.lapwing.race.consecutiveTime
import com.gfpv.lapwing.race.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun calloutFor(lang: VoiceLanguage, type: AnnouncerType, pilot: String, laps: List<Lap>, lap: Lap) =
    com.gfpv.lapwing.race.calloutFor(type, lang, pilot, laps, lap)

class LapMathTest {
    private val laps = listOf(Lap(0, 2_500), Lap(1, 10_000), Lap(2, 11_000), Lap(3, 9_000), Lap(4, 12_000))

    @Test
    fun consecutiveExcludesHoleShot() {
        assertNull(consecutiveTime(laps, laps[1], 2))
        assertEquals(21_000L, consecutiveTime(laps, laps[2], 2))
        assertNull(consecutiveTime(laps, laps[2], 3))
        assertEquals(30_000L, consecutiveTime(laps, laps[3], 3))
    }

    @Test
    fun consecutiveNeedsEveryLap() {
        val missing = listOf(Lap(0, 2_500), Lap(1, 10_000), Lap(3, 9_000))
        assertNull(consecutiveTime(missing, missing[2], 2))
    }

    @Test
    fun callouts() {
        assertEquals("Hole shot 2.50", calloutFor(VoiceLanguage.ENGLISH, AnnouncerType.LAP_TIME, "", laps, laps[0]))
        assertEquals("Ace Lap 2, 11.00", calloutFor(VoiceLanguage.ENGLISH, AnnouncerType.LAP_TIME, "Ace", laps, laps[2]))
        assertEquals("2 laps 21.00", calloutFor(VoiceLanguage.ENGLISH, AnnouncerType.TWO_LAPS, "", laps, laps[2]))
        assertNull(calloutFor(VoiceLanguage.ENGLISH, AnnouncerType.THREE_LAPS, "", laps, laps[2]))
        assertEquals("3 laps 30.00", calloutFor(VoiceLanguage.ENGLISH, AnnouncerType.THREE_LAPS, "", laps, laps[3]))
        assertNull(calloutFor(VoiceLanguage.ENGLISH, AnnouncerType.BEEP, "", laps, laps[3]))
    }

    @Test
    fun frenchCallouts() {
        val fr = VoiceLanguage.FRENCH
        assertEquals("Premier passage 2,50", calloutFor(fr, AnnouncerType.LAP_TIME, "", laps, laps[0]))
        assertEquals("Ace Tour 2, 11,00", calloutFor(fr, AnnouncerType.LAP_TIME, "Ace", laps, laps[2]))
        assertEquals("2 tours 21,00", calloutFor(fr, AnnouncerType.TWO_LAPS, "", laps, laps[2]))
        assertEquals("3 tours 30,00", calloutFor(fr, AnnouncerType.THREE_LAPS, "", laps, laps[3]))
    }

    @Test
    fun frenchIsDefault() {
        assertEquals(VoiceLanguage.FRENCH, VoiceLanguage.DEFAULT)
    }

    @Test
    fun stats() {
        val s = RaceStats.of(laps)
        assertEquals(4, s.lapCount)
        assertEquals(9_000L, s.bestMs)
        assertEquals(10_500L, s.averageMs)
        assertEquals(30_000L, s.bestThreeMs)
        assertEquals(2_500L, s.holeShotMs)
        assertEquals(1_118L, s.stdDevMs) // population std dev of 10, 11, 9, 12 s
    }

    @Test
    fun bestThreeConsecutiveLaps() {
        val (total, best) = bestThreeConsecutive(laps)!!
        assertEquals(30_000L, total)
        assertEquals(listOf(1, 2, 3), best.map { it.index })
        assertNull(bestThreeConsecutive(laps.take(3)))
    }

    @Test
    fun progressionAcrossRaces() {
        val older = listOf(Lap(0, 3_000), Lap(1, 12_000), Lap(2, 12_500))
        val newer = laps
        val p = Progression.of(listOf(older, newer))
        assertEquals(listOf(12_000L, 9_000L), p.bestLaps)
        assertEquals(listOf(null, 30_000L), p.bestThrees)
        assertEquals(9_000L, p.bestLapMs)
        assertEquals(30_000L, p.bestThreeMs)
        assertEquals(listOf(10_000L, 11_000L, 9_000L), p.bestThreeLaps)
    }

    @Test
    fun emptyProgression() {
        val p = Progression.of(emptyList())
        assertNull(p.bestLapMs)
        assertNull(p.bestThreeLaps)
    }

    @Test
    fun emptyStats() {
        val s = RaceStats.of(emptyList())
        assertEquals(0, s.lapCount)
        assertNull(s.bestMs)
    }

    @Test
    fun clock() {
        assertEquals("01:02.34", formatClock(62_345))
    }
}
