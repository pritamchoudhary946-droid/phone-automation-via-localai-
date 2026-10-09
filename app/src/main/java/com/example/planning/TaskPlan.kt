package com.example.planning

import com.example.agent.AgentAction

/** Lifecycle of a single planned subgoal. */
enum class PlanStepStatus { PENDING, EXECUTING, COMPLETED, FAILED, SKIPPED }

/** How a step's postcondition is judged. */
enum class VerificationMethod {
    FOREGROUND_PACKAGE,
    SCREEN_CHANGED,
    TEXT_PRESENT,
    RESULTS_PRESENT,
    HOME_FOREGROUND,
    NONE
}

/**
 * A single validated subgoal produced by the local model. It never carries
 * free-form executable text: [action] is always one of the approved, mapped
 * [AgentAction] variants produced by [TaskPlanParser].
 */
data class PlanStep(
    val id: Int,
    val sequence: Int,
    val description: String,
    val action: AgentAction,
    val rawActionName: String,
    val expectedPostcondition: String,
    val verification: VerificationMethod,
    val dependsOn: List<Int> = emptyList(),
    val maxRetries: Int = 1,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val attempts: Int = 0
) {
    fun withStatus(newStatus: PlanStepStatus): PlanStep = copy(status = newStatus)
    fun withAttempt(): PlanStep = copy(attempts = attempts + 1, status = PlanStepStatus.EXECUTING)

    /** Records a failed/rejected attempt but restores the step to PENDING for a bounded retry. */
    fun withRetry(): PlanStep = copy(attempts = attempts + 1, status = PlanStepStatus.PENDING)
}

/** The full validated plan for a user goal. */
data class TaskPlan(
    val goal: String,
    val steps: List<PlanStep>,
    val fromModel: Boolean
) {
    val size: Int get() = steps.size

    fun nextPending(): PlanStep? = steps.firstOrNull {
        it.status == PlanStepStatus.PENDING || it.status == PlanStepStatus.FAILED
    }

    fun replace(step: PlanStep): TaskPlan = copy(steps = steps.map { if (it.id == step.id) step else it })

    fun completedCount(): Int = steps.count { it.status == PlanStepStatus.COMPLETED }
}

sealed class PlanParseResult {
    data class Success(val plan: TaskPlan) : PlanParseResult()
    data class Invalid(val reason: String, val rawOutput: String) : PlanParseResult()
}
