package com.example.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.example.accessibility.AgentAccessibilityService
import com.example.notifications.NotificationBus
import com.example.perception.BatteryGuard

/**
 * Samples the live on-device conditions a Phase 9.2 event trigger is evaluated against
 * (plan 9.2). Everything here is read from already-permissioned, on-device sources:
 * the bound accessibility service's foreground package, the bounded in-memory
 * notification bus, the connectivity manager and the battery guard. Location is left
 * null (a permissioned provider is required and is opt-in), so a LOCATION_ARRIVE trigger
 * honestly never matches unless the caller supplies a region.
 *
 * The evaluation itself is [TriggerMatcher.matches], which is pure; this object is the
 * thin device adapter around it so the decision stays unit-testable.
 */
object AutomationConditions {

    fun sample(context: Context, lastRunMs: Long = 0L): TriggerMatcher.Conditions {
        val battery = BatteryGuard.read(context)
        val network = readNetworkType(context)
        return TriggerMatcher.Conditions(
            nowMs = System.currentTimeMillis(),
            lastRunMs = lastRunMs,
            wifiConnected = network == "WIFI",
            ssid = readSsid(context),
            batteryPercent = battery.batteryPercent,
            charging = battery.charging,
            networkType = network,
            foregroundPackage = AgentAccessibilityService.instance
                ?.screenEvents?.value?.activePackageName,
            recentNotificationPackages = NotificationBus.recent(20).map { it.packageName }.toSet(),
            locationRegion = null
        )
    }

    private fun readNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "NONE"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    /**
     * The connected Wi-Fi SSID, or null when it cannot be read. Reading the SSID needs
     * location permission on modern Android; without it the platform returns a masked
     * value, which we treat as "unknown" rather than inventing a network name.
     */
    @Suppress("DEPRECATION")
    private fun readSsid(context: Context): String? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val name = runCatching { wifi.connectionInfo?.ssid }.getOrNull()?.trim('"')
        return if (name.isNullOrBlank() || name == "<unknown ssid>") null else name
    }

    /** True when a foreground-package style trigger needs the accessibility service bound. */
    fun needsAccessibility(triggerType: String): Boolean =
        triggerType == AutomationTrigger.APP_FOREGROUND
}
