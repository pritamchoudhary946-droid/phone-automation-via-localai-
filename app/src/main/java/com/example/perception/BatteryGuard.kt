package com.example.perception

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Battery / thermal / Doze guard for always-on features (plan 9.14).
 *
 * The policy is pure and unit-testable; the device readings are read once from a
 * [Context]. Always-on features (proactive awareness, context triggers) consult
 * this so they never drain the phone: they pause below a level threshold, while
 * Doze-ing, or when the device is thermally throttled, and say so.
 */
object BatteryGuard {

    const val LOW_BATTERY_LEVEL = 15
    const val PAUSE_BELOW_LEVEL = 20

    data class DeviceState(
        val batteryPercent: Int,
        val charging: Boolean,
        val doze: Boolean,
        val thermalStatus: Int // PowerManager thermal status (0 = none)
    )

    data class Decision(val allowed: Boolean, val reason: String?) {
        companion object {
            val OK = Decision(true, null)
        }
    }

    /**
     * Decides whether an always-on feature may run. Pauses when the battery is low
     * and not charging, when the device is Doze-ing (screen off / idle), or when it
     * is thermally throttled.
     */
    fun decide(state: DeviceState): Decision = when {
        state.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
            Decision(false, "Paused: the phone is running hot. Give it a moment to cool down.")
        state.doze ->
            Decision(false, "Paused while the phone is idle to save battery.")
        (!state.charging && state.batteryPercent < PAUSE_BELOW_LEVEL) ->
            Decision(false, "Paused: battery is at ${state.batteryPercent}%. Plug in or charge up to keep this on.")
        else -> Decision.OK
    }

    fun read(context: Context): DeviceState {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val doze = pm?.isDeviceIdleMode == true
        val thermal = if (pm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            pm.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return DeviceState(percent.coerceIn(0, 100), charging, doze, thermal)
    }

    /** Convenience: the live decision for [context]. */
    fun check(context: Context): Decision = decide(read(context))
}
