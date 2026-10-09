package com.example.planning

import android.util.Log
import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.model.GemmaInferenceEngine
import com.example.model.HeuristicPlanner
import com.example.model.LiteRtModelManager
import org.json.JSONObject

/**
 * The reasoning brain. When the local Gemma engine is ready it asks the real
 * model to interpret the command and produce a validated plan; otherwise it
 * returns an explicitly labelled basic fallback. It never fabricates a model
 * response and never reports Gemma planning when the engine is not ready.
 */
class AgentPlanner(
    private val inferenceEngine: GemmaInferenceEngine,
    private val modelManager: LiteRtModelManager,
    private val parser: TaskPlanParser = TaskPlanParser(),
    private val heuristicPlanner: HeuristicPlanner = HeuristicPlanner(),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val maxOutputTokens: Int = 768
) {

    private val availableActions = PlannerPromptBuilder.availableActions(toolRegistry)

    /**
     * Produces a plan for [command]. Tries the real model with one bounded repair
     * attempt, then falls back to the clearly labelled basic planner.
     */
    suspend fun plan(request: PlanningRequest): PlanOutcome {
        if (!inferenceEngine.isReady()) {
            return PlanOutcome.Planned(
                plan = heuristicPlanner.toFallbackPlan(request.userCommand),
                source = PlanSource.BASIC_FALLBACK
            )
        }

        val prompt = PlannerPromptBuilder.buildPlanningPrompt(request)
        var lastRaw = ""
        var lastReason = "unknown"

        repeat(2) { attempt ->
            val raw = try {
                inferenceEngine.generate(prompt, PlannerPromptBuilder.SYSTEM_INSTRUCTION, maxOutputTokens)
            } catch (e: Exception) {
                Log.w(TAG, "Model generate threw: ${e.message}")
                null
            }
            if (raw.isNullOrBlank()) {
                lastReason = "Model returned no output"
                return@repeat
            }
            lastRaw = raw
            when (val parsed = parser.parse(raw, request.userCommand)) {
                is PlanParseResult.Success -> return PlanOutcome.Planned(parsed.plan, PlanSource.GEMMA_MODEL)
                is PlanParseResult.Invalid -> {
                    lastReason = parsed.reason
                    Log.w(TAG, "Invalid plan (attempt ${attempt + 1}): ${parsed.reason}")
                }
            }
        }

        Log.w(TAG, "Falling back to basic mode: $lastReason")
        val fallback = heuristicPlanner.toFallbackPlan(request.userCommand)
        return PlanOutcome.Planned(fallback, PlanSource.BASIC_FALLBACK)
    }

    /** Asks the model to choose the single next action given the fresh observation. */
    suspend fun decideNextAction(request: StepDecisionRequest): StepDecision {
        if (!inferenceEngine.isReady()) {
            return StepDecision.None("Gemma engine not ready")
        }
        val prompt = PlannerPromptBuilder.buildStepDecisionPrompt(request)
        val raw = try {
            inferenceEngine.generate(prompt, PlannerPromptBuilder.SYSTEM_INSTRUCTION, maxOutputTokens = 256)
        } catch (e: Exception) {
            Log.w(TAG, "Step decision generate threw: ${e.message}")
            null
        } ?: return StepDecision.None("Model returned no output")

        val json = extractFirstJson(raw) ?: return StepDecision.None("No JSON in step decision output")
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return StepDecision.None("Malformed step decision JSON")
        }

        val rawAction = obj.optString("action", "").trim().lowercase()
        if (rawAction.isBlank()) return StepDecision.None("Step decision missing action")

        // Reuse the strict plan parser so the decision passes exactly the same
        // validation gates as a full plan (known action, valid params).
        val wrapped = """{"steps":[{"id":1,"seq":1,"desc":"next","action":"$rawAction","params":${obj.optJSONObject("params") ?: JSONObject()}}]}"""
        return when (val parsed = parser.parse(wrapped, request.userCommand)) {
            is PlanParseResult.Success -> {
                val action = parsed.plan.steps.first().action
                if (action is AgentAction.FinishTask || action is AgentAction.FailTask) {
                    StepDecision.None("Step decision returned a terminal action; use plan flow")
                } else {
                    StepDecision.Act(action, obj.optString("thought", "model-selected action"), fromModel = true)
                }
            }
            is PlanParseResult.Invalid -> StepDecision.None("Rejected step decision: ${parsed.reason}")
        }
    }

    fun fallbackPlan(command: String): TaskPlan = heuristicPlanner.toFallbackPlan(command)

    fun availableActions(): List<String> = availableActions

    private fun extractFirstJson(text: String): String? {
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

    private val TAG = "AgentPlanner"
}
