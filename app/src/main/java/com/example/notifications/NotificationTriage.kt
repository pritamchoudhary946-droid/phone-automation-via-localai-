package com.example.notifications

/**
 * On-device smart notification triage (plan 9.9).
 *
 * Groups and summarises posted notifications without a network call, and derives
 * the safe actions that may be *offered* - never taken - for each. Pure and
 * deterministic so it is unit-testable with a fake [NotificationSource].
 *
 * Safety: a reply is only ever offered; the app must confirm before sending. Nothing
 * auto-sends, auto-dismisses, or leaves the device.
 */
object NotificationTriage {

    data class Group(
        val packageName: String,
        val count: Int,
        val summaries: List<String>,
        val canReply: Boolean
    )

    data class Digest(val groups: List<Group>, val line: String)

    fun digest(notifications: List<AgentNotification>): Digest {
        val groups = notifications
            .groupBy { it.packageName }
            .map { (pkg, items) ->
                Group(
                    packageName = pkg,
                    count = items.size,
                    summaries = items.take(3).map { it.title.ifBlank { it.text } }.filter { it.isNotBlank() },
                    canReply = items.any { it.canReply }
                )
            }
            .sortedByDescending { it.count }

        val line = if (groups.isEmpty()) {
            "No new notifications."
        } else {
            buildString {
                append("${notifications.size} new notification")
                if (notifications.size != 1) append("s")
                append(" from ${groups.size} app")
                if (groups.size != 1) append("s")
                append(": ")
                append(groups.take(3).joinToString(", ") { g ->
                    val label = friendlyApp(g.packageName)
                    "$label (${g.count})"
                })
                append(".")
            }
        }
        return Digest(groups, line)
    }

    /**
     * The actions that may be *offered* for a notification. Reply is offered only
     * when the notification advertises a reply affordance; taking any of these still
     * requires an explicit user confirmation elsewhere.
     */
    fun offeredActions(notification: AgentNotification): List<String> = buildList {
        add("open")
        if (notification.canReply) add("reply")
        add("dismiss")
    }

    /** A short, human label for a package, without a package-name lookup table. */
    fun friendlyApp(packageName: String): String {
        val last = packageName.substringAfterLast('.')
        return last.replaceFirstChar { it.uppercase() }.ifBlank { packageName }
    }
}
