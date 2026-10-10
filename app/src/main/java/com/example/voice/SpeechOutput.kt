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

    fun shutdown()
}

/** Android platform TTS implementation. Cheap, offline for most devices. */
class AndroidTextToSpeechOutput(
    context: Context,
    private val enabled: Boolean = true
) : SpeechOutput {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        if (enabled) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
    }

    override fun isReady(): Boolean = enabled && ready

    override fun speak(text: String) {
        if (!isReady() || text.isBlank()) return
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
