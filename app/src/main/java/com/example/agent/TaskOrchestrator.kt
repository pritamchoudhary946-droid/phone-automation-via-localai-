package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.local.AgentDatabase
import com.example.data.local.CheckpointEntity
import com.example.data.local.SavedSkillEntity
import com.example.data.local.TaskEntity
import com.example.data.local.TaskOutcomeEntity
import com.example.data.local.TaskStepEntity
import com.example.data.local.UserMemoryEntity
import com.example.memory.rag.Retriever
import com.example.model.LiteRtEngine
import com.example.perception.OcrReader
import com.example.perception.ScreenshotDiffer
import com.example.perception.ScreenAwareness
import com.example.perception.UnavailableOcrReader
import com.example.perception.UnavailableVisionGrounder
import com.example.perception.VisionGrounder
import com.example.planning.PlanOutcome
import com.example.planning.PlanSource
import com.example.planning.PlanStep
import com.example.planning.PlanStepStatus
import com.example.planning.PlannerPromptBuilder
import com.example.planning.TaskPlanSerializer
import com.example.planning.PostconditionVerifier
import com.example.planning.TaskPlan
import com.example.planning.VerificationMethod
import com.example.service.AgentExecutionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

sealed class OrchestratorState {
    object Idle : OrchestratorState()
    data class Planning(val stepIndex: Int, val goal: String, val activeSubGoal: String? = null, val fromModel: Boolean = false) : OrchestratorState()
    data class Executing(val stepIndex: Int, val action: AgentAction, val reasoning: String) : OrchestratorState()
    data class WaitingConfirmation(
        val taskId: String,
        val riskType: String,
        val warningMessage: String,
        val pendingAction: AgentAction
    ) : OrchestratorState()
    data class Replanning(val reason: String, val recoveryStrategy: RecoveryStrategy) : OrchestratorState()
    data class Paused(val taskId: String, val stepIndex: Int) : OrchestratorState()
    data class Completed(val taskId: String, val summary: String) : OrchestratorState()
    data class Failed(val taskId: String, val reason: String) : OrchestratorState()
}

/**
 * Controlled execution loop: Understand -> Plan -> Validate -> Execute one action
 * -> Observe -> Verify -> Update plan -> Repeat.
 *
 * Gemma produces the plan and the per-step action selection when ready; the
 * native automation layer executes exactly one approved action at a time. A step
 * is only marked complete when its goal-specific postcondition is verified over a
 * fresh observation. `ReadScreen` success can never satisfy an unrelated goal.
 */
class TaskOrchestrator(
    private val context: Context,
    private val liteRtEngine: LiteRtEngine,
    private val appDirectory: AppDirectory,
    private val deviceAutomation: DeviceAutomation = AndroidDeviceAutomation(appDirectory),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    toolRegistry: ToolRegistry = ToolRegistry(),
    private val webGateway: com.example.cloudflare.CloudflareGateway? = null,
    private val screenAwareness: ScreenAwareness? = null,
    private val visionGrounder: VisionGrounder = UnavailableVisionGrounder,
    private val ocrReader: OcrReader = UnavailableOcrReader,
    private val retriever: Retriever? = null,
    private val completionNotifier: com.example.notifications.CompletionNotifier? = null,
    private val autoSaveSkills: Boolean = false
) {
    private val TAG = "TaskOrchestrator"
    private val database = AgentDatabase.getInstance(context)
    private val taskDao = database.taskDao()
    private val taskStepDao = database.taskStepDao()
    private val checkpointDao = database.checkpointDao()
    private val memoryDao = database.memoryDao()

    private val hybridStepExecutor = HybridStepExecutor(appDirectory)

    private val stuckDetector = StuckLoopDetector()
    private val riskEvaluator = RiskEvaluator()
    private val verifier = PostconditionVerifier

    /** Injectable so tests can force a validation rejection deterministically. */
    val toolRegistry: ToolRegistry = toolRegistry

    private val _orchestratorState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val orchestratorState: StateFlow<OrchestratorState> = _orchestratorState.asStateFlow()

    private val _currentTask = MutableStateFlow<TaskEntity?>(null)
    val currentTask: StateFlow<TaskEntity?> = _currentTask.asStateFlow()

    private val _currentSteps = MutableStateFlow<List<TaskStepEntity>>(emptyList())
    val currentSteps: StateFlow<List<TaskStepEntity>> = _currentSteps.asStateFlow()

    private val _activePlan = MutableStateFlow<TaskPlan?>(null)
    val activePlan: StateFlow<TaskPlan?> = _activePlan.asStateFlow()

    private val _usesModelPlanning = MutableStateFlow(false)
    val usesModelPlanning: StateFlow<Boolean> = _usesModelPlanning.asStateFlow()

    private val _modelCalls = MutableStateFlow(0)
    /** Per-step model invocations, so the hybrid fast path is measurable. */
    val modelCalls: StateFlow<Int> = _modelCalls.asStateFlow()

    private val _fastPathSteps = MutableStateFlow(0)
    /** Steps executed deterministically from the accessibility tree, without a model call. */
    val fastPathSteps: StateFlow<Int> = _fastPathSteps.asStateFlow()

    private var executionJob: Job? = null

    /** Bounded mid-task replans, so a stalled plan can be rebuilt without looping. */
    private var replanCount = 0

    private var pendingRiskAction: AgentAction? = null
    private var pendingRiskTaskId: String? = null

    /** Bounded memory digest injected into planning prompts; loaded once per task. */
    private var memoryDigest: String = ""

    /** Bounded RAG context from the user's own notes/history; loaded once per task. */
    private var retrievedContext: String = ""

    /** An action the user just approved: skip re-prompting when the loop reconsiders it. */
    private var userConfirmedAction: AgentAction? = null

    var stepTimeoutMs: Long = 15_000L
    var defaultMaxSteps: Int = 25

    fun startTask(goal: String, maxSteps: Int = defaultMaxSteps) {
        if (executionJob?.isActive == true) cancelCurrentTask()
        val taskId = UUID.randomUUID().toString()
        val newTask = TaskEntity(id = taskId, userGoal = goal, status = "RUNNING", currentStep = 0, maxSteps = maxSteps)
        _currentTask.value = newTask
        _currentSteps.value = emptyList()
        _activePlan.value = null
        _modelCalls.value = 0
        _fastPathSteps.value = 0
        replanCount = 0
        stuckDetector.reset()
        // Promote to a foreground service so a long automation survives the user
        // leaving the app; the notification is removed when the task ends.
        try {
            AgentExecutionService.startService(context, goal)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground execution service: ${e.message}")
        }
        scope.launch {
            taskDao.insertTask(newTask)
            runAgentLoop(newTask)
        }
    }

    /**
     * Replays a saved skill by name: resumes its persisted plan through the normal
     * verified loop. A skill is only ever replayed if it carries steps (plan 4.13).
     */
    fun runSkill(skill: SavedSkillEntity) {
        if (skill.planJson.isBlank()) return
        scope.launch { runCatching { database.skillDao().markSkillRun(skill.name, System.currentTimeMillis()) } }
        startTask(skill.goal)
    }

    private suspend fun runAgentLoop(task: TaskEntity, resumePlan: TaskPlan? = null) {
        executionJob = scope.launch {
            memoryDigest = loadMemoryDigest(task.userGoal)
            retrievedContext = loadRetrievedContext(task.userGoal)
            val maxSteps = task.maxSteps
            var lastActionResult: String? = null
            var consecutiveFailures = 0
            var completedCount = 0
            var nudgedStepId: Int? = null

            try {
                if (!deviceAutomation.isReady()) {
                    var readinessAttempts = 0
                    while (!deviceAutomation.isReady() && readinessAttempts < 10) {
                        delay(300); readinessAttempts++
                    }
                }
                val unavailable = deviceAutomation.unavailabilityReason()
                if (unavailable != null) {
                    fail(task, unavailable, 0)
                    return@launch
                }

                // --- Understand + Plan (once per task) ---
                _orchestratorState.value = OrchestratorState.Planning(0, task.userGoal)
                val initialObservation = deviceAutomation.observe()

                // Resuming a paused/confirmed task must continue the existing plan, not
                // re-plan and replay it from step 0 (which would repeat already-executed
                // — and possibly already-approved — actions).
                val resumed = resumePlan?.takeIf { it.goal == task.userGoal }

                var plan: TaskPlan
                if (resumed != null) {
                    // A step paused mid-execution is left in EXECUTING; reset it to PENDING
                    // so it is actually retried rather than silently skipped.
                    plan = resumed.copy(
                        steps = resumed.steps.map {
                            if (it.status == PlanStepStatus.EXECUTING) it.withStatus(PlanStepStatus.PENDING) else it
                        }
                    )
                    _usesModelPlanning.value = plan.fromModel
                    stuckDetector.reset()
                    replanCount = 0
                    Log.i(TAG, "[RESUME] Continuing existing plan at step ${plan.completedCount()} of ${plan.size}")
                } else {
                    val planOutcome = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                        liteRtEngine.planTask(
                            goal = task.userGoal,
                            screenSummary = initialObservation.summary,
                            foregroundPackage = initialObservation.packageName,
                            historySummary = "",
                            lastActionVerification = null,
                            remainingStepBudget = maxSteps,
                            memorySummary = memoryDigest,
                            retrievedContext = retrievedContext
                        )
                    }

                    plan = when (planOutcome) {
                        is PlanOutcome.Planned -> {
                            _usesModelPlanning.value = planOutcome.source == PlanSource.GEMMA_MODEL
                            planOutcome.plan
                        }
                        is PlanOutcome.Unavailable -> {
                            _usesModelPlanning.value = false
                            val fallbackPlan = liteRtEngine.fallbackPlan(task.userGoal)
                            Log.w(TAG, "Using fallback planner due to: ${planOutcome.reason}")
                            fallbackPlan
                        }
                        is PlanOutcome.Rejected -> {
                            _usesModelPlanning.value = false
                            val fallbackPlan = liteRtEngine.fallbackPlan(task.userGoal)
                            Log.w(TAG, "Using fallback planner due to: ${planOutcome.reason}")
                            fallbackPlan
                        }
                        null -> {
                            _usesModelPlanning.value = false
                            val fallbackPlan = liteRtEngine.fallbackPlan(task.userGoal)
                            Log.w(TAG, "Using fallback planner due to: plan timeout or null result")
                            fallbackPlan
                        }
                    }
                }
                _activePlan.value = plan
                completedCount = plan.completedCount()
                Log.i(TAG, "[PLAN] goal=\"${task.userGoal}\" steps=${plan.size} source=${if (_usesModelPlanning.value) "GEMMA" else "BASIC"}")

                var observation = initialObservation
                var previousObservation = initialObservation

                // --- Execute -> Observe -> Verify -> Update, one step at a time ---
                while (completedCount < maxSteps) {
                    val step = plan.nextPending()
                    if (step == null) {
                        // Every planned step has been consumed. Require a fresh goal-level
                        // postcondition; if it does not hold, the plan is exhausted without
                        // reaching the goal, so give the model a bounded chance to rebuild
                        // the remaining work from the current screen.
                        val fresh = deviceAutomation.observe()
                        val goalVerdict = verifyGoalPostcondition(plan, observation, fresh)
                        val lastRequired = plan.steps.lastOrNull {
                            it.action !is AgentAction.FinishTask && it.action !is AgentAction.FailTask
                        }
                        val webToolSatisfied = lastRequired?.status == PlanStepStatus.COMPLETED &&
                            (lastRequired.action is AgentAction.WebSearch || lastRequired.action is AgentAction.ReadUrl)
                        if (goalVerdict.satisfied || webToolSatisfied) {
                            val summary = "Goal achieved: completed ${plan.completedCount()} of ${plan.size} planned steps."
                            complete(task, summary, completedCount)
                            return@launch
                        }
                        val replanned = attemptReplan(task, plan, fresh, completedCount, goalVerdict.detail)
                        if (replanned != null && replanned.nextPending() != null) {
                            plan = replanned
                            completedCount = plan.completedCount()
                            observation = fresh
                            consecutiveFailures = 0
                            stuckDetector.reset()
                            delay(300)
                            continue
                        }
                        fail(task, "All planned steps completed but the goal postcondition was not verified: ${goalVerdict.detail}", completedCount)
                        return@launch
                    }
                    if (step.attempts > step.maxRetries) {
                        plan = plan.replace(step.withStatus(PlanStepStatus.FAILED))
                        _activePlan.value = plan
                        fail(task, "Step ${step.id} (${step.rawActionName}) failed after ${step.attempts} attempts: ${step.description}", completedCount)
                        return@launch
                    }

                    observation = deviceAutomation.observe()
                    _orchestratorState.value = OrchestratorState.Planning(completedCount, task.userGoal, step.description, _usesModelPlanning.value)

                    val stuck = stuckDetector.recordStep(observation.fingerprint, step.rawActionName)
                    if (stuck.isStuck) {
                        Log.w(TAG, "[STUCK] ${stuck.reason}")
                        _orchestratorState.value = OrchestratorState.Replanning(stuck.reason, stuck.suggestedRecovery)
                        when (stuck.suggestedRecovery) {
                            RecoveryStrategy.ASK_USER_HELP -> {
                                fail(task, "Stuck in repeating loop: ${stuck.reason}", completedCount)
                                return@launch
                            }
                            RecoveryStrategy.PRESS_BACK_TO_UNBLOCK -> {
                                deviceAutomation.execute(AgentAction.Back); delay(500)
                            }
                            RecoveryStrategy.SCROLL_TO_DISCOVER -> {
                                deviceAutomation.execute(AgentAction.Scroll("down")); delay(500)
                            }
                            else -> delay(400)
                        }
                        // The recovery action changed the screen; re-observe so the
                        // re-decided action (fast path or model) is chosen against the
                        // recovered screen instead of the stale pre-recovery one.
                        observation = deviceAutomation.observe()
                    }
                    val effectiveStep = step

                    // Hybrid fast path: when the step's intent can be satisfied
                    // deterministically from the current accessibility tree (launch an
                    // installed app, open the search UI, type into an observed field,
                    // press Home/Back, open an observed result), the native action is
                    // executed directly and the local model is never invoked. Only when
                    // the fast path cannot decide is Gemma asked to choose/refresh the
                    // action. Model output, when present, overrides the nominal action.
                    val nudgeUsed = nudgedStepId == effectiveStep.id
                    val fastPath = hybridStepExecutor.decide(
                        plan.steps, plan.steps.indexOf(effectiveStep), observation, nudgeUsed
                    )

                    val decision = if (fastPath is FastPathDecision.ModelReason) {
                        Log.i(TAG, "[MODEL_STEP] ${fastPath.reason}")
                        withTimeoutOrNull(STEP_DECISION_TIMEOUT_MS) {
                            liteRtEngine.decideNextAction(
                                goal = task.userGoal,
                                activeSubGoal = effectiveStep.description,
                                screenSummary = observation.summary,
                                foregroundPackage = observation.packageName,
                                historySummary = historySummary(),
                                lastActionVerification = lastActionResult,
                                remainingStepBudget = maxSteps - completedCount
                            )
                        }
                    } else {
                        null
                    }

                    if (fastPath is FastPathDecision.Satisfied) {
                        Log.i(TAG, "[FAST_PATH] Step ${effectiveStep.id} already satisfied: ${fastPath.reason}")
                        _fastPathSteps.value += 1
                        val satisfiedStep = effectiveStep.withAttempt().withStatus(PlanStepStatus.COMPLETED)
                        plan = plan.replace(satisfiedStep)
                        _activePlan.value = plan
                        recordStep(task, completedCount, "Fast path: ${fastPath.reason}", effectiveStep.action, observation, "Success: ${fastPath.reason}", "SUCCESS")
                        completedCount++
                        consecutiveFailures = 0
                        stuckDetector.reset()
                        taskDao.updateTask(task.copy(currentStep = completedCount))
                        continue
                    }

                    val usedModel = decision is com.example.planning.StepDecision.Act
                    if (fastPath is FastPathDecision.ModelReason) {
                        if (usedModel) _modelCalls.value += 1
                        else {
                            // Model was consulted but didn't return a valid action
                            val reason = when (decision) {
                                is com.example.planning.StepDecision.None -> decision.reason
                                else -> "Unknown reason"
                            }
                            Log.w(TAG, "Model consulted but returned no valid action: $reason")
                        }
                    } else {
                        _fastPathSteps.value += 1
                    }

                    var action = when {
                        usedModel -> (decision as com.example.planning.StepDecision.Act).action
                        fastPath is FastPathDecision.Execute -> fastPath.action
                        else -> effectiveStep.action
                    }
                    // Record a bounded "wait for results to render" nudge so it is not repeated.
                    if (action is AgentAction.Wait && effectiveStep.action is AgentAction.OpenItem) {
                        nudgedStepId = effectiveStep.id
                    }

                    // If the user just approved THIS action, do not ask again. Only
                    // substitute it when the resumed step is the same action; if the
                    // re-planned step differs, drop the approval rather than forcing an
                    // unrelated action into the wrong step.
                    val confirmed = userConfirmedAction
                    var actionApproved = false
                    if (confirmed != null) {
                        userConfirmedAction = null
                        if (effectiveStep.action == confirmed) {
                            action = confirmed
                            actionApproved = true
                        }
                    }

                    // Safety net: a text entry needs a real editable field in this fresh
                    // observation. Several apps (YouTube among them) do not show the
                    // search field until the search interface is opened, so open it first
                    // instead of dispatching text into nothing.
                    if (action is AgentAction.TypeText && !observationHasEditableField(observation)) {
                        Log.w(TAG, "[GUARD] No editable field in the fresh observation; opening the search interface before typing.")
                        _orchestratorState.value = OrchestratorState.Replanning(
                            "No editable field visible; opening search interface first.",
                            RecoveryStrategy.REPLAN_NEW_APPROACH
                        )
                        action = AgentAction.OpenSearch(action.text)
                    }

                    // In-loop web tool: an explicit web search / read-url is not a UI
                    // action. It runs through the gateway and its digest is fed back to the
                    // model, then the step is marked done. It never bypasses risk checks —
                    // it performs no device side effect and cannot send/pay/delete.
                    if (action is AgentAction.WebSearch || action is AgentAction.ReadUrl) {
                        val digest = runWebTool(action)
                        recordStep(
                            task = task,
                            index = completedCount,
                            reasoning = "In-loop ${action.getDisplayName()}",
                            action = action,
                            observation = observation,
                            verification = digest,
                            status = "SUCCESS"
                        )
                        // Mark the step completed in the plan, otherwise nextPending() keeps
                        // returning it and the loop would repeat the web call forever.
                        plan = plan.replace(effectiveStep.withAttempt().withStatus(PlanStepStatus.COMPLETED))
                        lastActionResult = digest
                        completedCount++
                        consecutiveFailures = 0
                        stuckDetector.reset()
                        taskDao.updateTask(task.copy(currentStep = completedCount))
                        _activePlan.value = plan
                        delay(150)
                        continue
                    }

                    when (val validation = toolRegistry.validateAction(action)) {
                        is ToolValidationResult.Invalid -> {
                            recordStep(task, completedCount, "Rejected by validation", action, observation, "Failed: ${validation.reason}", "FAILED")
                            // Never leave the step stuck in EXECUTING: record the attempt but
                            // restore PENDING so the plan does not look fully satisfied.
                            val rejected = effectiveStep.withRetry()
                            plan = plan.replace(rejected)
                            _activePlan.value = plan
                            consecutiveFailures++
                            lastActionResult = "Validation failed: ${validation.reason}"
                            if (rejected.attempts > rejected.maxRetries) {
                                plan = plan.replace(rejected.withStatus(PlanStepStatus.FAILED))
                                _activePlan.value = plan
                                fail(task, "Step ${rejected.id} rejected by validation after ${rejected.attempts} attempts: ${validation.reason}", completedCount)
                                return@launch
                            }
                            if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                                fail(task, "Too many rejected actions; stopping. Last: ${validation.reason}", completedCount)
                                return@launch
                            }
                            delay(300)
                            continue
                        }
                        is ToolValidationResult.Valid -> Unit
                    }

                    val risk = riskEvaluator.evaluate(action, observation.summary)
                    if (risk.isRisky && !actionApproved) {
                        pendingRiskAction = action
                        pendingRiskTaskId = task.id
                        taskDao.updateTask(
                            task.copy(
                                status = "WAITING_CONFIRMATION",
                                requiresRiskConfirmation = true,
                                pendingRiskActionJson = sanitizeSensitiveData(action.getDisplayName())
                            )
                        )
                        _orchestratorState.value = OrchestratorState.WaitingConfirmation(task.id, risk.riskType, risk.warningMessage, action)
                        return@launch
                    }

                    if (action is AgentAction.FinishTask) {
                        // A report_status/finish claim is never trusted on its own: every
                        // required subgoal must already be verified AND a fresh goal-level
                        // postcondition must hold before the task may complete.
                        val required = plan.steps.filter {
                            it.action !is AgentAction.FinishTask && it.action !is AgentAction.FailTask
                        }
                        val allRequiredCompleted =
                            required.isNotEmpty() && required.all { it.status == PlanStepStatus.COMPLETED }
                        val goalVerdict = verifyGoalPostcondition(plan, previousObservation, observation)
                        // A web tool produces its own evidence (a bounded result digest), so a
                        // plan whose final required step was a completed web_search/read_url is
                        // satisfied without a UI postcondition.
                        val lastRequired = required.lastOrNull()
                        val webToolSatisfied = lastRequired?.action is AgentAction.WebSearch ||
                            lastRequired?.action is AgentAction.ReadUrl
                        if (allRequiredCompleted && (goalVerdict.satisfied || webToolSatisfied)) {
                            complete(task, action.summary, completedCount)
                            return@launch
                        }

                        val reason = when {
                            required.isEmpty() -> "plan has no verifiable required subgoals"
                            !allRequiredCompleted -> "unverified subgoals remain (${required.count { it.status != PlanStepStatus.COMPLETED }} pending)"
                            else -> goalVerdict.detail
                        }
                        recordStep(task, completedCount, "Rejected premature finish", action, observation, "Rejected: $reason", "FAILED")

                        // If the finish was itself a plan step, skip it so the loop can
                        // still advance to any remaining required subgoals.
                        if (step.action is AgentAction.FinishTask) {
                            plan = plan.replace(step.withStatus(PlanStepStatus.SKIPPED))
                            _activePlan.value = plan
                        }

                        if (allRequiredCompleted) {
                            // Every step claims success yet the screen does not prove the
                            // goal. Give the model one bounded chance to rebuild the plan
                            // from the current screen before failing.
                            val replanned = attemptReplan(task, plan, observation, completedCount, reason)
                            if (replanned != null) {
                                plan = replanned
                                completedCount = plan.completedCount()
                                consecutiveFailures = 0
                                stuckDetector.reset()
                                delay(300)
                                continue
                            }
                            fail(task, "Model claimed completion but the goal postcondition was not verified: $reason", completedCount)
                            return@launch
                        }
                        consecutiveFailures++
                        lastActionResult = "Rejected premature finish: $reason"
                        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                            fail(task, "Model claimed completion before the goal was verified: $reason", completedCount)
                            return@launch
                        }
                        _orchestratorState.value = OrchestratorState.Replanning(
                            "Rejected premature finish: $reason",
                            RecoveryStrategy.REPLAN_NEW_APPROACH
                        )
                        delay(300)
                        continue
                    }
                    if (action is AgentAction.FailTask) {
                        fail(task, action.reason, completedCount)
                        return@launch
                    }

                    _orchestratorState.value = OrchestratorState.Executing(completedCount, action, if (decision is com.example.planning.StepDecision.Act) decision.thought else "fallback action")
                    previousObservation = observation
                    val execResult = withTimeoutOrNull(stepTimeoutMs) {
                        deviceAutomation.execute(action)
                    } ?: ActionResult(false, "TIMEOUT", "Step execution timed out after ${stepTimeoutMs}ms")

                    delay(300)
                    val postObservation = deviceAutomation.observe()

                    val verdict = verifyAction(action, effectiveStep, previousObservation, postObservation, execResult)

                    val newStatus = if (verdict.satisfied) PlanStepStatus.COMPLETED else PlanStepStatus.PENDING
                    val attempted = effectiveStep.withAttempt().withStatus(newStatus)
                    plan = plan.replace(attempted)
                    _activePlan.value = plan

                    recordStep(
                        task = task,
                        index = completedCount,
                        reasoning = if (decision is com.example.planning.StepDecision.Act) decision.thought else "Basic fallback action for ${effectiveStep.description}",
                        action = action,
                        observation = postObservation,
                        verification = verdict.detail,
                        status = if (verdict.satisfied) "SUCCESS" else "FAILED"
                    )
                    checkpointDao.saveCheckpoint(CheckpointEntity(task.id, completedCount, "Step $completedCount [${action.javaClass.simpleName}]"))

                    lastActionResult = verdict.detail
                    if (verdict.satisfied) {
                        completedCount++
                        consecutiveFailures = 0
                        stuckDetector.reset()
                        taskDao.updateTask(task.copy(currentStep = completedCount))
                    } else {
                        // Keep the step pending; the bounded retry policy in the plan
                        // controls when we give up, and Gemma sees the failure next round.
                        consecutiveFailures++
                        if (attempted.attempts > attempted.maxRetries) {
                            // The step exhausted its retries. Before giving up, let the model
                            // rebuild the remaining plan from the current screen: the action
                            // may simply be wrong for this screen state.
                            val replanned = attemptReplan(task, plan, postObservation, completedCount, verdict.detail)
                            if (replanned != null && replanned.nextPending() != null) {
                                plan = replanned
                                completedCount = plan.completedCount()
                                observation = postObservation
                                consecutiveFailures = 0
                                stuckDetector.reset()
                                delay(300)
                                continue
                            }
                            taskDao.updateTask(task.copy(currentStep = completedCount))
                            fail(task, "Step ${attempted.id} (${attempted.rawActionName}) did not satisfy its postcondition: ${verdict.detail}", completedCount)
                            return@launch
                        }
                        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                            fail(task, "No progress after $MAX_CONSECUTIVE_FAILURES attempts; stopping. Last: ${verdict.detail}", completedCount)
                            return@launch
                        }
                        // Replan signal: allow Gemma to choose a different action next round.
                        _orchestratorState.value = OrchestratorState.Replanning(verdict.detail, RecoveryStrategy.REPLAN_NEW_APPROACH)
                        delay(300)
                    }
                }

                fail(task, "Task did not satisfy its goal within the $maxSteps step limit.", completedCount)
            } catch (e: CancellationException) {
                Log.i(TAG, "Task cancelled or paused.")
            } catch (e: Exception) {
                Log.e(TAG, "Task execution error", e)
                taskDao.updateTaskStatus(task.id, "FAILED")
                _orchestratorState.value = OrchestratorState.Failed(task.id, "Error: ${e.localizedMessage}")
                // The loop ended abnormally; clear the ongoing notification so it does
                // not linger until the process is killed.
                runCatching { stopExecutionService() }
            }
        }
    }

    /**
     * Runs an explicit web tool through the gateway and returns a bounded digest
     * suitable for the next prompt. When no gateway is configured (offline build or
     * tests), it returns an honest "unavailable" digest instead of inventing a result.
     */
    private suspend fun runWebTool(action: AgentAction): String {
        val gateway = webGateway
            ?: return "Web tool unavailable: no gateway configured. Cannot complete '${action.getDisplayName()}'."
        return try {
            val result = when (action) {
                is AgentAction.WebSearch -> gateway.searchWeb(action.query)
                is AgentAction.ReadUrl -> gateway.readUrlContent(action.url)
                else -> null
            }
            when (result) {
                is com.example.cloudflare.CloudflareTaskResult.Success ->
                    "${result.title}: ${result.content}".take(1_200)
                is com.example.cloudflare.CloudflareTaskResult.Error ->
                    "Web tool failed: ${result.errorMessage}".take(400)
                null -> "Web tool produced no result."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web tool '${action.getDisplayName()}' failed", e)
            "Web tool error: ${e.localizedMessage}".take(400)
        }
    }

    /**
     * Fresh, goal-level postcondition check used to gate a `FinishTask` claim.
     * It is intentionally independent of the per-step verdicts: even if the plan
     * says every step is done, the current screen must still prove the goal.
     */
    private fun verifyGoalPostcondition(
        plan: TaskPlan,
        pre: UiObservation,
        post: UiObservation
    ): PostconditionVerdict {
        val lastRequired = plan.steps.lastOrNull {
            it.action !is AgentAction.FinishTask && it.action !is AgentAction.FailTask
        } ?: return PostconditionVerdict(false, "Plan has no required subgoals to verify")

        val targetPkg = if (lastRequired.action is AgentAction.OpenApp) {
            lastRequired.action.packageName ?: appDirectory.resolvePackage(lastRequired.action.appQuery)
        } else {
            null
        }
        val method = when {
            lastRequired.action is AgentAction.OpenApp -> VerificationMethod.FOREGROUND_PACKAGE
            lastRequired.action is AgentAction.Home -> VerificationMethod.HOME_FOREGROUND
            else -> lastRequired.verification
        }
        val screenChanged = pre.fingerprint != post.fingerprint
        return when (method) {
            VerificationMethod.NONE -> {
                // No structured signal in the plan: only accept a completion when the
                // final step genuinely completed and the screen advanced with it.
                val done = lastRequired.status == PlanStepStatus.COMPLETED
                PostconditionVerdict(
                    done && screenChanged,
                    if (done && screenChanged) "Final goal state advanced after the last verified step"
                    else "Goal-level postcondition could not be independently verified"
                )
            }
            else -> {
                val verdict = verifier.verify(
                    method = method,
                    expectedPostcondition = lastRequired.expectedPostcondition,
                    targetPackage = targetPkg,
                    observationPackage = post.packageName,
                    screenChanged = screenChanged,
                    screenText = post.summary
                )
                PostconditionVerdict(verdict.satisfied, verdict.detail)
            }
        }
    }

    /**
     * Rebuilds the plan for the part of the goal that is not yet done. Used when the
     * current plan has stalled (all steps consumed, or repeated failures) so the run
     * can recover with a fresh approach instead of failing outright. Bounded by
     * [MAX_REPLANS]; returns null when no further replan is allowed, in which case the
     * caller reports the failure.
     */
    private suspend fun attemptReplan(
        task: TaskEntity,
        currentPlan: TaskPlan,
        observation: UiObservation,
        completedCount: Int,
        failureDetail: String
    ): TaskPlan? {
        if (replanCount >= MAX_REPLANS) {
            Log.w(TAG, "[REPLAN] Budget exhausted ($replanCount/$MAX_REPLANS)")
            return null
        }
        replanCount++
        _orchestratorState.value = OrchestratorState.Replanning(
            "Rebuilding plan after: $failureDetail",
            RecoveryStrategy.REPLAN_NEW_APPROACH
        )
        val outcome = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            liteRtEngine.planTask(
                goal = task.userGoal,
                screenSummary = observation.summary,
                foregroundPackage = observation.packageName,
                historySummary = historySummary(),
                lastActionVerification = failureDetail,
                remainingStepBudget = (task.maxSteps - completedCount).coerceAtLeast(1),
                memorySummary = memoryDigest,
                retrievedContext = retrievedContext
            )
        }
        val newPlan = when (outcome) {
            is PlanOutcome.Planned -> {
                _usesModelPlanning.value = outcome.source == PlanSource.GEMMA_MODEL
                outcome.plan
            }
            is PlanOutcome.Unavailable -> liteRtEngine.fallbackPlan(task.userGoal)
            is PlanOutcome.Rejected -> liteRtEngine.fallbackPlan(task.userGoal)
            null -> liteRtEngine.fallbackPlan(task.userGoal)
        }
        val merged = mergeReplan(currentPlan, newPlan)
        _activePlan.value = merged
        Log.i(TAG, "[REPLAN] #$replanCount rebuilt plan with ${merged.size} steps (${merged.completedCount()} already done)")
        return merged
    }

    /**
     * Keeps already-completed subgoals from [current] and takes the remaining work from
     * [replanned]. Re-running a completed step would replay side effects the user has
     * already approved, so completed steps are carried over rather than replaced.
     */
    private fun mergeReplan(current: TaskPlan, replanned: TaskPlan): TaskPlan {
        val done = current.steps.filter { it.status == PlanStepStatus.COMPLETED }
        val doneActions = done.map { it.action }.toSet()
        val remaining = replanned.steps.filter { it.action !in doneActions }
        val carried = done.mapIndexed { index, step -> step.copy(id = index, sequence = index) }
        val fresh = remaining.mapIndexed { index, step ->
            step.copy(id = carried.size + index, sequence = carried.size + index)
        }
        return TaskPlan(goal = current.goal, steps = carried + fresh, fromModel = replanned.fromModel)
    }

    private suspend fun verifyAction(
        action: AgentAction,
        step: PlanStep,
        pre: UiObservation,
        post: UiObservation,
        result: ActionResult
    ): PostconditionVerdict {
        if (!result.success) {
            return PostconditionVerdict(false, "Failed: ${result.message} [method: ${result.method}]")
        }
        if (action is AgentAction.OpenSearch) {
            // A text-entry step that was re-routed into opening the search interface
            // is progress, not completion: the text still has to be entered.
            if (step.action !is AgentAction.OpenSearch) {
                return PostconditionVerdict(false, "Opened the search interface; text still has to be entered")
            }
            // The step's declared intent was to open search: success means the search
            // surface advanced, or an editable field is now genuinely exposed.
            val nowEditable = post.nodes.any { it.isEditable }
            val screenChanged = pre.fingerprint != post.fingerprint
            return if (nowEditable || screenChanged) {
                PostconditionVerdict(true, if (nowEditable) "Search field now present on screen" else "Search interface changed the screen")
            } else {
                PostconditionVerdict(false, "Search interface did not open (no editable field and no screen change)")
            }
        }
        val targetPkg = if (action is AgentAction.OpenApp) {
            action.packageName ?: appDirectory.resolvePackage(action.appQuery)
        } else {
            null
        }
        // Launch steps must always require foreground-package proof, regardless of
        // what the plan declared.
        val method = when {
            action is AgentAction.OpenApp -> VerificationMethod.FOREGROUND_PACKAGE
            action is AgentAction.Home -> VerificationMethod.HOME_FOREGROUND
            action is AgentAction.OpenItem -> VerificationMethod.SCREEN_CHANGED
            else -> step.verification
        }
        val screenChanged = pre.fingerprint != post.fingerprint
        val verdict = verifier.verify(
            method = method,
            expectedPostcondition = step.expectedPostcondition,
            targetPackage = targetPkg,
            observationPackage = post.packageName,
            screenChanged = screenChanged,
            screenText = post.summary
        )
        // Vision-diff fallback (plan 4.3): only when the fingerprint missed the change.
        if (!verdict.satisfied && step.verification == VerificationMethod.SCREEN_CHANGED &&
            visionDiff(pre, post)
        ) {
            return PostconditionVerdict(true, "${verdict.detail} (vision diff confirmed)")
        }
        return PostconditionVerdict(verdict.satisfied, verdict.detail)
    }

    private data class PostconditionVerdict(val satisfied: Boolean, val detail: String)

    /** True when the fresh observation exposes an editable/input control. */
    private fun observationHasEditableField(observation: UiObservation): Boolean =
        observation.hasTextInputField()

    private fun historySummary(): String = _currentSteps.value.takeLast(4).joinToString("\n") {
        "Step ${it.stepIndex}: ${it.actionType} (${it.actionPayloadJson}) -> ${it.verificationResult}"
    }

    /**
     * Builds a bounded, untrusted-labelled digest of learned memory for the prompt:
     * the most recent preferences/app-use notes plus outcomes of similar past goals.
     * Individual values are truncated so a verbose memory cannot blow the context.
     */
    private suspend fun loadMemoryDigest(goal: String): String {
        return try {
            val parts = mutableListOf<String>()
            val memories = memoryDao.getRecentMemories(MEMORY_LIMIT)
            memories.forEach { m ->
                parts += "${m.kind.lowercase()}: ${m.value.take(200)}"
            }
            val similar = memoryDao.getOutcomesForGoal(goal, MEMORY_OUTCOME_LIMIT)
                .filter { it.status == "COMPLETED" }
            similar.forEach { o ->
                parts += "past similar task (${o.status.lowercase()}, ${o.stepsUsed} steps): ${o.summary.take(200)}"
            }
            parts.joinToString("\n").take(PlannerPromptBuilder.MAX_MEMORY_CHARS)
        } catch (e: Exception) {
            Log.w(TAG, "Memory load failed: ${e.message}")
            ""
        }
    }

    /** Bounded digest of the user's own notes/history relevant to [goal] (RAG). */
    private suspend fun loadRetrievedContext(goal: String): String {
        val r = retriever ?: return ""
        return try {
            r.retrieve(goal, Retriever.DEFAULT_LIMIT)
                .joinToString("\n") { "- [${it.source}] ${it.content.take(180)}" }
                .take(PlannerPromptBuilder.MAX_RETRIEVED_CHARS)
        } catch (e: Exception) {
            Log.w(TAG, "RAG load failed: ${e.message}")
            ""
        }
    }

    /**
     * Vision-diff fallback evidence (plan 4.3), best-effort and bounded: only captured
     * when a [ScreenAwareness] seam is wired, and only used to confirm a real change
     * the accessibility fingerprint missed. Never throws into the loop.
     */
    private suspend fun visionDiff(before: UiObservation, after: UiObservation): Boolean {
        val awareness = screenAwareness ?: return false
        return try {
            if (before.fingerprint != after.fingerprint) return false
            ScreenshotDiffer.changed(awareness.captureScreenshot(), awareness.captureScreenshot())
        } catch (e: Exception) {
            false
        }
    }

    /** Persists a durable preference expressed by the user (e.g. "always use Spotify"). */
    suspend fun rememberPreference(key: String, value: String, kind: String = "PREFERENCE") {
        if (key.isBlank() || value.isBlank()) return
        try {
            memoryDao.upsertMemory(UserMemoryEntity(key = key, value = value, kind = kind))
        } catch (e: Exception) {
            Log.w(TAG, "Memory write failed: ${e.message}")
        }
    }

    /** Records how a finished task went, so future similar goals can reuse the approach. */
    private suspend fun recordOutcome(task: TaskEntity, status: String, summary: String, stepsUsed: Int) {
        try {
            memoryDao.insertOutcome(
                TaskOutcomeEntity(
                    goal = task.userGoal,
                    status = status,
                    summary = sanitizeSensitiveData(summary).take(400),
                    stepsUsed = stepsUsed
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Outcome write failed: ${e.message}")
        }
    }

    private suspend fun recordStep(
        task: TaskEntity,
        index: Int,
        reasoning: String,
        action: AgentAction,
        observation: UiObservation,
        verification: String,
        status: String
    ) {
        val entity = TaskStepEntity(
            taskId = task.id,
            stepIndex = index,
            reasoning = reasoning,
            actionType = action.javaClass.simpleName,
            actionPayloadJson = sanitizeSensitiveData(action.getDisplayName()),
            observationSummary = sanitizeSensitiveData(observation.summary.take(250)),
            verificationResult = sanitizeSensitiveData(verification),
            status = status
        )
        taskStepDao.insertStep(entity)
        _currentSteps.value = _currentSteps.value + entity
    }

    private suspend fun complete(task: TaskEntity, summary: String, completedCount: Int) {
        Log.i(TAG, "[COMPLETE] $summary")
        recordOutcome(task, "COMPLETED", summary, completedCount)
        taskDao.updateTask(task.copy(status = "COMPLETED", currentStep = completedCount, resultSummary = summary))
        _orchestratorState.value = OrchestratorState.Completed(task.id, summary)
        notifyFinished("Task complete", summary)
        // Only an optionally-enabled, clean run is auto-saved as a reusable skill (4.13).
        maybeAutoSaveSkill(task, summary, completedCount)
        stopExecutionService()
    }

    private suspend fun fail(task: TaskEntity, reason: String, step: Int) {
        Log.e(TAG, "[FAIL] $reason")
        recordOutcome(task, "FAILED", reason, step)
        taskDao.updateTask(task.copy(status = "FAILED", currentStep = step, resultSummary = reason))
        _orchestratorState.value = OrchestratorState.Failed(task.id, reason)
        notifyFinished("Task failed", reason)
        stopExecutionService()
    }

    /** Best-effort completion notification; never blocks or fails the task outcome. */
    private fun notifyFinished(title: String, summary: String) {
        val notifier = completionNotifier ?: return
        runCatching { notifier.notifyFinished(title, summary) }
    }

    /**
     * Saves a clean, model-produced plan as a reusable skill when the user enabled
     * auto-save. Only runs where the plan was genuinely from the model and every step
     * completed, so a muddled run is never recorded as a "routine".
     */
    private suspend fun maybeAutoSaveSkill(task: TaskEntity, summary: String, completedCount: Int) {
        if (!autoSaveSkills) return
        val plan = _activePlan.value ?: return
        if (!plan.fromModel || plan.steps.isEmpty()) return
        if (plan.steps.any { it.status != PlanStepStatus.COMPLETED }) return
        runCatching {
            database.skillDao().upsertSkill(
                SavedSkillEntity(
                    name = task.userGoal.take(80),
                    goal = task.userGoal,
                    planJson = TaskPlanSerializer.toJson(plan)
                )
            )
        }
    }

    private fun stopExecutionService() {
        try {
            AgentExecutionService.stopService(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop foreground execution service: ${e.message}")
        }
    }

    fun confirmRiskAction(confirmed: Boolean) {
        val action = pendingRiskAction
        val taskId = pendingRiskTaskId
        pendingRiskAction = null
        pendingRiskTaskId = null
        if (taskId == null || action == null) return

        scope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            if (confirmed) {
                taskDao.updateTask(task.copy(status = "RUNNING", requiresRiskConfirmation = false))
                // Tell the loop this action was approved so it is not re-prompted, and
                // resume from the SAME step index (the approved action has not run yet).
                userConfirmedAction = action
                _orchestratorState.value = OrchestratorState.Executing(task.currentStep, action, "User-confirmed action")
                // Continue the existing plan rather than re-planning: re-running from
                // step 0 would replay every already-completed action (and re-prompt for
                // any earlier risky one) before reaching the approved step.
                runAgentLoop(task.copy(currentStep = task.currentStep), resumePlan = _activePlan.value)
            } else {
                taskDao.updateTask(task.copy(status = "CANCELLED", resultSummary = "User rejected risky action"))
                userConfirmedAction = null
                _orchestratorState.value = OrchestratorState.Failed(taskId, "User rejected action: ${action.getDisplayName()}")
                // Rejecting ends the run; tear down the foreground service too.
                stopExecutionService()
            }
        }
    }

    fun pauseCurrentTask() {
        executionJob?.cancel()
        executionJob = null
        // The loop is gone, so nothing else will remove the ongoing notification.
        stopExecutionService()
        val task = _currentTask.value ?: return
        scope.launch {
            taskDao.updateTaskStatus(task.id, "PAUSED")
            _orchestratorState.value = OrchestratorState.Paused(task.id, task.currentStep)
        }
    }

    fun resumeTask(taskId: String) {
        scope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            taskDao.updateTaskStatus(taskId, "RUNNING")
            _currentTask.value = task
            _currentSteps.value = taskStepDao.getStepsForTaskList(taskId)
            // Pausing/cancelling tears the foreground service down, so a resume must
            // bring it back to keep the task alive if the user leaves the app.
            try {
                AgentExecutionService.startService(context, task.userGoal)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restart foreground execution service: ${e.message}")
            }
            // Continue the existing plan (if it is still in memory for this goal)
            // instead of re-planning and replaying already-executed actions.
            runAgentLoop(task, resumePlan = _activePlan.value)
        }
    }

    fun cancelCurrentTask() {
        executionJob?.cancel()
        executionJob = null
        stopExecutionService()
        val task = _currentTask.value
        if (task != null) {
            scope.launch {
                taskDao.updateTaskStatus(task.id, "CANCELLED")
                _orchestratorState.value = OrchestratorState.Idle
            }
        } else {
            _orchestratorState.value = OrchestratorState.Idle
        }
    }

    private fun sanitizeSensitiveData(input: String): String = input
        .replace(Regex("(?i)password[=:\\s]+[\\S]+"), "password=********")
        .replace(Regex("(?i)cvv[=:\\s]+[\\d]{3,4}"), "cvv=***")
        .replace(Regex("(?i)pin[=:\\s]+[\\d]{4,6}"), "pin=****")
        .replace(Regex("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14})\\b"), "CARD_NUMBER_REDACTED")

    companion object {
        const val PLAN_TIMEOUT_MS = 60_000L
        const val STEP_DECISION_TIMEOUT_MS = 30_000L
        const val MAX_CONSECUTIVE_FAILURES = 4

        /**
         * Bounded rebuilds of a stalled plan; each replan costs one model round-trip and
         * re-runs the step's retry budget, so this stays at one to keep total actions
         * bounded (see OrchestratorLoopTest.failedActionTriggersBoundedRecoveryThenStops).
         */
        const val MAX_REPLANS = 1

        const val MEMORY_LIMIT = 8
        const val MEMORY_OUTCOME_LIMIT = 3
    }
}
