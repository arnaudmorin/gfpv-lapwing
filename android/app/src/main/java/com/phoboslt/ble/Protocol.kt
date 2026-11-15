package com.phoboslt.ble

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** GATT layout of the timer, mirrors lib/BLESERVER/bleserver.h and docs/BLE_PROTOCOL.md. */
object PhobosUuids {
    private fun uuid(short: String) = UUID.fromString("5a3e$short-7c1f-4d8b-9a6e-2f4c1b0d9e10")

    val SERVICE: UUID = uuid("0001")
    val CONFIG: UUID = uuid("0002")
    val CONTROL: UUID = uuid("0003")
    val RSSI: UUID = uuid("0004")
    val LAP: UUID = uuid("0005")
    val STATUS: UUID = uuid("0006")
    val INFO: UUID = uuid("0007")
    val OTA_CTRL: UUID = uuid("0008")
    val OTA_DATA: UUID = uuid("0009")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

object ControlCommand {
    const val START: Byte = 0x01
    const val STOP: Byte = 0x02
}

object OtaCommand {
    const val BEGIN: Byte = 0x01
    const val END: Byte = 0x02
    const val ABORT: Byte = 0x03
    const val ERROR: Byte = 0xFF.toByte()

    fun errorText(code: Int): String = when (code) {
        1 -> "the timer could not start the update (image too large?)"
        2 -> "flash write failed"
        3 -> "verification failed (MD5 mismatch, wrong chip or corrupt image)"
        4 -> "invalid state (another update in progress?)"
        5 -> "size mismatch"
        else -> "error $code"
    }
}

enum class AnnouncerType(val label: String) {
    NONE("None"),
    BEEP("Beep"),
    LAP_TIME("Lap time"),
    TWO_LAPS("2 consecutive laps"),
    THREE_LAPS("3 consecutive laps");

    companion object {
        fun fromIndex(i: Int) = entries.getOrElse(i) { LAP_TIME }
    }
}

/** Timer configuration, in the same units as the firmware (see docs/BLE_PROTOCOL.md). */
data class TimerConfig(
    val frequency: Int = Frequencies.POWER_DOWN,
    val minLapDs: Int = 100,
    val alarmDv: Int = 36,
    val announcerType: AnnouncerType = AnnouncerType.LAP_TIME,
    val announcerRateX10: Int = 10,
    val enterRssi: Int = 120,
    val exitRssi: Int = 100,
    val pilotName: String = "",
) {
    val announcerRate: Float get() = announcerRateX10 / 10f

    fun toJson(): String = JSONObject()
        .put("freq", frequency)
        .put("minLap", minLapDs)
        .put("alarm", alarmDv)
        .put("anType", announcerType.ordinal)
        .put("anRate", announcerRateX10)
        .put("enterRssi", enterRssi)
        .put("exitRssi", exitRssi)
        .put("name", pilotName)
        .toString()

    companion object {
        const val MAX_NAME_LENGTH = 20

        fun fromJson(json: String): TimerConfig {
            val o = JSONObject(json)
            val d = TimerConfig()
            return TimerConfig(
                frequency = o.optInt("freq", d.frequency),
                minLapDs = o.optInt("minLap", d.minLapDs),
                alarmDv = o.optInt("alarm", d.alarmDv),
                announcerType = AnnouncerType.fromIndex(o.optInt("anType", d.announcerType.ordinal)),
                announcerRateX10 = o.optInt("anRate", d.announcerRateX10).coerceIn(1, 20),
                enterRssi = o.optInt("enterRssi", d.enterRssi),
                exitRssi = o.optInt("exitRssi", d.exitRssi),
                pilotName = o.optString("name", d.pilotName),
            )
        }
    }
}

object Frequencies {
    const val POWER_DOWN = 1111

    val bands = listOf("A", "B", "E", "F", "R", "L")
    val table = listOf(
        listOf(5865, 5845, 5825, 5805, 5785, 5765, 5745, 5725),
        listOf(5733, 5752, 5771, 5790, 5809, 5828, 5847, 5866),
        listOf(5705, 5685, 5665, 5645, 5885, 5905, 5925, 5945),
        listOf(5740, 5760, 5780, 5800, 5820, 5840, 5860, 5880),
        listOf(5658, 5695, 5732, 5769, 5806, 5843, 5880, 5917),
        listOf(5362, 5399, 5436, 5473, 5510, 5547, 5584, 5621),
    )

    /** (band, channel) indices for a frequency, or null if it is not in the table. */
    fun find(freq: Int): Pair<Int, Int>? {
        table.forEachIndexed { b, chans ->
            val c = chans.indexOf(freq)
            if (c >= 0) return b to c
        }
        return null
    }

    fun label(freq: Int): String {
        if (freq == POWER_DOWN) return "RX off"
        val bc = find(freq) ?: return "$freq MHz"
        return "${bands[bc.first]}${bc.second + 1} · $freq MHz"
    }
}

data class LapEvent(val index: Int, val timeMs: Long) {
    companion object {
        fun parse(b: ByteArray): LapEvent? {
            if (b.size < 6) return null
            return LapEvent(b.u16(0), b.u32(2))
        }
    }
}

data class TimerStatus(val batteryVolts: Float, val running: Boolean) {
    companion object {
        fun parse(b: ByteArray): TimerStatus? {
            if (b.size < 2) return null
            return TimerStatus((b[0].toInt() and 0xFF) / 10f, b[1].toInt() != 0)
        }
    }
}

data class DeviceInfo(val firmware: String, val chip: String, val name: String) {
    companion object {
        fun parse(json: String): DeviceInfo {
            val o = JSONObject(json)
            return DeviceInfo(o.optString("fw"), o.optString("chip"), o.optString("name"))
        }
    }
}

/** An ESP32 application image as produced by PlatformIO (.pio/build/<env>/firmware.bin). */
class FirmwareImage(val bytes: ByteArray) {
    val chipId: Int = bytes.u16(CHIP_ID_OFFSET)
    val version: String = bytes.cString(APP_DESC_OFFSET + 16, 32)
    val projectName: String = bytes.cString(APP_DESC_OFFSET + 48, 32)
    val md5: ByteArray by lazy { MessageDigest.getInstance("MD5").digest(bytes) }

    val chipName: String get() = chipNames[chipId] ?: "unknown chip ($chipId)"

    fun beginCommand(): ByteArray {
        val cmd = ByteArray(1 + 4 + 16)
        cmd[0] = OtaCommand.BEGIN
        cmd.putU32(1, bytes.size.toLong())
        md5.copyInto(cmd, 5)
        return cmd
    }

    companion object {
        private const val IMAGE_MAGIC = 0xE9
        private const val CHIP_ID_OFFSET = 12
        private const val APP_DESC_OFFSET = 32 // after the 24 byte image header and 8 byte first segment header
        private const val APP_DESC_MAGIC = 0xABCD5432L

        private val chipNames = mapOf(0 to "ESP32", 2 to "ESP32-S2", 5 to "ESP32-C3", 9 to "ESP32-S3", 13 to "ESP32-C6")

        /** Returns the parsed image, or throws with a user facing reason. */
        fun parse(bytes: ByteArray): FirmwareImage {
            require(bytes.size > 256) { "File is too small to be a firmware image" }
            require((bytes[0].toInt() and 0xFF) == IMAGE_MAGIC) { "Not an ESP32 firmware image" }
            require(bytes.u32(APP_DESC_OFFSET) == APP_DESC_MAGIC) {
                "Not an application image. Use firmware.bin, not a merged/factory image or the bootloader"
            }
            return FirmwareImage(bytes)
        }

        /** Image chip id expected for a chip model string reported by the firmware (ESP.getChipModel()). */
        fun expectedChipId(chipModel: String): Int? {
            val m = chipModel.uppercase()
            return when {
                "C3" in m -> 5
                "S3" in m -> 9
                "S2" in m -> 2
                "C6" in m -> 13
                m.startsWith("ESP32") -> 0
                else -> null
            }
        }
    }
}

internal fun ByteArray.u16(off: Int): Int = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32(off: Int): Long =
    (this[off].toLong() and 0xFF) or ((this[off + 1].toLong() and 0xFF) shl 8) or
        ((this[off + 2].toLong() and 0xFF) shl 16) or ((this[off + 3].toLong() and 0xFF) shl 24)

internal fun ByteArray.putU32(off: Int, v: Long) {
    for (i in 0 until 4) this[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
}

internal fun ByteArray.cString(off: Int, max: Int): String {
    if (off >= size) return ""
    val end = (off until minOf(size, off + max)).firstOrNull { this[it] == 0.toByte() } ?: minOf(size, off + max)
    return String(this, off, end - off, Charsets.US_ASCII)
}
