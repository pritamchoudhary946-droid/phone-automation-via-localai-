package com.example.planning

import com.example.agent.ToolRegistry

/**
 * Builds bounded, deterministic prompts for the local Gemma model.
 *
 * Prompts stay small on purpose: an 8 GB device must fit them comfortably and
 * generation must stay fast. Screen context is truncated upstream and clearly
 * flagged as possibly incomplete.
 */
object PlannerPromptBuilder {

    const val SYSTEM_INSTRUCTION =
        "You are OmniAgent, an on-device Android automation planner. " +
            "You convert a natural-language command into a short ordered list of safe UI steps. " +
            "Respond with ONLY a single JSON object. Never include prose, markdown or code fences."

    private val SCHEMA_HINT = """
        Output schema:
        {"steps":[
          {"id":1,"seq":1,"desc":"short result","action":"<one of the allowed actions>",
           "params":{...},"expect":"postcondition","verify":"FOREGROUND_PACKAGE|SCREEN_CHANGED|TEXT_PRESENT|RESULTS_PRESENT|HOME_FOREGROUND|NONE",
           "dependsOn":[],"maxRetries":1}
        ]}
        Rules:
        - Use ONLY actions from the allowed list. Never invent an action name.
        - Keep plans minimal. For "Open YouTube" produce a single launch step.
        - Stop planning once the goal is satisfied; do not add extra tasks.
        - verify=FOREGROUND_PACKAGE is required for launch_app steps.
        - To search inside an app, use open_search before enter_text: the search
          field is often not visible until the search interface is opened.
        - end a plan with report_status only when the goal is fully achieved.
    """.trimIndent()

    fun buildPlanningPrompt(request: PlanningRequest): String = buildString {
        appendLine("Task: produce the ordered subgoals needed to satisfy the user command.")
        appendLine("User command: \"${request.userCommand}\"")
        appendLine("Desired final outcome: ${request.desiredOutcome}")
        appendLine("Foreground package now: ${request.foregroundPackage ?: "unknown"}")
        appendLine("Remaining step budget: ${request.remainingStepBudget}")
        appendLine("Allowed actions: ${request.availableActions.joinToString(", ")}")
        if (request.completedStepsSummary.isNotBlank()) {
            appendLine("Already completed steps:")
            appendLine(request.completedStepsSummary.take(600))
        }
        if (!request.lastActionResult.isNullOrBlank()) {
            appendLine("Last action result: ${request.lastActionResult.take(300)}")
        }
        appendLine("Current screen (bounded summary, may be truncated):")
        appendLine(request.screenSummary.take(MAX_SCREEN_CHARS))
        appendLine()
        appendLine(SCHEMA_HINT)
    }

    fun buildStepDecisionPrompt(request: StepDecisionRequest): String = buildString {
        appendLine("Task: choose the single next action to make progress on the active subgoal.")
        appendLine("User command: \"${request.userCommand}\"")
        appendLine("Active subgoal: ${request.activeSubGoal}")
        appendLine("Foreground package now: ${request.foregroundPackage ?: "unknown"}")
        appendLine("Remaining step budget: ${request.remainingStepBudget}")
        appendLine("Allowed actions: ${request.availableActions.joinToString(", ")}")
        if (request.completedStepsSummary.isNotBlank()) {
            appendLine("Completed steps so far:")
            appendLine(request.completedStepsSummary.take(500))
        }
        if (!request.lastActionResult.isNullOrBlank()) {
            appendLine("Last action result (act differently if it failed): ${request.lastActionResult.take(300)}")
        }
        appendLine("Current screen (bounded summary, may be truncated):")
        appendLine(request.screenSummary.take(MAX_SCREEN_CHARS))
        appendLine()
        appendLine(
            "Reply with ONLY this JSON: {\"thought\":\"...\",\"action\":\"<allowed action>\",\"params\":{...}}"
        )
    }

    /** Short catalogue handed to the model. Never exposes free-form Android APIs. */
    fun availableActions(registry: ToolRegistry): List<String> =
        registry.getRegisteredTools().map { it.name }

    const val MAX_SCREEN_CHARS = 1200
}
