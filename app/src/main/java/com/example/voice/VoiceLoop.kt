package com.example.voice

/**
 * Full hands-free voice loop (plan 9.7): wake -> listen -> confirm -> execute ->
 * spoken result, with a hard stop phrase. Everything here is inert unless the user
 * opted in AND granted microphone permission; no audio ever leaves the device.
 *
 * [parseUtterance] and [isConfirmation]/[isDenial]/[isStopPhrase] are pure so the
 * whole dialogue policy is unit-testable without a microphone.
 */
object VoiceLoop {

    const val DEFAULT_WAKE_WORD = "jarvis"

    sealed class Utterance {
        /** No wake word: ignored entirely (nothing is captured or acted on). */
        object Ignored : Utterance()
        /** A command to run, with the wake word stripped. */
        data class Command(val command: String) : Utterance()
        /** The hard stop phrase: halt the current run. */
        object Stop : Utterance()
    }

    /**
     * Interprets one recognised utterance. The wake word may appear anywhere, so
     * "what's the weather, Jarvis" still counts. A bare stop phrase always wins.
     */
    fun parseUtterance(text: String, wakeWord: String = DEFAULT_WAKE_WORD): Utterance {
        val raw = text.trim()
        if (raw.isBlank()) return Utterance.Ignored
        if (isStopPhrase(raw) && !raw.lowercase().contains(wakeWord)) return Utterance.Stop
        val lower = raw.lowercase()
        val idx = lower.indexOf(wakeWord)
        if (idx < 0) return Utterance.Ignored
        val after = raw.substring(idx + wakeWord.length).trim().trimStart(',', '.', '!', '-').trim()
        if (after.isBlank()) return Utterance.Command("") // wake only, awaiting the command
        return when {
            isStopPhrase(after) -> Utterance.Stop
            else -> Utterance.Command(after)
        }
    }

    fun isStopPhrase(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "stop" || t == "ruko" || t == "band karo" || t == "cancel" ||
            t.contains("stop listening") || t.contains("stop it")
    }

    fun isConfirmation(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "yes" || t == "yeah" || t == "confirm" || t == "ok" || t == "okay" ||
            t == "haan" || t == "ha" || t == "sure" || t == "do it" || t == "go ahead"
    }

    fun isDenial(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "no" || t == "nope" || t == "nahi" || t == "nahin" || t == "cancel" ||
            t == "stop" || t == "don't" || t == "dont"
    }

    /**
     * A short, spoken confirmation for a risky action, phrased in the user's language
     * (plan 9.13). The caller must hear a [isConfirmation] reply before running it.
     */
    fun confirmationPrompt(actionLabel: String, hindi: Boolean): String =
        if (hindi) "$actionLabel karne se pehle confirm kariye. Haan ya nahi?"
        else "$actionLabel. Should I go ahead?"
}

/**
 * Minimal, on-device speech-recognition seam. [listenOnce] returns the recognised
 * text, or null when nothing was heard. Production uses the platform recogniser;
 * tests use a fake. No implementation may upload audio.
 */
interface SpeechRecognizer {
    /** True when the microphone is available and permission has been granted. */
    fun isAvailable(): Boolean
    suspend fun listenOnce(timeoutMs: Long = 6_000L): String?
}

/**
 * Drives the hands-free dialogue. All side effects are injected, so the loop is
 * fully unit-testable and stays inert unless [optIn] and [micPermission] both pass.
 *
 * @param optIn live settings gate (handsFreeEnabled)
 * @param micPermission live microphone permission check
 * @param recognizer on-device listening seam
 * @param speak spoken output (already language-aware)
 * @param runCommand runs a command; returns true when it started
 * @param confirmRisk runs a risky command only after the user says yes
 * @param stopCurrent stops the current run (the hard stop phrase)
 */
class VoiceLoopController(
    private val optIn: () -> Boolean,
    private val micPermission: () -> Boolean,
    private val recognizer: SpeechRecognizer,
    private val speak: (String, String) -> Unit,
    private val runCommand: (String) -> Boolean,
    private val stopCurrent: () -> Unit,
    private val wakeWord: String = VoiceLoop.DEFAULT_WAKE_WORD
) {
    data class Result(val action: String, val detail: String = "")

    /** One listen-and-act cycle. Returns what happened, for logging and tests. */
    suspend fun cycle(): Result {
        if (!optIn()) return Result("off", "hands-free is disabled")
        if (!micPermission()) return Result("no_permission", "microphone permission not granted")
        if (!recognizer.isAvailable()) return Result("unavailable", "recognizer not ready")

        val heard = recognizer.listenOnce() ?: return Result("silence")
        return when (val u = VoiceLoop.parseUtterance(heard, wakeWord)) {
            is VoiceLoop.Utterance.Ignored -> Result("ignored", heard)
            is VoiceLoop.Utterance.Stop -> {
                stopCurrent()
                Result("stopped")
            }
            is VoiceLoop.Utterance.Command -> {
                if (u.command.isBlank()) return Result("wake_only")
                val hindi = LanguageDetector.prefersHindi(u.command)
                val started = runCommand(u.command)
                // Speak the truth: a command that could not start (no plan, model not
                // ready, or a clarifying question) must not be acknowledged as running.
                val ack = when {
                    !started -> if (hindi) "Maaf kijiye, main yeh nahi kar paya." else "Sorry, I couldn't start that."
                    hindi -> "Theek hai."
                    else -> "Okay."
                }
                speak(ack, if (hindi) "hi" else "")
                return if (started) Result("ran", u.command) else Result("not_ran", u.command)
            }
        }
    }

    /**
     * A risky action: asks for spoken confirmation and runs it only on an explicit
     * yes. A denial or silence cancels; nothing is auto-sent.
     */
    suspend fun confirmAndRun(actionLabel: String, action: () -> Unit): Result {
        if (!optIn() || !micPermission()) return Result("off", "confirmation requires hands-free")
        val hindi = LanguageDetector.prefersHindi(actionLabel)
        speak(VoiceLoop.confirmationPrompt(actionLabel, hindi), if (hindi) "hi" else "")
        val reply = recognizer.listenOnce() ?: return Result("no_reply", "confirm cancelled")
        return when {
            VoiceLoop.isConfirmation(reply) -> {
                action()
                Result("confirmed", reply)
            }
            VoiceLoop.isDenial(reply) -> Result("denied", reply)
            else -> Result("unclear", reply)
        }
    }
}
