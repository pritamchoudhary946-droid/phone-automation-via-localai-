package com.example.agent

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The two screen-rotation-lock states the `auto_rotate` control of
 * [AgentAction.SetDeviceControl] can switch between. Kept as a closed enum (rather than a raw
 * 0/1 int) so an invalid value is rejected at the tool boundary and can never write a bad
 * value into the platform [Settings.System] setting.
 */
enum class AutoRotateMode(val key: String) {
    /** Auto-rotation unlocked: the screen follows the device orientation. */
    ON("on"),

    /** Auto-rotation locked: the screen keeps its current orientation. */
    OFF("off");

    companion object {
        /**
         * Resolves a natural name or alias to a mode, or null when it is neither. "enable"/
         * "unlock"/"unlocked" mean on; "disable"/"lock"/"locked"/"fixed"/"portrait" mean off.
         */
        fun fromKey(key: String): AutoRotateMode? {
            val normalised = key.trim().lowercase().replace('-', '_').replace(' ', '_')
            return when (normalised) {
                "on", "true", "enable", "enabled", "1", "yes", "start", "unlock", "unlocked",
                "rotate", "auto", "automatic", "allow_rotation" -> ON
                "off", "false", "disable", "disabled", "0", "no", "stop", "lock", "locked",
                "fixed", "fix", "portrait", "lock_rotation", "rotation_lock", "rotation_locked" -> OFF
                else -> entries.firstOrNull { it.key == normalised }
            }
        }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single auto-rotate lock change. */
data class AutoRotateResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The screen auto-rotation boundary for the `auto_rotate` control of
 * [AgentAction.SetDeviceControl]. This is the "lock the screen rotation" / "turn on
 * auto-rotate" / "stop the screen rotating" primitive: whether the display follows the
 * device orientation is a system display setting ([Settings.System.ACCELEROMETER_ROTATION]),
 * not an accessibility-tree control, so no screen read or UI tap can set it reliably on an
 * arbitrary screen (it is a quick-settings tile, usually off-screen).
 *
 * It is a sibling of [ScreenBrightnessController]: both write a `Settings.System` display
 * setting under the same single `WRITE_SETTINGS` special access the platform enforces by
 * refusing the write. An implementation must check for it first and report an honest
 * [AutoRotateResult.method] `AUTO_ROTATE_PERMISSION` rather than throwing. The resulting
 * state is read back and compared to the request, so a display that ignored it is an honest
 * failure, never a fake success.
 *
 * It is injectable so the automation layer is testable without real system settings; an
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface AutoRotateController {
    /**
     * Applies [mode]. Implementations must report honestly: a request the platform refused
     * (no write-settings access), or a display that ignored the write, is a failure, never a
     * claimed success.
     */
    fun set(mode: AutoRotateMode): AutoRotateResult
}

/**
 * Real implementation over [Settings.System.ACCELEROMETER_ROTATION]. The write is guarded by
 * [Settings.System.canWrite] (the platform denies it otherwise) and the state is read back
 * afterwards, so an ignored write or a missing permission is a failure
 * (`AUTO_ROTATE_UNCHANGED` / `AUTO_ROTATE_PERMISSION`), never a fake success.
 */
class AndroidAutoRotateController(context: Context) : AutoRotateController {

    private val appContext = context.applicationContext

    override fun set(mode: AutoRotateMode): AutoRotateResult {
        if (!Settings.System.canWrite(appContext)) {
            return AutoRotateResult(
                false,
                "AUTO_ROTATE_PERMISSION",
                "Changing the screen auto-rotation needs the \"modify system settings\" permission, which is not granted"
            )
        }

        val requested = if (mode == AutoRotateMode.ON) ENABLED else DISABLED

        try {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                requested
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Auto-rotate change denied: ${e.message}")
            return AutoRotateResult(
                false,
                "AUTO_ROTATE_PERMISSION",
                "Changing the screen auto-rotation is not permitted (modify system settings is not granted)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Auto-rotate change failed: ${e.message}")
            return AutoRotateResult(false, "AUTO_ROTATE_ERROR", "The screen auto-rotation could not be changed: ${e.localizedMessage}")
        }

        // Read the state back; a display that ignored the write must not be reported as a
        // success.
        val actual = readState()
        return if (actual == requested) {
            AutoRotateResult(true, "AUTO_ROTATE_SET", "Screen auto-rotation is ${if (mode == AutoRotateMode.ON) "on" else "off"}")
        } else {
            AutoRotateResult(
                false,
                "AUTO_ROTATE_UNCHANGED",
                "The screen auto-rotation did not change; the request was ignored"
            )
        }
    }

    private fun readState(): Int =
        Settings.System.getInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)

    companion object {
        private const val TAG = "AndroidAutoRotate"

        /** [Settings.System.ACCELEROMETER_ROTATION] when auto-rotation is enabled. */
        private const val ENABLED = 1

        /** [Settings.System.ACCELEROMETER_ROTATION] when auto-rotation is locked off. */
        private const val DISABLED = 0
    }
}
