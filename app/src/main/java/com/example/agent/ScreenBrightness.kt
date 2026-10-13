package com.example.agent

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The named screen-brightness levels a task may switch the display to. Kept as a closed
 * enum so an invalid level is rejected at the tool boundary and can never index the
 * platform [Settings.System] incorrectly.
 *
 * The absolute setting maps to the 0-255 backlight value the platform documents for
 * [Settings.System.SCREEN_BRIGHTNESS] (scaled so [percent] 100 -> [MAX_LEVEL]). The
 * reserved 0 value means "darkest the platform allows", never a fully black screen,
 * since the platform clamps 0 up to its own minimum.
 */
enum class ScreenBrightness(val key: String, val percent: Int) {
    /** Darkest the platform allows - "dim the screen", "minimum brightness". */
    DIM("dim", 0),

    LOW("low", 25),

    MEDIUM("medium", 50),

    HIGH("high", 75),

    /** Brightest the platform allows - "turn the brightness all the way up". */
    MAX("max", 100);

    /** The 0-255 backlight level this level maps to. */
    val level: Int get() = percent * MAX_LEVEL / 100

    companion object {
        const val MAX_LEVEL = 255

        /** How much a relative request ("brighter"/"dimmer") moves the level. */
        const val STEP_LEVEL = 64

        fun fromKey(key: String): ScreenBrightness? {
            val normalised = key.trim().lowercase().replace('-', '_').replace(' ', '_')
            return when (normalised) {
                "dim", "dimmer", "dark", "darkest", "min", "minimum", "lowest", "very_low" -> DIM
                "low", "low_brightness", "faint" -> LOW
                "medium", "med", "normal", "half", "mid", "middle", "default" -> MEDIUM
                "high", "bright" -> HIGH
                "max", "maximum", "full", "brightest", "highest", "up", "all_the_way" -> MAX
                else -> entries.firstOrNull { it.key == normalised }
            }
        }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }

        /**
         * Parses a numeric brightness request - "80", "80%", "80 percent" - into a 0-100
         * percentage, or null when [value] is not numeric. Values are clamped into 0-100.
         */
        fun percentFromValue(value: String): Int? {
            val cleaned = value.trim().lowercase()
                .removeSuffix("%")
                .removeSuffix("percent")
                .trim()
            val number = cleaned.toIntOrNull() ?: return null
            return number.coerceIn(0, 100)
        }

        /** The 0-255 level a numeric [value] maps to, or null when it is not numeric. */
        fun levelFromValue(value: String): Int? =
            percentFromValue(value)?.let { (it * MAX_LEVEL / 100).coerceIn(1, MAX_LEVEL) }
    }
}

/** Result of a single screen-brightness change. */
data class BrightnessResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The screen-brightness boundary for the `brightness` control of
 * [AgentAction.SetDeviceControl]. This is the "dim the screen", "turn the brightness up",
 * "set brightness to 40%" primitive: the screen backlight is a display/system setting, not
 * an accessibility-tree control, so no screen read or UI tap can set it reliably on an
 * arbitrary screen (it lives in quick settings / the display panel, usually off-screen).
 *
 * Changing the *system* brightness requires the platform's `WRITE_SETTINGS` special
 * access, which the platform enforces by refusing the write; an implementation must check
 * for it first and report an honest [BrightnessResult.method] `BRIGHTNESS_PERMISSION`
 * rather than throwing. The resulting level is read back and compared to the requested
 * one, so a display that ignored the request is an honest failure, never a fake success.
 *
 * It is injectable so the automation layer is testable without real system settings; an
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface ScreenBrightnessController {
    /**
     * Applies [target]. Implementations must report honestly: a request the platform
     * refused (no write-settings access), or a display that ignored the write, is a
     * failure, never a claimed success.
     */
    fun set(target: BrightnessTarget): BrightnessResult
}

/**
 * A resolved brightness request produced by [DeviceControl.resolve] for the `brightness`
 * control. It is a [BrightnessTarget.Absolute] when the caller named a level (a closed
 * [ScreenBrightness] or a numeric 0-255 absolute), and a [BrightnessTarget.Relative] when
 * the caller asked to move up/down from wherever the screen is now (the current level is
 * read at execution time, so a "brighter" request is honest about the level it lands on).
 */
sealed interface BrightnessTarget {
    data class Absolute(val level: Int) : BrightnessTarget
    data class Relative(val delta: Int) : BrightnessTarget
}

/** Interface name kept symmetrical with the other device-control boundaries. */
typealias BrightnessController = ScreenBrightnessController

/**
 * Real implementation over [Settings.System.SCREEN_BRIGHTNESS]. The write is guarded by
 * [Settings.System.canWrite] (the platform denies it otherwise) and the level is read back
 * afterwards, so an ignored write or a missing permission is a failure
 * (`BRIGHTNESS_UNCHANGED` / `BRIGHTNESS_PERMISSION`), never a fake success.
 */
class AndroidScreenBrightnessController(context: Context) : ScreenBrightnessController {

    private val appContext = context.applicationContext

    override fun set(target: BrightnessTarget): BrightnessResult {
        if (!Settings.System.canWrite(appContext)) {
            return BrightnessResult(
                false,
                "BRIGHTNESS_PERMISSION",
                "Changing the screen brightness needs the \"modify system settings\" permission, which is not granted"
            )
        }

        val current = readLevel().takeIf { it in MIN_LEVEL..MAX_LEVEL } ?: ScreenBrightness.MEDIUM.level
        val requested = when (target) {
            is BrightnessTarget.Absolute -> target.level
            is BrightnessTarget.Relative -> current + target.delta
        }.coerceIn(MIN_LEVEL, MAX_LEVEL)

        try {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                requested
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Brightness change denied: ${e.message}")
            return BrightnessResult(
                false,
                "BRIGHTNESS_PERMISSION",
                "Changing the screen brightness is not permitted (modify system settings is not granted)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Brightness change failed: ${e.message}")
            return BrightnessResult(false, "BRIGHTNESS_ERROR", "The screen brightness could not be changed: ${e.localizedMessage}")
        }

        // Read the level back; a display that ignored the write must not be reported as a
        // success.
        val actual = readLevel()
        return if (actual == requested) {
            BrightnessResult(true, "BRIGHTNESS_SET", "Screen brightness set to ${percent(actual)}%")
        } else {
            BrightnessResult(
                false,
                "BRIGHTNESS_UNCHANGED",
                "The screen brightness stayed at ${percent(actual)}%; the request to set it was ignored"
            )
        }
    }

    private fun readLevel(): Int =
        Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)

    private fun percent(level: Int): Int = level * 100 / MAX_LEVEL

    companion object {
        private const val TAG = "AndroidScreenBrightness"

        /** The platform documents 1 as the minimum writable backlight level (0 is reserved). */
        private const val MIN_LEVEL = 1

        /** The documented maximum backlight level. */
        private const val MAX_LEVEL = ScreenBrightness.MAX_LEVEL
    }
}
