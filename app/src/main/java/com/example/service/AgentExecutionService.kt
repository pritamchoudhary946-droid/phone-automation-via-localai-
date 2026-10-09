package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class AgentExecutionService : Service {

    constructor() : super()

    companion object {
        const val CHANNEL_ID = "agent_execution_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "ACTION_START_AGENT"
        const val ACTION_STOP = "ACTION_STOP_AGENT"
        const val EXTRA_TASK_GOAL = "EXTRA_TASK_GOAL"

        fun startService(context: Context, goal: String) {
            val intent = Intent(context, AgentExecutionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_GOAL, goal)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AgentExecutionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val goal = intent.getStringExtra(EXTRA_TASK_GOAL) ?: "Automating task..."
                startForeground(NOTIFICATION_ID, buildNotification(goal))
            }
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniAgent Active Task",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status while OmniAgent is automating actions"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(goalText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniAgent Running")
            .setContentText(goalText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
