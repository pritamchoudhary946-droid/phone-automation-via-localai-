package com.example.agent

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log

/** Result of a single "open the calendar" request. */
data class CalendarViewResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * The calendar *viewing* boundary for [AgentAction.OpenCalendar]. This is the
 * "open my calendar" / "show the calendar" / "what's on today" primitive: the calendar
 * app is a separate process no accessibility tap can open reliably on an arbitrary
 * screen, so rather than driving a specific calendar UI (app-specific) this dispatches
 * the platform's documented calendar time URI, which any device's calendar app handles,
 * and falls back to the documented calendar-app launch category when that is not
 * handled.
 *
 * It carries no permission or destructive change: it only opens the calendar for the
 * user to look at, so nothing consequential is performed by the primitive itself
 * (section 3.5). It is distinct from [AgentAction.AddCalendarEvent], which pre-fills
 * the calendar editor to *create* an event.
 *
 * It is injectable so the automation layer is testable without a real device. An
 * unwired viewer returns an honest "unavailable" result rather than a silent no-op.
 */
interface CalendarViewer {
    /**
     * Opens the calendar at the day [dayOffset] days from the day containing [nowMillis]
     * (0 = today, 1 = tomorrow, negative = a past day). Implementations must report
     * honestly: if no calendar app can handle the request the result is a failure, never
     * a claimed success. A successful result means only that the calendar was *opened*.
     */
    fun openCalendar(nowMillis: Long, dayOffset: Int): CalendarViewResult
}

/**
 * Real implementation that dispatches the documented calendar time URI (the AOSP
 * calendar's agenda view) and falls back to `Intent.CATEGORY_APP_CALENDAR`. Because the
 * calendar app is a separate process this cannot read back that the day is showing to
 * prove it; it verifies only that the intent was delivered to a calendar handler, and
 * says so honestly.
 */
class AndroidCalendarViewer(context: Context) : CalendarViewer {

    private val appContext = context.applicationContext

    override fun openCalendar(nowMillis: Long, dayOffset: Int): CalendarViewResult {
        // Prefer the agenda/time URI so the calendar opens on the chosen day.
        val targetMillis = dayStart(nowMillis, java.util.TimeZone.getDefault(), dayOffset)
        val timeUri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(targetMillis.toString())
            .build()
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    data = timeUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return CalendarViewResult(true, "CALENDAR_OPENED", "Opening the calendar")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No calendar handles the time URI, falling back to the app launch: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar time view denied: ${e.message}")
            return CalendarViewResult(false, "CALENDAR_PERMISSION", "Opening the calendar is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Calendar time view failed: ${e.message}")
        }

        // Fallback: launch the calendar app's main screen.
        return try {
            appContext.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CALENDAR)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            CalendarViewResult(true, "CALENDAR_OPENED", "Opening the calendar app")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No calendar app can handle CATEGORY_APP_CALENDAR: ${e.message}")
            CalendarViewResult(false, "CALENDAR_UNAVAILABLE", "No calendar app is available to open")
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar launch denied: ${e.message}")
            CalendarViewResult(false, "CALENDAR_PERMISSION", "Opening the calendar is not permitted on this device")
        } catch (e: Exception) {
            Log.w(TAG, "Calendar launch failed: ${e.message}")
            CalendarViewResult(false, "CALENDAR_ERROR", "Calendar request failed: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "AndroidCalendarViewer"

        /**
         * Resolves the midnight (local) instant of the day [dayOffset] days from the day
         * containing [nowMillis]. Pure so the resolved instant is unit-testable without a
         * device; the calendar time URI receives an absolute instant, not a relative day.
         */
        fun dayStart(nowMillis: Long, zone: java.util.TimeZone, dayOffset: Int): Long {
            val cal = java.util.Calendar.getInstance(zone)
            cal.timeInMillis = nowMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
