package com.example.agent

/**
 * Progress meter for a long task (plan 5.10): it tracks how much of the step budget
 * and how many recoveries have been spent and, when the run is struggling, tells the
 * loop which degradation step comes next. The order is fixed and decreasingly cheap:
 * re-plan first, then explore, then escalate to the user, and finally stop cleanly —
 * so a hard task degrades gracefully instead of hanging or faking success.
 */
class ProgressBudget(
    private val maxSteps: Int,
    maxReplans: Int,
    maxExplores: Int
) {
    enum class Stage { OK, REPLAN, EXPLORE, ESCALATE, STOP }

    val maxReplans: Int = maxReplans
    val maxExplores: Int = maxExplores

    var replansUsed: Int = 0
        private set
    var exploresUsed: Int = 0
        private set

    fun stepsRemaining(completed: Int): Int = (maxSteps - completed).coerceAtLeast(0)

    fun recordReplan() {
        replansUsed++
    }

    fun recordExplore() {
        exploresUsed++
    }

    fun canReplan(): Boolean = replansUsed < maxReplans

    fun canExplore(): Boolean = exploresUsed < maxExplores

    /**
     * The next degradation step given that the run has just failed and cannot make
     * progress with the current plan. Never returns [Stage.OK] from here: a failure
     * that cannot be absorbed cheaply must degrade.
     */
    fun nextStageAfterFailure(stepsRemaining: Int): Stage = when {
        stepsRemaining <= 0 -> Stage.STOP
        canReplan() -> Stage.REPLAN
        canExplore() -> Stage.EXPLORE
        else -> Stage.ESCALATE
    }

    /** True when nothing further can be tried and the task should stop honestly. */
    fun exhausted(stepsRemaining: Int): Boolean = stepsRemaining <= 0 || nextStageAfterFailure(stepsRemaining) == Stage.STOP
}
