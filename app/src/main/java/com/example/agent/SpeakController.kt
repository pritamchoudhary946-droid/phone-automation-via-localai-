package com.example.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Result of a single "say this aloud" request. */
data class SpeakResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The on-device text-to-speech boundary for [AgentAction.Speak]. This is the "say
 * good morning" / "read that out loud" / "tell me what it says" primitive.
 *
 * Speech is an opt-in capability (the user's voice-output setting), so like
 * [AgentAction.ReadNotifications] it is resolved before the accessibility-service gate
 * and refuses honestly when the capability is off rather than silently doing nothing.
 * The system TTS engine is a platform service, not an accessibility-tree control, so no
 * screen read or UI tap can produce speech on an arbitrary screen.
 *
 * The controller reports success only when the platform confirmed the utterance was
 * accepted by the engine (and reports completion when the engine finishes): an engine
 * that fails to initialise or a `speak` call the engine rejects is an honest failure
 * ([SpeakResult.method] `TTS_UNAVAILABLE` / `TTS_ERROR` / `SPEAK_DISABLED`), never a fake
 * "spoken". This is what makes [AgentAction.Speak] self-evidencing despite carrying a
 * default `NONE` verification.
 *
 * It is injectable so the automation layer is testable without a real device. An unwired
 * controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface SpeakController {
    /**
     * Speaks [text] aloud. Implementations must report honestly: a device with no usable
     * TTS engine, or an utterance the engine rejected, is a failure, never a claimed
     * success. A successful result means the platform accepted the utterance.
     */
    fun speak(text: String): SpeakResult
}

/**
 * Real implementation backed by the platform [TextToSpeech] engine. The engine is created
 * lazily the first time speech is requested and its async initialisation is awaited under
 * a bounded budget. A completion [UtteranceProgressListener] confirms when the utterance
 * has actually been spoken; the result is `SPOKEN` once the engine accepted it (and it
 * waits briefly for completion), `TTS_ERROR` when the engine reported an error, and
 * `TTS_UNAVAILABLE` / `SPEAK_DISABLED` / `SPEAK_EMPTY` otherwise — never a fake success.
 */
class AndroidSpeakController(
    context: Context,
    /** Live opt-in gate: speech output must be enabled to speak. */
    private val enabled: () -> Boolean = { true }
) : SpeakController {

    private val appContext = context.applicationContext
    private val engineLock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false

    /** Set true when the engine reports an error for the in-flight utterance. */
    private val lastError = AtomicBoolean(false)

    /** Latch the completion listener counts down when the in-flight utterance ends. */
    private val inFlight = AtomicReference<CountDownLatch?>(null)

    private fun ensureReady(): Boolean {
        var latch: CountDownLatch? = null
        synchronized(engineLock) {
            if (tts == null) {
                val created = CountDownLatch(1)
                latch = created
                tts = TextToSpeech(appContext) { status ->
                    synchronized(engineLock) {
                        ready = status == TextToSpeech.SUCCESS
                        if (ready) {
                            tts?.language = Locale.getDefault()
                            attachListener(tts)
                        }
                    }
                    created.countDown()
                }
            } else if (ready) {
                return true
            }
        }
        // The engine initialises asynchronously; wait a bounded time for it, then report.
        latch?.await(INIT_BUDGET_MS, TimeUnit.MILLISECONDS)
        return synchronized(engineLock) { ready }
    }

    private fun attachListener(engine: TextToSpeech?) {
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                inFlight.get()?.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                lastError.set(true)
                inFlight.get()?.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                lastError.set(true)
                inFlight.get()?.countDown()
            }
        })
    }

    override fun speak(text: String): SpeakResult {
        if (!enabled()) {
            return SpeakResult(false, "SPEAK_DISABLED", "Speech output is turned off")
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return SpeakResult(false, "SPEAK_EMPTY", "There was nothing to say")
        }
        val bounded = trimmed.take(MAX_SPEAK_CHARS)
        if (!ensureReady()) {
            return SpeakResult(false, "TTS_UNAVAILABLE", "Speech output is not available on this device")
        }
        val engine = tts
            ?: return SpeakResult(false, "TTS_UNAVAILABLE", "Speech output is not available on this device")

        lastError.set(false)
        val done = CountDownLatch(1)
        inFlight.set(done)
        val utteranceId = "jarvis-${System.nanoTime()}"
        val queued = try {
            engine.speak(bounded, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.w(TAG, "speak failed: ${e.message}")
            inFlight.set(null)
            return SpeakResult(false, "TTS_ERROR", "The device could not speak: ${e.localizedMessage}")
        }
        if (queued != TextToSpeech.SUCCESS) {
            inFlight.set(null)
            return SpeakResult(false, "TTS_ERROR", "The device could not speak the text")
        }
        // Wait (bounded) for the engine to confirm the utterance finished. A long text may
        // outlast the budget; the utterance was still accepted, so report it as spoken.
        done.await(SPEAK_BUDGET_MS, TimeUnit.MILLISECONDS)
        inFlight.set(null)
        return if (lastError.get()) {
            SpeakResult(false, "TTS_ERROR", "The device could not speak the text")
        } else {
            SpeakResult(true, "SPOKEN", "Said: ${bounded.take(120)}")
        }
    }

    companion object {
        private const val TAG = "AndroidSpeakController"

        /** Bounded wait for the async TTS engine to initialise. */
        private const val INIT_BUDGET_MS = 2_000L

        /** Bounded wait for an utterance to finish before reporting it as spoken. */
        private const val SPEAK_BUDGET_MS = 4_000L

        /** Upper bound on a single utterance, mirroring the tool's validation. */
        private const val MAX_SPEAK_CHARS = 1_000
    }
}
