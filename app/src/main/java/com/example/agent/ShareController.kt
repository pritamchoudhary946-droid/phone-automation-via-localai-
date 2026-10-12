package com.example.agent

import android.content.Context
import android.content.Intent
import android.util.Log

/** Result of a single share-sheet request. */
data class ShareResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The share-sheet boundary for [AgentAction.ShareText]. This is the "share this with
 * <app>" primitive: no accessibility-tree read or UI tap can hand a piece of text to an
 * arbitrary target app reliably on an arbitrary screen, and driving a specific app's UI
 * would be app-specific. Instead this dispatches the platform's documented
 * [android.content.Intent.ACTION_SEND] intent with a `text/plain` payload and an
 * optional `EXTRA_TITLE`.
 *
 * If a specific target app is named, the intent is addressed to it (with
 * [Intent.EXTRA_INITIAL_INTENTS] left for the platform chooser as a fallback); otherwise
 * the platform's own share chooser is shown. Either way the user picks the final
 * destination and confirms - the agent never silently posts anywhere, so a consequential
 * share stays under the user's control (section 3.5).
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface ShareController {
    /**
     * Shares [text] through the platform share mechanism, optionally addressed to
     * [targetPackage]. Implementations must report honestly: if no share target can
     * handle the request, the result is a failure, never a claimed success. A successful
     * result means only that the share intent was delivered - the user still chooses the
     * destination and confirms.
     */
    fun share(text: String, targetPackage: String?): ShareResult
}

/**
 * Real implementation that dispatches the [Intent.ACTION_SEND] intent with a
 * `text/plain` payload. Because the receiving app is a separate process this cannot
 * read back that the text was posted to prove it; it verifies only that the intent was
 * delivered (either directly to a named package or to the platform chooser) and reports
 * the target so the result is honest about what was and was not confirmed.
 */
class AndroidShareController(context: Context) : ShareController {

    private val appContext = context.applicationContext

    override fun share(text: String, targetPackage: String?): ShareResult {
        if (text.isBlank()) {
            return ShareResult(false, "INVALID_SHARE_TEXT", "Share text must not be blank")
        }
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkg = targetPackage?.trim()?.takeIf { it.isNotBlank() }
        val intent = if (pkg != null) base.setPackage(pkg) else base
        val chooser = if (pkg != null) {
            // Address the named app but keep the platform chooser as a fallback so a
            // missing/renamed package degrades to "let the user choose" rather than
            // failing the whole share.
            Intent.createChooser(base.removePackage(), "Share via").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(intent))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent.createChooser(base, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val label = pkg ?: "the share menu"
        return try {
            appContext.startActivity(chooser)
            ShareResult(true, "SHARE_DISPATCHED", "Opening $label to share the text - choose a destination to confirm")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No app can handle ACTION_SEND text/plain: ${e.message}")
            ShareResult(false, "SHARE_UNAVAILABLE", "No app is available to share text")
        } catch (e: SecurityException) {
            Log.w(TAG, "Share dispatch denied: ${e.message}")
            ShareResult(false, "SHARE_PERMISSION", "Sharing is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Share dispatch failed: ${e.message}")
            ShareResult(false, "SHARE_ERROR", "Share request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidShareController"
    }
}
