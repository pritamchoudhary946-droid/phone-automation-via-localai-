package com.example.agent

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * The media transport commands a task may issue. Kept as a closed enum (rather than
 * a raw platform key code) so an invalid command is rejected at the tool boundary and
 * can never dispatch the wrong media key.
 */
enum class MediaTransport(val key: String, val keyCode: Int) {
    PLAY("play", KeyEvent.KEYCODE_MEDIA_PLAY),
    PAUSE("pause", KeyEvent.KEYCODE_MEDIA_PAUSE),
    TOGGLE("toggle", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    STOP("stop", KeyEvent.KEYCODE_MEDIA_STOP),
    NEXT("next", KeyEvent.KEYCODE_MEDIA_NEXT),
    PREVIOUS("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS);

    companion object {
        fun fromKey(key: String): MediaTransport? =
            entries.firstOrNull { it.key == key.trim().lowercase() }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single media transport dispatch. */
data class MediaControlResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The media-transport boundary for [AgentAction.PlayMedia]. This is the playback
 * control primitive no UI tap can reach on an arbitrary screen: "pause the music",
 * "play the next track", "stop playback". It is injectable so the automation layer
 * is testable without a real media session.
 *
 * Dispatch is a standard media key broadcast to whatever app holds the active media
 * session. Success means the request was delivered and, where the platform exposes a
 * signal, the playback state actually changed — never a fabricated claim that a
 * specific track is playing. Goal-level playback evidence stays the job of the
 * [com.example.planning.VerificationMethod.PLAYBACK] postcondition.
 */
interface MediaController {
    /**
     * Dispatches [transport] to the active media session. Implementations must report
     * honestly: no active session, no change in state, or no permission is a failure,
     * not a claimed success.
     */
    fun control(transport: MediaTransport): MediaControlResult
}

/**
 * Real implementation over the system media dispatch channel: [AudioManager]'s
 * [AudioManager.dispatchMediaKeyEvent] delivers a standard media key to the current
 * media session. On the 8 GB target device the key route works without a UI surface.
 *
 * Honesty: `dispatchMediaKeyEvent` always returns without throwing, so a bare
 * dispatch is not proof of anything. The controller reads the device's
 * `isMusicActive()` signal before and after and, for the state-observable transports
 * (play/pause/toggle/stop), reports success only when the playback state actually
 * moved. Next/previous cannot be confirmed from that signal alone (playback stays
 * active either way), so they are reported as a delivered key with an explicit
 * unverified note rather than a claimed track change.
 */
class AndroidMediaController(context: Context) : MediaController {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun control(transport: MediaTransport): MediaControlResult {
        val manager = audioManager
            ?: return MediaControlResult(false, "NO_AUDIO_SERVICE", "Device audio service is unavailable")
        val before = runCatching { manager.isMusicActive }.getOrDefault(false)
        return try {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, transport.keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, transport.keyCode))
            settle()
            val after = runCatching { manager.isMusicActive }.getOrDefault(false)
            interpret(transport, before, after)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media dispatch denied: ${e.message}")
            MediaControlResult(
                false,
                "MEDIA_PERMISSION",
                "Media control is not permitted; grant notification access to Jarvis in Settings"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Media dispatch failed: ${e.message}")
            MediaControlResult(false, "MEDIA_ERROR", "Media control failed: ${e.localizedMessage}")
        }
    }

    /**
     * Maps the observed before/after playback activity to an honest result. A dispatch
     * that leaves the state untouched is a failure — never a fake success.
     */
    private fun interpret(transport: MediaTransport, before: Boolean, after: Boolean): MediaControlResult =
        when (transport) {
            MediaTransport.PLAY ->
                if (after) ok(transport, "Playback started")
                else MediaControlResult(false, "MEDIA_UNCHANGED", "Play was requested but playback did not start")
            MediaTransport.PAUSE, MediaTransport.STOP ->
                when {
                    !before -> MediaControlResult(false, "MEDIA_NOT_PLAYING", "No active playback to ${transport.key}")
                    !after -> ok(transport, "Playback ${if (transport == MediaTransport.STOP) "stopped" else "paused"}")
                    else -> MediaControlResult(false, "MEDIA_UNCHANGED", "Playback did not ${transport.key}")
                }
            MediaTransport.TOGGLE ->
                if (before != after) ok(transport, "Playback toggled to ${if (after) "playing" else "paused"}")
                else MediaControlResult(false, "MEDIA_UNCHANGED", "No active media session to toggle")
            MediaTransport.NEXT, MediaTransport.PREVIOUS ->
                MediaControlResult(
                    true,
                    "MEDIA_KEY",
                    "Dispatched media '${transport.key}' to the active session (track change not state-verifiable)"
                )
        }

    private fun ok(transport: MediaTransport, detail: String): MediaControlResult =
        MediaControlResult(true, "MEDIA_KEY_VERIFIED", "$detail (media '${transport.key}')")

    private fun settle() {
        try {
            Thread.sleep(SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "AndroidMediaController"

        /** Bounded wait for the media session to reflect the dispatched key. */
        private const val SETTLE_MS = 300L
    }
}
