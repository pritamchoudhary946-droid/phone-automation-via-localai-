package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.local.AgentDatabase
import com.example.data.local.CheckpointEntity
import com.example.data.local.TaskEntity
import com.example.data.local.TaskStepEntity
import com.example.model.LiteRtEngine
import com.example.planning.PlanOutcome
import com.example.planning.PlanSource
import com.example.planning.PlanStep
import com.example.planning.PlanStepStatus
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
    toolRegistry: ToolRegistry = ToolRegistry()
) {
    private val TAG = "TaskOrchestrator"
    private val database = AgentDatabase.getInstance(context)
    private val taskDao = database.taskDao()
    private val taskStepDao = database.taskStepDao()
    private val checkpointDao = database.checkpointDao()

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

    private var pendingRiskAction: AgentAction? = null
    private var pendingRiskTaskId: String? = null

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

    private suspend fun runAgentLoop(task: TaskEntity, resumePlan: TaskPlan? = null) {
        executionJob = scope.launch {
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
                    Log.i(TAG, "[RESUME] Continuing existing plan at step ${plan.completedCount()} of ${plan.size}")
                } else {
                    val planOutcome = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                        liteRtEngine.planTask(
                            goal = task.userGoal,
                            screenSummary = initialObservation.summary,
                            foregroundPackage = initialObservation.packageName,
                            historySummary = "",
                            lastActionVerification = null,
                            remainingStepBudget = maxSteps
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
                        // All planned steps are done; still require a fresh goal-level
                        // postcondition before declaring success.
                        val fresh = deviceAutomation.observe()
                        val goalVerdict = verifyGoalPostcondition(plan, observation, fresh)
                        if (goalVerdict.satisfied) {
                            val summary = "Goal achieved: completed ${plan.completedCount()} of ${plan.size} planned steps."
                            complete(task, summary, completedCount)
                        } else {
                            fail(task, "All planned steps completed but the goal postcondition was not verified: ${goalVerdict.detail}", completedCount)
                        }
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
                        if (allRequiredCompleted && goalVerdict.satisfied) {
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
                            // goal: fail honestly rather than looping.
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

    private fun verifyAction(
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
        return PostconditionVerdict(verdict.satisfied, verdict.detail)
    }

    private data class PostconditionVerdict(val satisfied: Boolean, val detail: String)

    /** True when the fresh observation exposes an editable/input control. */
    private fun observationHasEditableField(observation: UiObservation): Boolean =
        observation.nodes.any { it.isEditable }

    private fun historySummary(): String = _currentSteps.value.takeLast(4).joinToString("\n") {
        "Step ${it.stepIndex}: ${it.actionType} (${it.actionPayloadJson}) -> ${it.verificationResult}"
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
        taskDao.updateTask(task.copy(status = "COMPLETED", currentStep = completedCount, resultSummary = summary))
        _orchestratorState.value = OrchestratorState.Completed(task.id, summary)
        stopExecutionService()
    }

    private suspend fun fail(task: TaskEntity, reason: String, step: Int) {
        Log.e(TAG, "[FAIL] $reason")
        taskDao.updateTask(task.copy(status = "FAILED", currentStep = step, resultSummary = reason))
        _orchestratorState.value = OrchestratorState.Failed(task.id, reason)
        stopExecutionService()
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
    }
}
