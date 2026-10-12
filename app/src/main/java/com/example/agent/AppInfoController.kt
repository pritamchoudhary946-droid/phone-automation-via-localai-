package com.example.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** Result of a single "open this app's settings" request. */
data class AppInfoResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The app-settings boundary for [AgentAction.OpenAppInfo]. This is the "open
 * <app>'s settings / its app info page" primitive: an app's own Settings or App-info
 * screen is not reachable from the accessibility tree reliably on an arbitrary screen
 * (driving it would be a long device-specific tap path), so instead this dispatches
 * the platform's documented `ACTION_APPLICATION_DETAILS_SETTINGS` intent addressed to
 * the target package, which the system Settings app handles.
 *
 * It carries no permission or destructive change: it only *opens* the App-info page,
 * where the user decides what to do (force-stop, clear data, uninstall, change
 * permissions). Nothing consequential is performed by the primitive itself, so it
 * stays inside the user's control, matching the section 3.5 rule used by
 * [DialerController]/[SmsController]/[NavigationController].
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface AppInfoController {
    /**
     * Opens the system Settings App-info page for [packageName]. Implementations must
     * report honestly: if no Settings app can handle the request, or the package is
     * unknown, the result is a failure, never a claimed success. A successful result
     * means only that the App-info page was opened - nothing in it was changed.
     */
    fun openAppInfo(packageName: String): AppInfoResult
}

/**
 * Real implementation that dispatches the [Settings.ACTION_APPLICATION_DETAILS_SETTINGS]
 * intent with a `package:` URI [Uri]. Because the Settings app is a separate process
 * this cannot read back that the page was shown to prove it; it verifies only that the
 * intent was delivered to a settings handler, and reports the package it addressed so
 * the result is honest about what was and was not confirmed.
 */
class AndroidAppInfoController(context: Context) : AppInfoController {

    private val appContext = context.applicationContext

    override fun openAppInfo(packageName: String): AppInfoResult {
        val normalized = normalizePackageName(packageName)
            ?: return AppInfoResult(false, "INVALID_APP_INFO_PACKAGE", "App package must not be blank")
        val uri = Uri.fromParts("package", normalized, null)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            AppInfoResult(true, "APP_INFO_OPENED", "Opening settings for $normalized")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No settings app can handle ACTION_APPLICATION_DETAILS_SETTINGS: ${e.message}")
            AppInfoResult(false, "APP_INFO_UNAVAILABLE", "No settings app is available to show app info")
        } catch (e: SecurityException) {
            Log.w(TAG, "App-info dispatch denied: ${e.message}")
            AppInfoResult(false, "APP_INFO_PERMISSION", "Opening app info is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "App-info dispatch failed: ${e.message}")
            AppInfoResult(false, "APP_INFO_ERROR", "App info request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidAppInfoController"

        /** Longest package string the tool accepts, so a runaway value cannot build an oversized URI. */
        const val MAX_PACKAGE_CHARS = 200

        /**
         * Trims [raw] and rejects anything that is not a usable Android package name:
         * blank, longer than [MAX_PACKAGE_CHARS], or containing a control character. This
         * is a pure, unit-tested gate so a blank or garbage plan value never builds a
         * nonsense `package:` URI. Returns null when the input is not a valid package.
         */
        fun normalizePackageName(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.length > MAX_PACKAGE_CHARS) return null
            if (trimmed.any { it.isISOControl() }) return null
            return trimmed
        }
    }
}
