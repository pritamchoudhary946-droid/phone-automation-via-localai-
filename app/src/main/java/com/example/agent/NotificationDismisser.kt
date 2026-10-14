package com.example.agent

import com.example.notifications.AgentNotificationListenerService

/** Result of a single notification dismissal. */
data class NotificationDismissResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The notification-dismiss boundary for [AgentAction.DismissNotification].
 *
 * Posted notifications live in the system's notification shade, not in the
 * accessibility tree, so this is the service-free action behind "clear that
 * notification" / "dismiss my notifications" - the *act* half of the smart-triage
 * capability ([com.example.notifications.NotificationTriage] already *offers* a
 * `dismiss` action for every posted notification, but until now nothing could take it).
 *
 * It is injectable so the automation layer is testable without a real notification
 * shade; an unwired dismisser returns an honest "unavailable" result rather than
 * pretending the notification went away. Implementations must never report a
 * dismissal they did not perform.
 */
interface NotificationDismisser {
    /**
     * Dismisses the active notification from [packageName]. A blank package dismisses
     * every notification. Implementations must report honestly: a missing notification
     * access, an empty shade, or a platform refusal is a failure, not a claimed success.
     */
    fun dismiss(packageName: String?): NotificationDismissResult
}

/**
 * Real implementation over the app's own `NotificationListenerService` connection.
 *
 * Dismissal is a system-service side effect no accessibility screen reflects, so it
 * cannot be verified from a fresh observation. Instead the notification-access
 * connection is treated as the evidence: a dismissal is reported as success only when
 * a live listener connection is held (the same connection the platform requires to
 * remove a notification at all). An absent connection is reported honestly as a
 * failure, and a per-package dismissal that matches nothing is reported as
 * "nothing to dismiss" rather than a fake success.
 *
 * `cancelAllNotifications()` / `cancelNotification()` need notification access; without
 * it the platform simply does not remove anything, so a missing connection is the exact
 * blocker and is surfaced rather than assumed away.
 */
class AndroidNotificationDismisser : NotificationDismisser {

    override fun dismiss(packageName: String?): NotificationDismissResult {
        val service = AgentNotificationListenerService.instance
            ?: return NotificationDismissResult(
                false,
                "DISMISS_UNAVAILABLE",
                "Notification access is not granted. Enable Jarvis in Settings > Notifications > Device & app notifications."
            )
        return try {
            val target = packageName?.takeIf { it.isNotBlank() }
            if (target == null) {
                service.cancelAllNotifications()
                NotificationDismissResult(true, "DISMISSED_ALL", "Dismissed all notifications")
            } else {
                val removed = service.dismissPackage(target)
                if (removed > 0) {
                    NotificationDismissResult(true, "DISMISSED", "Dismissed $removed notification(s) from $target")
                } else {
                    NotificationDismissResult(false, "DISMISS_NONE", "No active notification from $target to dismiss")
                }
            }
        } catch (e: SecurityException) {
            NotificationDismissResult(
                false,
                "DISMISS_PERMISSION",
                "Dismissing notifications is not permitted; grant notification access to Jarvis"
            )
        } catch (e: Exception) {
            NotificationDismissResult(false, "DISMISS_ERROR", "Dismissal failed: ${e.localizedMessage}")
        }
    }
}
