package com.phoboslt

import com.phoboslt.ble.AnnouncerType
import com.phoboslt.race.Lap
import com.phoboslt.race.RaceStats
import com.phoboslt.race.calloutFor
import com.phoboslt.race.consecutiveTime
import com.phoboslt.race.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertEquals("Hole shot 2.50", calloutFor(AnnouncerType.LAP_TIME, "", laps, laps[0]))
        assertEquals("Ace Lap 2, 11.00", calloutFor(AnnouncerType.LAP_TIME, "Ace", laps, laps[2]))
        assertEquals("2 laps 21.00", calloutFor(AnnouncerType.TWO_LAPS, "", laps, laps[2]))
        assertNull(calloutFor(AnnouncerType.THREE_LAPS, "", laps, laps[2]))
        assertEquals("3 laps 30.00", calloutFor(AnnouncerType.THREE_LAPS, "", laps, laps[3]))
        assertNull(calloutFor(AnnouncerType.BEEP, "", laps, laps[3]))
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
