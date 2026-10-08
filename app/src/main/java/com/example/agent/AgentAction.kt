package com.example.agent

sealed class AgentAction {
    data class OpenApp(val appQuery: String, val packageName: String?) : AgentAction()
    data class Tap(val targetText: String?, val x: Int?, val y: Int?) : AgentAction()
    data class TypeText(val text: String, val targetNodeId: String?) : AgentAction()
    data class Scroll(val direction: String) : AgentAction() // "up", "down", "left", "right"
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
            is Scroll -> "Scroll $direction"
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

    fun evaluate(action: AgentAction, currentScreenContext: String): RiskAssessment {
        val screenLower = currentScreenContext.lowercase()

        when (action) {
            is AgentAction.Tap -> {
                val target = (action.targetText ?: "").lowercase()
                if (paymentKeywords.any { target.contains(it) || screenLower.contains(it) }) {
                    return RiskAssessment(
                        isRisky = true,
                        riskType = "Financial / Payment",
                        warningMessage = "Tapping '$target' appears to initiate a financial payment or checkout."
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
            else -> {}
        }

        return RiskAssessment(isRisky = false)
    }
}
