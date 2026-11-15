package com.gfpv.lapwing.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/** Voice callouts (Android TextToSpeech) and tones, replacing articulate.js / WebAudio of the web UI. */
class Announcer(context: Context, private val voicePrefs: VoicePrefs) {
    private val ready = CompletableDeferred<Boolean>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ids = AtomicInteger()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                done.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                done.remove(utteranceId)?.complete(Unit)
            }
        })
        tts.setAudioAttributes(attributes)
    }

    @Volatile var rate: Float = 1f

    /** Current callout language (a phone setting, see [VoicePrefs]). */
    val language: VoiceLanguage get() = voicePrefs.language.value

    /** Callout texts in the current language, including the pilot's own texts. */
    val callouts: Callouts get() = voicePrefs.callouts()

    private suspend fun prepare(): Boolean {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        if (ok) {
            tts.language = language.locale
            tts.setSpeechRate(rate)
        }
        return ok
    }

    /** Queues a callout without waiting for it. */
    suspend fun speak(text: String) {
        if (!prepare()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "u${ids.incrementAndGet()}")
    }

    /** Speaks and suspends until the callout has been spoken. */
    suspend fun speakAndWait(text: String) {
        if (!prepare()) return
        val id = "u${ids.incrementAndGet()}"
        val d = CompletableDeferred<Unit>()
        done[id] = d
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        withTimeoutOrNull(10_000) { d.await() }
        done.remove(id)
    }

    /** False if the phone has no text-to-speech voice for [lang] (it can be installed in Android settings). */
    suspend fun isAvailable(lang: VoiceLanguage): Boolean {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        return ok && tts.isLanguageAvailable(lang.locale) >= TextToSpeech.LANG_AVAILABLE
    }

    fun stopSpeaking() {
        tts.stop()
    }

    /** Plays a square wave tone, like the web UI's start (880 Hz) and lap (330 Hz) beeps. */
    suspend fun tone(frequencyHz: Int, durationMs: Int) = withContext(Dispatchers.Default) {
        val sampleRate = 44_100
        val samples = sampleRate * durationMs / 1000
        val pcm = ShortArray(samples) { i ->
            val s = sin(2 * PI * frequencyHz * i / sampleRate)
            // soft square wave at half volume, with a short fade to avoid clicks
            val fade = minOf(1.0, minOf(i, samples - i) / (sampleRate * 0.005))
            ((if (s >= 0) 1 else -1) * 0.5 * fade * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(durationMs + 50L)
        } finally {
            track.release()
        }
    }

    companion object {
        private val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
