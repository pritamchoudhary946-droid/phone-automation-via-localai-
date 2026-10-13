package com.example.automation

/**
 * Pure gating for scheduled automations (plan 4.14).
 *
 * Automations are OFF by default: nothing is scheduled unless the user turned on
 * the master switch AND the individual automation is enabled. Unit-testable with
 * no WorkManager/device.
 */
object AutomationGate {
    /** WorkManager's periodic minimum; also the floor the UI enforces before scheduling. */
    const val MIN_INTERVAL_MINUTES = 15

    fun isSchedulable(masterEnabled: Boolean, automationEnabled: Boolean): Boolean =
        masterEnabled && automationEnabled

    /** Periodic interval in minutes, clamped to WorkManager's 15-minute minimum. */
    fun intervalMinutes(rawMinutes: Int): Long = rawMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
}

/** Kinds of trigger an automation can have. */
object AutomationTrigger {
    const val INTERVAL = "INTERVAL"
    const val WIFI_CONNECTED = "WIFI_CONNECTED"
    // Phase 9.2 additions - event-driven triggers, evaluated by TriggerMatcher.
    const val BATTERY_BELOW = "BATTERY_BELOW"
    const val BATTERY_ABOVE = "BATTERY_ABOVE"
    const val CHARGING_STARTED = "CHARGING_STARTED"
    const val NETWORK_TYPE = "NETWORK_TYPE"
    const val APP_FOREGROUND = "APP_FOREGROUND"
    const val NOTIFICATION_FROM_APP = "NOTIFICATION_FROM_APP"
    const val LOCATION_ARRIVE = "LOCATION_ARRIVE"
}
