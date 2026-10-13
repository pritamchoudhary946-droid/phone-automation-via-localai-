package com.example.agent

/**
 * Clean internal Tool Registry defining all supported agent actions,
 * their parameter schemas, and rigorous pre-execution validation.
 */

data class ToolDefinition(
    val name: String,
    val description: String,
    val requiredParameters: List<String>,
    val optionalParameters: List<String> = emptyList(),
    val isConsequential: Boolean = false
)

sealed class ToolValidationResult {
    object Valid : ToolValidationResult()
    data class Invalid(val reason: String) : ToolValidationResult()
}

open class ToolRegistry {

    companion object {
        const val TOOL_LAUNCH_APP = "launch_app"
        const val TOOL_READ_SCREEN = "read_screen"
        const val TOOL_TAP_ELEMENT = "tap_element"
        const val TOOL_ENTER_TEXT = "enter_text"
        const val TOOL_CLEAR_TEXT = "clear_text"
        const val TOOL_SUBMIT = "submit"
        const val TOOL_OPEN_URL = "open_url"
        const val TOOL_COPY_TEXT = "copy_text"
        const val TOOL_PASTE_TEXT = "paste_text"
        const val TOOL_DRAG = "drag"
        const val TOOL_DEEP_LINK = "deep_link"
        const val TOOL_READ_PAGE_CONTENT = "read_page_content"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_READ_URL = "read_url"
        const val TOOL_OPEN_SEARCH = "open_search"
        const val TOOL_OPEN_ITEM = "open_item"
        const val TOOL_SCROLL = "scroll"
        const val TOOL_LONG_PRESS = "long_press"
        const val TOOL_SCROLL_UNTIL_FOUND = "scroll_until_found"
        const val TOOL_FIND_ELEMENT = "find_element"
        const val TOOL_READ_TEXT = "read_text"
        const val TOOL_WAIT_FOR_TEXT = "wait_for_text"
        const val TOOL_ADJUST_VOLUME = "adjust_volume"
        const val TOOL_SET_RINGER_MODE = "set_ringer_mode"
        const val TOOL_PLAY_MEDIA = "play_media"
        const val TOOL_SET_ALARM = "set_alarm"
        const val TOOL_SET_TIMER = "set_timer"
        const val TOOL_ADD_CALENDAR_EVENT = "add_calendar_event"
        const val TOOL_ADD_CONTACT = "add_contact"
        const val TOOL_DIAL_NUMBER = "dial_number"
        const val TOOL_SEND_SMS = "send_sms"
        const val TOOL_SHARE_TEXT = "share_text"
        const val TOOL_SEND_EMAIL = "send_email"
        const val TOOL_NAVIGATE_TO = "navigate_to"
        const val TOOL_OPEN_APP_INFO = "open_app_info"
        const val TOOL_SET_TORCH = "set_torch"
        const val TOOL_SET_DEVICE_CONTROL = "set_device_control"
        const val TOOL_SPEAK = "speak"
        const val TOOL_OPEN_SETTINGS = "open_settings"
        const val TOOL_OPEN_CALENDAR = "open_calendar"
        const val TOOL_CREATE_NOTE = "create_note"
        const val TOOL_DICTATE_NOTE = "dictate_note"
        const val TOOL_PICK_FILE = "pick_file"
        const val TOOL_SUMMARIZE_TEXT = "summarize_text"
        const val TOOL_READ_NOTIFICATIONS = "read_notifications"
        const val TOOL_READ_DEVICE_STATUS = "read_device_status"
        const val TOOL_OPEN_NOTIFICATIONS = "open_notifications"
        const val TOOL_OPEN_QUICK_SETTINGS = "open_quick_settings"
        const val TOOL_OPEN_RECENTS = "open_recents"
        const val TOOL_OPEN_POWER_DIALOG = "power_dialog"
        const val TOOL_NAVIGATE_BACK = "navigate_back"
        const val TOOL_NAVIGATE_HOME = "navigate_home"
        const val TOOL_WAIT = "wait"
        const val TOOL_SCREENSHOT = "screenshot"
        const val TOOL_ASK_CONFIRMATION = "ask_confirmation"
        const val TOOL_REPORT_STATUS = "report_status"

        /** Longest countdown timer the tool accepts: 24 hours, in seconds. */
        const val MAX_TIMER_SECONDS = 24 * 60 * 60
        /** Longest text a single speak step may utter, to bound TTS work. */
        const val MAX_SPEAK_CHARS = 1000
        /** Upper bound on a note title/body so a runaway model value cannot flood the notes editor. */
        const val MAX_NOTE_TITLE_CHARS = 200
        const val MAX_NOTE_CONTENT_CHARS = 2000
        /** Longest mime type a pick_file step may request, to bound the picker filter value. */
        const val MAX_MIME_TYPE_CHARS = 100
        /** Longest text a single summarize_text step may condense, to bound summarizer work. */
        const val MAX_SUMMARIZE_INPUT_CHARS = 8000
        /** Bounds on the requested summary length so a runaway value cannot distort the digest. */
        const val MIN_SUMMARY_CHARS = 40
        const val MAX_SUMMARY_CHARS = 1200
        /** Longest forward day offset a calendar event may be scheduled for. */
        const val MAX_EVENT_DAY_OFFSET = 30

        /** Longest calendar event duration the tool accepts: 24 hours, in minutes. */
        const val MAX_EVENT_DURATION_MINUTES = 24 * 60
    }

    private val tools = mapOf(
        TOOL_LAUNCH_APP to ToolDefinition(
            name = TOOL_LAUNCH_APP,
            description = "Launches an installed Android application by name or package identifier",
            requiredParameters = listOf("appQuery"),
            optionalParameters = listOf("packageName")
        ),
        TOOL_READ_SCREEN to ToolDefinition(
            name = TOOL_READ_SCREEN,
            description = "Inspects and dumps visible accessibility nodes and actionable screen elements",
            requiredParameters = emptyList()
        ),
        TOOL_TAP_ELEMENT to ToolDefinition(
            name = TOOL_TAP_ELEMENT,
            description = "Performs an accessibility click on an element by text/label or fallback bounds",
            requiredParameters = emptyList(),
            optionalParameters = listOf("targetText", "x", "y")
        ),
        TOOL_ENTER_TEXT to ToolDefinition(
            name = TOOL_ENTER_TEXT,
            description = "Injects text into an editable focused input field via ACTION_SET_TEXT",
            requiredParameters = listOf("text"),
            optionalParameters = listOf("targetNodeId")
        ),
        TOOL_CLEAR_TEXT to ToolDefinition(
            name = TOOL_CLEAR_TEXT,
            description = "Clears the focused (or named) editable field to empty via ACTION_SET_TEXT, so stale/default text can be replaced",
            requiredParameters = emptyList(),
            optionalParameters = listOf("targetText")
        ),
        TOOL_SUBMIT to ToolDefinition(
            name = TOOL_SUBMIT,
            description = "Submits the current text entry by pressing Enter/Send or activating a visible Send control",
            requiredParameters = emptyList(),
            optionalParameters = listOf("targetText")
        ),
        TOOL_OPEN_URL to ToolDefinition(
            name = TOOL_OPEN_URL,
            description = "Opens a web URL in the default browser via an ACTION_VIEW https intent",
            requiredParameters = listOf("url")
        ),
        TOOL_COPY_TEXT to ToolDefinition(
            name = TOOL_COPY_TEXT,
            description = "Copies the focused field's text, or a labelled element's text, to the clipboard",
            requiredParameters = emptyList(),
            optionalParameters = listOf("targetText")
        ),
        TOOL_PASTE_TEXT to ToolDefinition(
            name = TOOL_PASTE_TEXT,
            description = "Pastes the clipboard into the focused editable field",
            requiredParameters = emptyList(),
            optionalParameters = listOf("targetText")
        ),
        TOOL_DRAG to ToolDefinition(
            name = TOOL_DRAG,
            description = "Drags from one visible element to another (e.g. to reorder or swipe a card), between label centres",
            requiredParameters = listOf("fromText", "toText")
        ),
        TOOL_DEEP_LINK to ToolDefinition(
            name = TOOL_DEEP_LINK,
            description = "Opens an app-specific URI (deep link) via an ACTION_VIEW intent",
            requiredParameters = listOf("uri")
        ),
        TOOL_READ_PAGE_CONTENT to ToolDefinition(
            name = TOOL_READ_PAGE_CONTENT,
            description = "Extracts the visible text of the current (scrollable) page into the observation",
            requiredParameters = emptyList()
        ),
        TOOL_WEB_SEARCH to ToolDefinition(
            name = TOOL_WEB_SEARCH,
            description = "Performs a web search through the configured gateway and returns a bounded digest",
            requiredParameters = listOf("query")
        ),
        TOOL_READ_URL to ToolDefinition(
            name = TOOL_READ_URL,
            description = "Fetches a web page's text content through the configured gateway and returns a bounded digest",
            requiredParameters = listOf("url")
        ),
        TOOL_OPEN_SEARCH to ToolDefinition(
            name = TOOL_OPEN_SEARCH,
            description = "Opens the active app's search interface from a fresh accessibility observation (search icon/field), so text can be entered",
            requiredParameters = emptyList(),
            optionalParameters = listOf("query")
        ),
        TOOL_OPEN_ITEM to ToolDefinition(
            name = TOOL_OPEN_ITEM,
            description = "Opens an observed result item (e.g. a search result) by its visible label, reacquired from a fresh observation",
            requiredParameters = listOf("targetText"),
            optionalParameters = emptyList()
        ),
        TOOL_SCROLL to ToolDefinition(
            name = TOOL_SCROLL,
            description = "Dispatches a scroll gesture in one of four cardinal directions",
            requiredParameters = listOf("direction")
        ),
        TOOL_LONG_PRESS to ToolDefinition(
            name = TOOL_LONG_PRESS,
            description = "Presses and holds a visible control to open a context menu or start a hold gesture",
            requiredParameters = listOf("targetText"),
            optionalParameters = listOf("durationMs")
        ),
        TOOL_SCROLL_UNTIL_FOUND to ToolDefinition(
            name = TOOL_SCROLL_UNTIL_FOUND,
            description = "Scrolls the current list until a target label becomes visible, bounded by maxSwipes",
            requiredParameters = listOf("targetText"),
            optionalParameters = listOf("direction", "maxSwipes")
        ),
        TOOL_FIND_ELEMENT to ToolDefinition(
            name = TOOL_FIND_ELEMENT,
            description = "Finds a target label on the current surface (scrolling if needed) and taps it once",
            requiredParameters = listOf("targetText"),
            optionalParameters = listOf("direction", "maxSwipes")
        ),
        TOOL_READ_TEXT to ToolDefinition(
            name = TOOL_READ_TEXT,
            description = "Reads the visible on-screen text back to the agent as a bounded digest",
            requiredParameters = emptyList()
        ),
        TOOL_WAIT_FOR_TEXT to ToolDefinition(
            name = TOOL_WAIT_FOR_TEXT,
            description = "Waits until a target label appears on screen, bounded by timeoutMs",
            requiredParameters = listOf("targetText"),
            optionalParameters = listOf("timeoutMs")
        ),
        TOOL_ADJUST_VOLUME to ToolDefinition(
            name = TOOL_ADJUST_VOLUME,
            description = "Changes a device audio stream (media/ring/alarm/notification/call) by a signed number of steps; negative lowers, positive raises; set mute=true to silence it",
            requiredParameters = emptyList(),
            optionalParameters = listOf("stream", "steps", "mute")
        ),
        TOOL_PLAY_MEDIA to ToolDefinition(
            name = TOOL_PLAY_MEDIA,
            description = "Controls the active media session's transport: play, pause, toggle (play/pause), stop, next or previous track",
            requiredParameters = listOf("transport"),
            optionalParameters = emptyList()
        ),
        TOOL_SET_RINGER_MODE to ToolDefinition(
            name = TOOL_SET_RINGER_MODE,
            description = "Switches the device ringer mode between silent, vibrate and normal. Use for 'put my phone on silent', 'set it to vibrate', 'unmute my phone' - no tap or volume change does this reliably",
            requiredParameters = listOf("mode"),
            optionalParameters = emptyList()
        ),
        TOOL_SET_ALARM to ToolDefinition(
            name = TOOL_SET_ALARM,
            description = "Sets a clock alarm at a given 24-hour time. Use for 'set an alarm for 7am', 'wake me at 6:30'. No screen read or tap can set an alarm reliably",
            requiredParameters = listOf("hour", "minute"),
            optionalParameters = listOf("label", "days")
        ),
        TOOL_SET_TIMER to ToolDefinition(
            name = TOOL_SET_TIMER,
            description = "Starts a countdown timer for a duration in seconds. Use for 'set a timer for 10 minutes', 'start a 25-minute timer'. Distinct from an alarm (which fires at a clock time); no screen read or tap can start a timer reliably",
            requiredParameters = listOf("seconds"),
            optionalParameters = listOf("label")
        ),
        TOOL_ADD_CALENDAR_EVENT to ToolDefinition(
            name = TOOL_ADD_CALENDAR_EVENT,
            description = "Adds an event to the user's calendar. Use for 'add lunch with Sam tomorrow at 1pm', 'put the standup on my calendar at 9:30'. Provide a title and the start time; dayOffset is 0 for today, 1 for tomorrow. No screen read or tap can add an event reliably",
            requiredParameters = listOf("title"),
            optionalParameters = listOf("dayOffset", "startHour", "startMinute", "durationMinutes", "location")
        ),
        TOOL_ADD_CONTACT to ToolDefinition(
            name = TOOL_ADD_CONTACT,
            description = "Adds a contact to the user's address book. Use for 'save Priya's number 9876543210', 'add an email contact for Sam at sam@example.com'. Provide a name and at least one of phone/email. No screen read or tap can add a contact reliably",
            requiredParameters = listOf("name"),
            optionalParameters = listOf("phone", "email")
        ),
        TOOL_DIAL_NUMBER to ToolDefinition(
            name = TOOL_DIAL_NUMBER,
            description = "Opens the system dialer pre-filled with a phone number; the user presses call to place it. Use for 'dial 9876543210', 'call Priya's number'. It never places the call itself",
            requiredParameters = listOf("number")
        ),
        TOOL_SEND_SMS to ToolDefinition(
            name = TOOL_SEND_SMS,
            description = "Opens the messaging app pre-filled with a number and message text; the user presses send to deliver it. Use for 'text Priya 9876543210 that I'll be late'. It never sends the message itself",
            requiredParameters = listOf("number", "message")
        ),
        TOOL_SHARE_TEXT to ToolDefinition(
            name = TOOL_SHARE_TEXT,
            description = "Shares a piece of text through the platform share sheet, optionally addressed to a named app. Use for 'share this with WhatsApp', 'send this link to Instagram'. The user picks the destination and confirms; it never posts anywhere by itself",
            requiredParameters = listOf("text"),
            optionalParameters = listOf("targetPackage", "package")
        ),
        TOOL_SEND_EMAIL to ToolDefinition(
            name = TOOL_SEND_EMAIL,
            description = "Opens the mail app pre-filled with a recipient, and optionally a subject and body; the user presses send to deliver it. Use for 'email Sam at sam@example.com that the report is ready'. It never sends the message itself",
            requiredParameters = listOf("to"),
            optionalParameters = listOf("subject", "body")
        ),
        TOOL_NAVIGATE_TO to ToolDefinition(
            name = TOOL_NAVIGATE_TO,
            description = "Opens the maps app showing a place name or street address; the user starts directions from there. Use for 'navigate to Connaught Place', 'take me to the nearest petrol pump', 'show me the way to <address>'. It never begins navigating by itself",
            requiredParameters = listOf("target"),
            optionalParameters = listOf("place", "destination", "address", "query")
        ),
        TOOL_OPEN_APP_INFO to ToolDefinition(
            name = TOOL_OPEN_APP_INFO,
            description = "Opens the system Settings App-info page for an app. Use for 'open Chrome's settings' / 'show WhatsApp app info' / 'app settings for Instagram'. Provide an installed package name or an app alias (e.g. com.android.chrome or chrome). It only opens the page; it never changes anything there",
            requiredParameters = listOf("packageName"),
            optionalParameters = listOf("package", "app", "appQuery", "target")
        ),
        TOOL_SET_TORCH to ToolDefinition(
            name = TOOL_SET_TORCH,
            description = "Turns the device flashlight/torch on or off. Use for 'turn on the flashlight', 'switch the torch off', 'is the light on'. It reads the state back, so a toggle the hardware ignored is reported as a failure, never a fake success",
            requiredParameters = listOf("on"),
            optionalParameters = listOf("state", "enabled", "enable", "bool")
        ),
        TOOL_SET_DEVICE_CONTROL to ToolDefinition(
            name = TOOL_SET_DEVICE_CONTROL,
            description = "Sets a device control to an explicit value. Use for a scheduled automation's action, e.g. 'turn the flashlight on when charging starts', 'go silent at night', 'turn on do not disturb', 'dim the screen'. The controls are torch (value on/off), ringer_mode (value silent/vibrate/normal), dnd (value off/priority/alarms/none) and brightness (value dim/low/medium/high/max, a percentage like 40 or 40%, or brighter/dimmer to move from the current level). It reads the state back, so a change the device ignored is reported as a failure, never a fake success",
            requiredParameters = listOf("control", "value"),
            optionalParameters = listOf("setting", "target", "name", "state", "on")
        ),
        TOOL_SPEAK to ToolDefinition(
            name = TOOL_SPEAK,
            description = "Speaks text aloud through the device's text-to-speech engine. Use for 'say good morning', 'tell me what it says', 'read that out loud'. Speech output is opt-in; when it is off the step is refused honestly rather than silently ignored",
            requiredParameters = listOf("text"),
            optionalParameters = listOf("message", "say", "content", "value")
        ),
        TOOL_OPEN_SETTINGS to ToolDefinition(
            name = TOOL_OPEN_SETTINGS,
            description = "Opens a named system Settings panel. Use for 'open wifi settings', 'show bluetooth settings', 'open display settings', 'take me to accessibility settings'. Provide a panel name such as wifi, bluetooth, display, sound, battery, location, apps, accessibility, network, data_usage, airplane_mode, nfc, date, language, storage, security, privacy, cast, night_display, keyboard, dnd, home or settings. It only opens the panel; it never changes a setting there",
            requiredParameters = listOf("panel"),
            optionalParameters = listOf("name", "setting", "settingsPanel", "section", "target", "value")
        ),
        TOOL_OPEN_CALENDAR to ToolDefinition(
            name = TOOL_OPEN_CALENDAR,
            description = "Opens the device's calendar app. Use for 'open my calendar', 'show the calendar', 'what's on today'. It only opens the calendar for the user to look at; it never adds or changes an event (use add_calendar_event to create one)",
            requiredParameters = emptyList()
        ),
        TOOL_CREATE_NOTE to ToolDefinition(
            name = TOOL_CREATE_NOTE,
            description = "Opens the notes app to create a note. Use for 'make a note that the wifi password is 1234', 'note down to call Sam tomorrow', 'create a note titled Shopping'. Provide a title and/or the note text. No screen read or tap can create a note reliably",
            requiredParameters = emptyList(),
            optionalParameters = listOf("title", "content", "text", "body", "message", "name")
        ),
        TOOL_PICK_FILE to ToolDefinition(
            name = TOOL_PICK_FILE,
            description = "Opens the system file/document picker so the user can choose a file. Use for 'pick a file', 'choose a photo to attach', 'select a document', 'open the file picker'. Optionally filter by a mimeType such as image/*, video/*, audio/*, application/pdf or text/plain, and set allowMultiple to let the user pick more than one. It only opens the picker for the user to choose; it never reads, deletes or uploads anything",
            requiredParameters = emptyList(),
            optionalParameters = listOf("mimeType", "mime_type", "type", "fileType", "accept", "multiple", "allowMultiple", "many")
        ),
        TOOL_DICTATE_NOTE to ToolDefinition(
            name = TOOL_DICTATE_NOTE,
            description = "Opens the notes app to take a note by voice (dictation). Use for 'dictate a note', 'take a voice note', 'make a note by speaking', 'start a voice memo in notes'. Provide an optional title. It opens the notes app ready for the user to speak; it never claims the note was spoken or saved",
            requiredParameters = emptyList(),
            optionalParameters = listOf("title", "name", "label", "subject")
        ),
        TOOL_SUMMARIZE_TEXT to ToolDefinition(
            name = TOOL_SUMMARIZE_TEXT,
            description = "Condenses a block of text to a short digest. Use for 'summarise this page', 'give me the gist of this thread', 'summarise that message'. Provide the text to condense (often the value captured by an earlier read_text/read_page_content step) and optionally a maxChars length. It returns the summary as the step's result and never fabricates one when the summarizer is unavailable",
            requiredParameters = listOf("text"),
            optionalParameters = listOf("maxChars", "max_chars", "length", "chars", "content", "value")
        ),
        TOOL_READ_NOTIFICATIONS to ToolDefinition(
            name = TOOL_READ_NOTIFICATIONS,
            description = "Reads a bounded list of recently posted notifications (title/text) for the planner",
            requiredParameters = emptyList()
        ),
        TOOL_READ_DEVICE_STATUS to ToolDefinition(
            name = TOOL_READ_DEVICE_STATUS,
            description = "Reads the device's own status: battery level/charging, screen on/off, ringer mode and connectivity. Use for questions like 'how much battery do I have', 'is wifi on', 'am I on silent' - no screen read or tap can answer these",
            requiredParameters = emptyList()
        ),
        TOOL_OPEN_NOTIFICATIONS to ToolDefinition(
            name = TOOL_OPEN_NOTIFICATIONS,
            description = "Opens the notification shade via the system global action",
            requiredParameters = emptyList()
        ),
        TOOL_OPEN_QUICK_SETTINGS to ToolDefinition(
            name = TOOL_OPEN_QUICK_SETTINGS,
            description = "Opens the quick settings panel via the system global action",
            requiredParameters = emptyList()
        ),
        TOOL_OPEN_RECENTS to ToolDefinition(
            name = TOOL_OPEN_RECENTS,
            description = "Opens the recent-apps switcher via the system global action",
            requiredParameters = emptyList()
        ),
        TOOL_OPEN_POWER_DIALOG to ToolDefinition(
            name = TOOL_OPEN_POWER_DIALOG,
            description = "Opens the system power menu (power off / restart) via the system global action; the user chooses an item there",
            requiredParameters = emptyList()
        ),
        TOOL_NAVIGATE_BACK to ToolDefinition(
            name = TOOL_NAVIGATE_BACK,
            description = "Executes global BACK action via AccessibilityService",
            requiredParameters = emptyList()
        ),
        TOOL_NAVIGATE_HOME to ToolDefinition(
            name = TOOL_NAVIGATE_HOME,
            description = "Executes global HOME action via AccessibilityService",
            requiredParameters = emptyList()
        ),
        TOOL_WAIT to ToolDefinition(
            name = TOOL_WAIT,
            description = "Pauses execution for a specified duration in milliseconds for UI transitions",
            requiredParameters = listOf("durationMs")
        ),
        TOOL_SCREENSHOT to ToolDefinition(
            name = TOOL_SCREENSHOT,
            description = "Captures screen content bitmap or invokes system screenshot",
            requiredParameters = emptyList()
        ),
        TOOL_ASK_CONFIRMATION to ToolDefinition(
            name = TOOL_ASK_CONFIRMATION,
            description = "Prompts the user for explicit approval before consequential or sensitive actions",
            requiredParameters = listOf("riskType", "description"),
            isConsequential = true
        ),
        TOOL_REPORT_STATUS to ToolDefinition(
            name = TOOL_REPORT_STATUS,
            description = "Reports final completion or failure status of the autonomous agent task",
            requiredParameters = listOf("summary")
        )
    )

    fun getRegisteredTools(): List<ToolDefinition> = tools.values.toList()

    fun getTool(name: String): ToolDefinition? = tools[name]

    /**
     * Validates an AgentAction and its parameters prior to execution.
     * Prevents invalid or malformed actions from reaching the Accessibility layer.
     */
    open fun validateAction(action: AgentAction): ToolValidationResult {
        return when (action) {
            is AgentAction.OpenApp -> {
                if (action.appQuery.isBlank() && action.packageName.isNullOrBlank()) {
                    ToolValidationResult.Invalid("App query or package name must not be blank")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.Tap -> {
                if (action.targetText.isNullOrBlank() && action.x == null && action.y == null) {
                    ToolValidationResult.Invalid("Tap action must provide either targetText or valid (x, y) coordinates")
                } else if ((action.x != null && action.x < 0) || (action.y != null && action.y < 0)) {
                    ToolValidationResult.Invalid("Tap coordinates cannot be negative: (${action.x}, ${action.y})")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.TypeText -> {
                if (action.text.isEmpty()) {
                    ToolValidationResult.Invalid("Text to input cannot be empty")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.ClearText -> ToolValidationResult.Valid
            is AgentAction.Submit -> ToolValidationResult.Valid
            is AgentAction.OpenUrl -> {
                if (action.url.isBlank()) {
                    ToolValidationResult.Invalid("open_url requires a non-blank url")
                } else if (!action.url.trim().lowercase().startsWith("http")) {
                    ToolValidationResult.Invalid("open_url only supports http(s) URLs: '${action.url}'")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.DeepLink -> {
                if (action.uri.isBlank()) {
                    ToolValidationResult.Invalid("deep_link requires a non-blank uri")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.Drag -> {
                when {
                    action.fromText.isBlank() || action.toText.isBlank() ->
                        ToolValidationResult.Invalid("drag requires non-blank fromText and toText")
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.CopyText, is AgentAction.PasteText -> ToolValidationResult.Valid
            is AgentAction.ReadPageContent -> ToolValidationResult.Valid
            is AgentAction.WebSearch -> {
                if (action.query.isBlank()) {
                    ToolValidationResult.Invalid("web_search requires a non-blank query")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.ReadUrl -> {
                if (action.url.isBlank()) {
                    ToolValidationResult.Invalid("read_url requires a non-blank url")
                } else if (!action.url.trim().lowercase().startsWith("http")) {
                    ToolValidationResult.Invalid("read_url only supports http(s) URLs: '${action.url}'")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.OpenSearch -> ToolValidationResult.Valid
            is AgentAction.OpenItem -> {
                if (action.targetText.isBlank()) {
                    ToolValidationResult.Invalid("open_item requires a non-blank target label")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.Scroll -> {
                val validDirs = setOf("up", "down", "left", "right")
                if (!validDirs.contains(action.direction.lowercase())) {
                    ToolValidationResult.Invalid("Invalid scroll direction '${action.direction}'. Allowed: up, down, left, right")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.LongPress -> {
                if (action.targetText.isNullOrBlank()) {
                    ToolValidationResult.Invalid("long_press requires a non-blank target label")
                } else if (action.durationMs < 300 || action.durationMs > 5_000) {
                    ToolValidationResult.Invalid("Long-press duration must be between 300ms and 5000ms: ${action.durationMs}")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.ScrollUntilFound -> {
                val validDirs = setOf("up", "down")
                when {
                    action.targetText.isBlank() ->
                        ToolValidationResult.Invalid("scroll_until_found requires a non-blank target label")
                    !validDirs.contains(action.direction.lowercase()) ->
                        ToolValidationResult.Invalid("scroll_until_found direction must be 'up' or 'down': ${action.direction}")
                    action.maxSwipes < 1 || action.maxSwipes > 20 ->
                        ToolValidationResult.Invalid("scroll_until_found maxSwipes must be between 1 and 20: ${action.maxSwipes}")
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.FindElement -> {
                val validDirs = setOf("up", "down")
                when {
                    action.targetText.isBlank() ->
                        ToolValidationResult.Invalid("find_element requires a non-blank target label")
                    !validDirs.contains(action.direction.lowercase()) ->
                        ToolValidationResult.Invalid("find_element direction must be 'up' or 'down': ${action.direction}")
                    action.maxSwipes < 1 || action.maxSwipes > 20 ->
                        ToolValidationResult.Invalid("find_element maxSwipes must be between 1 and 20: ${action.maxSwipes}")
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.WaitForText -> {
                if (action.targetText.isBlank()) {
                    ToolValidationResult.Invalid("wait_for_text requires a non-blank target label")
                } else if (action.timeoutMs < 200 || action.timeoutMs > 30_000) {
                    ToolValidationResult.Invalid("wait_for_text timeout must be between 200ms and 30000ms: ${action.timeoutMs}")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.AdjustVolume -> {
                val validStreams = AudioStream.keys()
                when {
                    AudioStream.fromKey(action.stream) == null ->
                        ToolValidationResult.Invalid("adjust_volume stream must be one of $validStreams: ${action.stream}")
                    action.steps < -15 || action.steps > 15 ->
                        ToolValidationResult.Invalid("adjust_volume steps must be between -15 and 15: ${action.steps}")
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.PlayMedia -> {
                if (MediaTransport.fromKey(action.transport) == null) {
                    ToolValidationResult.Invalid(
                        "play_media transport must be one of ${MediaTransport.keys()}: ${action.transport}"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.SetRingerMode -> {
                if (RingerMode.fromKey(action.mode) == null) {
                    ToolValidationResult.Invalid(
                        "set_ringer_mode mode must be one of ${RingerMode.keys()}: ${action.mode}"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.SetAlarm -> {
                if (action.hour !in 0..23 || action.minute !in 0..59) {
                    ToolValidationResult.Invalid(
                        "set_alarm time must be a valid 24-hour time (hour 0-23, minute 0-59): ${action.hour}:${action.minute}"
                    )
                } else if (action.days.any { it !in 1..7 }) {
                    ToolValidationResult.Invalid(
                        "set_alarm days must be Calendar weekday constants (1=Sunday .. 7=Saturday): ${action.days}"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.SetTimer -> {
                if (action.seconds <= 0 || action.seconds > MAX_TIMER_SECONDS) {
                    ToolValidationResult.Invalid(
                        "set_timer seconds must be between 1 and $MAX_TIMER_SECONDS (24 hours): ${action.seconds}"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.AddCalendarEvent -> {
                when {
                    action.title.isBlank() ->
                        ToolValidationResult.Invalid("add_calendar_event requires a non-blank title")
                    action.dayOffset !in 0..MAX_EVENT_DAY_OFFSET ->
                        ToolValidationResult.Invalid(
                            "add_calendar_event dayOffset must be between 0 and $MAX_EVENT_DAY_OFFSET: ${action.dayOffset}"
                        )
                    action.startHour !in 0..23 || action.startMinute !in 0..59 ->
                        ToolValidationResult.Invalid(
                            "add_calendar_event start time must be a valid 24-hour time (hour 0-23, minute 0-59): ${action.startHour}:${action.startMinute}"
                        )
                    action.durationMinutes <= 0 || action.durationMinutes > MAX_EVENT_DURATION_MINUTES ->
                        ToolValidationResult.Invalid(
                            "add_calendar_event durationMinutes must be between 1 and $MAX_EVENT_DURATION_MINUTES (24 hours): ${action.durationMinutes}"
                        )
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.AddContact -> {
                when {
                    action.name.isBlank() ->
                        ToolValidationResult.Invalid("add_contact requires a non-blank name")
                    action.phone.isNullOrBlank() && action.email.isNullOrBlank() ->
                        ToolValidationResult.Invalid("add_contact requires at least one of phone or email")
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.DialNumber -> {
                if (action.number.isBlank()) {
                    ToolValidationResult.Invalid("dial_number requires a non-blank number")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.SendSms -> {
                when {
                    action.number.isBlank() ->
                        ToolValidationResult.Invalid("send_sms requires a non-blank number")
                    action.message.isBlank() ->
                        ToolValidationResult.Invalid("send_sms requires a non-blank message")
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.ShareText -> {
                if (action.text.isBlank()) {
                    ToolValidationResult.Invalid("share_text requires non-blank text")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.SendEmail -> {
                if (action.to.isBlank()) {
                    ToolValidationResult.Invalid("send_email requires a non-blank recipient")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.NavigateTo -> {
                if (action.target.isBlank()) {
                    ToolValidationResult.Invalid("navigate_to requires a non-blank place or address")
                } else if (action.target.length > AndroidNavigationController.MAX_NAV_TARGET_CHARS) {
                    ToolValidationResult.Invalid(
                        "navigate_to target must be at most ${AndroidNavigationController.MAX_NAV_TARGET_CHARS} characters"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.OpenAppInfo -> {
                if (AndroidAppInfoController.normalizePackageName(action.packageName) == null) {
                    ToolValidationResult.Invalid("open_app_info requires a usable app package name")
                } else {
                    ToolValidationResult.Valid
                }
            }
            // set_torch carries a Boolean on/off, so there is no blank/unsafe value to
            // reject; it is always a valid request (the executor reports honestly when no
            // flash is available).
            is AgentAction.SetTorch -> ToolValidationResult.Valid
            // set_device_control: reject an unknown control or a value invalid for that
            // control before any dispatch, so a malformed plan/automation cannot reach the
            // executor.
            is AgentAction.SetDeviceControl -> {
                if (com.example.agent.DeviceControl.resolve(action.control, action.value) == null) {
                    ToolValidationResult.Invalid(
                        "set_device_control requires a known control (${com.example.agent.DeviceControl.keys().joinToString("/")}) and a value valid for it: '${action.control}'='${action.value}'"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.Speak -> {
                if (action.text.isBlank()) {
                    ToolValidationResult.Invalid("speak requires non-blank text")
                } else if (action.text.length > ToolRegistry.MAX_SPEAK_CHARS) {
                    ToolValidationResult.Invalid("speak text is too long (max ${ToolRegistry.MAX_SPEAK_CHARS} chars)")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.OpenSettings -> {
                if (SettingsPanel.fromKey(action.panel) == null) {
                    ToolValidationResult.Invalid(
                        "open_settings panel must be one of: ${SettingsPanel.keys().joinToString(", ")}"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            // open_calendar's only parameter is a day offset; reject an absurd offset so
            // a malformed plan cannot fling the calendar years away from today.
            is AgentAction.OpenCalendar -> {
                if (action.dayOffset !in -MAX_EVENT_DAY_OFFSET..MAX_EVENT_DAY_OFFSET) {
                    ToolValidationResult.Invalid(
                        "open_calendar dayOffset must be between -$MAX_EVENT_DAY_OFFSET and $MAX_EVENT_DAY_OFFSET: ${action.dayOffset}"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.Note -> {
                when {
                    action.title.isNullOrBlank() && action.content.isNullOrBlank() ->
                        ToolValidationResult.Invalid("create_note requires a non-blank title or content")
                    (action.title?.length ?: 0) > MAX_NOTE_TITLE_CHARS ->
                        ToolValidationResult.Invalid("create_note title is too long (max $MAX_NOTE_TITLE_CHARS chars)")
                    (action.content?.length ?: 0) > MAX_NOTE_CONTENT_CHARS ->
                        ToolValidationResult.Invalid("create_note content is too long (max $MAX_NOTE_CONTENT_CHARS chars)")
                    else -> ToolValidationResult.Valid
                }
            }
            // dictate_note's only parameter is an optional title; an over-long one is
            // rejected so a malformed plan cannot hand the notes app nonsense. A blank
            // title is fine (the app opens its voice-input mode) so it is never rejected.
            is AgentAction.DictateNote -> {
                if ((action.title?.length ?: 0) > MAX_NOTE_TITLE_CHARS) {
                    ToolValidationResult.Invalid(
                        "dictate_note title is too long (max $MAX_NOTE_TITLE_CHARS chars)"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            // pick_file's only parameter is an optional mime filter; reject an absurd one so
            // a malformed plan cannot hand the picker a nonsense type. A blank type is fine
            // (any file) so it is never rejected.
            is AgentAction.PickFile -> {
                if ((action.mimeType?.length ?: 0) > MAX_MIME_TYPE_CHARS) {
                    ToolValidationResult.Invalid(
                        "pick_file mimeType is too long (max $MAX_MIME_TYPE_CHARS chars)"
                    )
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.SummarizeText -> {
                when {
                    action.text.isBlank() ->
                        ToolValidationResult.Invalid("summarize_text requires non-blank text")
                    action.text.length > MAX_SUMMARIZE_INPUT_CHARS ->
                        ToolValidationResult.Invalid("summarize_text input is too long (max $MAX_SUMMARIZE_INPUT_CHARS chars)")
                    action.maxChars < MIN_SUMMARY_CHARS || action.maxChars > MAX_SUMMARY_CHARS ->
                        ToolValidationResult.Invalid(
                            "summarize_text maxChars must be between $MIN_SUMMARY_CHARS and $MAX_SUMMARY_CHARS"
                        )
                    else -> ToolValidationResult.Valid
                }
            }
            is AgentAction.Wait -> {
                if (action.durationMs < 0 || action.durationMs > 30_000) {
                    ToolValidationResult.Invalid("Wait duration must be between 0ms and 30000ms: ${action.durationMs}")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.AskConfirmation -> {
                if (action.riskType.isBlank() || action.description.isBlank()) {
                    ToolValidationResult.Invalid("Risk confirmation must provide riskType and description")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.FinishTask -> {
                if (action.summary.isBlank()) {
                    ToolValidationResult.Invalid("Finish task summary cannot be blank")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.FailTask -> {
                if (action.reason.isBlank()) {
                    ToolValidationResult.Invalid("Fail task reason cannot be blank")
                } else {
                    ToolValidationResult.Valid
                }
            }
            is AgentAction.Back,
            is AgentAction.Home,
            is AgentAction.ReadScreen,
            is AgentAction.ReadText,
            is AgentAction.ReadNotifications,
            is AgentAction.ReadDeviceStatus,
            is AgentAction.OpenNotifications,
            is AgentAction.OpenQuickSettings,
            is AgentAction.PressRecents,
            is AgentAction.OpenPowerDialog,
            is AgentAction.Screenshot -> ToolValidationResult.Valid
        }
    }
}
