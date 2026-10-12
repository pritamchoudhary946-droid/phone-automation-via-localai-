package com.example.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Result of a single email-compose request. */
data class EmailResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The email boundary for [AgentAction.SendEmail]. This is the "email Sam at
 * sam@example.com that the report is ready" primitive: no accessibility-tree read or UI
 * tap can open a mail compose screen with a recipient and body reliably on an arbitrary
 * screen, and driving a specific mail app's UI would be app-specific. Instead this
 * dispatches the platform's documented [android.content.Intent.ACTION_SENDTO] intent
 * with a `mailto:` data URI (recipient), which any device's mail app handles.
 *
 * Like the SMS/dialer primitives it opens the compose screen PRE-FILLED for the user to
 * review and press send - it does NOT send the message itself. Sending mail is a
 * consequential, hard-to-undo action that must stay under the user's explicit control
 * (section 3.5), so this boundary deliberately does not request any send permission.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface EmailController {
    /**
     * Opens the mail compose screen pre-filled with [to], and optionally [subject] and
     * [body]. Implementations must report honestly: if no mail app can handle the
     * request, the result is a failure, never a claimed success. A successful result
     * means only that the compose screen was opened - the message is never sent.
     */
    fun compose(to: String, subject: String?, body: String?): EmailResult
}

/**
 * Real implementation that dispatches the [Intent.ACTION_SENDTO] intent with a
 * `mailto:` URI and the `EXTRA_EMAIL`/`EXTRA_SUBJECT`/`EXTRA_TEXT` extras. Because the
 * mail app is a separate process this cannot read back that the message was sent (or
 * even typed) to prove it; it verifies only that the intent was delivered to a mail app,
 * and reports the recipient it opened so the result is honest about what was and was not
 * confirmed.
 */
class AndroidEmailController(context: Context) : EmailController {

    private val appContext = context.applicationContext

    override fun compose(to: String, subject: String?, body: String?): EmailResult {
        val normalized = normalizeEmailAddress(to)
        if (normalized == null) {
            return EmailResult(false, "INVALID_EMAIL_ADDRESS", "Email recipient must be a valid address")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$normalized")).apply {
            // Some mail clients read the recipient from EXTRA_EMAIL rather than the URI;
            // supplying both keeps the address populated across clients.
            putExtra(Intent.EXTRA_EMAIL, arrayOf(normalized))
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            EmailResult(true, "EMAIL_DISPATCHED", "Opening mail to email $normalized - press send to deliver it")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No mail app can handle ACTION_SENDTO mailto:: ${e.message}")
            EmailResult(false, "EMAIL_UNAVAILABLE", "No mail app is available to compose an email")
        } catch (e: SecurityException) {
            Log.w(TAG, "Email compose dispatch denied: ${e.message}")
            EmailResult(false, "EMAIL_PERMISSION", "Opening the mail app is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Email compose dispatch failed: ${e.message}")
            EmailResult(false, "EMAIL_ERROR", "Email request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidEmailController"

        /**
         * Accepts only a plausible single email address (a non-empty local part, a single
         * `@`, and a dotted domain with no empty labels) and rejects blank or garbage
         * input before an intent is built, so a bad plan value cannot open a nonsense
         * `mailto:` compose screen. Returns null when the input is not a valid address.
         */
        fun normalizeEmailAddress(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it.isWhitespace() }) return null
            val at = trimmed.indexOf('@')
            if (at <= 0 || at != trimmed.lastIndexOf('@')) return null
            val local = trimmed.substring(0, at)
            val domain = trimmed.substring(at + 1)
            if (local.isEmpty() || domain.isEmpty()) return null
            if (domain.startsWith('.') || domain.endsWith('.') || ".." in domain) return null
            if (!domain.contains('.')) return null
            if (domain.split('.').any { it.isEmpty() }) return null
            return trimmed
        }
    }
}
