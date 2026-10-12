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
        const val TOOL_READ_NOTIFICATIONS = "read_notifications"
        const val TOOL_READ_DEVICE_STATUS = "read_device_status"
        const val TOOL_OPEN_NOTIFICATIONS = "open_notifications"
        const val TOOL_OPEN_QUICK_SETTINGS = "open_quick_settings"
        const val TOOL_OPEN_RECENTS = "open_recents"
        const val TOOL_NAVIGATE_BACK = "navigate_back"
        const val TOOL_NAVIGATE_HOME = "navigate_home"
        const val TOOL_WAIT = "wait"
        const val TOOL_SCREENSHOT = "screenshot"
        const val TOOL_ASK_CONFIRMATION = "ask_confirmation"
        const val TOOL_REPORT_STATUS = "report_status"
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
            is AgentAction.Screenshot -> ToolValidationResult.Valid
        }
    }
}
