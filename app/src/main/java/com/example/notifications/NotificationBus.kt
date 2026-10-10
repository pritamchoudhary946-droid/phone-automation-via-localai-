package com.example.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** A bounded, on-device view of one posted notification. */
data class AgentNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val canReply: Boolean
)

/**
 * In-memory, bounded bus of posted notifications (plan 4.4).
 *
 * Content stays on-device. The agent sees a capped, most-recent list only. Kept as
 * an interface so the planner can be fed notifications in tests without a device.
 */
interface NotificationSource {
    fun recent(limit: Int = DEFAULT_LIMIT): List<AgentNotification>

    companion object {
        const val DEFAULT_LIMIT = 5
    }
}

/** Holds the most recent notifications, capped to avoid unbounded memory. */
object NotificationBus : NotificationSource {

    private const val CAPACITY = 20
    private val items = ArrayDeque<AgentNotification>()

    @Synchronized
    fun offer(notification: AgentNotification) {
        items.addFirst(notification)
        while (items.size > CAPACITY) items.removeLast()
    }

    @Synchronized
    override fun recent(limit: Int): List<AgentNotification> = items.take(limit)

    @Synchronized
    fun clear() = items.clear()
}

/**
 * Reads posted notifications into [NotificationBus]. Requires the user to grant
 * "notification access"; it does nothing beyond the listener contract and never
 * reads notification content off-device.
 */
class AgentNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val canReply = n.notification.actions
            ?.any { it.remoteInputs?.isNotEmpty() == true } == true
        NotificationBus.offer(
            AgentNotification(
                packageName = n.packageName,
                title = title,
                text = text,
                postedAt = n.postTime,
                canReply = canReply
            )
        )
    }
}
