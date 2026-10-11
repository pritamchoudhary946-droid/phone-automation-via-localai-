package com.example.agent

/**
 * Names *why* a step failed, so the recovery cycle can pick a fitting route instead
 * of blindly retrying (plan 5.2). Classification is purely deterministic over the
 * observed signals, so it is unit-testable without a model.
 */
enum class FailureType {
    NONE,
    NO_SCREEN_CHANGE,
    TARGET_NOT_FOUND,
    WRONG_PACKAGE,
    TIMEOUT,
    PERMISSION_DIALOG,
    LOGIN_REQUIRED,
    NETWORK_ERROR,
    VALIDATION_REJECTED,
    LOOP_DETECTED,
    UNKNOWN
}

/**
 * The typed diagnosis of a failed step plus the recovery it suggests and the
 * confidence-free human reason used in logs and in the next planning prompt.
 */
data class FailureDiagnosis(
    val type: FailureType,
    val reason: String,
    val suggestedRecovery: RecoveryStrategy
)

/**
 * Maps observed execution signals to a [FailureType]. Order matters: the most
 * specific, actionable signal wins (a timeout is a timeout even if the screen also
 * did not change).
 */
object FailureClassifier {

    fun classify(
        success: Boolean,
        resultMessage: String,
        resultMethod: String,
        verificationDetail: String,
        screenChanged: Boolean,
        expectedPackage: String?,
        observedPackage: String?,
        screenText: String,
        validationRejected: Boolean = false,
        loopDetected: Boolean = false
    ): FailureDiagnosis {
        val lowerDetail = verificationDetail.lowercase()
        val lowerScreen = screenText.lowercase()

        if (success) return FailureDiagnosis(FailureType.NONE, "Step succeeded", RecoveryStrategy.NONE)

        if (loopDetected) {
            return FailureDiagnosis(
                FailureType.LOOP_DETECTED,
                "Repeated state/action pairs with no progress.",
                RecoveryStrategy.REPLAN_NEW_APPROACH
            )
        }
        if (validationRejected) {
            return FailureDiagnosis(
                FailureType.VALIDATION_REJECTED,
                "Action rejected by the tool registry: $verificationDetail",
                RecoveryStrategy.REPLAN_NEW_APPROACH
            )
        }
        if (resultMethod.equals("TIMEOUT", ignoreCase = true) || lowerDetail.contains("timed out") ||
            resultMessage.contains("TIMEOUT", ignoreCase = true)
        ) {
            return FailureDiagnosis(
                FailureType.TIMEOUT,
                "Action did not complete in time.",
                RecoveryStrategy.RETRY_WITH_COORDINATES
            )
        }
        // A permission dialog is only diagnosed when the screen really shows BOTH a
        // permission grant affordance and a dialog/consent cue. "allow"/"permission"
        // alone appears in ordinary UI text (e.g. "Allow notifications" in settings),
        // so a bare keyword must not hijack recovery. Parenthesised so `&&` binds
        // correctly — Kotlin's `&&` has higher precedence than `||`, and the
        // unparenthesised form would match any screen merely containing "permission".
        if ((lowerScreen.contains("permission") || lowerScreen.contains("allow")) &&
            (lowerScreen.contains("dialog") || lowerScreen.contains("grant") ||
                lowerScreen.contains("while using") || lowerScreen.contains("deny"))
        ) {
            return FailureDiagnosis(
                FailureType.PERMISSION_DIALOG,
                "A permission dialog is blocking the screen.",
                RecoveryStrategy.PRESS_BACK_TO_UNBLOCK
            )
        }
        if (lowerDetail.contains("network") || lowerDetail.contains("offline") ||
            lowerDetail.contains("connection") || lowerDetail.contains("unavailable: no gateway")
        ) {
            return FailureDiagnosis(
                FailureType.NETWORK_ERROR,
                "The step needs the network, which was unavailable.",
                RecoveryStrategy.REPLAN_NEW_APPROACH
            )
        }
        if (!expectedPackage.isNullOrBlank() && !observedPackage.isNullOrBlank() &&
            !expectedPackage.equals(observedPackage, ignoreCase = true)
        ) {
            return FailureDiagnosis(
                FailureType.WRONG_PACKAGE,
                "Expected $expectedPackage but $observedPackage is foreground.",
                RecoveryStrategy.REPLAN_NEW_APPROACH
            )
        }
        // The action never found its target: the screen exposes no matching control.
        if (lowerDetail.contains("not found") || lowerDetail.contains("no matching") ||
            lowerDetail.contains("no editable") || lowerDetail.contains("could not find")
        ) {
            return FailureDiagnosis(
                FailureType.TARGET_NOT_FOUND,
                "The target control was not found on screen.",
                RecoveryStrategy.SCROLL_TO_DISCOVER
            )
        }
        if (!screenChanged) {
            return FailureDiagnosis(
                FailureType.NO_SCREEN_CHANGE,
                "The action produced no visible screen change.",
                RecoveryStrategy.SCROLL_TO_DISCOVER
            )
        }
        return FailureDiagnosis(
            FailureType.UNKNOWN,
            verificationDetail.ifBlank { "Step did not satisfy its postcondition." },
            RecoveryStrategy.PRESS_BACK_TO_UNBLOCK
        )
    }
}
