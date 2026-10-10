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
            is ReadText -> "Read screen text"
            is WaitForText -> "Wait for text '$targetText' (up to ${timeoutMs}ms)"
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
