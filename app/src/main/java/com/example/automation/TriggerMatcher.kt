package com.example.automation

import com.example.perception.BatteryGuard

/**
 * The outcome of evaluating one automation at run time (plan 7.8/9.2). [armed] means the
 * master switch is on, the automation row still exists, is enabled, and its skill is
 * present; [fired] means the trigger condition is actually satisfied right now. A run is
 * allowed only when armed AND fired, so an event trigger never replays a skill until its
 * condition holds.
 */
data class TriggerDecision(
    val armed: Boolean,
    val fired: Boolean,
    val reason: String
)

/**
 * Pure evaluation of automation triggers (plan 9.2). Extends the clock/interval and
 * Wi-Fi triggers with battery level, charging, network type, app-foreground, and
 * notification-from-app. Location arrive/leave is evaluated by the caller (it needs
 * a permissioned location provider) but is modelled here so the set is complete.
 *
 * Gating rule (unchanged from 4.14): an automation fires only when the master switch
 * is on AND the automation is enabled AND its trigger condition is met.
 */
object TriggerMatcher {

    /** The system conditions sampled at evaluation time. */
    data class Conditions(
        val nowMs: Long = System.currentTimeMillis(),
        val lastRunMs: Long = 0,
        val wifiConnected: Boolean = false,
        val ssid: String? = null,
        val batteryPercent: Int = 100,
        val charging: Boolean = false,
        val networkType: String = "NONE", // WIFI, CELLULAR, NONE
        val foregroundPackage: String? = null,
        val recentNotificationPackages: Set<String> = emptySet(),
        val locationRegion: String? = null // e.g. "home", "work", or null
    )

    /**
     * Whether a persisted automation's condition is satisfied right now.
     * [intervalMinutes] is only consulted for the INTERVAL trigger.
     */
    fun matches(
        triggerType: String,
        triggerValue: String,
        intervalMinutes: Long,
        conditions: Conditions
    ): Boolean = when (triggerType) {
        AutomationTrigger.INTERVAL ->
            conditions.nowMs - conditions.lastRunMs >= intervalMinutes * 60_000L

        AutomationTrigger.WIFI_CONNECTED ->
            conditions.wifiConnected &&
                (triggerValue.isBlank() || conditions.ssid.equals(triggerValue, ignoreCase = true))

        AutomationTrigger.BATTERY_BELOW ->
            conditions.batteryPercent <= (triggerValue.toIntOrNull() ?: 20)

        AutomationTrigger.BATTERY_ABOVE ->
            conditions.batteryPercent >= (triggerValue.toIntOrNull() ?: 80)

        AutomationTrigger.CHARGING_STARTED ->
            conditions.charging

        AutomationTrigger.NETWORK_TYPE ->
            conditions.networkType.equals(triggerValue.ifBlank { "WIFI" }, ignoreCase = true)

        AutomationTrigger.APP_FOREGROUND ->
            triggerValue.isNotBlank() &&
                conditions.foregroundPackage?.lowercase()?.contains(triggerValue.lowercase()) == true

        AutomationTrigger.NOTIFICATION_FROM_APP ->
            triggerValue.isNotBlank() &&
                conditions.recentNotificationPackages.any { it.lowercase().contains(triggerValue.lowercase()) }

        AutomationTrigger.LOCATION_ARRIVE ->
            triggerValue.isNotBlank() &&
                conditions.locationRegion.equals(triggerValue, ignoreCase = true)

        else -> false
    }

    /**
     * The full decision, including the master-switch + enabled gate and the battery
     * guard for event-driven (always-on) triggers (plan 9.14).
     */
    fun shouldFire(
        masterEnabled: Boolean,
        automationEnabled: Boolean,
        triggerType: String,
        triggerValue: String,
        intervalMinutes: Long,
        conditions: Conditions,
        battery: BatteryGuard.Decision = BatteryGuard.Decision.OK
    ): Boolean {
        if (!AutomationGate.isSchedulable(masterEnabled, automationEnabled)) return false
        if (triggerType != AutomationTrigger.INTERVAL && !battery.allowed) return false
        return matches(triggerType, triggerValue, intervalMinutes, conditions)
    }

    /** All supported trigger kinds, so the UI can offer them honestly. */
    val allTriggers: List<String> = listOf(
        AutomationTrigger.INTERVAL,
        AutomationTrigger.WIFI_CONNECTED,
        AutomationTrigger.BATTERY_BELOW,
        AutomationTrigger.BATTERY_ABOVE,
        AutomationTrigger.CHARGING_STARTED,
        AutomationTrigger.NETWORK_TYPE,
        AutomationTrigger.APP_FOREGROUND,
        AutomationTrigger.NOTIFICATION_FROM_APP,
        AutomationTrigger.LOCATION_ARRIVE
    )
}
