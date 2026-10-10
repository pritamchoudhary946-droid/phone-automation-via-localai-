package com.example.voice

/**
 * Wake-word / continuous listening seam (plan 4.10).
 *
 * Must remain completely inert until the user explicitly opts in AND microphone
 * permission is granted. Implementations report [isEnabled] = false when off so
 * no audio is ever captured silently.
 */
interface WakeWordDetector {
    /** True only when listening is permitted (opt-in + permission) and armed. */
    fun isEnabled(): Boolean

    /** Arms listening. No-op when not permitted. */
    fun start()

    /** Disarms listening. Safe to call repeatedly. */
    fun stop()
}

/**
 * Default detector: disabled unless constructed with an explicit opt-in. This is
 * the safe shipped default and what tests assert (never listens when off).
 */
class OptInWakeWordDetector(
    private val optIn: Boolean,
    private val hasMicPermission: () -> Boolean
) : WakeWordDetector {

    private var armed = false

    override fun isEnabled(): Boolean = optIn && hasMicPermission()

    override fun start() {
        if (isEnabled()) armed = true
    }

    override fun stop() {
        armed = false
    }

    /** True only while actually listening. */
    fun isListening(): Boolean = armed && isEnabled()
}
