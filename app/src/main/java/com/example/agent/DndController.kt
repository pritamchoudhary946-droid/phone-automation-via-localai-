package com.example.agent

import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * The named Do-Not-Disturb states a task may switch the device into. Kept as a closed
 * enum (rather than the raw platform interruption-filter integer) so an invalid mode is
 * rejected at the tool boundary and can never index [NotificationManager] incorrectly.
 */
enum class DndMode(val key: String, val filter: Int) {
    /** All interruptions allowed - i.e. Do Not Disturb is OFF. */
    OFF("off", NotificationManager.INTERRUPTION_FILTER_ALL),

    /** Only priority interruptions (allowed contacts/apps) get through. */
    PRIORITY("priority", NotificationManager.INTERRUPTION_FILTER_PRIORITY),

    /** Only alarms get through. */
    ALARMS("alarms", NotificationManager.INTERRUPTION_FILTER_ALARMS),

    /** Total silence - nothing gets through. */
    NONE("none", NotificationManager.INTERRUPTION_FILTER_NONE);

    companion object {
        fun fromKey(key: String): DndMode? {
            val normalised = key.trim().lowercase().replace('-', '_').replace(' ', '_')
            return when (normalised) {
                "off", "all", "disable", "disabled", "no", "false", "0", "none_off" -> OFF
                "priority", "priority_only", "only_priority" -> PRIORITY
                "alarms", "alarms_only", "only_alarms" -> ALARMS
                "none", "silent", "total_silence", "mute", "on", "enable", "enabled", "yes", "true", "1" -> NONE
                else -> entries.firstOrNull { it.key == normalised }
            }
        }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single Do-Not-Disturb switch. */
data class DndResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The Do-Not-Disturb boundary for the `dnd` control of [AgentAction.SetDeviceControl].
 * This is the "turn on do not disturb" / "put my phone on silent" (total silence) /
 * "only let priority contacts through" primitive: the DND state is a system
 * notification-policy setting that no accessibility-tree read or UI tap can change
 * reliably on an arbitrary screen (it lives in quick settings / the sound panel, often
 * off-screen).
 *
 * It is distinct from [RingerModeController]: the ringer mode controls whether the phone
 * rings, while DND controls which *notifications* are allowed to interrupt at all. DND
 * also requires the user to grant notification-policy access, which the platform
 * enforces by refusing the change.
 *
 * It is injectable so the automation layer is testable without a real
 * [NotificationManager]. An unwired controller returns an honest "unavailable" result
 * rather than a silent no-op.
 */
interface DndController {
    /**
     * Switches the device to [mode]. Implementations must report honestly: if the
     * platform refused the change (most commonly because notification-policy access is
     * not granted, or the device ignored the request), the result is a failure, never a
     * claimed success.
     */
    fun set(mode: DndMode): DndResult
}

/**
 * Real implementation over the system [NotificationManager]. The resulting interruption
 * filter is read back and compared to the requested mode, so a device that ignored the
 * request - or an app that lacks notification-policy access (the platform then refuses
 * with [SecurityException]) - is reported as a failure rather than a fake success.
 */
class AndroidDndController(context: Context) : DndController {

    private val notificationManager: NotificationManager? =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    override fun set(mode: DndMode): DndResult {
        val manager = notificationManager
            ?: return DndResult(false, "DND_UNAVAILABLE", "Device notification service is unavailable")

        // Android only allows the interruption filter to be set by an app the user has
        // granted notification-policy access to. Check first so the failure is reported
        // with a clear, actionable reason instead of a bare SecurityException.
        if (!manager.isNotificationPolicyAccessGranted) {
            return DndResult(
                false,
                "DND_PERMISSION",
                "Do Not Disturb control needs notification-policy access, which is not granted"
            )
        }

        try {
            manager.setInterruptionFilter(mode.filter)
        } catch (e: SecurityException) {
            Log.w(TAG, "Do Not Disturb change denied: ${e.message}")
            return DndResult(
                false,
                "DND_PERMISSION",
                "Changing Do Not Disturb is not permitted (notification policy access is not granted)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Do Not Disturb change failed: ${e.message}")
            return DndResult(false, "DND_ERROR", "Do Not Disturb change failed: ${e.localizedMessage}")
        }

        val actual = manager.currentInterruptionFilter
        return if (actual == mode.filter) {
            DndResult(true, "DND_MODE", "Do Not Disturb set to ${mode.key}")
        } else {
            DndResult(
                false,
                "DND_UNCHANGED",
                "Do Not Disturb stayed at ${filterName(actual)}; the request to switch to ${mode.key} was ignored"
            )
        }
    }

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "AndroidDndController"
    }
}
