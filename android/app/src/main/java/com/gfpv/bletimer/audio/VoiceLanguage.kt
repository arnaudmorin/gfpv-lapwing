package com.gfpv.bletimer.audio

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

    val armQuad: String
        get() = when (this) {
            FRENCH -> "Armez votre drone"
            ENGLISH -> "Arm your quad"
        }

    val startOnTone: String
        get() = when (this) {
            FRENCH -> "Départ au bip dans moins de cinq secondes"
            ENGLISH -> "Starting on the tone in less than five"
        }

    val raceStopped: String
        get() = when (this) {
            FRENCH -> "Course arrêtée"
            ENGLISH -> "Race stopped"
        }

    fun holeShot(ms: Long): String = when (this) {
        FRENCH -> "Premier passage ${seconds(ms)}"
        ENGLISH -> "Hole shot ${seconds(ms)}"
    }

    fun lap(pilot: String, index: Int, ms: Long): String = when (this) {
        FRENCH -> "${prefix(pilot)}Tour $index, ${seconds(ms)}"
        ENGLISH -> "${prefix(pilot)}Lap $index, ${seconds(ms)}"
    }

    /** Time of [count] consecutive laps. */
    fun laps(pilot: String, count: Int, ms: Long): String = when (this) {
        FRENCH -> "${prefix(pilot)}$count tours ${seconds(ms)}"
        ENGLISH -> "${prefix(pilot)}$count laps ${seconds(ms)}"
    }

    fun testVoice(pilot: String): String = when (this) {
        FRENCH -> if (pilot.isBlank()) "Test du son" else "Test du son pour le pilote $pilot"
        ENGLISH -> if (pilot.isBlank()) "Testing sound" else "Testing sound for pilot $pilot"
    }

    private fun prefix(pilot: String) = if (pilot.isBlank()) "" else "$pilot "

    companion object {
        val DEFAULT = FRENCH
    }
}

/** The callout language is a phone setting, stored locally (not on the timer). */
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

    private companion object {
        const val KEY = "language"
    }
}
