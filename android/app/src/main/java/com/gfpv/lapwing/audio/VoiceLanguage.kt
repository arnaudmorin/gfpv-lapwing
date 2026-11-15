package com.gfpv.lapwing.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Language of the spoken callouts. Times are formatted the way the voice reads them ("23,45" in French). */
enum class VoiceLanguage(val locale: Locale, val label: String) {
    FRENCH(Locale.FRANCE, "Français"),
    ENGLISH(Locale.US, "English");

    fun seconds(ms: Long): String = String.format(locale, "%.2f", ms / 1000.0)

    /** Built-in text of a callout, see [Callout] for the placeholders. */
    fun default(callout: Callout): String = when (this) {
        FRENCH -> when (callout) {
            Callout.ARM -> "Armez votre quad"
            Callout.START -> "Départ au bip dans moins de cinq secondes"
            Callout.HOLE_SHOT -> "Premier passage {time}"
            Callout.LAP -> "{pilot} Tour {lap}, {time}"
            Callout.LAPS -> "{pilot} {count} tours {time}"
            Callout.STOPPED -> "Course arrêtée"
        }
        ENGLISH -> when (callout) {
            Callout.ARM -> "Arm your quad"
            Callout.START -> "Starting on the tone in less than five"
            Callout.HOLE_SHOT -> "Hole shot {time}"
            Callout.LAP -> "{pilot} Lap {lap}, {time}"
            Callout.LAPS -> "{pilot} {count} laps {time}"
            Callout.STOPPED -> "Race stopped"
        }
    }

    fun testVoice(pilot: String): String = when (this) {
        FRENCH -> if (pilot.isBlank()) "Test du son" else "Test du son pour le pilote $pilot"
        ENGLISH -> if (pilot.isBlank()) "Testing sound" else "Testing sound for pilot $pilot"
    }

    companion object {
        val DEFAULT = FRENCH
    }
}

/** The callouts a pilot can rewrite in Settings, with the placeholders each one understands. */
enum class Callout(val label: String, val placeholders: List<String>) {
    ARM("Before the countdown", emptyList()),
    START("Countdown", emptyList()),
    HOLE_SHOT("First pass (hole shot)", listOf("{pilot}", "{time}")),
    LAP("Lap time", listOf("{pilot}", "{lap}", "{time}")),
    LAPS("2 or 3 consecutive laps", listOf("{pilot}", "{count}", "{time}")),
    STOPPED("Race stopped", emptyList()),
}

/**
 * Callout texts for one language: the pilot's own text where set, the built-in one otherwise.
 * A blank custom text also means the built-in one, so a cleared field never makes the app mute.
 */
class Callouts(val language: VoiceLanguage, private val custom: Map<Callout, String> = emptyMap()) {

    fun template(callout: Callout): String = custom[callout]?.takeIf { it.isNotBlank() } ?: language.default(callout)

    fun text(callout: Callout, pilot: String = "", lap: Int = 0, count: Int = 0, ms: Long = 0): String =
        template(callout)
            .replace("{pilot}", pilot)
            .replace("{lap}", lap.toString())
            .replace("{count}", count.toString())
            .replace("{time}", language.seconds(ms))
            // an empty {pilot} must not leave a stray space or comma behind
            .replace(Regex("\\s+"), " ")
            .replace(" ,", ",")
            .trim()
            .trimStart(',', ';', ':', '.', ' ')

    val arm get() = text(Callout.ARM)
    val start get() = text(Callout.START)
    val stopped get() = text(Callout.STOPPED)
    fun holeShot(pilot: String, ms: Long) = text(Callout.HOLE_SHOT, pilot = pilot, ms = ms)
    fun lap(pilot: String, index: Int, ms: Long) = text(Callout.LAP, pilot = pilot, lap = index, ms = ms)
    fun laps(pilot: String, count: Int, ms: Long) = text(Callout.LAPS, pilot = pilot, count = count, ms = ms)
}

/** The callout language and custom callout texts are phone settings, stored locally (not on the timer). */
class VoicePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { VoiceLanguage.valueOf(it) }.getOrNull() } ?: VoiceLanguage.DEFAULT,
    )
    val language: StateFlow<VoiceLanguage> = _language.asStateFlow()

    fun setLanguage(language: VoiceLanguage) {
        prefs.edit().putString(KEY, language.name).apply()
        _language.value = language
    }

    /** Custom texts per language; a callout missing from the map uses the built-in text. */
    private val _custom = MutableStateFlow(
        VoiceLanguage.entries.associateWith { lang ->
            Callout.entries.mapNotNull { c -> prefs.getString(customKey(lang, c), null)?.let { c to it } }.toMap()
        },
    )
    val custom: StateFlow<Map<VoiceLanguage, Map<Callout, String>>> = _custom.asStateFlow()

    fun callouts(language: VoiceLanguage = this.language.value) = Callouts(language, _custom.value[language].orEmpty())

    fun setCustom(language: VoiceLanguage, callout: Callout, text: String) {
        prefs.edit().putString(customKey(language, callout), text).apply()
        _custom.value = _custom.value + (language to (_custom.value[language].orEmpty() + (callout to text)))
    }

    fun resetCustom(language: VoiceLanguage) {
        prefs.edit().apply { Callout.entries.forEach { remove(customKey(language, it)) } }.apply()
        _custom.value = _custom.value + (language to emptyMap())
    }

    private fun customKey(language: VoiceLanguage, callout: Callout) = "callout.${language.name}.${callout.name}"

    private companion object {
        const val KEY = "language"
    }
}
