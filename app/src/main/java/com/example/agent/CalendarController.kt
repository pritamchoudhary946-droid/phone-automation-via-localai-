package com.example.agent

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log

/** Result of a single calendar-event insert request. */
data class CalendarResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The calendar boundary for [AgentAction.AddCalendarEvent]. This is the "add lunch
 * with Sam tomorrow at 1pm" / "put the standup on my calendar at 9:30" primitive:
 * no accessibility-tree read or UI tap can add an event reliably on an arbitrary
 * screen, and driving a specific calendar app's UI would be app-specific. Instead
 * this dispatches the platform's documented [android.content.Intent.ACTION_INSERT]
 * intent (the calendar's own editor, pre-filled), which any device's calendar app
 * handles.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired controller returns an honest "unavailable" result rather than a silent
 * no-op.
 */
interface CalendarController {
    /**
     * Adds an event titled [title] on [dayOffset] (0 = today, 1 = tomorrow) starting
     * at [startHour]:[startMinute] (24-hour clock) for [durationMinutes]. [location]
     * is optional. Implementations must report honestly: if no calendar app can
     * handle the request, the result is a failure, never a claimed success.
     */
    fun addEvent(
        title: String,
        dayOffset: Int,
        startHour: Int,
        startMinute: Int,
        durationMinutes: Int,
        location: String?
    ): CalendarResult
}

/**
 * Real implementation that dispatches [android.content.Intent.ACTION_INSERT] with the
 * event's fields pre-filled. Because the event is created by the calendar app (a
 * separate process) this cannot read the event back to prove it now exists; it
 * verifies only that the intent was delivered to a calendar app, and reports the
 * accepted title/time so the result is honest about what was and was not confirmed.
 */
class AndroidCalendarController(context: Context) : CalendarController {

    private val appContext = context.applicationContext

    override fun addEvent(
        title: String,
        dayOffset: Int,
        startHour: Int,
        startMinute: Int,
        durationMinutes: Int,
        location: String?
    ): CalendarResult {
        if (title.isBlank()) {
            return CalendarResult(false, "INVALID_EVENT_TITLE", "Calendar event title must not be blank")
        }
        if (dayOffset !in 0..MAX_DAY_OFFSET) {
            return CalendarResult(false, "INVALID_EVENT_DAY", "Event day offset must be between 0 and $MAX_DAY_OFFSET days: $dayOffset")
        }
        if (startHour !in 0..23 || startMinute !in 0..59) {
            return CalendarResult(false, "INVALID_EVENT_TIME", "Invalid event start time $startHour:$startMinute")
        }
        if (durationMinutes <= 0 || durationMinutes > MAX_DURATION_MINUTES) {
            return CalendarResult(false, "INVALID_EVENT_DURATION", "Event duration must be between 1 and $MAX_DURATION_MINUTES minutes: $durationMinutes")
        }
        val begin = beginMillis(System.currentTimeMillis(), java.util.TimeZone.getDefault(), dayOffset, startHour, startMinute)
        val end = begin + durationMinutes * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            if (!location.isNullOrBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            CalendarResult(
                true,
                "CALENDAR_DISPATCHED",
                "Opening calendar to add '$title' at ${formatTime(startHour, startMinute)}${daySuffix(dayOffset)}${locationSuffix(location)}"
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No calendar app can insert an event: ${e.message}")
            CalendarResult(false, "CALENDAR_UNAVAILABLE", "No calendar app is available to add an event")
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar insert denied: ${e.message}")
            CalendarResult(false, "CALENDAR_PERMISSION", "Adding a calendar event is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Calendar insert failed: ${e.message}")
            CalendarResult(false, "CALENDAR_ERROR", "Calendar request failed: ${e.localizedMessage}")
        }
    }

    private fun daySuffix(dayOffset: Int): String = when (dayOffset) {
        0 -> " today"
        1 -> " tomorrow"
        else -> " in $dayOffset days"
    }

    private fun locationSuffix(location: String?): String =
        if (location.isNullOrBlank()) "" else " at $location"

    private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    companion object {
        private const val TAG = "AndroidCalendarController"

        /** Longest forward day offset the tool accepts (about a month ahead). */
        const val MAX_DAY_OFFSET = 30

        /** Longest event duration the tool accepts: 24 hours, in minutes. */
        const val MAX_DURATION_MINUTES = 24 * 60

        /**
         * Resolves [dayOffset] days after [nowMillis] at [hour]:[minute] in [zone] to an
         * epoch-millis instant. Pure so the resolved instant is unit-testable without a
         * device; the calendar editor receives an absolute begin time, not a relative one.
         */
        fun beginMillis(nowMillis: Long, zone: java.util.TimeZone, dayOffset: Int, hour: Int, minute: Int): Long {
            val cal = java.util.Calendar.getInstance(zone)
            cal.timeInMillis = nowMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
