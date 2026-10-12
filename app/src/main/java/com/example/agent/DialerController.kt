package com.example.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Result of a single dialer request. */
data class DialResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The dialer boundary for [AgentAction.DialNumber]. This is the "dial this number" /
 * "call Priya" primitive: no accessibility-tree read or UI tap can open a dialer with
 * a number reliably on an arbitrary screen, and driving a specific phone app's UI would
 * be app-specific. Instead this dispatches the platform's documented
 * [android.content.Intent.ACTION_DIAL] intent with a `tel:` data URI, which any
 * device's dialer handles.
 *
 * This places the number in the dialer for the user to press the call button - it does
 * NOT place the call. `ACTION_CALL` would require the `CALL_PHONE` runtime permission
 * and would start a call without the user's confirmation, which crosses the app's
 * safety boundary (section 3.5: consequential/irreversible actions need the user's
 * explicit confirmation). The user remains in control of the final call.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface DialerController {
    /**
     * Opens the system dialer pre-filled with [number]. Implementations must report
     * honestly: if no dialer can handle the request, the result is a failure, never a
     * claimed success. A successful result means only that the dialer was opened - the
     * call itself is never placed.
     */
    fun dial(number: String): DialResult
}

/**
 * Real implementation that dispatches the [Intent.ACTION_DIAL] intent with a `tel:`
 * URI. Because the dialer is a separate process this cannot read back that the number
 * was entered to prove it; it verifies only that the intent was delivered to a dialer,
 * and reports the number it opened so the result is honest about what was and was not
 * confirmed.
 */
class AndroidDialerController(context: Context) : DialerController {

    private val appContext = context.applicationContext

    override fun dial(number: String): DialResult {
        val normalized = normalizeDialNumber(number)
        if (normalized == null) {
            return DialResult(false, "INVALID_DIAL_NUMBER", "Dial number must contain digits and may include + * # , ;")
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$normalized")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            DialResult(true, "DIAL_DISPATCHED", "Opening the dialer with $normalized - press call to place it")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No dialer can handle ACTION_DIAL: ${e.message}")
            DialResult(false, "DIAL_UNAVAILABLE", "No dialer app is available to place the number")
        } catch (e: SecurityException) {
            Log.w(TAG, "Dialer dispatch denied: ${e.message}")
            DialResult(false, "DIAL_PERMISSION", "Opening the dialer is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Dialer dispatch failed: ${e.message}")
            DialResult(false, "DIAL_ERROR", "Dialer request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidDialerController"

        /**
         * Accepts only characters a `tel:` URI permits for a dialable number (digits,
         * an optional leading `+`, and the pause/wait/extension marks `* # , ;`), and
         * requires at least one digit. This rejects a blank or garbage target before an
         * intent is built, so a bad plan value cannot open a nonsense dialer. Returns
         * null when the input is not dialable.
         */
        fun normalizeDialNumber(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val allowed = "+*#,;0123456789"
            if (trimmed.any { it !in allowed }) return null
            if (trimmed.none { it.isDigit() }) return null
            return trimmed
        }
    }
}
