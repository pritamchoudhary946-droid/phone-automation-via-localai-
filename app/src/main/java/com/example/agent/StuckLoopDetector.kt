package com.example.agent

enum class RecoveryStrategy {
    NONE,
    SCROLL_TO_DISCOVER,
    PRESS_BACK_TO_UNBLOCK,
    RETRY_WITH_COORDINATES,
    REPLAN_NEW_APPROACH,
    ASK_USER_HELP
}

data class StuckStatus(
    val isStuck: Boolean,
    val consecutiveStuckCount: Int,
    val reason: String,
    val suggestedRecovery: RecoveryStrategy
)

class StuckLoopDetector {

    private val screenFingerprints = mutableListOf<String>()
    private val actionHistory = mutableListOf<String>()
    private var consecutiveIdenticalScreens = 0
    private var identicalActionStreak = 0

    fun recordStep(fingerprint: String, actionName: String): StuckStatus {
        // Track duplicate actions
        if (actionHistory.isNotEmpty() && actionHistory.last() == actionName) {
            identicalActionStreak++
        } else {
            identicalActionStreak = 0
        }
        actionHistory.add(actionName)
        if (actionHistory.size > 10) actionHistory.removeAt(0)

        // Track screen changes
        if (screenFingerprints.isNotEmpty() && screenFingerprints.last() == fingerprint) {
            consecutiveIdenticalScreens++
        } else {
            consecutiveIdenticalScreens = 0
        }
        screenFingerprints.add(fingerprint)
        if (screenFingerprints.size > 10) screenFingerprints.removeAt(0)

        // Stuck Condition 1: Same action repeated with no screen change (Progressive Escalation)
        if (identicalActionStreak >= 2 && consecutiveIdenticalScreens >= 2) {
            val strategy = when {
                identicalActionStreak >= 4 -> RecoveryStrategy.ASK_USER_HELP
                identicalActionStreak >= 3 -> RecoveryStrategy.PRESS_BACK_TO_UNBLOCK
                actionName.contains("Tap") -> RecoveryStrategy.SCROLL_TO_DISCOVER
                else -> RecoveryStrategy.PRESS_BACK_TO_UNBLOCK
            }
            return StuckStatus(
                isStuck = true,
                consecutiveStuckCount = identicalActionStreak,
                reason = "Action '$actionName' executed $identicalActionStreak times with zero screen change.",
                suggestedRecovery = strategy
            )
        }

        // Stuck Condition 2: 3 consecutive actions resulted in identical screen state
        if (consecutiveIdenticalScreens >= 3) {
            return StuckStatus(
                isStuck = true,
                consecutiveStuckCount = consecutiveIdenticalScreens,
                reason = "Screen has remained frozen/unchanged across 3 consecutive interactions.",
                suggestedRecovery = RecoveryStrategy.PRESS_BACK_TO_UNBLOCK
            )
        }

        // Stuck Condition 3: A-B-A-B Ping Pong loop
        if (actionHistory.size >= 4) {
            val a1 = actionHistory[actionHistory.size - 4]
            val b1 = actionHistory[actionHistory.size - 3]
            val a2 = actionHistory[actionHistory.size - 2]
            val b2 = actionHistory[actionHistory.size - 1]
            if (a1 == a2 && b1 == b2 && a1 != b1) {
                return StuckStatus(
                    isStuck = true,
                    consecutiveStuckCount = 4,
                    reason = "Oscillation loop detected between '$a1' and '$b1'.",
                    suggestedRecovery = RecoveryStrategy.REPLAN_NEW_APPROACH
                )
            }
        }

        return StuckStatus(
            isStuck = false,
            consecutiveStuckCount = 0,
            reason = "Execution progressing normally.",
            suggestedRecovery = RecoveryStrategy.NONE
        )
    }

    fun reset() {
        screenFingerprints.clear()
        actionHistory.clear()
        consecutiveIdenticalScreens = 0
        identicalActionStreak = 0
    }
}
