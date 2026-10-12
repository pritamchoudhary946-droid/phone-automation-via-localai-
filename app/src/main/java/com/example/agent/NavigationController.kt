package com.example.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Result of a single navigation request. */
data class NavigationResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The navigation boundary for [AgentAction.NavigateTo]. This is the "navigate to a
 * place" / "take me to <address>" primitive: no accessibility-tree read or UI tap can
 * open a map at a chosen place reliably on an arbitrary screen, and driving a
 * specific maps app's UI would be app-specific. Instead this dispatches the platform's
 * documented `geo:` URI with [android.content.Intent.ACTION_VIEW], which any device's
 * maps app handles.
 *
 * The `geo:0,0?q=<place>` form shows the named place/address on the map; the user can
 * start turn-by-turn directions from there. It does NOT start navigating by itself, so
 * the consequential act (actually beginning a route) stays under the user's control,
 * matching the section 3.5 rule used by [DialerController]/[SmsController].
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface NavigationController {
    /**
     * Opens the maps app showing [target] (a place name or address). Implementations
     * must report honestly: if no app can handle the request, the result is a failure,
     * never a claimed success. A successful result means only that the map was opened
     * at the place - the user still drives the route.
     */
    fun navigate(target: String): NavigationResult
}

/**
 * Real implementation that dispatches the [Intent.ACTION_VIEW] intent with a `geo:0,0?q=`
 * URI. Because the maps app is a separate process this cannot read back that the place
 * was shown to prove it; it verifies only that the intent was delivered to a maps app,
 * and reports the place it opened so the result is honest about what was and was not
 * confirmed.
 */
class AndroidNavigationController(context: Context) : NavigationController {

    private val appContext = context.applicationContext

    override fun navigate(target: String): NavigationResult {
        val normalized = normalizeNavigationTarget(target)
        if (normalized == null) {
            return NavigationResult(false, "INVALID_NAV_TARGET", "Navigation target must not be blank")
        }
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(normalized)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            NavigationResult(true, "NAV_DISPATCHED", "Opening the map at $normalized - start directions from there")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No maps app can handle ACTION_VIEW geo: ${e.message}")
            NavigationResult(false, "NAV_UNAVAILABLE", "No maps app is available to show the place")
        } catch (e: SecurityException) {
            Log.w(TAG, "Navigation dispatch denied: ${e.message}")
            NavigationResult(false, "NAV_PERMISSION", "Opening the map is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Navigation dispatch failed: ${e.message}")
            NavigationResult(false, "NAV_ERROR", "Navigation request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidNavigationController"

        /** Longest place/address string the tool accepts, so a runaway value cannot build an oversized URI. */
        const val MAX_NAV_TARGET_CHARS = 200

        /**
         * Trims [raw] and rejects anything that is not a usable place string: blank,
         * longer than [MAX_NAV_TARGET_CHARS], or containing a control character (a
         * newline/tab or other unprintable). This is a pure, unit-tested gate so a blank
         * or garbage plan value never builds a nonsense `geo:` URI. Returns null when the
         * input is not a valid target.
         */
        fun normalizeNavigationTarget(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.length > MAX_NAV_TARGET_CHARS) return null
            if (trimmed.any { it.isISOControl() }) return null
            return trimmed
        }
    }
}
