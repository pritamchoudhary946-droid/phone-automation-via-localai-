package com.example.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * The named system Settings panels a task may open. Kept as a closed enum (rather than
 * a raw intent-action string) so a wrong or unknown panel is rejected at the tool
 * boundary and the agent can never dispatch an arbitrary intent action. Each entry maps
 * to a documented `Settings.ACTION_*` constant the system Settings app handles.
 */
enum class SettingsPanel(val key: String, val action: String, val aliases: List<String>) {
    MAIN("settings", Settings.ACTION_SETTINGS, listOf("main", "system")),
    WIFI("wifi", Settings.ACTION_WIFI_SETTINGS, listOf("wi_fi", "wlan")),
    NETWORK("network", Settings.ACTION_WIRELESS_SETTINGS, listOf("wireless", "mobile_network", "cellular", "sim", "sims")),
    DATA_USAGE("data_usage", Settings.ACTION_DATA_USAGE_SETTINGS, listOf("data", "internet_usage")),
    BLUETOOTH("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth_devices")),
    DISPLAY("display", Settings.ACTION_DISPLAY_SETTINGS, listOf("screen")),
    SOUND("sound", Settings.ACTION_SOUND_SETTINGS, listOf("audio", "volume")),
    BATTERY("battery", Settings.ACTION_BATTERY_SAVER_SETTINGS, listOf("battery_saver", "power")),
    LOCATION("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("gps", "location_services")),
    APPS("apps", Settings.ACTION_APPLICATION_SETTINGS, listOf("applications", "app_manager", "installed_apps")),
    ACCESSIBILITY("accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("a11y")),
    NFC("nfc", Settings.ACTION_NFC_SETTINGS, listOf("nfc_payments")),
    AIRPLANE_MODE("airplane_mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("flight_mode", "airplane")),
    DATE("date", Settings.ACTION_DATE_SETTINGS, listOf("date_time", "time", "clock")),
    LANGUAGE("language", Settings.ACTION_LOCALE_SETTINGS, listOf("locale", "languages", "language_input")),
    STORAGE("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("internal_storage", "memory")),
    SECURITY("security", Settings.ACTION_SECURITY_SETTINGS, listOf("lock_screen")),
    PRIVACY("privacy", Settings.ACTION_PRIVACY_SETTINGS, listOf("permissions")),
    CAST("cast", Settings.ACTION_CAST_SETTINGS, listOf("screen_cast", "cast_screen")),
    NIGHT_DISPLAY("night_display", Settings.ACTION_NIGHT_DISPLAY_SETTINGS, listOf("night_light", "night_mode")),
    KEYBOARD("keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, listOf("input_method", "keyboards")),
    DND("dnd", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, listOf("do_not_disturb", "zen", "priority")),
    HOME("home", Settings.ACTION_HOME_SETTINGS, listOf("launcher", "home_screen"));

    companion object {
        /** Resolves a plan-provided panel name (key or alias, case/space/hyphen-insensitive). */
        fun fromKey(raw: String): SettingsPanel? {
            val normalized = raw.trim().lowercase().replace(' ', '_').replace('-', '_')
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.key == normalized || normalized in it.aliases }
        }

        /** The accepted keys, for validation messages and the prompt catalogue. */
        fun keys(): List<String> = entries.map { it.key }
    }
}

/** Result of a single "open this system Settings panel" request. */
data class SettingsResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The system-settings boundary for [AgentAction.OpenSettings]. This is the "open
 * wifi / bluetooth / display / accessibility settings" primitive: a named panel of the
 * system Settings app is not reachable from the accessibility tree reliably on an
 * arbitrary screen (driving it would be a long, device-specific tap path), so instead
 * this dispatches the platform's documented `Settings.ACTION_*` intent for a known
 * panel, which the system Settings app handles.
 *
 * It carries no permission or destructive change: it only *opens* the panel, where the
 * user decides what to do (turn a radio on, change a setting), so the consequential
 * change stays inside the user's control, matching the section 3.5 rule used by
 * [AppInfoController]/[DialerController]/[NavigationController].
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface SettingsController {
    /**
     * Opens the system Settings panel named by [panel]. Implementations must report
     * honestly: if no Settings app can handle the request the result is a failure,
     * never a claimed success. A successful result means only that the panel was
     * opened - nothing in it was changed.
     */
    fun openPanel(panel: SettingsPanel): SettingsResult
}

/**
 * Real implementation that dispatches the panel's documented `Settings.ACTION_*`
 * intent. Because the Settings app is a separate process this cannot read back that the
 * panel was shown to prove it; it verifies only that the intent was delivered to a
 * settings handler, and reports the panel it addressed so the result is honest about
 * what was and was not confirmed.
 */
class AndroidSettingsController(context: Context) : SettingsController {

    private val appContext = context.applicationContext

    override fun openPanel(panel: SettingsPanel): SettingsResult {
        val intent = Intent(panel.action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            SettingsResult(true, "SETTINGS_OPENED", "Opening ${panel.key} settings")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No settings app can handle ${panel.action}: ${e.message}")
            SettingsResult(false, "SETTINGS_UNAVAILABLE", "No settings app is available to open ${panel.key} settings")
        } catch (e: SecurityException) {
            Log.w(TAG, "Settings dispatch denied: ${e.message}")
            SettingsResult(false, "SETTINGS_PERMISSION", "Opening ${panel.key} settings is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Settings dispatch failed: ${e.message}")
            SettingsResult(false, "SETTINGS_ERROR", "Settings request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidSettingsController"
    }
}
