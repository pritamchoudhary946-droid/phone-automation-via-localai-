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
 *
 * 8.7/BS3: a second constructor takes live suppliers so the hands-free flow can arm
 * the detector against the current settings toggle + permission (the class was
 * otherwise dead code, referenced by nothing).
 */
class OptInWakeWordDetector(
    private val optIn: Boolean,
    private val hasMicPermission: () -> Boolean
) : WakeWordDetector {

    constructor(
        optIn: () -> Boolean,
        hasMicPermission: () -> Boolean
    ) : this(optIn = optIn(), hasMicPermission = hasMicPermission) {
        this.liveOptIn = optIn
    }

    private var liveOptIn: (() -> Boolean)? = null

    private var armed = false

    override fun isEnabled(): Boolean = (liveOptIn?.invoke() ?: optIn) && hasMicPermission()

    override fun start() {
        if (isEnabled()) armed = true
    }

    override fun stop() {
        armed = false
    }

    /** True only while actually listening. */
    fun isListening(): Boolean = armed && isEnabled()
}
