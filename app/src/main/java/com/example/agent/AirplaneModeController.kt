package com.example.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * The two airplane-mode states the `airplane_mode` control of
 * [AgentAction.SetDeviceControl] can switch between. Kept as a closed enum (rather than a
 * raw boolean) so the accepted names and the on/off meaning live in one place and an
 * invalid value is rejected at the tool boundary.
 */
enum class AirplaneMode(val key: String) {
    /** Radio on: Wi-Fi / cellular / Bluetooth may be active. */
    OFF("off"),

    /** Radio off: all wireless radios are disabled. */
    ON("on");

    companion object {
        /**
         * Resolves a natural name or alias to a mode, or null when it is neither.
         * "on"/"enable"/"flight" mean on; "off"/"disable" mean off.
         */
        fun fromKey(key: String): AirplaneMode? {
            val normalised = key.trim().lowercase().replace('-', '_').replace(' ', '_')
            return when (normalised) {
                "on", "true", "enable", "enabled", "1", "yes", "start", "activate",
                "flight", "flight_mode", "airplane", "airplane_mode" -> ON
                "off", "false", "disable", "disabled", "0", "no", "stop", "deactivate",
                "normal", "ground" -> OFF
                else -> entries.firstOrNull { it.key == normalised }
            }
        }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single airplane-mode change. */
data class AirplaneModeResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The airplane-mode boundary for the `airplane_mode` control of
 * [AgentAction.SetDeviceControl]. This is the "turn on airplane mode" / "go offline" /
 * "turn airplane mode off" primitive: whether the radios are disabled is a global system
 * setting ([Settings.Global.AIRPLANE_MODE_ON]), not an accessibility-tree control, so no
 * screen read or UI tap can set it reliably on an arbitrary screen (it lives in quick
 * settings, usually off-screen).
 *
 * The platform enforces the write with a signature permission: on modern Android the
 * write is *not* granted to ordinary apps even with `WRITE_SETTINGS` special access. The
 * implementation therefore reads the current state back and compares it to the request,
 * and reports an honest failure (`AIRPLANE_MODE_UNCHANGED` /
 * `AIRPLANE_MODE_PERMISSION`) rather than a fake success. Because a direct write may be
 * refused, the [AndroidAirplaneModeController] also *opens the documented airplane-mode
 * Settings panel* as a fallback so the user can complete the change themselves - the
 * same "open the panel, the user chooses" pattern as `open_settings` (section 3.5); it
 * never claims the setting changed unless the read-back confirms it.
 *
 * It is injectable so the automation layer is testable without real system settings; an
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface AirplaneModeController {
    /**
     * Applies [mode]. Implementations must report honestly: a request the platform
     * refused, or a radio state that did not change, is a failure, never a claimed
     * success.
     */
    fun set(mode: AirplaneMode): AirplaneModeResult
}

/**
 * Real implementation over [Settings.Global.AIRPLANE_MODE_ON]. It reads the current
 * state first (so it never repeats a change that is already in effect, and never claims
 * one), attempts the guarded write, reads the state back, and - when the write did not
 * take effect - opens the documented `Settings.ACTION_AIRPLANE_MODE_SETTINGS` panel so
 * the user can finish the change. Success (`AIRPLANE_MODE_SET`) is reported only on a
 * confirmed state change; `AIRPLANE_MODE_UNCHANGED` (request ignored) or
 * `AIRPLANE_MODE_PERMISSION` (write not permitted) is never reported as success.
 */
class AndroidAirplaneModeController(context: Context) : AirplaneModeController {

    private val appContext = context.applicationContext

    override fun set(mode: AirplaneMode): AirplaneModeResult {
        val requested = mode == AirplaneMode.ON

        if (readState() == requested) {
            // Already in the requested state: report success without touching the radio
            // (an idempotent no-op would otherwise read back "unchanged" and be misreported
            // as a failure).
            return AirplaneModeResult(
                true,
                "AIRPLANE_MODE_SET",
                "Airplane mode is already ${if (requested) "on" else "off"}"
            )
        }

        var denied = false
        try {
            Settings.Global.putInt(
                appContext.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (requested) ENABLED else DISABLED
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Airplane-mode change denied: ${e.message}")
            denied = true
        } catch (e: Exception) {
            Log.w(TAG, "Airplane-mode change failed: ${e.message}")
            denied = true
        }

        // Read the state back; a radio that ignored the write must not be reported as a
        // success.
        if (readState() == requested) {
            return AirplaneModeResult(
                true,
                "AIRPLANE_MODE_SET",
                "Airplane mode is ${if (requested) "on" else "off"}"
            )
        }

        // The write was refused (modern Android requires a signature permission). Open the
        // documented panel so the user can complete the change; never claim it happened.
        val panelOpened = openSettingsPanel()
        val suffix = if (panelOpened) "; opened the airplane-mode settings so you can change it" else ""
        return if (denied) {
            AirplaneModeResult(
                false,
                "AIRPLANE_MODE_PERMISSION",
                "Changing airplane mode needs a system permission this app does not have$suffix"
            )
        } else {
            AirplaneModeResult(
                false,
                "AIRPLANE_MODE_UNCHANGED",
                "Airplane mode did not change; the request was ignored$suffix"
            )
        }
    }

    /** Opens the airplane-mode Settings panel so the user can toggle it. Best-effort. */
    private fun openSettingsPanel(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open airplane-mode settings: ${e.message}")
            false
        }
    }

    private fun readState(): Boolean =
        Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            DEFAULT_OFF
        ) != DEFAULT_OFF

    companion object {
        private const val TAG = "AndroidAirplaneMode"

        /** [Settings.Global.AIRPLANE_MODE_ON] when airplane mode is enabled. */
        private const val ENABLED = 1

        /** [Settings.Global.AIRPLANE_MODE_ON] when airplane mode is disabled. */
        private const val DISABLED = 0

        private const val DEFAULT_OFF = 0
    }
}
