package com.example.automation

/**
 * Pure gating for scheduled automations (plan 4.14).
 *
 * Automations are OFF by default: nothing is scheduled unless the user turned on
 * the master switch AND the individual automation is enabled. Unit-testable with
 * no WorkManager/device.
 */
object AutomationGate {
    fun isSchedulable(masterEnabled: Boolean, automationEnabled: Boolean): Boolean =
        masterEnabled && automationEnabled

    /** Periodic interval in minutes, clamped to WorkManager's 15-minute minimum. */
    fun intervalMinutes(rawMinutes: Int): Long = rawMinutes.coerceAtLeast(15).toLong()
}

/** Kinds of trigger an automation can have. */
object AutomationTrigger {
    const val INTERVAL = "INTERVAL"
    const val WIFI_CONNECTED = "WIFI_CONNECTED"
}
