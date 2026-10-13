package com.example.automation

/**
 * Pure, device-free presentation of an automation trigger (plan 9.2). Keeping the
 * wording here means the Library Automations tab and the schedule dialog render the
 * same honest label for a persisted trigger, and the mapping is unit-testable without
 * a UI or a device.
 */
object TriggerLabel {

    /** Human sentence for a persisted automation row (triggerType + triggerValue). */
    fun describe(triggerType: String, triggerValue: String): String = when (triggerType) {
        AutomationTrigger.INTERVAL ->
            "Every ${AutomationGate.intervalMinutes(triggerValue.toIntOrNull() ?: AutomationGate.MIN_INTERVAL_MINUTES)} minutes"
        AutomationTrigger.WIFI_CONNECTED ->
            if (triggerValue.isBlank()) "When Wi-Fi connects" else "When Wi-Fi connects to \"$triggerValue\""
        AutomationTrigger.BATTERY_BELOW ->
            "When battery drops to ${valueOrDefault(triggerValue, "20")}%"
        AutomationTrigger.BATTERY_ABOVE ->
            "When battery reaches ${valueOrDefault(triggerValue, "80")}%"
        AutomationTrigger.CHARGING_STARTED -> "When charging starts"
        AutomationTrigger.NETWORK_TYPE -> "When network is ${valueOrDefault(triggerValue, "WIFI")}"
        AutomationTrigger.APP_FOREGROUND ->
            "When ${valueOrDefault(triggerValue, "an app")} is in the foreground"
        AutomationTrigger.NOTIFICATION_FROM_APP ->
            "When a notification arrives from ${valueOrDefault(triggerValue, "an app")}"
        AutomationTrigger.LOCATION_ARRIVE ->
            "When you arrive at ${valueOrDefault(triggerValue, "a place")}"
        else -> triggerType
    }

    /** Compact chip label for the schedule dialog's trigger picker. */
    fun shortName(triggerType: String): String = when (triggerType) {
        AutomationTrigger.INTERVAL -> "Interval"
        AutomationTrigger.WIFI_CONNECTED -> "Wi-Fi"
        AutomationTrigger.BATTERY_BELOW -> "Battery low"
        AutomationTrigger.BATTERY_ABOVE -> "Battery high"
        AutomationTrigger.CHARGING_STARTED -> "Charging"
        AutomationTrigger.NETWORK_TYPE -> "Network"
        AutomationTrigger.APP_FOREGROUND -> "App opens"
        AutomationTrigger.NOTIFICATION_FROM_APP -> "Notification"
        AutomationTrigger.LOCATION_ARRIVE -> "Arrive at place"
        else -> triggerType
    }

    /** Short input hint for the schedule dialog, matching the trigger's value shape. */
    fun valueHint(triggerType: String): String = when (triggerType) {
        AutomationTrigger.INTERVAL -> "Every how many minutes?"
        AutomationTrigger.WIFI_CONNECTED -> "SSID (leave blank for any Wi-Fi)"
        AutomationTrigger.BATTERY_BELOW, AutomationTrigger.BATTERY_ABOVE -> "Battery percent"
        AutomationTrigger.NETWORK_TYPE -> "WIFI, CELLULAR or NONE"
        AutomationTrigger.APP_FOREGROUND, AutomationTrigger.NOTIFICATION_FROM_APP -> "App label or package"
        AutomationTrigger.LOCATION_ARRIVE -> "Place name"
        else -> "Value"
    }

    /** Sensible default the dialog pre-fills for a trigger, so a user can schedule fast. */
    fun defaultIntervalMinutes() = AutomationGate.MIN_INTERVAL_MINUTES.toString()

    /**
     * True when [triggerType] needs a triggerValue to be meaningful. An INTERVAL and
     * WIFI_CONNECTED trigger have defaults (the floor interval / any network), so a
     * blank value is accepted there; the rest require a real value.
     */
    fun requiresValue(triggerType: String): Boolean = when (triggerType) {
        AutomationTrigger.INTERVAL, AutomationTrigger.WIFI_CONNECTED, AutomationTrigger.CHARGING_STARTED -> false
        else -> true
    }

    /**
     * Normalises a dialed trigger value: digits stay digits, everything else is trimmed.
     * Returns "" when the value is blank (the caller decides whether that is allowed).
     */
    fun normalizeValue(triggerType: String, raw: String): String {
        val trimmed = raw.trim()
        return when (triggerType) {
            AutomationTrigger.INTERVAL -> trimmed.filter { it.isDigit() }.take(5)
            AutomationTrigger.BATTERY_BELOW, AutomationTrigger.BATTERY_ABOVE ->
                trimmed.filter { it.isDigit() }.take(3)
            else -> trimmed
        }
    }

    private fun valueOrDefault(value: String, fallback: String) =
        value.ifBlank { fallback }
}
