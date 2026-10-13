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
    /**
     * Opens the system dialer pre-filled with [number] ("dial this number" / "call
     * Priya"). Like [AddContact] no accessibility-tree read or UI tap opens a dialer
     * with a number reliably on an arbitrary screen, so it is dispatched through the
     * platform [android.content.Intent.ACTION_DIAL] intent and resolved before the
     * accessibility-service gate. It opens the dialer for the user to confirm; it never
     * places the call, so a consequential call stays under the user's control.
     */
    data class DialNumber(val number: String) : AgentAction()
    /**
     * Opens the messaging compose screen pre-filled with [number] and [message] ("text
     * Priya that I'll be late"). Like [DialNumber] no accessibility-tree read or UI tap
     * opens a compose screen with a number and body reliably on an arbitrary screen, so
     * it is dispatched through the platform [android.content.Intent.ACTION_SENDTO]
     * intent (`smsto:` URI + `"sms_body"`) and resolved before the accessibility-service
     * gate. It opens the compose screen for the user to review and press send; it never
     * sends the message, so a consequential send stays under the user's control.
     */
    data class SendSms(val number: String, val message: String) : AgentAction()
    /**
     * Shares [text] through the platform share sheet, optionally addressed to
     * [targetPackage] ("share this with WhatsApp"). Like [SendSms] no accessibility-tree
     * read or UI tap can hand text to an arbitrary target app reliably on an arbitrary
     * screen, so it is dispatched through the platform
     * [android.content.Intent.ACTION_SEND] intent (`text/plain` + an optional
     * `EXTRA_TITLE`) and resolved before the accessibility-service gate. The user still
     * picks the destination and confirms, so a consequential share stays under the
     * user's control.
     */
    data class ShareText(val text: String, val targetPackage: String? = null) : AgentAction()
    /**
     * Opens the mail compose screen pre-filled with [to], and optionally [subject] and
     * [body] ("email Sam at sam@example.com that the report is ready"). Like [SendSms]
     * no accessibility-tree read or UI tap opens a compose screen with a recipient and
     * body reliably on an arbitrary screen, so it is dispatched through the platform
     * [android.content.Intent.ACTION_SENDTO] intent (`mailto:` URI) and resolved before
     * the accessibility-service gate. It opens the compose screen for the user to review
     * and press send; it never sends the message, so a consequential send stays under
     * the user's control.
     */
    data class SendEmail(
        val to: String,
        val subject: String? = null,
        val body: String? = null
    ) : AgentAction()
    /**
     * Opens the maps app showing [target] - a place name or street address - the
     * "navigate to a place" / "take me to <address>" primitive. Like [SendEmail] no
     * accessibility-tree read or UI tap can open a map at a chosen place reliably on an
     * arbitrary screen, so it is dispatched through the platform
     * [android.content.Intent.ACTION_VIEW] intent with a `geo:0,0?q=` URI and resolved
     * before the accessibility-service gate. The map opens showing the place and the
     * user starts turn-by-turn directions from there; it never begins navigating by
     * itself, so the consequential route stays under the user's control.
     */
    data class NavigateTo(val target: String) : AgentAction()
    /**
     * Opens the system Settings App-info page for a named app package (or app alias),
     * e.g. "open Chrome's settings" / "show WhatsApp app info". The platform's
     * documented `ACTION_APPLICATION_DETAILS_SETTINGS` intent is dispatched addressed
     * to the resolved package and resolved before the accessibility-service gate, so a
     * tap path over an arbitrary screen is never needed. It only *opens* the page; the
     * user decides what to change there (force-stop, clear data, permissions), so no
     * consequential action is performed by the primitive itself.
     */
    data class OpenAppInfo(val packageName: String) : AgentAction()
    /**
     * Turns the device torch/flashlight on or off - the "turn on the flashlight" /
     * "switch the torch off" primitive. The torch is a camera-hardware setting, not an
     * accessibility-tree control, so like [AdjustVolume]/[SetRingerMode] it is resolved
     * before the accessibility-service gate and driven through
     * [AndroidTorchController] (`CameraManager.setTorchMode`). The state is read back, so
     * a toggle the hardware ignored is reported as an honest failure, never a fake
     * success.
     */
    data class SetTorch(val on: Boolean) : AgentAction()
    /**
     * Sets a device control to [value] - the parameterised form of [SetTorch] used by
     * scheduled automations, where the same automation row turns the flashlight on in one
     * condition and off in another. The only supported [control] is "torch", whose [value]
     * is "on"/"off"; it is dispatched through the same [AndroidTorchController] as
     * [SetTorch] and read back, so an ignored request is an honest failure, never a fake
     * success. It is resolved before the accessibility-service gate exactly like [SetTorch].
     */
    data class SetDeviceControl(val control: String, val value: String) : AgentAction()
    /**
     * Speaks [text] aloud through the on-device text-to-speech engine - the "say
     * ..." / "read that out" / "tell me what it says" primitive. Speech output is an
     * opt-in capability ([AndroidSpeakController] is gated by the user's voice-output
     * setting), so like [ReadNotifications] it is resolved before the
     * accessibility-service gate and refuses honestly when the capability is off. The
     * controller reports success only when the platform confirms the utterance was
     * accepted or completed, so a muted/broken engine is an honest failure, never a
     * fake "spoken".
     */
    data class Speak(val text: String) : AgentAction()
    /**
     * Opens a named system Settings panel, e.g. "open wifi settings" / "show me
     * bluetooth settings" / "open accessibility settings". Like [OpenAppInfo], the
     * system Settings app is a separate process no accessibility tap can drive reliably
     * on an arbitrary screen, so the platform's documented `Settings.ACTION_*` intent
     * for a known panel is dispatched and resolved before the accessibility-service
     * gate. The panel name is a closed set ([SettingsPanel]); an unknown panel is
     * refused honestly rather than dispatched as an arbitrary intent. It only *opens*
     * the panel; the user decides what to change there, so no consequential action is
     * performed by the primitive itself (section 3.5).
     */
    data class OpenSettings(val panel: String) : AgentAction()
    /**
     * Opens the device's calendar app at the current day, e.g. "open my calendar" /
     * "show the calendar" / "what's on today". Like [AddCalendarEvent] the calendar app
     * is a separate process no accessibility tap can open reliably on an arbitrary
     * screen, so the platform's documented calendar time URI is dispatched (with a
     * documented calendar-app launch fallback) and resolved before the
     * accessibility-service gate. It only *opens* the calendar for the user to look at;
     * it never adds or changes an event (use [AddCalendarEvent] to create one).
     *
     * [dayOffset] is the day to open at (0 = today, 1 = tomorrow, negative = a past
     * day); "show my calendar for next Monday" is resolved by the planner into the
     * numeric offset, and the viewer opens the calendar on that day rather than always
     * on today.
     */
    data class OpenCalendar(val dayOffset: Int = 0) : AgentAction()
    /**
     * Opens the notes app to create a note titled [title] with [content] - the "note
     * down that ..." / "make a note titled Shopping" primitive. Like [AddContact] the
     * notes app is a separate process no accessibility tap can drive reliably on an
     * arbitrary screen, so the platform's documented
     * [android.content.Intent.ACTION_CREATE_NOTE] intent (the notes app's own editor,
     * pre-filled) is dispatched and resolved before the accessibility-service gate. At
     * least one of [title]/[content] is required so an empty note is never created. It
     * only *opens* the editor for the user to save; it never saves the note itself
     * (section 3.5).
     */
    data class Note(val title: String? = null, val content: String? = null) : AgentAction()
    /**
     * Opens the notes app to create a note by *dictation* - the "dictate a note" /
     * "take a note by voice" / "make a voice note" primitive. It is the same
     * [android.content.Intent.ACTION_CREATE_NOTE] dispatch as [Note] (a separate
     * process no accessibility tap can drive reliably), resolved before the
     * accessibility-service gate, but it carries only an optional [title] and no body
     * text, so the notes app opens its editor ready for the user to speak. This is
     * distinct from [Speak] (which utters text aloud) and from typing a note's body
     * into the current screen (which is app-specific): it is the general way to start a
     * voice note on an arbitrary device. It only *opens* the editor for the user to
     * dictate and save; it never claims the note was spoken or saved (section 3.5).
     */
    data class DictateNote(val title: String? = null) : AgentAction()
    /**
     * Opens the system file/document picker so the user can choose a file - the
     * "pick a file" / "choose a photo to attach" / "select a document" primitive. The
     * picker is a separate process no accessibility-tree read or UI tap can open
     * reliably on an arbitrary screen (and driving a specific file-manager UI would be
     * app-specific), so like [Note] the platform's documented
     * [android.content.Intent.ACTION_GET_CONTENT] intent is dispatched and resolved
     * before the accessibility-service gate. [mimeType] filters the picker (e.g. an
     * image type, or `application/pdf`; null/blank means any file) and [allowMultiple] lets
     * the user pick more than one. It only *opens* the picker for the user to choose;
     * it never reads, deletes or uploads anything itself, so the selection stays under
     * the user's control (section 3.5), and the app cannot read back which file was
     * chosen (the picker is a separate process).
     */
    data class PickFile(val mimeType: String? = null, val allowMultiple: Boolean = false) : AgentAction()
    /**
     * Condenses [text] to a short [maxChars]-bounded digest through the configured
     * cloud summarizer (the same engine used for the planning memory/lessons digests).
     * This is the "summarise this page/thread/result" primitive: a multi-app chain
     * like "read the last message from X and summarise it" reads the screen with
     * [ReadText]/[ReadPageContent], then feeds the captured text here and receives a
     * short digest back in the loop - no screen reflects a summarisation, so it is
     * resolved before the accessibility-service gate like the other service-backed
     * actions. The digest is carried as [ActionResult.readText] and is the step's own
     * evidence. When the summarizer is unavailable (engine not ready) it returns an
     * honest failure rather than fabricating a summary.
     */
    data class SummarizeText(val text: String, val maxChars: Int = DEFAULT_SUMMARY_CHARS) : AgentAction() {
        companion object {
            const val DEFAULT_SUMMARY_CHARS = 400
        }
    }
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
     * Opens the system power menu (power off / restart / emergency) through the
     * documented AccessibilityService global action [GLOBAL_ACTION_POWER_DIALOG].
     *
     * Like the other system-UI global actions (notification shade, quick settings,
     * recents) no accessibility-tree read reflects the resulting overlay, so this is
     * verified against the screen fingerprint moving (a model-visible screen change),
     * never by a stale observation. It only *opens* the menu; choosing an item there is
     * the user's explicit action (section 3.5) - the executor never selects power off.
     */
    object OpenPowerDialog : AgentAction()
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
            is DialNumber -> "Open the dialer with $number"
            is SendSms -> "Open messages to text $number"
            is ShareText -> targetPackage?.takeIf { it.isNotBlank() }?.let { "Share text with $it" } ?: "Share text"
            is SendEmail -> "Open mail to email $to"
            is NavigateTo -> "Open the map at '$target'"
            is OpenAppInfo -> "Open app info for '$packageName'"
            is SetTorch -> if (on) "Turn the flashlight on" else "Turn the flashlight off"
            is SetDeviceControl -> "Set $control to $value"
            is Speak -> "Say: \"$text\""
            is OpenSettings -> "Open ${panel} settings"
            is OpenCalendar -> when (dayOffset) {
                0 -> "Open the calendar"
                1 -> "Open the calendar for tomorrow"
                -1 -> "Open the calendar for yesterday"
                in 2..Int.MAX_VALUE -> "Open the calendar in $dayOffset days"
                else -> "Open the calendar ${-dayOffset} days ago"
            }
            is Note -> title?.takeIf { it.isNotBlank() }?.let { "Create note '$it'" } ?: "Create a note"
            is DictateNote -> title?.takeIf { it.isNotBlank() }?.let { "Dictate note '$it'" } ?: "Dictate a note"
            is PickFile -> buildString {
                append("Open the file picker")
                mimeType?.takeIf { it.isNotBlank() }?.let { append(" for $it") }
                if (allowMultiple) append(" (multiple)")
            }
            is SummarizeText -> "Summarise ${text.length} chars of text"
            is ReadNotifications -> "Read notifications"
            is ReadDeviceStatus -> "Read device status"
            is OpenNotifications -> "Open notification shade"
            is OpenQuickSettings -> "Open quick settings"
            is PressRecents -> "Open recents"
            is OpenPowerDialog -> "Open the power menu"
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
