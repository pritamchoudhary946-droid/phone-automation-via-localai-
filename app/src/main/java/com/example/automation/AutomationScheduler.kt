package com.example.automation

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.NetworkType
import com.example.OmniAgentApplication
import com.example.settings.CapabilitySettings
import java.util.concurrent.TimeUnit

/** WorkManager tag prefix for every scheduled automation job. */
const val AUTOMATION_WORK_TAG = "omniagent_automation"

/**
 * Runs one saved automation's skill by name (plan 7.8). Honours the master
 * [CapabilitySettings.automationsEnabled] off switch and [AutomationGate], so
 * nothing runs when the user has automations disabled — even if a job was
 * previously enqueued.
 */
class AutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val skillName = inputData.getString(KEY_SKILL) ?: return Result.failure()
        val automationId = inputData.getLong(KEY_AUTOMATION_ID, 0L)
        return try {
            val app = applicationContext as OmniAgentApplication
            // Re-check at run time: a scheduled job whose automation was disabled/deleted
            // (or whose skill was removed) must never drive the orchestrator. For an event
            // trigger, the condition must also actually hold right now, so an always-on
            // poll never replays the skill until its trigger genuinely fires (plan 9.2).
            if (automationId > 0L) {
                val decision = kotlinx.coroutines.runBlocking { app.orchestrator.evaluateCondition(automationId) }
                if (!decision.armed || !decision.fired) {
                    Log.i(TAG, "Automation $automationId not running: ${decision.reason}")
                    return Result.success()
                }
            }
            val skill = kotlinx.coroutines.runBlocking { app.database.skillDao().getSkill(skillName) }
            if (skill == null) {
                Log.w(TAG, "Automation skill '$skillName' no longer exists")
                Result.failure()
            } else {
                if (!kotlinx.coroutines.runBlocking { app.orchestrator.skillExists(skillName) }) {
                    Log.w(TAG, "Skill '$skillName' vanished before run")
                    Result.success()
                } else {
                    app.orchestrator.runSkill(skill)
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Automation run failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutomationWorker"
        const val KEY_SKILL = "skill_name"
        const val KEY_AUTOMATION_ID = "automation_id"
    }
}

/**
 * Schedules (or cancels) automation jobs via WorkManager (plan 7.8). All scheduling
 * is gated by the master switch; disabling automations cancels everything.
 */
object AutomationScheduler {

    private const val TAG = "AutomationScheduler"

    /** Enqueues a periodic job for one persisted automation, or nothing when disabled. */
    fun schedule(context: Context, automation: com.example.data.local.AutomationEntity) {
        val settings = CapabilitySettings(context)
        if (!AutomationGate.isSchedulable(settings.automationsEnabled, automation.enabled)) {
            cancel(context, automation.id)
            return
        }
        // An INTERVAL automation fires on its own cadence. An event automation (plan 9.2) is
        // polled at the floor cadence; the worker re-checks battery/screen/Wi-Fi/foreground
        // at run time and only replays the skill when the condition genuinely holds, so the
        // poll itself does nothing when the trigger is not met.
        val minutes = if (automation.triggerType == AutomationTrigger.INTERVAL) {
            AutomationGate.intervalMinutes(automation.triggerValue.toIntOrNull() ?: 60)
        } else {
            AutomationGate.MIN_INTERVAL_MINUTES.toLong()
        }
        val request = PeriodicWorkRequestBuilder<AutomationWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(
                androidx.work.workDataOf(
                    AutomationWorker.KEY_SKILL to automation.skillName,
                    AutomationWorker.KEY_AUTOMATION_ID to automation.id
                )
            )
            .addTag(AUTOMATION_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueName(automation.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Scheduled automation ${automation.id} for skill '${automation.skillName}' every ${minutes}m")
    }

    /** One-off run used by the in-app self-check and by "run now". */
    fun runOnce(context: Context, skillName: String) {
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(androidx.work.workDataOf(AutomationWorker.KEY_SKILL to skillName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .addTag(AUTOMATION_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel(context: Context, automationId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(automationId))
    }

    /** Cancels every automation job; called when the master switch is turned off. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(AUTOMATION_WORK_TAG)
    }

    private fun uniqueName(id: Long) = "omniagent_automation_$id"
}
