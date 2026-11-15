package com.gfpv.bletimer

import com.gfpv.bletimer.ble.AnnouncerType
import com.gfpv.bletimer.ble.FirmwareImage
import com.gfpv.bletimer.ble.Frequencies
import com.gfpv.bletimer.ble.LapEvent
import com.gfpv.bletimer.ble.OtaCommand
import com.gfpv.bletimer.ble.TimerConfig
import com.gfpv.bletimer.ble.TimerStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class ProtocolTest {
    @Test
    fun configRoundTrip() {
        val cfg = TimerConfig(5658, 55, 33, AnnouncerType.THREE_LAPS, 12, 140, 110, "Arnaud")
        assertEquals(cfg, TimerConfig.fromJson(cfg.toJson()))
    }

    @Test
    fun configFromFirmwareJson() {
        // exactly what Config::toJson produces
        val json = """{"freq":1111,"minLap":100,"alarm":36,"anType":2,"anRate":10,"enterRssi":120,"exitRssi":100,"name":""}"""
        val cfg = TimerConfig.fromJson(json)
        assertEquals(Frequencies.POWER_DOWN, cfg.frequency)
        assertEquals(AnnouncerType.LAP_TIME, cfg.announcerType)
        assertEquals(1.0f, cfg.announcerRate)
        assertEquals("RX off", Frequencies.label(cfg.frequency))
    }

    @Test
    fun lapIsLittleEndian() {
        // index 3, 23456 ms = 0x5BA0
        val lap = LapEvent.parse(byteArrayOf(0x03, 0x00, 0xA0.toByte(), 0x5B, 0x00, 0x00))
        assertEquals(LapEvent(3, 23456), lap)
        assertNull(LapEvent.parse(byteArrayOf(1, 2)))
    }

    @Test
    fun status() {
        assertEquals(TimerStatus(4.1f, true), TimerStatus.parse(byteArrayOf(41, 1)))
    }

    @Test
    fun frequencyLookup() {
        assertEquals(4 to 0, Frequencies.find(5658))
        assertEquals("R1 · 5658 MHz", Frequencies.label(5658))
        assertNull(Frequencies.find(5000))
    }

    private fun fakeImage(chipId: Int, appDescMagic: Long = 0xABCD5432L): ByteArray {
        val b = ByteArray(1024)
        b[0] = 0xE9.toByte()
        b[12] = chipId.toByte()
        for (i in 0 until 4) b[32 + i] = ((appDescMagic shr (8 * i)) and 0xFF).toByte()
        "v2.0.0".toByteArray().copyInto(b, 48)
        "PhobosLT".toByteArray().copyInto(b, 80)
        return b
    }

    @Test
    fun firmwareImageParsing() {
        val img = FirmwareImage.parse(fakeImage(5))
        assertEquals("ESP32-C3", img.chipName)
        assertEquals("v2.0.0", img.version)
        assertEquals("PhobosLT", img.projectName)

        val cmd = img.beginCommand()
        assertEquals(OtaCommand.BEGIN, cmd[0])
        assertArrayEquals(byteArrayOf(0x00, 0x04, 0x00, 0x00), cmd.copyOfRange(1, 5)) // 1024 LE
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(img.bytes), cmd.copyOfRange(5, 21))
    }

    @Test
    fun rejectsNonApplicationImages() {
        assertThrows(IllegalArgumentException::class.java) { FirmwareImage.parse(ByteArray(1024)) }
        // a bootloader / merged factory image starts with 0xE9 but has no app descriptor
        assertThrows(IllegalArgumentException::class.java) { FirmwareImage.parse(fakeImage(0, appDescMagic = 0)) }
    }

    @Test
    fun chipMatching() {
        assertEquals(0, FirmwareImage.expectedChipId("ESP32-D0WDQ6"))
        assertEquals(0, FirmwareImage.expectedChipId("ESP32-PICO-D4"))
        assertEquals(5, FirmwareImage.expectedChipId("ESP32-C3"))
        assertEquals(9, FirmwareImage.expectedChipId("ESP32-S3"))
    }
}
