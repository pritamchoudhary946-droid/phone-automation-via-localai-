package com.example.agent

import android.content.Context
import android.content.Intent
import android.util.Log

/** Result of a single "open the file picker" request. */
data class FilePickResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The file/document *picking* boundary for [AgentAction.PickFile]. This is the
 * "pick a file" / "choose a photo to attach" / "select a document" primitive: the
 * system document picker is a separate process no accessibility-tree read or UI tap can
 * open reliably on an arbitrary screen (and driving a specific file-manager UI would be
 * app-specific). Instead this dispatches the platform's documented
 * [android.content.Intent.ACTION_GET_CONTENT] intent, which every device resolves to the
 * system picker (Files / MediaStore / a chooser).
 *
 * It carries no permission and performs nothing consequential: it only *opens* the
 * picker for the user to choose a file themselves, so the selection stays under the
 * user's control (section 3.5). The app cannot read back which file was chosen (the
 * picker is a separate process), so a successful result means only that the picker was
 * *opened*, never that a file was selected.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface FilePickerController {
    /**
     * Opens the system file picker filtered to [mimeType] (e.g. `image/*`, `application/pdf`;
     * null/blank means "any file"). When [allowMultiple] is set the user may select more
     * than one file. Implementations must report honestly: if no picker can handle the
     * request the result is a failure, never a claimed success.
     */
    fun pickFile(mimeType: String?, allowMultiple: Boolean): FilePickResult
}

/**
 * Real implementation that dispatches the documented [Intent.ACTION_GET_CONTENT] intent
 * with [Intent.CATEGORY_OPENABLE] and an optional [Intent.EXTRA_ALLOW_MULTIPLE]. Because
 * the picker is a separate process this cannot read back the chosen file to prove a
 * selection was made; it verifies only that the intent was delivered to a picker, and
 * says so honestly.
 */
class AndroidFilePickerController(context: Context) : FilePickerController {

    private val appContext = context.applicationContext

    override fun pickFile(mimeType: String?, allowMultiple: Boolean): FilePickResult {
        val mime = mimeType?.trim()?.takeIf { it.isNotBlank() } ?: ANY_MIME
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mime
            // CATEGORY_OPENABLE restricts the result to files the app can actually open,
            // which is what every file-consuming flow needs.
            addCategory(Intent.CATEGORY_OPENABLE)
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            FilePickResult(true, "FILE_PICKER_OPENED", "Opening the file picker for $mime")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No picker can handle ACTION_GET_CONTENT: ${e.message}")
            FilePickResult(false, "FILE_PICKER_UNAVAILABLE", "No file picker is available on this device")
        } catch (e: SecurityException) {
            Log.w(TAG, "File picker denied: ${e.message}")
            FilePickResult(false, "FILE_PICKER_PERMISSION", "Opening the file picker is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "File picker failed: ${e.message}")
            FilePickResult(false, "FILE_PICKER_ERROR", "File picker request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidFilePickerController"
        const val ANY_MIME = "*/*"
    }
}
