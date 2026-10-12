package com.example.agent

sealed class AgentAction {
    data class OpenApp(val appQuery: String, val packageName: String?) : AgentAction()
    data class Tap(val targetText: String?, val x: Int?, val y: Int?) : AgentAction()
    data class TypeText(val text: String, val targetNodeId: String?) : AgentAction()
    /**
     * Submits/commits the current text entry: presses the keyboard Enter/Send action
     * or activates a visible Send button. Needed for chat apps where text entry does
     * not auto-submit. [targetText] optionally names the Send control to activate.
     */
    data class Submit(val targetText: String? = null) : AgentAction()
    /**
     * Opens a URL in the default browser via an ACTION_VIEW https intent.
     */
    data class OpenUrl(val url: String) : AgentAction()
    /**
     * Copies text to the clipboard: the focused field's content when [targetText]
     * is null, otherwise the named element's text.
     */
    data class CopyText(val targetText: String? = null) : AgentAction()
    /**
     * Pastes the clipboard into the focused (or named) editable field.
     */
    data class PasteText(val targetText: String? = null) : AgentAction()
    /**
     * Drags from the centre of one visible element to another. Used for reorder,
     * swipe-to-dismiss, and drag-handle interactions a Tap cannot express.
     */
    data class Drag(val fromText: String, val toText: String) : AgentAction()
    /**
     * Opens an app-specific URI (deep link) via an ACTION_VIEW intent, e.g.
     * "spotify:artist:..." or "https://maps.google.com/...".
     */
    data class DeepLink(val uri: String) : AgentAction()
    /**
     * Extracts the visible text of the current page/scrollable container into the
     * observation so the planner can reason about the content it is on.
     */
    object ReadPageContent : AgentAction()
    /**
     * Performs a web search through the configured gateway. Used only for explicit
     * web tasks; the result digest is fed back into the loop.
     */
    data class WebSearch(val query: String) : AgentAction()
    /**
     * Fetches a web page's text through the configured gateway. Used only for
     * explicit "read this page" tasks.
     */
    data class ReadUrl(val url: String) : AgentAction()
    /**
     * Opens the active app's search interface using a fresh accessibility
     * observation. Used when a search/entry field is not already on screen, so a
     * subsequent [TypeText] has a real editable node to target. [query] is an
     * optional hint used only by the bounded native-search-intent fallback.
     */
    data class OpenSearch(val query: String? = null) : AgentAction()
    data class Scroll(val direction: String) : AgentAction() // "up", "down", "left", "right"
    /**
     * Press and hold an observed control (long press). Some UI affordances
     * (context menus, drag handles, "hold to record") only respond to a sustained
     * press, which a normal [Tap] cannot trigger.
     */
    data class LongPress(val targetText: String?, val durationMs: Long = 700L) : AgentAction()
    /**
     * Scrolls the current surface until [targetText] becomes visible, bounded by
     * [maxSwipes] so a missing label cannot scroll forever. [direction] defaults to
     * "down"; use "up" to search back toward the top of a list.
     */
    data class ScrollUntilFound(
        val targetText: String,
        val direction: String = "down",
        val maxSwipes: Int = 6
    ) : AgentAction()
    /**
     * Locates [targetText] anywhere on the current surface by scrolling one direction
     * until the label is visible, then taps it once. This is the general
     * "find and press a thing" primitive: a [Tap] cannot reach an item that is
     * scrolled off-screen, and [ScrollUntilFound] only reveals it without acting.
     * Bounded by [maxSwipes] so a missing label cannot scroll forever.
     */
    data class FindElement(
        val targetText: String,
        val direction: String = "down",
        val maxSwipes: Int = 6
    ) : AgentAction()
    /**
     * Clears the focused (or named) editable field to an empty value. A field that
     * already contains a default/previous value cannot be overwritten by appending,
     * so this is the general "start typing fresh" primitive (e.g. correcting a
     * mistyped query before re-entering it).
     */
    data class ClearText(val targetText: String? = null) : AgentAction()
    /**
     * Reads the visible text of the current screen back to the agent loop. Unlike
     * [ReadScreen] (which only refreshes the tree), this exposes a bounded text
     * digest that can be summarised or acted on.
     */
    object ReadText : AgentAction()
    /**
     * Waits until [targetText] appears on screen, bounded by [timeoutMs]. Used for
     * slow-loading surfaces where a fixed [Wait] is either too short or wasteful.
     */
    data class WaitForText(val targetText: String, val timeoutMs: Long = 5_000L) : AgentAction()
    /**
     * Changes a device audio stream by [steps] from its current level, where one step
     * is the platform's own volume increment for that stream (e.g. one media-volume
     * press). A negative [steps] lowers it; a positive one raises it. This is the
     * media-playback control primitive: "turn the volume up/down" or "mute the music"
     * is not reachable through any UI tap on an arbitrary screen.
     *
     * [stream] is one of "media" (default), "ring", "alarm", "notification" or "call".
     * [mute] is an explicit "mute this stream" intent; a request to unmute is expressed
     * as a positive [steps] raise. It is kept separate from `steps == 0` so the tool
     * contract stays unambiguous for the model.
     */
    data class AdjustVolume(val stream: String = "media", val steps: Int = 1, val mute: Boolean = false) : AgentAction()
    /**
     * Switches the device ringer mode between "silent", "vibrate" and "normal" — the
     * "put my phone on silent" / "unmute my phone" primitive. The ringer mode is a
     * system audio setting that no accessibility-tree read or UI tap can change
     * reliably on an arbitrary screen, so like [AdjustVolume] it is resolved before the
     * accessibility-service gate. It is distinct from muting an audio *stream*: it
     * changes the whole device's ring/vibrate behaviour, not one stream's level.
     * [mode] is one of the [RingerMode] keys.
     */
    data class SetRingerMode(val mode: String = "silent") : AgentAction()
    /**
     * Controls the active media session's transport: play/pause/stop or skip to the
     * next/previous track. This is the playback-control primitive no UI tap can reach
     * on an arbitrary screen ("pause the music", "play the next song"). [transport] is
     * one of the [MediaTransport] keys. A dispatch is a delivered media key, not a
     * claim that a specific track is now playing; precise playback state is verified
     * separately by the PLAYBACK postcondition.
     */
    data class PlayMedia(val transport: String = "toggle") : AgentAction()
    /**
     * Schedules a clock alarm at [hour]:[minute] (24-hour clock), the "wake me at 7am"
     * primitive. No accessibility-tree read or UI tap can set an alarm reliably on an
     * arbitrary screen, so like [SetRingerMode] this is dispatched through a system
     * service (the platform [AlarmClock] intent) and resolved before the
     * accessibility-service gate. [label] is an optional alarm name and [days] an
     * optional list of `Calendar` weekday constants for a repeating alarm.
     */
    data class SetAlarm(
        val hour: Int = 7,
        val minute: Int = 0,
        val label: String? = null,
        val days: List<Int> = emptyList()
    ) : AgentAction()
    /**
     * Starts a countdown timer for [seconds], the "set a 10-minute timer" primitive.
     * This is distinct from [SetAlarm]: a timer counts down from now, an alarm fires at
     * a clock time. Like [SetAlarm] no accessibility-tree read or UI tap can start one
     * reliably on an arbitrary screen, so it is dispatched through the platform
     * [AlarmClock] intent and resolved before the accessibility-service gate. [label] is
     * an optional timer name.
     */
    data class SetTimer(
        val seconds: Int = 300,
        val label: String? = null
    ) : AgentAction()
    /**
     * Adds a calendar event titled [title] on [dayOffset] (0 = today, 1 = tomorrow),
     * starting at [startHour]:[startMinute] (24-hour clock) for [durationMinutes],
     * optionally at [location]. This is the "add lunch with Sam tomorrow at 1pm"
     * primitive. Like [SetAlarm]/[SetTimer] no accessibility-tree read or UI tap can
     * add an event reliably on an arbitrary screen, so it is dispatched through the
     * platform [android.provider.CalendarContract] insert intent and resolved before
     * the accessibility-service gate. The planner resolves a relative day ("tomorrow")
     * from the prompt's current date into the numeric [dayOffset].
     */
    data class AddCalendarEvent(
        val title: String,
        val dayOffset: Int = 0,
        val startHour: Int = 9,
        val startMinute: Int = 0,
        val durationMinutes: Int = 60,
        val location: String? = null
    ) : AgentAction()
    /**
     * Adds a contact named [name], optionally with [phone] and [email]. This is the
     * "save Priya's number 98xxxxxx" / "add an email contact for Sam" primitive. Like
     * [AddCalendarEvent] no accessibility-tree read or UI tap can add a contact
     * reliably on an arbitrary screen, so it is dispatched through the platform
     * [android.provider.ContactsContract] insert intent (the contacts app's own editor,
     * pre-filled) and resolved before the accessibility-service gate. At least one of
     * [phone]/[email] is required so an empty contact is never created.
     */
    data class AddContact(
        val name: String,
        val phone: String? = null,
        val email: String? = null
    ) : AgentAction()
    object ReadNotifications : AgentAction()
    /**
     * Reads the device's own status (battery level/charging, screen on/off, ringer
     * mode, connectivity) back to the agent loop. None of these live in the
     * accessibility tree, so no screen read or UI tap can answer "how much battery
     * do I have" / "is wifi on" / "am I on silent". Like [ReadNotifications] this is
     * a service-backed read resolved before the accessibility-service gate.
     */
    object ReadDeviceStatus : AgentAction()
    object OpenNotifications : AgentAction()
    object OpenQuickSettings : AgentAction()
    object PressRecents : AgentAction()
    /**
     * Opens an observed result item (e.g. a search result) by its visible label.
     * The executor reacquires the node from a fresh observation rather than reusing
     * a stale accessibility node.
     */
    data class OpenItem(val targetText: String) : AgentAction()
    object Back : AgentAction()
    object Home : AgentAction()
    data class Wait(val durationMs: Long) : AgentAction()
    object ReadScreen : AgentAction()
    object Screenshot : AgentAction()
    data class AskConfirmation(val riskType: String, val description: String, val pendingAction: AgentAction) : AgentAction()
    data class FinishTask(val summary: String) : AgentAction()
    data class FailTask(val reason: String) : AgentAction()

    fun getDisplayName(): String {
        return when (this) {
            is OpenApp -> "Open App: $appQuery"
            is Tap -> if (targetText != null) "Tap: '$targetText'" else "Tap at ($x, $y)"
            is TypeText -> "Type: \"$text\""
            is Submit -> if (targetText != null) "Submit via '$targetText'" else "Submit / press Enter"
            is OpenUrl -> "Open URL: $url"
            is CopyText -> if (targetText != null) "Copy text from '$targetText'" else "Copy focused text"
            is PasteText -> "Paste clipboard"
            is Drag -> "Drag '$fromText' -> '$toText'"
            is DeepLink -> "Open deep link: $uri"
            is ReadPageContent -> "Read page content"
            is WebSearch -> "Web search: '$query'"
            is ReadUrl -> "Read URL: $url"
            is OpenSearch -> "Open Search"
            is Scroll -> "Scroll $direction"
            is LongPress -> "Long press: '${targetText ?: "($durationMs ms)"}'"
            is ScrollUntilFound -> "Scroll $direction until '$targetText' (max $maxSwipes)"
            is FindElement -> "Find and tap '$targetText' ($direction, max $maxSwipes swipes)"
            is ClearText -> if (targetText != null) "Clear text in '$targetText'" else "Clear focused text"
            is ReadText -> "Read screen text"
            is WaitForText -> "Wait for text '$targetText' (up to ${timeoutMs}ms)"
            is AdjustVolume -> when {
                mute || steps == 0 -> "Mute $stream volume"
                else -> "Turn $stream volume ${if (steps > 0) "up" else "down"} $steps step(s)"
            }
            is SetRingerMode -> "Set ringer mode to $mode"
            is PlayMedia -> "Media ${transport}: control playback"
            is SetAlarm -> "Set alarm for %02d:%02d".format(hour, minute) +
                (label?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            is SetTimer -> "Start a timer for ${formatTimerSeconds(seconds)}" +
                (label?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            is AddCalendarEvent -> "Add calendar event '$title' at %02d:%02d".format(startHour, startMinute) +
                when (dayOffset) {
                    0 -> " today"
                    1 -> " tomorrow"
                    else -> " in $dayOffset days"
                }
            is AddContact -> buildString {
                append("Add contact '$name'")
                if (!phone.isNullOrBlank()) append(" tel $phone")
                if (!email.isNullOrBlank()) append(" email $email")
            }
            is ReadNotifications -> "Read notifications"
            is ReadDeviceStatus -> "Read device status"
            is OpenNotifications -> "Open notification shade"
            is OpenQuickSettings -> "Open quick settings"
            is PressRecents -> "Open recents"
            is OpenItem -> "Open Item: '$targetText'"
            is Back -> "Press Back"
            is Home -> "Press Home"
            is Wait -> "Wait ${durationMs}ms"
            is ReadScreen -> "Read Screen Tree"
            is Screenshot -> "Capture Screenshot Fallback"
            is AskConfirmation -> "Ask Confirmation: $riskType"
            is FinishTask -> "Finish: $summary"
            is FailTask -> "Fail: $reason"
        }
    }

    /** Renders a countdown length as `M:SS` / `H:MM:SS` for a human-readable display name. */
    private fun formatTimerSeconds(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
    }
}

data class RiskAssessment(
    val isRisky: Boolean,
    val riskType: String = "",
    val warningMessage: String = ""
)

class RiskEvaluator {

    private val paymentKeywords = listOf("pay", "buy", "checkout", "order", "purchase", "transfer", "send money", "confirm payment", "credit card")
    private val deleteKeywords = listOf("delete", "remove account", "wipe", "format", "clear data", "uninstall", "erase")
    private val externalSendKeywords = listOf("send message", "send email", "post", "tweet", "publish", "share to")

    /** Generic confirm-style labels whose risk depends on the surrounding screen. */
    private val genericConfirmKeywords = listOf("confirm", "continue", "submit", "proceed", "ok")

    fun evaluate(action: AgentAction, currentScreenContext: String): RiskAssessment {
        val screenLower = currentScreenContext.lowercase()

        when (action) {
            is AgentAction.Tap, is AgentAction.LongPress -> {
                val target = when (action) {
                    is AgentAction.Tap -> (action.targetText ?: "").lowercase()
                    is AgentAction.LongPress -> (action.targetText ?: "").lowercase()
                    else -> ""
                }
                // Risk is judged from the element being tapped. Matching payment words
                // against the whole screen made unrelated taps risky whenever the screen
                // merely contained a word like "order" or "post" (e.g. a video feed or a
                // sorted list), which blocked ordinary navigation.
                if (paymentKeywords.any { target.contains(it) }) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = "Financial / Payment",
                        warningMessage = "Tapping '$target' appears to initiate a financial payment or checkout."
                    )
                }
                // A generic "Confirm"/"Continue" is only financial when the screen is a
                // checkout/payment surface.
                if (genericConfirmKeywords.any { target.contains(it) } &&
                    paymentKeywords.any { screenLower.contains(it) }
                ) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = "Financial / Payment",
                        warningMessage = "Tapping '$target' on a payment screen may complete a financial transaction."
                    )
                }
                if (deleteKeywords.any { target.contains(it) }) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = "Destructive Deletion",
                        warningMessage = "Tapping '$target' may permanently delete files, accounts, or records."
                    )
                }
                if (externalSendKeywords.any { target.contains(it) }) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = "External Communication",
                        warningMessage = "Tapping '$target' may send a message or publish content publicly."
                    )
                }
            }
            is AgentAction.TypeText -> {
                val textLower = action.text.lowercase()
                if (textLower.contains("password") || textLower.contains("cvv") || textLower.contains("pin")) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = "Sensitive Credentials",
                        warningMessage = "Attempting to type sensitive security credentials."
                    )
                }
            }
            is AgentAction.FinishTask -> {
                val summaryLower = action.summary.lowercase()
                // A finish summary is the model's claim that a sensitive operation
                // succeeded. The same words that make a tap risky must not be allowed to
                // complete silently just because they arrived via report_status.
                val riskType = when {
                    paymentKeywords.any { summaryLower.contains(it) } -> "Financial / Payment"
                    deleteKeywords.any { summaryLower.contains(it) } -> "Destructive Deletion"
                    externalSendKeywords.any { summaryLower.contains(it) } -> "External Communication"
                    else -> null
                }
                if (riskType != null) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = riskType,
                        warningMessage = "The agent reported completing a sensitive ($riskType) operation; confirm before it is accepted as done."
                    )
                }
            }
            else -> {}
        }

        return RiskAssessment(isRisky = false)
    }
}
