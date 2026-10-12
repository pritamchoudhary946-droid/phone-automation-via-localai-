package com.example.model

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.planning.AgentPlanner
import com.example.planning.PlanOutcome
import com.example.planning.PlanSource
import com.example.planning.PlanningRequest
import com.example.planning.PlannerPromptBuilder
import com.example.planning.StepDecision
import com.example.planning.StepDecisionRequest
import com.example.planning.TaskPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SystemResourceStats(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val usedRamMb: Long,
    val isLowMemory: Boolean,
    val modelLoaded: Boolean
)

/**
 * Backing model layer used by the UI and orchestrator.
 *
 * The reasoning runs through a remote LLM API (any provider the user configures with
 * their own key). When an API is configured and reachable it drives real planning;
 * otherwise it returns an explicitly labelled [PlanSource.BASIC_FALLBACK] plan so native
 * automation stays usable, or blocks honestly when no key is set and the user has not
 * enabled the degraded mode.
 */
class AgentEngine(
    private val context: Context,
    private val inferenceEngine: ApiInferenceEngine,
    private val planner: AgentPlanner,
    private val heuristicPlanner: HeuristicPlanner = HeuristicPlanner()
) {
    private val TAG = "AgentEngine"
    val toolRegistry = ToolRegistry()

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            inferenceEngine.ensureReady()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Engine initialization failed", e)
            false
        }
    }

    fun isReady(): Boolean = inferenceEngine.isReady()

    /**
     * Explicit, user-visible degraded mode. When false (default) a task blocks while no
     * LLM API is configured instead of silently running keywords.
     */
    fun setFallbackExplicit(enabled: Boolean) {
        planner.fallbackExplicit = enabled
    }

    /** True only when the user explicitly enabled degraded heuristic planning. */
    fun isFallbackExplicit(): Boolean = planner.fallbackExplicit

    fun engineState(): EngineState = inferenceEngine.state.value

    /** Observable engine lifecycle state for the UI. */
    fun engineStateFlow(): kotlinx.coroutines.flow.StateFlow<EngineState> = inferenceEngine.state

    /** True only when genuine remote-model inference will drive planning. */
    fun isModelActive(): Boolean = inferenceEngine.isReady()

    fun getResourceStats(): SystemResourceStats {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        return SystemResourceStats(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            usedRamMb = totalMb - availMb,
            isLowMemory = memInfo.lowMemory,
            modelLoaded = isReady()
        )
    }

    /**
     * Primary planning entry point. The API model produces the plan when configured and
     * ready; otherwise a clearly labelled basic fallback is returned (never reported as AI).
     */
    suspend fun planTask(
        goal: String,
        screenSummary: String,
        foregroundPackage: String?,
        historySummary: String,
        lastActionVerification: String?,
        remainingStepBudget: Int,
        memorySummary: String = "",
        retrievedContext: String = "",
        lessonsSummary: String = "",
        variableSummary: String = ""
    ): PlanOutcome {
        val request = PlanningRequest(
            userCommand = goal,
            desiredOutcome = goal,
            foregroundPackage = foregroundPackage,
            screenSummary = screenSummary,
            completedStepsSummary = historySummary,
            lastActionResult = lastActionVerification,
            remainingStepBudget = remainingStepBudget,
            availableActions = planner.availableActions(),
            memorySummary = memorySummary,
            retrievedContext = retrievedContext,
            lessonsSummary = lessonsSummary,
            variableSummary = variableSummary
        )
        return planner.plan(request)
    }

    /** Asks the model to select the single next action against a fresh observation. */
    suspend fun decideNextAction(
        goal: String,
        activeSubGoal: String,
        screenSummary: String,
        foregroundPackage: String?,
        historySummary: String,
        lastActionVerification: String?,
        remainingStepBudget: Int,
        variableSummary: String = "",
        screenshotBytes: ByteArray? = null
    ): StepDecision {
        val request = StepDecisionRequest(
            userCommand = goal,
            activeSubGoal = activeSubGoal,
            foregroundPackage = foregroundPackage,
            screenSummary = screenSummary,
            completedStepsSummary = historySummary,
            lastActionResult = lastActionVerification,
            remainingStepBudget = remainingStepBudget,
            availableActions = planner.availableActions(),
            variableSummary = variableSummary
        )
        return planner.decideNextAction(request, screenshotBytes)
    }

    fun fallbackPlan(goal: String): TaskPlan = planner.fallbackPlan(goal)

    /** Reflection-on-demand (plan 5.4), surfaced for the orchestrator's recovery cycle. */
    suspend fun reflect(request: com.example.planning.ReflectionRequest): String =
        planner.reflect(request)

    /** One bounded exploration probe (plan 5.6), surfaced for the orchestrator. */
    suspend fun exploreNextAction(request: com.example.planning.ExplorationRequest): AgentAction =
        planner.exploreNextAction(request)

    /**
     * Backward-compatible decision path used ONLY by the legacy diagnostic tests; it is
     * NOT part of the live execution loop. The live loop plans via [planTask] and steps
     * via [decideNextAction] through the orchestrator, which passes a real
     * `remainingStepBudget` and the observed `foregroundPackage`.
     *
     * The fixed `remainingStepBudget = 10` and `foregroundPackage = null` below are
     * deliberate values for this bounded diagnostic probe, not a claim about the live
     * budget - do not treat them as the loop's configuration.
     */
    suspend fun reasonAndPlan(
        goal: String,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): AgentDecision = withContext(Dispatchers.Default) {
        if (inferenceEngine.isReady()) {
            val decision = decideNextAction(
                goal = goal,
                activeSubGoal = goal,
                screenSummary = screenSummary,
                // Diagnostic probe: no foreground package is tracked on this path.
                foregroundPackage = null,
                historySummary = historySummary,
                lastActionVerification = lastActionVerification,
                // Diagnostic probe: a fixed bound, independent of the live step budget.
                remainingStepBudget = 10
            )
            if (decision is StepDecision.Act) {
                return@withContext AgentDecision(
                    thought = decision.thought,
                    action = decision.action,
                    rawOutput = "Thought: ${decision.thought}\nAction: model-selected"
                )
            }
        }
        heuristicPlanner.reasonAndPlan(goal, screenSummary, historySummary, lastActionVerification)
    }

    fun heuristicPlanner(): HeuristicPlanner = heuristicPlanner

    companion object {
        const val SYSTEM_PROMPT = PlannerPromptBuilder.SYSTEM_INSTRUCTION
    }
}
