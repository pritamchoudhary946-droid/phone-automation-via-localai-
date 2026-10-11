package com.example.agent

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * The named ringer modes a task may switch the device into. Kept as a closed enum
 * (rather than the raw platform integer) so an invalid mode is rejected at the tool
 * boundary and can never index [AudioManager] incorrectly.
 */
enum class RingerMode(val key: String, val managerMode: Int) {
    SILENT("silent", AudioManager.RINGER_MODE_SILENT),
    VIBRATE("vibrate", AudioManager.RINGER_MODE_VIBRATE),
    NORMAL("normal", AudioManager.RINGER_MODE_NORMAL);

    companion object {
        fun fromKey(key: String): RingerMode? =
            entries.firstOrNull { it.key == key.trim().lowercase() }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single ringer-mode switch. */
data class RingerModeResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The ringer-mode boundary for [AgentAction.SetRingerMode]. This is the "put the phone
 * on silent" / "unmute my phone" primitive: the ringer mode is a system audio setting
 * that no accessibility-tree read or UI tap can change reliably on an arbitrary screen
 * (and driving it through the volume panel would also touch the volume level).
 *
 * It is injectable so the automation layer is testable without a real [AudioManager].
 * An unwired controller returns an honest "unavailable" result rather than a silent
 * no-op.
 */
interface RingerModeController {
    /**
     * Switches the device to [mode]. Implementations must report honestly: if the
     * platform refused the change (e.g. the notification policy is not granted, or the
     * device ignores it), the result is a failure, never a claimed success.
     */
    fun set(mode: RingerMode): RingerModeResult
}

/**
 * Real implementation over the system [AudioManager]. The resulting ringer mode is
 * read back and compared to the requested mode, so a device that ignored the request
 * (most commonly because the notification policy access is not granted, which the
 * platform enforces by throwing [SecurityException]) is reported as a failure rather
 * than a fake success.
 */
class AndroidRingerModeController(context: Context) : RingerModeController {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun set(mode: RingerMode): RingerModeResult {
        val manager = audioManager
            ?: return RingerModeResult(false, "NO_AUDIO_SERVICE", "Device audio service is unavailable")

        try {
            manager.ringerMode = mode.managerMode
        } catch (e: SecurityException) {
            // Android only allows RINGER_MODE_SILENT/VIBRATE to be set by an app that
            // holds notification-policy access; NORMAL is always permitted. Report the
            // real reason rather than pretending the phone is now silent.
            Log.w(TAG, "Ringer mode change denied: ${e.message}")
            return RingerModeResult(
                false,
                "RINGER_PERMISSION",
                "Changing the ringer mode is not permitted (notification policy access is not granted)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ringer mode change failed: ${e.message}")
            return RingerModeResult(false, "RINGER_ERROR", "Ringer mode change failed: ${e.localizedMessage}")
        }

        val actual = manager.ringerMode
        return if (actual == mode.managerMode) {
            RingerModeResult(true, "RINGER_MODE", "Ringer mode set to ${mode.key}")
        } else {
            RingerModeResult(
                false,
                "RINGER_UNCHANGED",
                "Ringer mode stayed at ${modeName(actual)}; the request to switch to ${mode.key} was ignored"
            )
        }
    }

    private fun modeName(managerMode: Int): String = when (managerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "AndroidRingerModeController"
    }
}
