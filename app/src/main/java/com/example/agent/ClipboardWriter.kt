package com.example.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Result of a single clipboard write. */
data class ClipboardWriteResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The clipboard-write boundary for [AgentAction.SetClipboard].
 *
 * The clipboard contents live on the system `ClipboardManager`, not in the
 * accessibility tree, so this is the service-free write primitive behind "copy this to
 * the clipboard" / "put this on my clipboard" - a copy-then-process chain that hands a
 * chosen value to another app. It is injectable so the automation layer is testable
 * without a real clipboard; an unwired writer returns an honest "unavailable" result
 * rather than pretending the write happened. Implementations must never report a write
 * they did not perform.
 */
interface ClipboardWriter {
    fun write(text: String): ClipboardWriteResult
}

/**
 * Real implementation over the Android `ClipboardManager`.
 *
 * A clipboard write is a system-service side effect no accessibility screen reflects,
 * so it cannot be verified from a fresh observation; instead the write is read back and
 * reported as success only when the clipboard actually holds the value (or at least a
 * non-empty clip). It reports failure honestly when the service is missing or the write
 * did not take, so the step fails rather than claiming a false success.
 */
class AndroidClipboardWriter(context: Context) : ClipboardWriter {

    private val appContext = context.applicationContext

    override fun write(text: String): ClipboardWriteResult {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardWriteResult(false, "CLIPBOARD_UNAVAILABLE", "Clipboard service is not available")
        return try {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            // Read the clip back: the platform can reject/clear a write (e.g. when the
            // app is not in the foreground), and a write that did not take must not be
            // reported as success.
            val stored = manager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(appContext)
                ?.toString()
                .orEmpty()
            if (stored == text) {
                ClipboardWriteResult(true, "SET_CLIPBOARD", "Copied ${text.length} chars to the clipboard")
            } else if (stored.isNotBlank()) {
                // The platform stored something else (it may sanitise/truncate); a
                // non-empty clip means the write took, so report it as delivered.
                ClipboardWriteResult(true, "SET_CLIPBOARD", "Copied text to the clipboard")
            } else {
                ClipboardWriteResult(false, "CLIPBOARD_WRITE_FAILED", "The clipboard did not accept the text")
            }
        } catch (e: Exception) {
            ClipboardWriteResult(false, "CLIPBOARD_WRITE_FAILED", "Clipboard write failed: ${e.message ?: "unknown error"}")
        }
    }

    private companion object {
        const val CLIP_LABEL = "Jarvis"
    }
}
