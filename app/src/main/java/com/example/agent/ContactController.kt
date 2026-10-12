package com.example.agent

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.util.Log

/** Result of a single contact insert request. */
data class ContactResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The contacts boundary for [AgentAction.AddContact]. This is the "save Priya's
 * number 98xxxxxx" / "add an email contact for Sam" primitive: no accessibility-tree
 * read or UI tap can add a contact reliably on an arbitrary screen, and driving a
 * specific contacts app's UI would be app-specific. Instead this dispatches the
 * platform's documented [android.content.Intent.ACTION_INSERT] intent with the
 * [ContactsContract.Intents.Insert] extras (the contacts app's own editor,
 * pre-filled), which any device's contacts app handles.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent
 * no-op.
 */
interface ContactController {
    /**
     * Adds a contact named [name] with an optional [phone] and/or [email]. At least one
     * of [phone]/[email] is required so an empty contact is never created.
     * Implementations must report honestly: if no contacts app can handle the request,
     * the result is a failure, never a claimed success.
     */
    fun addContact(name: String, phone: String?, email: String?): ContactResult
}

/**
 * Real implementation that dispatches the [ContactsContract.Intents.Insert.ACTION]
 * intent with the contact's fields pre-filled. Because the contact is created by the
 * contacts app (a separate process) this cannot read the contact back to prove it now
 * exists; it verifies only that the intent was delivered to a contacts app, and
 * reports the accepted name so the result is honest about what was and was not
 * confirmed.
 */
class AndroidContactController(context: Context) : ContactController {

    private val appContext = context.applicationContext

    override fun addContact(name: String, phone: String?, email: String?): ContactResult {
        if (name.isBlank()) {
            return ContactResult(false, "INVALID_CONTACT_NAME", "Contact name must not be blank")
        }
        val hasPhone = !phone.isNullOrBlank()
        val hasEmail = !email.isNullOrBlank()
        if (!hasPhone && !hasEmail) {
            return ContactResult(false, "INVALID_CONTACT_DETAILS", "A contact needs at least a phone number or an email")
        }
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            if (hasPhone) putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            if (hasEmail) putExtra(ContactsContract.Intents.Insert.EMAIL, email)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            ContactResult(
                true,
                "CONTACT_DISPATCHED",
                "Opening contacts to add '$name'${detailSuffix(phone, email)}"
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No contacts app can insert a contact: ${e.message}")
            ContactResult(false, "CONTACT_UNAVAILABLE", "No contacts app is available to add a contact")
        } catch (e: SecurityException) {
            Log.w(TAG, "Contact insert denied: ${e.message}")
            ContactResult(false, "CONTACT_PERMISSION", "Adding a contact is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Contact insert failed: ${e.message}")
            ContactResult(false, "CONTACT_ERROR", "Contact request failed: ${e.localizedMessage}")
        }
    }

    private fun detailSuffix(phone: String?, email: String?): String = buildString {
        if (!phone.isNullOrBlank()) append(" ($phone)")
        if (!email.isNullOrBlank()) append(" ($email)")
    }

    companion object {
        private const val TAG = "AndroidContactController"
    }
}
