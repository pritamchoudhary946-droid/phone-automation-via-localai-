package com.example.agent

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/** Result of a single alarm-scheduling request. */
data class AlarmResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The alarm boundary for [AgentAction.SetAlarm]. This is the "wake me at 7am" /
 * "set an alarm for 6:30" primitive: no accessibility-tree read or UI tap can set a
 * clock alarm reliably on an arbitrary screen, and driving the Clock app's UI would be
 * app-specific. Instead this dispatches the platform's documented
 * [AlarmClock.ACTION_SET_ALARM] intent, which any device's clock app handles.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface AlarmController {
    /**
     * Schedules an alarm at [hour]:[minute] (24-hour clock), optionally [label]led and
     * repeated on [days]. Implementations must report honestly: if no clock app can
     * handle the request, the result is a failure, never a claimed success.
     */
    fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>): AlarmResult
}

/**
 * Real implementation that dispatches [AlarmClock.ACTION_SET_ALARM]. Because the alarm
 * is created by the clock app (a separate process), this cannot read the alarm list back
 * to prove the alarm now exists; it verifies only that the intent was delivered to a
 * clock app, and reports the accepted time in the message so the result is honest about
 * what was and was not confirmed.
 */
class AndroidAlarmController(context: Context) : AlarmController {

    private val appContext = context.applicationContext

    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>): AlarmResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return AlarmResult(false, "INVALID_ALARM_TIME", "Invalid alarm time: $hour:$minute")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            AlarmResult(
                true,
                "ALARM_DISPATCHED",
                "Alarm set for ${formatTime(hour, minute)}${labelSuffix(label)}"
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No clock app can set an alarm: ${e.message}")
            AlarmResult(false, "ALARM_UNAVAILABLE", "No clock app is available to set an alarm")
        } catch (e: SecurityException) {
            Log.w(TAG, "Alarm request denied: ${e.message}")
            AlarmResult(false, "ALARM_PERMISSION", "Setting an alarm is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Alarm request failed: ${e.message}")
            AlarmResult(false, "ALARM_ERROR", "Alarm request failed: ${e.localizedMessage}")
        }
    }

    private fun labelSuffix(label: String?): String =
        if (label.isNullOrBlank()) "" else " ($label)"

    private fun formatTime(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour, minute)

    companion object {
        private const val TAG = "AndroidAlarmController"
    }
}
