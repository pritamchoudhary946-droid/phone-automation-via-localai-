package com.example.agent

import android.content.Context
import android.content.Intent
import android.util.Log

/** Result of a single note-creation request. */
data class NoteResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The notes boundary for [AgentAction.Note]. This is the "note down that the wifi
 * password is X" / "create a note titled Shopping" primitive: no accessibility-tree
 * read or UI tap can create a note reliably on an arbitrary screen, and driving a
 * specific notes app's UI would be app-specific. Instead this dispatches the platform's
 * documented [android.content.Intent.ACTION_CREATE_NOTE] intent with the
 * [Intent.EXTRA_TITLE] / [Intent.EXTRA_TEXT] extras (the notes app's own editor,
 * pre-filled), which any device's notes app handles.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent
 * no-op.
 */
interface NoteController {
    /**
     * Opens the notes app to create a note titled [title] with [content]. At least one
     * of [title]/[content] must be non-blank so an empty note is never created.
     * Implementations must report honestly: if no notes app can handle the request, the
     * result is a failure, never a claimed success.
     */
    fun createNote(title: String?, content: String?): NoteResult
}

/**
 * Real implementation that dispatches the [Intent.ACTION_CREATE_NOTE] intent with the
 * note's title/text pre-filled. Because the note is created by the notes app (a separate
 * process) this cannot read the note back to prove it now exists; it verifies only that
 * the intent was delivered to a notes app, and reports the accepted title so the result
 * is honest about what was and was not confirmed.
 */
class AndroidNoteController(context: Context) : NoteController {

    private val appContext = context.applicationContext

    override fun createNote(title: String?, content: String?): NoteResult {
        val hasTitle = !title.isNullOrBlank()
        val hasContent = !content.isNullOrBlank()
        if (!hasTitle && !hasContent) {
            return NoteResult(false, "INVALID_NOTE", "A note needs a title or some text")
        }
        val intent = Intent(Intent.ACTION_CREATE_NOTE).apply {
            if (hasTitle) putExtra(Intent.EXTRA_TITLE, title)
            if (hasContent) putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            NoteResult(true, "NOTE_DISPATCHED", "Opening notes to create ${describe(title)}")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No notes app can create a note: ${e.message}")
            NoteResult(false, "NOTE_UNAVAILABLE", "No notes app is available to create a note")
        } catch (e: SecurityException) {
            Log.w(TAG, "Note creation denied: ${e.message}")
            NoteResult(false, "NOTE_PERMISSION", "Creating a note is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Note creation failed: ${e.message}")
            NoteResult(false, "NOTE_ERROR", "Note request failed: ${e.localizedMessage}")
        }
    }

    private fun describe(title: String?): String =
        if (!title.isNullOrBlank()) "'$title'" else "a new note"

    companion object {
        private const val TAG = "AndroidNoteController"
    }
}
