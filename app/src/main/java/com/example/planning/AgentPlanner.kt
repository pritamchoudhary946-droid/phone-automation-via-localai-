package com.example.planning

import android.util.Log
import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.model.ApiInferenceEngine
import com.example.model.HeuristicPlanner
import org.json.JSONArray
import org.json.JSONObject

/**
 * The reasoning brain. When the remote LLM API engine is ready it asks the real
 * model to interpret the command and produce a validated plan; otherwise it
 * returns an explicitly labelled basic fallback. It never fabricates a model
 * response and never reports model planning when the engine is not ready.
 */
class AgentPlanner(
    private val inferenceEngine: ApiInferenceEngine,
    private val parser: TaskPlanParser = TaskPlanParser(),
    private val heuristicPlanner: HeuristicPlanner = HeuristicPlanner(),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val maxOutputTokens: Int = 768,
    /** Live opt-in gate for the notification-triage capability (plan 9.9). */
    private val notificationTriageEnabled: () -> Boolean = { false }
) {

    /**
     * Read lazily per call, not captured once, so flipping the Settings toggle takes
     * effect on the next prompt: the opt-in `read_notifications` tool disappears from
     * the advertised catalogue (and its schema from [PlannerPromptBuilder]) while off.
     */
    private fun availableToolNames(): List<String> =
        PlannerPromptBuilder.availableActions(toolRegistry, notificationTriageEnabled())

    /** Hierarchical reflection-on-demand (plan 5.4), shared by the orchestrator. */
    private val reflector = Reflector(inferenceEngine)

    /**
     * Fills the current date/time when the caller did not already provide one, so the
     * model can resolve a relative time ("in 20 minutes", "tomorrow at 7") into the
     * absolute hour/minute that set_alarm needs. A caller-supplied value (tests) is
     * kept, which also makes the prompt deterministic in unit tests.
     */
    private fun PlanningRequest.withCurrentDateTime(): PlanningRequest =
        if (currentDateTime.isNotBlank()) this
        else copy(currentDateTime = PlannerPromptBuilder.formatDateTime(System.currentTimeMillis()))

    /**
     * When false (the default), a task is [PlanOutcome.Unavailable] instead of being
     * silently planned by the keyword fallback while the LLM API is not loaded
     * (plan 6.1: never quietly run keywords). A user-visible, explicitly enabled
     * degraded mode sets this true, and only then does the labelled BASIC fallback run.
     */
    var fallbackExplicit: Boolean = false

    /**
     * Produces a plan for [command]. Tries the real model with one bounded repair
     * attempt, then — only when the user has explicitly enabled the degraded mode —
     * falls back to the clearly labelled basic planner.
     */
    suspend fun plan(request: PlanningRequest): PlanOutcome {
        if (!inferenceEngine.isReady()) {
            return if (fallbackExplicit) {
                PlanOutcome.Planned(
                    plan = heuristicPlanner.toFallbackPlan(request.userCommand),
                    source = PlanSource.BASIC_FALLBACK
                )
            } else {
                // 6.1: BLOCK. An LLM API must be configured and healthy before a task runs; do not
                // silently substitute the keyword planner for the AI.
                PlanOutcome.Unavailable("No LLM API configured - add a provider and key in Setup")
            }
        }

        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            request.withCurrentDateTime(),
            notificationTriageEnabled()
        )
        var lastRaw = ""
        var lastReason = "unknown"

        repeat(2) { attempt ->
            // The second attempt appends an explicit correction block so the model sees
            // what was wrong instead of being handed the identical prompt again.
            val effectivePrompt = if (attempt == 0) prompt
                else prompt + PlannerPromptBuilder.buildRepairNote(lastRaw, lastReason)
            val raw = try {
                inferenceEngine.generate(effectivePrompt, PlannerPromptBuilder.SYSTEM_INSTRUCTION, maxOutputTokens)
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
                is PlanParseResult.Success -> return PlanOutcome.Planned(parsed.plan, PlanSource.MODEL)
                is PlanParseResult.Invalid -> {
                    lastReason = parsed.reason
                    Log.w(TAG, "Invalid plan (attempt ${attempt + 1}): ${parsed.reason}")
                }
            }
        }

        Log.w(TAG, "Model plan rejected after bounded repair: $lastReason")
        return if (fallbackExplicit) {
            val fallback = heuristicPlanner.toFallbackPlan(request.userCommand)
            PlanOutcome.Planned(fallback, PlanSource.BASIC_FALLBACK)
        } else {
            // Never silently downgrade a malformed model response into keyword
            // automation. The caller surfaces this as a clear planning failure.
            PlanOutcome.Rejected("Model output was invalid after repair: $lastReason", rawOutput = lastRaw)
        }
    }

    /**
     * Asks the model to choose the single next action given the fresh observation.
     * When [screenshotBytes] is provided (a tree-miss/blind screen, plan 7.4) the
     * vision-capable model receives the image alongside the text prompt.
     */
    suspend fun decideNextAction(request: StepDecisionRequest, screenshotBytes: ByteArray? = null): StepDecision {
        if (!inferenceEngine.isReady()) {
            return StepDecision.None("LLM API engine not ready")
        }
        val prompt = PlannerPromptBuilder.buildStepDecisionPrompt(request)
        val raw = try {
            if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
                inferenceEngine.generateWithImage(prompt, PlannerPromptBuilder.SYSTEM_INSTRUCTION, 256, screenshotBytes)
            } else {
                inferenceEngine.generate(prompt, PlannerPromptBuilder.SYSTEM_INSTRUCTION, maxOutputTokens = 256)
            }
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
        // validation gates as a full plan (known action, valid params). Build the
        // wrapper as real JSON rather than interpolating the model's strings, so a
        // quoted/escaped value in the model output cannot corrupt the wrapper.
        val wrapped = JSONObject().apply {
            put("steps", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1)
                    put("seq", 1)
                    put("desc", "next")
                    put("action", rawAction)
                    put("params", obj.optJSONObject("params") ?: JSONObject())
                })
            })
        }.toString()
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

    fun availableActions(): List<String> = availableToolNames()

    /**
     * Reflection-on-demand (plan 5.4): a short, bounded second opinion on the last
     * action and the current screen, asked only when the loop has hit a failure or a
     * stall. Delegates to the shared [Reflector]; never blank, never fabricating a
     * decision.
     */
    suspend fun reflect(request: ReflectionRequest): String = reflector.reflect(request)

    /**
     * Asks the model for ONE safe exploration probe (plan 5.6). Falls back to a plain
     * bounded scroll when the model is unavailable, so exploration still makes progress
     * without a model but never wanders. Only non-terminal actions are ever returned.
     */
    suspend fun exploreNextAction(request: ExplorationRequest): AgentAction {
        if (inferenceEngine.isReady()) {
            val raw = try {
                inferenceEngine.generate(
                    PlannerPromptBuilder.buildExplorationPrompt(request),
                    PlannerPromptBuilder.SYSTEM_INSTRUCTION,
                    maxOutputTokens = 256
                )
            } catch (e: Exception) {
                Log.w(TAG, "Exploration generate threw: ${e.message}")
                null
            }
            val action = parseSingleAction(raw, request.goal)
            if (action != null && action !is AgentAction.FinishTask && action !is AgentAction.FailTask) {
                return action
            }
        }
        return AgentAction.Scroll("down")
    }

    /** Parses a single action out of a model decision object, or null when unusable. */
    private fun parseSingleAction(raw: String?, goal: String): AgentAction? {
        if (raw.isNullOrBlank()) return null
        val json = extractFirstJson(raw) ?: return null
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val rawAction = obj.optString("action", "").trim().lowercase()
        if (rawAction.isBlank()) return null
        val wrapped = JSONObject().apply {
            put("steps", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1)
                    put("seq", 1)
                    put("desc", "probe")
                    put("action", rawAction)
                    put("params", obj.optJSONObject("params") ?: JSONObject())
                })
            })
        }.toString()
        return when (val parsed = parser.parse(wrapped, goal)) {
            is PlanParseResult.Success -> parsed.plan.steps.firstOrNull()?.action
            is PlanParseResult.Invalid -> null
        }
    }

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
