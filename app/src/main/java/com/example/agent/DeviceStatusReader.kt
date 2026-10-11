package com.example.agent

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/** Result of a single on-device status read. */
data class DeviceStatusResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The device-status boundary for [AgentAction.ReadDeviceStatus]. Battery, screen,
 * ringer mode and connectivity live on system services, not in the accessibility
 * tree, so this is the service-free read primitive behind questions like "how much
 * battery do I have", "is wifi on" and "am I on silent".
 *
 * It is injectable so the automation layer is testable without real system services;
 * an unwired reader returns an honest "unavailable" result rather than a fabricated
 * status. Implementations must never invent a reading.
 */
interface DeviceStatusReader {
    fun read(): DeviceStatusResult
}

/**
 * Real implementation over Android system services. Every reading is read fresh on
 * each call (no caching), so the agent always reports the live device state.
 *
 * The digest is produced by the pure [format] function so the exact planner-visible
 * text is unit-testable without a device.
 */
class AndroidDeviceStatusReader(context: Context) : DeviceStatusReader {

    private val appContext = context.applicationContext

    override fun read(): DeviceStatusResult {
        val message = format(
            batteryPercent = readBatteryPercent(),
            charging = readCharging(),
            screenOn = readScreenOn(),
            ringerMode = readRingerMode(),
            connectivity = readConnectivity()
        )
        return DeviceStatusResult(true, "READ_DEVICE_STATUS", message)
    }

    /** Battery percentage, or -1 when the platform does not expose it. */
    private fun readBatteryPercent(): Int {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (percent in 0..100) percent else -1
    }

    private fun readCharging(): Boolean {
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // A sticky broadcast is the most reliable charging signal; fall back to the
        // manager's own property when the broadcast carries no status extra.
        val intent: Intent? =
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            manager?.isCharging == true
    }

    private fun readScreenOn(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        // "Screen on" means interactive AND not showing the lock screen, which is what
        // the user means by the question.
        return pm?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }

    private fun readRingerMode(): String {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown"
        }
    }

    private fun readConnectivity(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "online"
        }
    }

    companion object {
        /**
         * Pure, deterministic digest of the device state. Kept separate from the
         * Android reads so the exact planner-visible text is unit-testable.
         */
        fun format(
            batteryPercent: Int,
            charging: Boolean,
            screenOn: Boolean,
            ringerMode: String,
            connectivity: String
        ): String {
            val batteryText = if (batteryPercent in 0..100) "$batteryPercent%" else "unknown"
            return buildString {
                append("Battery $batteryText")
                if (charging) append(" (charging)")
                append(" | Screen ${if (screenOn) "on" else "off"}")
                append(" | Ringer $ringerMode")
                append(" | Network $connectivity")
            }
        }
    }
}
