package com.gfpv.lapwing.ble

import android.content.Context

/**
 * Timer firmware shipped inside the APK. CI copies the firmware it built in the same run to
 * `assets/firmware/<target>.bin` with its version in `assets/firmware/version.txt`, so the app
 * can offer the matching update as soon as it connects. Local builds have no bundled firmware.
 */
object BundledFirmware {
    private const val DIR = "firmware"

    /** PlatformIO target name per ESP image chip id, as in platformio.ini. */
    private val targets = mapOf(0 to "ESP32", 5 to "ESP32C3", 9 to "ESP32S3")

    fun version(ctx: Context): String? =
        runCatching { ctx.assets.open("$DIR/version.txt").bufferedReader().use { it.readText().trim() } }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * The bundled image for a timer reporting [chipModel] (e.g. "ESP32-S3") and [board]
     * (the firmware's PlatformIO target name), or null if none fits. The exact board image
     * wins over the chip's generic one — e.g. a T-Energy S3 must get T-Energy-S3.bin, not
     * ESP32S3.bin, since both are ESP32-S3 chips but with different pinouts.
     */
    fun forChip(ctx: Context, chipModel: String, board: String = ""): FirmwareImage? {
        val chipId = FirmwareImage.expectedChipId(chipModel) ?: return null
        val names = buildList {
            if (board.isNotBlank()) add(board)
            targets[chipId]?.let { add(it) }
        }
        for (name in names) {
            val bytes = runCatching { ctx.assets.open("$DIR/$name.bin").use { it.readBytes() } }.getOrNull() ?: continue
            // If the board-specific image exists but is invalid, do NOT fall back to the
            // generic chip image: same chip id, different pins (e.g. VBAT on GPIO1 vs GPIO3).
            return runCatching { FirmwareImage.parse(bytes) }.getOrNull()?.takeIf { it.chipId == chipId }
        }
        return null
    }
}

/**
 * Compares dotted numeric versions such as "1.0.42". Returns null when either one is not
 * numeric, e.g. "dev" for a firmware built locally, since then there is no order to trust.
 */
fun compareVersions(a: String, b: String): Int? {
    val x = a.split('.').map { it.toIntOrNull() ?: return null }
    val y = b.split('.').map { it.toIntOrNull() ?: return null }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
