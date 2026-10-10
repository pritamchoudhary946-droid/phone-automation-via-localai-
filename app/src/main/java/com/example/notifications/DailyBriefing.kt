package com.example.notifications

import android.content.Context
import android.util.Log
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.OmniAgentApplication
import com.example.settings.CapabilitySettings
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily briefing / digest (plan 9.8).
 *
 * A bounded, on-device summary of the day's notifications, spoken (when voice
 * output is on) and posted as a notification. Never leaves the device; the
 * optional web addition is intentionally not performed here so the briefing is
 * deterministic and offline by default.
 */
object DailyBriefing {

    /**
     * Pure composition of the briefing line from the recent notifications, so it is
     * unit-testable without a device. Reuses the triage summariser (plan 9.9).
     */
    fun compose(notifications: List<AgentNotification>): String {
        val digest = NotificationTriage.digest(notifications)
        return "Good day. " + digest.line
    }

    /** Milliseconds from [now] until the next occurrence of [minuteOfDay]. */
    fun millisUntil(minuteOfDay: Int, now: Calendar): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, (minuteOfDay / 60).coerceIn(0, 23))
            set(Calendar.MINUTE, (minuteOfDay % 60).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}

/**
 * Runs the daily briefing. Honours the opt-in toggle and the automation master
 * switch; when disabled it silently succeeds without speaking.
 */
class DailyBriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val settings = CapabilitySettings(applicationContext)
        if (!settings.briefingEnabled) {
            // Not opted in: nothing to do (and nothing spoken).
            return Result.success()
        }
        return try {
            val app = applicationContext as? OmniAgentApplication ?: return Result.success()
            val line = DailyBriefing.compose(NotificationBus.recent(NotificationSource.DEFAULT_LIMIT))
            app.completionNotifier.notifyBriefing(line)
            if (settings.voiceOutputEnabled) {
                runCatching { app.speechOutput.speak(line) }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Briefing failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DailyBriefing"
    }
}

/** Scheduling for the daily briefing job (plan 9.8). */
object BriefingScheduler {

    private const val UNIQUE = "omniagent_daily_briefing"
    const val TAG = "omniagent_briefing"

    /** Enqueues (or cancels) the daily briefing to match the current settings. */
    fun sync(context: Context) {
        val settings = CapabilitySettings(context)
        if (!settings.briefingEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            return
        }
        val delay = DailyBriefing.millisUntil(settings.briefingMinuteOfDay, Calendar.getInstance())
        val request = PeriodicWorkRequestBuilder<DailyBriefingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}
