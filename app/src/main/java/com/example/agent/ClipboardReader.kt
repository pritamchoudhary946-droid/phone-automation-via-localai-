package com.example.agent

import android.content.ClipboardManager
import android.content.Context

/** Result of a single clipboard read. */
data class ClipboardReadResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The clipboard-read boundary for [AgentAction.ReadClipboard].
 *
 * The clipboard contents live on the system `ClipboardManager`, not in the
 * accessibility tree, so this is the service-free read primitive behind "what did I
 * just copy?" and a copy-then-process chain (copy in one app, read the value here,
 * feed it to a later step). It is injectable so the automation layer is testable
 * without a real clipboard; an unwired reader returns an honest "unavailable" result
 * rather than a fabricated value. Implementations must never invent clipboard text.
 */
interface ClipboardReader {
    fun read(): ClipboardReadResult
}

/**
 * Real implementation over the Android `ClipboardManager`.
 *
 * The read is fresh on each call (no caching) and never persists the value. It reports
 * an empty clipboard honestly: the clipboard can legitimately be empty (or hold a
 * non-text clip), which is *not* a success - the carried digest would be blank, so the
 * step fails rather than satisfying a NONE postcondition with nothing.
 */
class AndroidClipboardReader(context: Context) : ClipboardReader {

    private val appContext = context.applicationContext

    override fun read(): ClipboardReadResult {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ClipboardReadResult(false, "CLIPBOARD_UNAVAILABLE", "Clipboard service is not available")
        if (!manager.hasPrimaryClip()) {
            return ClipboardReadResult(false, "CLIPBOARD_EMPTY", "The clipboard is empty")
        }
        val clip = manager.primaryClip ?: return ClipboardReadResult(false, "CLIPBOARD_EMPTY", "The clipboard is empty")
        if (clip.itemCount == 0) {
            return ClipboardReadResult(false, "CLIPBOARD_EMPTY", "The clipboard is empty")
        }
        // Only a text clip is readable text; a copied image/URI would otherwise be
        // coerced to "null"/garbage, so refuse it honestly instead of returning junk.
        val text = clip.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        return if (text.isBlank()) {
            ClipboardReadResult(false, "CLIPBOARD_EMPTY", "The clipboard holds no readable text")
        } else {
            ClipboardReadResult(true, "READ_CLIPBOARD", text)
        }
    }
}
