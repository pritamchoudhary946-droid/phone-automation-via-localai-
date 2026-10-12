package com.example.agent

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/** Result of a single countdown-timer request. */
data class TimerResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The countdown-timer boundary for [AgentAction.SetTimer]. This is the "set a 10-minute
 * timer" / "start a 25-minute timer" primitive: a countdown is a clock-app action, and no
 * accessibility-tree read or UI tap can start one reliably on an arbitrary screen (driving
 * the Clock app's UI would be app-specific and fragile). Instead this dispatches the
 * platform's documented [AlarmClock.ACTION_SET_TIMER] intent, which any device's clock app
 * handles.
 *
 * It is injectable so the automation layer is testable without a real device. An unwired
 * controller returns an honest "unavailable" result rather than a silent no-op.
 */
interface TimerController {
    /**
     * Starts a countdown timer for [seconds], optionally [label]led. Implementations must
     * report honestly: if no clock app can handle the request, the result is a failure,
     * never a claimed success.
     */
    fun setTimer(seconds: Int, label: String?): TimerResult
}

/**
 * Real implementation that dispatches [AlarmClock.ACTION_SET_TIMER]. Because the timer is
 * created by the clock app (a separate process), this cannot read the running timer back
 * to prove it now exists; it verifies only that the intent was delivered to a clock app,
 * and reports the accepted duration in the message so the result is honest about what was
 * and was not confirmed.
 */
class AndroidTimerController(context: Context) : TimerController {

    private val appContext = context.applicationContext

    override fun setTimer(seconds: Int, label: String?): TimerResult {
        if (seconds <= 0) {
            return TimerResult(false, "INVALID_TIMER_DURATION", "Timer duration must be a positive number of seconds: $seconds")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            TimerResult(
                true,
                "TIMER_DISPATCHED",
                "Timer set for ${formatDuration(seconds)}${labelSuffix(label)}"
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No clock app can set a timer: ${e.message}")
            TimerResult(false, "TIMER_UNAVAILABLE", "No clock app is available to set a timer")
        } catch (e: SecurityException) {
            Log.w(TAG, "Timer request denied: ${e.message}")
            TimerResult(false, "TIMER_PERMISSION", "Setting a timer is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Timer request failed: ${e.message}")
            TimerResult(false, "TIMER_ERROR", "Timer request failed: ${e.localizedMessage}")
        }
    }

    private fun labelSuffix(label: String?): String =
        if (label.isNullOrBlank()) "" else " ($label)"

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%d:%02d".format(minutes, secs)
        }
    }

    companion object {
        private const val TAG = "AndroidTimerController"
    }
}
