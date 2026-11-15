package com.gfpv.bletimer

import com.gfpv.bletimer.audio.VoiceLanguage
import com.gfpv.bletimer.ble.AnnouncerType
import com.gfpv.bletimer.race.Lap
import com.gfpv.bletimer.race.RaceStats
import com.gfpv.bletimer.race.consecutiveTime
import com.gfpv.bletimer.race.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun calloutFor(lang: VoiceLanguage, type: AnnouncerType, pilot: String, laps: List<Lap>, lap: Lap) =
    com.gfpv.bletimer.race.calloutFor(type, lang, pilot, laps, lap)

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
