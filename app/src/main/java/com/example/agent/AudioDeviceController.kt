package com.example.agent

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * The named audio-output routes the `audio_device` control of
 * [AgentAction.SetDeviceControl] can switch between. Kept as a closed enum (rather than a
 * raw platform device type) so an invalid route is rejected at the tool boundary and can
 * never index [AudioManager] incorrectly.
 */
enum class AudioDeviceMode(val key: String) {
    /** The loudspeaker / speakerphone: play through the phone's speaker. */
    SPEAKER("speaker"),

    /** The earpiece / a wired or Bluetooth headset: route to the private/headset output. */
    HEADSET("headset");

    companion object {
        /**
         * Resolves a natural name or alias to a mode, or null when it is not one of the two
         * backed routes. "speakerphone"/"loudspeaker" mean speaker; "earpiece"/"headphones"/
         * "earphone" mean headset.
         */
        fun fromKey(key: String): AudioDeviceMode? {
            val normalised = key.trim().lowercase().replace('-', '_').replace(' ', '_')
            return when (normalised) {
                "speaker", "speakerphone", "phone_speaker", "loudspeaker", "handsfree", "handsfree_speaker" -> SPEAKER
                "headset", "earpiece", "headphones", "headphone", "wired_headset", "earphone", "receiver" -> HEADSET
                else -> entries.firstOrNull { it.key == normalised }
            }
        }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single audio-output routing request. */
data class AudioDeviceResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The audio-output routing boundary for the `audio_device` control of
 * [AgentAction.SetDeviceControl]. This is the "put it on speaker" / "switch to the
 * headset" primitive: the active call/media output route is a system audio setting
 * (`AudioManager`) that no accessibility-tree read or UI tap can change reliably on an
 * arbitrary screen (it is a button inside a call screen / quick settings, often
 * off-screen).
 *
 * It is distinct from the volume controls ([AudioController]) and the ringer/DND
 * settings: those change how loud or whether the phone rings, while this chooses *which
 * output device* the audio plays through.
 *
 * It is injectable so the automation layer is testable without a real [AudioManager]. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op,
 * and a routing request the device ignored is reported as a failure, never a fake
 * success.
 */
interface AudioDeviceController {
    /**
     * Routes audio output to [mode]. Implementations must report honestly: if the device
     * has no such output, or the request did not move the active route, the result is a
     * failure, never a claimed success.
     */
    fun set(mode: AudioDeviceMode): AudioDeviceResult
}

/**
 * Real implementation over the system [AudioManager]. The active route is read back and
 * compared to the requested mode, so a device that ignored the request (or lacks the
 * requested output) is reported as a failure rather than a fake success.
 *
 * On API 31+ it uses the documented communication-device APIs
 * ([AudioManager.setCommunicationDevice]/[AudioManager.getCommunicationDevice]), which
 * name the exact routed output. On older releases it falls back to the speakerphone flag,
 * which reflects whether call audio plays through the loudspeaker.
 */
class AndroidAudioDeviceController(context: Context) : AudioDeviceController {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun set(mode: AudioDeviceMode): AudioDeviceResult {
        val manager = audioManager
            ?: return AudioDeviceResult(false, "AUDIO_DEVICE_UNAVAILABLE", "Device audio service is unavailable")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routeViaCommunicationDevice(manager, mode)
            } else {
                routeViaSpeakerphone(manager, mode)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Audio route change denied: ${e.message}")
            AudioDeviceResult(false, "AUDIO_DEVICE_PERMISSION", "Changing the audio output is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Audio route change failed: ${e.message}")
            AudioDeviceResult(false, "AUDIO_DEVICE_ERROR", "The audio output could not be changed: ${e.localizedMessage}")
        }
    }

    private fun routeViaCommunicationDevice(manager: AudioManager, mode: AudioDeviceMode): AudioDeviceResult {
        val target = manager.availableCommunicationDevices.firstOrNull { modeForDeviceType(it.type) == mode }
            ?: return AudioDeviceResult(false, "AUDIO_DEVICE_UNAVAILABLE", "No ${mode.key} audio output is available")
        val applied = manager.setCommunicationDevice(target)
        val actual = manager.communicationDevice
        return if (applied && actual != null && modeForDeviceType(actual.type) == mode) {
            AudioDeviceResult(true, "AUDIO_DEVICE", "Audio output set to ${mode.key}")
        } else {
            AudioDeviceResult(false, "AUDIO_DEVICE_UNCHANGED", "Audio stayed on ${actual?.let { modeForDeviceType(it.type)?.key } ?: "the current output"}")
        }
    }

    @Suppress("DEPRECATION")
    private fun routeViaSpeakerphone(manager: AudioManager, mode: AudioDeviceMode): AudioDeviceResult {
        val wantSpeaker = mode == AudioDeviceMode.SPEAKER
        manager.isSpeakerphoneOn = wantSpeaker
        val actual = manager.isSpeakerphoneOn
        return if (actual == wantSpeaker) {
            AudioDeviceResult(true, "AUDIO_DEVICE", "Audio output set to ${mode.key}")
        } else {
            AudioDeviceResult(false, "AUDIO_DEVICE_UNCHANGED", "Audio stayed on ${if (actual) "speaker" else "earpiece"}")
        }
    }

    /**
     * Maps a platform output [AudioDeviceInfo] type to the route it belongs to, or null for
     * a type this control does not name.
     */
    private fun modeForDeviceType(type: Int): AudioDeviceMode? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceMode.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioDeviceMode.HEADSET
        else -> null
    }

    companion object {
        private const val TAG = "AndroidAudioDeviceController"
    }
}
