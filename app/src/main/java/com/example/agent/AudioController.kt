package com.example.agent

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * The named audio streams a task may target. Kept as a closed enum (rather than the
 * raw platform integer) so an invalid stream is rejected at the tool boundary and can
 * never index [AudioManager] incorrectly.
 */
enum class AudioStream(val key: String, val managerStream: Int) {
    MEDIA("media", AudioManager.STREAM_MUSIC),
    RING("ring", AudioManager.STREAM_RING),
    ALARM("alarm", AudioManager.STREAM_ALARM),
    NOTIFICATION("notification", AudioManager.STREAM_NOTIFICATION),
    CALL("call", AudioManager.STREAM_VOICE_CALL);

    companion object {
        fun fromKey(key: String): AudioStream? =
            entries.firstOrNull { it.key == key.trim().lowercase() }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single device audio-stream adjustment. */
data class AudioAdjustResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The device-audio boundary for [AgentAction.AdjustVolume]. This is the media-control
 * primitive that no UI tap can reach on an arbitrary screen: "turn the volume up",
 * "mute the music", "lower the ring volume". It is injectable so the automation layer
 * is testable without a real [AudioManager].
 */
interface AudioController {
    /**
     * Adjusts [stream] by [steps] platform increments (negative lowers, positive
     * raises) and, when [mute] is set, silences that stream instead. Implementations
     * must report honestly: a read-back that shows the level did not move is a failure,
     * never a claimed success.
     */
    fun adjust(stream: AudioStream, steps: Int, mute: Boolean): AudioAdjustResult
}

/**
 * Real implementation over the system [AudioManager]. Adjustments use the platform's
 * own volume increment via [AudioManager.adjustStreamVolume] so one step matches one
 * hardware volume press. The resulting level is read back and compared to the level
 * before the call, so a device that ignores the request (e.g. a fixed-volume stream
 * without the needed permission) is reported as a failure rather than a success.
 */
class AndroidAudioController(context: Context) : AudioController {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun adjust(stream: AudioStream, steps: Int, mute: Boolean): AudioAdjustResult {
        val manager = audioManager
            ?: return AudioAdjustResult(false, "NO_AUDIO_SERVICE", "Device audio service is unavailable")

        val managerStream = stream.managerStream
        val before = manager.getStreamVolume(managerStream)
        val flags = 0 // Do not surface the system volume UI; the agent acts silently.
        try {
            if (mute) {
                manager.adjustStreamVolume(managerStream, AudioManager.ADJUST_MUTE, flags)
            } else if (steps == 0) {
                // steps == 0 is an explicit mute in the tool contract.
                manager.adjustStreamVolume(managerStream, AudioManager.ADJUST_MUTE, flags)
            } else {
                val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(kotlin.math.abs(steps)) { manager.adjustStreamVolume(managerStream, direction, flags) }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Volume adjustment denied: ${e.message}")
            return AudioAdjustResult(
                false,
                "AUDIO_PERMISSION",
                "Changing device volume is not permitted (MODIFY_AUDIO_SETTINGS is not granted)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Volume adjustment failed: ${e.message}")
            return AudioAdjustResult(false, "AUDIO_ERROR", "Volume change failed: ${e.localizedMessage}")
        }

        val after = manager.getStreamVolume(managerStream)
        val isMuted = if (mute || steps == 0) manager.isStreamMute(managerStream) else false
        // A fixed-volume stream may be legitimately unchangeable; do not fabricate a change.
        val changed = after != before || (mute || steps == 0) && isMuted
        return if (changed) {
            val what = when {
                mute || steps == 0 -> "muted"
                steps > 0 -> "raised by ${kotlin.math.abs(steps)} step(s)"
                else -> "lowered by ${kotlin.math.abs(steps)} step(s)"
            }
            AudioAdjustResult(true, "AUDIO_VOLUME", "${stream.key} volume $what (level $before -> $after)")
        } else {
            AudioAdjustResult(
                false,
                "AUDIO_UNCHANGED",
                "${stream.key} volume did not change (level stayed at $before); the stream may be fixed or unavailable"
            )
        }
    }

    companion object {
        private const val TAG = "AndroidAudioController"
    }
}
