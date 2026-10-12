package com.example.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Result of a single SMS-compose request. */
data class SmsResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The SMS boundary for [AgentAction.SendSms]. This is the "text Priya 9876543210 that
 * I'll be late" primitive: no accessibility-tree read or UI tap can open a messaging
 * compose screen with a number and body reliably on an arbitrary screen, and driving a
 * specific messaging app's UI would be app-specific. Instead this dispatches the
 * platform's documented [android.content.Intent.ACTION_SENDTO] intent with an `smsto:`
 * data URI (number) and the `"sms_body"` extra (message), which any device's messaging
 * app handles.
 *
 * Like the dialer primitive it opens the compose screen PRE-FILLED for the user to
 * review and press send - it does NOT send the message itself. Sending is a
 * consequential, hard-to-undo action that must stay under the user's explicit control
 * (section 3.5), so this boundary deliberately does not request `SEND_SMS`.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface SmsController {
    /**
     * Opens the messaging compose screen pre-filled with [number] and [message].
     * Implementations must report honestly: if no messaging app can handle the request,
     * the result is a failure, never a claimed success. A successful result means only
     * that the compose screen was opened - the message is never sent.
     */
    fun compose(number: String, message: String): SmsResult
}

/**
 * Real implementation that dispatches the [Intent.ACTION_SENDTO] intent with an
 * `smsto:` URI and the `"sms_body"` extra. Because the messaging app is a separate
 * process this cannot read back that the message was sent (or even typed) to prove it;
 * it verifies only that the intent was delivered to a messaging app, and reports the
 * number it opened so the result is honest about what was and was not confirmed.
 */
class AndroidSmsController(context: Context) : SmsController {

    private val appContext = context.applicationContext

    override fun compose(number: String, message: String): SmsResult {
        val normalized = normalizeSmsNumber(number)
        if (normalized == null) {
            return SmsResult(false, "INVALID_SMS_NUMBER", "SMS number must contain digits and may include + * # , ;")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$normalized")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            SmsResult(true, "SMS_DISPATCHED", "Opening messages to text $normalized - press send to deliver it")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No messaging app can handle ACTION_SENDTO smsto:: ${e.message}")
            SmsResult(false, "SMS_UNAVAILABLE", "No messaging app is available to send an SMS")
        } catch (e: SecurityException) {
            Log.w(TAG, "SMS compose dispatch denied: ${e.message}")
            SmsResult(false, "SMS_PERMISSION", "Opening the messaging app is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "SMS compose dispatch failed: ${e.message}")
            SmsResult(false, "SMS_ERROR", "SMS request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidSmsController"

        /**
         * Accepts only characters an `smsto:` URI permits for a dialable number (digits,
         * an optional leading `+`, and the pause/wait/extension marks `* # , ;`), and
         * requires at least one digit. This rejects a blank or garbage target before an
         * intent is built, so a bad plan value cannot open a nonsense compose screen.
         * Returns null when the input is not a valid SMS target.
         */
        fun normalizeSmsNumber(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val allowed = "+*#,;0123456789"
            if (trimmed.any { it !in allowed }) return null
            if (trimmed.none { it.isDigit() }) return null
            return trimmed
        }
    }
}
