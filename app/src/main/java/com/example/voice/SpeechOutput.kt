package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * On-device speech output (plan 4.11). Inert until the user opts in; no cloud.
 */
interface SpeechOutput {
    /** True when speech output is enabled AND the engine is ready. */
    fun isReady(): Boolean

    /** Speaks [text] (bounded by the caller). No-op when not ready. */
    fun speak(text: String)

    /**
     * Speaks [text] using the best voice for [languageCode] (e.g. "hi" for Hindi),
     * falling back to the default locale when that voice is unavailable (plan 7.17).
     */
    fun speak(text: String, languageCode: String) = speak(text)

    fun shutdown()
}

/**
 * Android platform TTS implementation. Cheap, offline for most devices.
 *
 * [enabled] is a live supplier so the Settings toggle (plan 7.15) takes effect
 * immediately without rebuilding the orchestrator; the engine itself is only created
 * lazily the first time speech is requested while enabled.
 */
class AndroidTextToSpeechOutput(
    context: Context,
    private val enabled: () -> Boolean = { true }
) : SpeechOutput {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    override fun isReady(): Boolean {
        if (!enabled()) return false
        ensureEngine()
        return ready
    }

    override fun speak(text: String) {
        if (!isReady() || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "omniagent")
    }

    override fun speak(text: String, languageCode: String) {
        if (!isReady() || text.isBlank()) return
        if (languageCode.isNotBlank()) {
            val locale = Locale.forLanguageTag(languageCode)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // No Hindi voice installed: keep the default voice rather than going silent.
                tts?.language = Locale.getDefault()
            }
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "omniagent")
    }

    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        } finally {
            tts = null
            ready = false
        }
    }
}
