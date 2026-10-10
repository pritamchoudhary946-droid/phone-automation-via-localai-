package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R

/**
 * Posts a summary notification when a task finishes (plan 4.6), so the user is told
 * the outcome when they are not looking at the screen. Best-effort: silently no-ops
 * when the user has not granted notification permission.
 */
class CompletionNotifier(private val context: Context) {

    fun notifyFinished(title: String, summary: String) {
        if (!canPost()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title.take(60))
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(400)))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing else to do.
        }
    }

    /** Posts the daily briefing (plan 9.8) on its own channel/id, so it never clobbers task results. */
    fun notifyBriefing(summary: String) {
        if (!canPost()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Daily briefing")
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(400)))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(BRIEFING_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    /** Posts a proactive, opt-in suggestion (plan 9.1); a separate id so nothing is clobbered. */
    fun notifySuggestion(summary: String) {
        if (!canPost()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Jarvis suggests")
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(400)))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SUGGESTION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automation results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private companion object {
        const val CHANNEL_ID = "omniagent_results"
        const val NOTIFICATION_ID = 4210
        const val BRIEFING_ID = 4211
        const val SUGGESTION_ID = 4212
    }
}
