package com.example.planning

import com.example.agent.AgentAction

/** Everything the model needs to interpret a command and plan safely. */
data class PlanningRequest(
    val userCommand: String,
    val desiredOutcome: String,
    val foregroundPackage: String?,
    val screenSummary: String,
    val completedStepsSummary: String,
    val lastActionResult: String?,
    val remainingStepBudget: Int,
    val availableActions: List<String>,
    /** Bounded digest of learned user preferences / past outcomes, or blank. */
    val memorySummary: String = "",
    /** Bounded retrieved context from the user's own notes/history (RAG), or blank. */
    val retrievedContext: String = "",
    /** Bounded lessons from past episodes relevant to this goal (plan 5.9), or blank. */
    val lessonsSummary: String = ""
)

/** Everything the model needs to pick the single next action during execution. */
data class StepDecisionRequest(
    val userCommand: String,
    val activeSubGoal: String,
    val foregroundPackage: String?,
    val screenSummary: String,
    val completedStepsSummary: String,
    val lastActionResult: String?,
    val remainingStepBudget: Int,
    val availableActions: List<String>
)

/** Why a plan came from the model or from the labelled fallback. */
enum class PlanSource { GEMMA_MODEL, BASIC_FALLBACK }

sealed class PlanOutcome {
    data class Planned(val plan: TaskPlan, val source: PlanSource) : PlanOutcome()
    data class Unavailable(val reason: String) : PlanOutcome()
    data class Rejected(val reason: String, val rawOutput: String) : PlanOutcome()
}

sealed class StepDecision {
    data class Act(val action: AgentAction, val thought: String, val fromModel: Boolean) : StepDecision()
    data class None(val reason: String) : StepDecision()
}

/** Everything the model needs for a bounded reflection-on-demand (plan 5.4). */
data class ReflectionRequest(
    val goal: String,
    val activeSubGoal: String,
    val lastAction: String,
    val failureReason: String,
    val screenSummary: String,
    val foregroundPackage: String?
)

/** Everything the model needs to propose ONE safe exploration probe (plan 5.6). */
data class ExplorationRequest(
    val goal: String,
    val missingTarget: String,
    val probesUsed: Int,
    val screenSummary: String,
    val foregroundPackage: String?,
    val availableActions: List<String>
)
