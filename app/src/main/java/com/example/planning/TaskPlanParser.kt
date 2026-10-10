package com.example.planning

import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict parser that turns the local model's JSON plan into a validated [TaskPlan].
 *
 * Rejects anything unsafe or malformed instead of executing it:
 * - Unknown action names (only the approved tool catalog is accepted).
 * - Invalid parameters (delegated to [ToolRegistry.validateAction]).
 * - Empty plans and out-of-order/duplicate step identifiers.
 * - Impossible dependencies (referencing unknown or forward-only step ids).
 * The model can never inject arbitrary code or arbitrary action names.
 */
class TaskPlanParser(private val toolRegistry: ToolRegistry = ToolRegistry()) {

    fun parse(rawModelOutput: String, goal: String): PlanParseResult {
        val jsonText = extractJsonObject(rawModelOutput)
            ?: return PlanParseResult.Invalid("No JSON object found in model output", rawModelOutput)

        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return PlanParseResult.Invalid("Malformed JSON: ${e.message}", rawModelOutput)
        }

        val stepsArray: JSONArray = root.optJSONArray("steps")
            ?: return PlanParseResult.Invalid("Plan is missing a 'steps' array", rawModelOutput)

        if (stepsArray.length() == 0) {
            return PlanParseResult.Invalid("Plan contains no steps", rawModelOutput)
        }
        if (stepsArray.length() > MAX_STEPS) {
            return PlanParseResult.Invalid("Plan exceeds the maximum of $MAX_STEPS steps", rawModelOutput)
        }

        val parsedSteps = mutableListOf<PlanStep>()
        val seenIds = mutableSetOf<Int>()

        for (i in 0 until stepsArray.length()) {
            val stepObj = stepsArray.optJSONObject(i)
                ?: return PlanParseResult.Invalid("Step $i is not a JSON object", rawModelOutput)

            val id = stepObj.optInt("id", i + 1)
            if (!seenIds.add(id)) {
                return PlanParseResult.Invalid("Duplicate step id $id", rawModelOutput)
            }

            val rawAction = stepObj.optString("action", "").trim().lowercase()
            if (rawAction.isBlank()) {
                return PlanParseResult.Invalid("Step $id is missing an action", rawModelOutput)
            }
            if (toolRegistry.getTool(rawAction) == null) {
                return PlanParseResult.Invalid("Unknown action '$rawAction' is not in the approved catalog", rawModelOutput)
            }

            val params = stepObj.optJSONObject("params") ?: JSONObject()
            val action = try {
                mapAction(rawAction, params)
            } catch (e: Exception) {
                return PlanParseResult.Invalid("Step $id has invalid parameters: ${e.message}", rawModelOutput)
            }

            when (val validation = toolRegistry.validateAction(action)) {
                is ToolValidationResult.Invalid ->
                    return PlanParseResult.Invalid("Step $id rejected: ${validation.reason}", rawModelOutput)
                is ToolValidationResult.Valid -> Unit
            }

            val verification = parseVerification(stepObj.optString("verify", "NONE"))
            val dependsOn = parseDependencies(stepObj.optJSONArray("dependsOn"))

            parsedSteps.add(
                PlanStep(
                    id = id,
                    sequence = stepObj.optInt("seq", i + 1),
                    description = stepObj.optString("desc", "Step $id").ifBlank { "Step $id" },
                    action = action,
                    rawActionName = rawAction,
                    expectedPostcondition = stepObj.optString("expect", ""),
                    verification = verification,
                    dependsOn = dependsOn,
                    maxRetries = stepObj.optInt("maxRetries", 1).coerceIn(0, 3)
                )
            )
        }

        // Impossible references: a dependency must exist and must point to an earlier step.
        for (step in parsedSteps) {
            for (dep in step.dependsOn) {
                val target = parsedSteps.find { it.id == dep }
                    ?: return PlanParseResult.Invalid("Step ${step.id} depends on unknown step $dep", rawModelOutput)
                if (target.sequence >= step.sequence) {
                    return PlanParseResult.Invalid("Step ${step.id} depends on a non-earlier step $dep", rawModelOutput)
                }
            }
        }

        val ordered = parsedSteps.sortedBy { it.sequence }
        return PlanParseResult.Success(TaskPlan(goal = goal, steps = ordered, fromModel = true))
    }

    /** Extracts the first balanced top-level JSON object from surrounding prose/code fences. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun mapAction(rawAction: String, params: JSONObject): AgentAction {
        return when (rawAction) {
            ToolRegistry.TOOL_LAUNCH_APP -> {
                val query = params.optString("appQuery", "").ifBlank { params.optString("app", "") }
                val pkg = params.optString("packageName", "").ifBlank { null }
                AgentAction.OpenApp(query, pkg)
            }
            ToolRegistry.TOOL_READ_SCREEN -> AgentAction.ReadScreen
            ToolRegistry.TOOL_TAP_ELEMENT -> {
                val text = params.optString("targetText", "").ifBlank { null }
                val x = if (params.has("x")) params.optInt("x") else null
                val y = if (params.has("y")) params.optInt("y") else null
                AgentAction.Tap(text, x, y)
            }
            ToolRegistry.TOOL_ENTER_TEXT -> {
                val text = params.optString("text", "")
                val nodeId = params.optString("targetNodeId", "").ifBlank { null }
                AgentAction.TypeText(text, nodeId)
            }
            ToolRegistry.TOOL_OPEN_SEARCH -> {
                val query = params.optString("query", "").ifBlank { null }
                AgentAction.OpenSearch(query)
            }
            ToolRegistry.TOOL_OPEN_ITEM -> AgentAction.OpenItem(params.optString("targetText", ""))
            ToolRegistry.TOOL_SCROLL -> AgentAction.Scroll(params.optString("direction", "down"))
            ToolRegistry.TOOL_LONG_PRESS -> AgentAction.LongPress(
                targetText = params.optString("targetText", "").ifBlank { null },
                durationMs = params.optLong("durationMs", 700L)
            )
            ToolRegistry.TOOL_SCROLL_UNTIL_FOUND -> AgentAction.ScrollUntilFound(
                targetText = params.optString("targetText", ""),
                direction = params.optString("direction", "down"),
                maxSwipes = params.optInt("maxSwipes", 6)
            )
            ToolRegistry.TOOL_READ_TEXT -> AgentAction.ReadText
            ToolRegistry.TOOL_WAIT_FOR_TEXT -> AgentAction.WaitForText(
                targetText = params.optString("targetText", ""),
                timeoutMs = params.optLong("timeoutMs", 5_000L)
            )
            ToolRegistry.TOOL_OPEN_NOTIFICATIONS -> AgentAction.OpenNotifications
            ToolRegistry.TOOL_OPEN_QUICK_SETTINGS -> AgentAction.OpenQuickSettings
            ToolRegistry.TOOL_OPEN_RECENTS -> AgentAction.PressRecents
            ToolRegistry.TOOL_NAVIGATE_BACK -> AgentAction.Back
            ToolRegistry.TOOL_NAVIGATE_HOME -> AgentAction.Home
            ToolRegistry.TOOL_WAIT -> AgentAction.Wait(params.optLong("durationMs", 800L))
            ToolRegistry.TOOL_SCREENSHOT -> AgentAction.Screenshot
            ToolRegistry.TOOL_REPORT_STATUS -> {
                val summary = params.optString("summary", "Goal achieved")
                // Only an explicit success (or a bare report, whose completion is still
                // independently verified by the orchestrator) may finish the task. Any
                // other explicit status — "failure", "error", "partial" — must not be
                // silently upgraded to success.
                val status = params.optString("status", "").trim()
                if (status.isBlank() || status.equals("success", ignoreCase = true)) {
                    AgentAction.FinishTask(summary)
                } else {
                    AgentAction.FailTask(
                        params.optString("reason", "").ifBlank { "report_status reported '$status'" }
                    )
                }
            }
            else -> throw IllegalArgumentException("Unsupported action '$rawAction'")
        }
    }

    private fun parseVerification(value: String): VerificationMethod {
        return when (value.trim().uppercase()) {
            "FOREGROUND_PACKAGE", "FOREGROUND", "PACKAGE" -> VerificationMethod.FOREGROUND_PACKAGE
            "SCREEN_CHANGED", "SCREEN" -> VerificationMethod.SCREEN_CHANGED
            "TEXT_PRESENT", "TEXT" -> VerificationMethod.TEXT_PRESENT
            "RESULTS_PRESENT", "RESULTS" -> VerificationMethod.RESULTS_PRESENT
            "HOME_FOREGROUND", "HOME" -> VerificationMethod.HOME_FOREGROUND
            else -> VerificationMethod.NONE
        }
    }

    private fun parseDependencies(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optInt(it, -1).takeIf { v -> v >= 0 } }
    }

    companion object {
        const val MAX_STEPS = 12
    }
}
