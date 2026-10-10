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
        const val TOOL_OPEN_SEARCH = "open_search"
        const val TOOL_OPEN_ITEM = "open_item"
        const val TOOL_SCROLL = "scroll"
        const val TOOL_LONG_PRESS = "long_press"
        const val TOOL_SCROLL_UNTIL_FOUND = "scroll_until_found"
        const val TOOL_READ_TEXT = "read_text"
        const val TOOL_WAIT_FOR_TEXT = "wait_for_text"
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
            is AgentAction.WaitForText -> {
                if (action.targetText.isBlank()) {
                    ToolValidationResult.Invalid("wait_for_text requires a non-blank target label")
                } else if (action.timeoutMs < 200 || action.timeoutMs > 30_000) {
                    ToolValidationResult.Invalid("wait_for_text timeout must be between 200ms and 30000ms: ${action.timeoutMs}")
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
            is AgentAction.OpenNotifications,
            is AgentAction.OpenQuickSettings,
            is AgentAction.PressRecents,
            is AgentAction.Screenshot -> ToolValidationResult.Valid
        }
    }
}
