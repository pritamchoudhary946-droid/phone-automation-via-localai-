package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.local.AgentDatabase
import com.example.data.local.CheckpointEntity
import com.example.data.local.EpisodeEntity
import com.example.data.local.SavedSkillEntity
import com.example.data.local.TaskEntity
import com.example.data.local.TaskOutcomeEntity
import com.example.data.local.TaskStepEntity
import com.example.data.local.UserMemoryEntity
import com.example.memory.rag.Retriever
import com.example.model.AgentEngine
import com.example.perception.OcrReader
import com.example.perception.ScreenshotDiffer
import com.example.perception.ScreenAwareness
import com.example.perception.UnavailableOcrReader
import com.example.perception.UnavailableVisionGrounder
import com.example.perception.VisionGrounder
import com.example.planning.EpisodeDeriver
import com.example.planning.ExplorationRequest
import com.example.planning.PlanOutcome
import com.example.planning.PlanSelfReview
import com.example.planning.PlanSource
import com.example.planning.PlanStep
import com.example.planning.PlanStepStatus
import com.example.planning.PlanVariableStore
import com.example.planning.PlannerPromptBuilder
import com.example.planning.ReflectionRequest
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
    /**
     * A terminal failure. When [escalated] is true the bounded recovery cycle was
     * exhausted and the run stopped honestly (plan 5.8) with a clear [blockedOn] reason
     * and a [suggestion] instead of a bare error; it never fakes success.
     */
    data class Failed(
        val taskId: String,
        val reason: String,
        val escalated: Boolean = false,
        val blockedOn: String? = null,
        val suggestion: String? = null
    ) : OrchestratorState()
}

/**
 * Controlled execution loop: Understand -> Plan -> Validate -> Execute one action
 * -> Observe -> Verify -> Update plan -> Repeat.
 *
 * The configured LLM API produces the plan and the per-step action selection when ready; the
 * native automation layer executes exactly one approved action at a time. A step
 * is only marked complete when its goal-specific postcondition is verified over a
 * fresh observation. `ReadScreen` success can never satisfy an unrelated goal.
 */
class TaskOrchestrator(
    private val context: Context,
    private val agentEngine: AgentEngine,
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
    private val autoSaveSkills: Boolean = false,
    private val nanoSummarizer: com.example.model.NanoSummarizer = com.example.model.UnavailableNanoSummarizer,
    private val speechOutput: com.example.voice.SpeechOutput? = null,
    /** Live opt-in gate for the notification-triage capability (plan 9.9). */
    private val notificationTriageEnabled: () -> Boolean = { false },
    /** Live opt-in gate for on-device speech output (the `speak` capability). */
    private val voiceOutputEnabled: () -> Boolean = { false }
) {
    private val TAG = "TaskOrchestrator"
    private val database = AgentDatabase.getInstance(context)
    private val taskDao = database.taskDao()
    private val taskStepDao = database.taskStepDao()
    private val checkpointDao = database.checkpointDao()
    private val memoryDao = database.memoryDao()
    private val episodeDao = database.episodeDao()

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

    /** Bounded lessons from past episodes; loaded once per task (plan 5.9). */
    private var lessonsDigest: String = ""

    /** 9.3 bounded conversational context, so follow-ups ("and send it") resolve. */
    private val conversationMemory = ConversationMemory()

    /** 9.4 per-run variable store: a value read in one app is reusable by a later step. */
    private val planVariables = PlanVariableStore()

    /** 9.4: bounded, untrusted-labelled summary of captured variables for the prompt. */
    private fun variableSummary(): String {
        val map = planVariables.asMap()
        if (map.isEmpty()) return ""
        return map.entries
            .take(MAX_VARIABLE_ENTRIES)
            .joinToString("\n") { (name, value) ->
                "$name = \"${value.replace("\n", " ").take(MAX_VARIABLE_VALUE_CHARS)}\""
            }
    }

    private val _pendingClarification = MutableStateFlow<String?>(null)
    /** A question Jarvis must ask before it can act (9.3): a follow-up with no context. */
    val pendingClarification: StateFlow<String?> = _pendingClarification.asStateFlow()

    /** 9.12 bounded count of obstacle dismissals per run, so recovery cannot loop. */
    private var obstacleRecoveries = 0

    /** Progress meter for the current run (plan 5.10); (re)created at the top of each run. */
    private lateinit var progressBudget: ProgressBudget

    /** Human-readable record of recoveries this run took, for the episode record (5.9). */
    private val recoveryNotes = mutableListOf<String>()

    /** The last failure that drove a recovery, so the episode lesson can name it. */
    private var lastFailureType: FailureType = FailureType.NONE
    private var lastFailureDetail: String = ""

    /** An action the user just approved: skip re-prompting when the loop reconsiders it. */
    private var userConfirmedAction: AgentAction? = null

    val stepTimeoutMs: Long = 15_000L
    var defaultMaxSteps: Int = 25

    /**
     * The single user-facing entry point for a typed/spoken command (plan 9.3/9.5).
     * Handles explicit memory commands inline, expands a conversational follow-up
     * against the previous turn, and otherwise starts the goal as-is. Returns the
     * goal actually run, or null when Jarvis asked a clarifying question instead.
     */
    fun submitCommand(command: String): String? {
        _pendingClarification.value = null
        when (val mem = com.example.memory.MemoryCommands.parse(command)) {
            is com.example.memory.MemoryCommands.Command.Remember -> {
                scope.launch { rememberPreference(mem.key, mem.value, kind = "FACT") }
                _orchestratorState.value = OrchestratorState.Completed(
                    taskId = "memory", summary = "Got it - I'll remember that."
                )
                return null
            }
            is com.example.memory.MemoryCommands.Command.Forget -> {
                scope.launch { runCatching { memoryDao.clearMemories() } }
                _orchestratorState.value = OrchestratorState.Completed(
                    taskId = "memory", summary = "Okay, I've forgotten that."
                )
                return null
            }
            is com.example.memory.MemoryCommands.Command.Recall -> {
                val query = mem.query
                scope.launch {
                    val answer = recallAnswer(query)
                    _orchestratorState.value = OrchestratorState.Completed(taskId = "memory", summary = answer)
                }
                return null
            }
            is com.example.memory.MemoryCommands.Command.NotAMemoryCommand -> Unit
        }

        when (val resolution = conversationMemory.resolve(command)) {
            is ConversationMemory.Resolution.NeedsContext -> {
                _pendingClarification.value = resolution.reason
                return null
            }
            is ConversationMemory.Resolution.Resolved -> {
                startTask(resolution.goal)
                return resolution.goal
            }
            is ConversationMemory.Resolution.Standalone -> {
                startTask(resolution.goal)
                return resolution.goal
            }
        }
    }

    /**
     * 9.11: when the tree has real elements, hand the model a ranked candidate list
     * (most likely target first) instead of the raw position-ordered dump. Falls back
     * to the original summary when ranking yields nothing, so behavior never regresses.
     */
    private fun decisionScreenSummary(observation: UiObservation, hint: String): String {
        val ranked = com.example.perception.ElementRanker.rankedSummary(
            observation.nodes, hint, observation.packageName
        )
        return ranked.ifBlank { observation.summary }
    }

    /** Answers a "what do you know about X" command from the on-device store only (9.5). */
    private suspend fun recallAnswer(query: String): String {
        val key = com.example.memory.MemoryCommands.keyOf(query)
        val exact = runCatching { memoryDao.getMemory(key) }.getOrNull()
        if (exact != null) return "You told me: ${exact.value}"
        val recent = runCatching { memoryDao.getRecentMemories(50) }.getOrDefault(emptyList())
        val needle = query.lowercase()
        val hit = recent.firstOrNull { it.value.lowercase().contains(needle) || it.key.contains(needle) }
        return hit?.value?.let { "You told me: $it" } ?: "I don't have anything stored about that yet."
    }

    fun startTask(goal: String, maxSteps: Int = defaultMaxSteps, resumePlan: TaskPlan? = null) {
        if (executionJob?.isActive == true) cancelCurrentTask()
        // 9.3: expand a follow-up ("and send it") into a self-contained goal before it
        // is persisted, so the plan and the history both read correctly.
        val effectiveGoal = when (val r = conversationMemory.resolve(goal)) {
            is ConversationMemory.Resolution.Resolved -> r.goal
            else -> goal
        }
        conversationMemory.remember(effectiveGoal, null)
        val taskId = UUID.randomUUID().toString()
        val newTask = TaskEntity(id = taskId, userGoal = effectiveGoal, status = "RUNNING", currentStep = 0, maxSteps = maxSteps)
        _currentTask.value = newTask
        _currentSteps.value = emptyList()
        _activePlan.value = null
        _modelCalls.value = 0
        _fastPathSteps.value = 0
        replanCount = 0
        stuckDetector.reset()
        recoveryNotes.clear()
        lastFailureType = FailureType.NONE
        lastFailureDetail = ""
        // 9.3/9.4/9.12: each run starts with a clean variable store and obstacle budget.
        planVariables.clear()
        obstacleRecoveries = 0
        _pendingClarification.value = null
        // Promote to a foreground service so a long automation survives the user
        // leaving the app; the notification is removed when the task ends.
        try {
            AgentExecutionService.startService(context, goal)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground execution service: ${e.message}")
        }
        scope.launch {
            taskDao.insertTask(newTask)
            runAgentLoop(newTask, resumePlan)
        }
    }

    /**
     * Replays a saved skill by name. The persisted plan is reconstructed and run
     * through the normal verified loop, so each replayed step still has to satisfy
     * its postcondition and the goal-level gate still applies. A skill whose JSON
     * cannot be reconstructed (legacy label-only format, or a malformed record) is
     * re-planned from its goal instead of replaying a guessed action.
     */
    fun runSkill(skill: SavedSkillEntity) {
        if (skill.planJson.isBlank()) return
        scope.launch { runCatching { database.skillDao().markSkillRun(skill.name, System.currentTimeMillis()) } }
        val plan = TaskPlanSerializer.fromJson(skill.planJson, goalOverride = skill.goal)
        if (plan == null) {
            Log.w(TAG, "Skill '${skill.name}' has no reconstructable plan; re-planning from its goal")
            startTask(skill.goal)
        } else {
            startTask(skill.goal, resumePlan = plan)
        }
    }

    private suspend fun runAgentLoop(task: TaskEntity, resumePlan: TaskPlan? = null) {
        executionJob = scope.launch {
            memoryDigest = loadMemoryDigest(task.userGoal)
            retrievedContext = loadRetrievedContext(task.userGoal)
            lessonsDigest = loadLessons(task.userGoal)
            progressBudget = ProgressBudget(task.maxSteps, MAX_REPLANS, MAX_EXPLORES)
            recoveryNotes.clear()
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
                        agentEngine.planTask(
                            goal = task.userGoal,
                            screenSummary = initialObservation.summary,
                            foregroundPackage = initialObservation.packageName,
                            historySummary = "",
                            lastActionVerification = null,
                            remainingStepBudget = maxSteps,
                            memorySummary = memoryDigest,
                            retrievedContext = retrievedContext,
                            lessonsSummary = lessonsDigest,
                            variableSummary = variableSummary()
                        )
                    }

                    plan = when (planOutcome) {
                        is PlanOutcome.Planned -> {
                            _usesModelPlanning.value = planOutcome.source == PlanSource.MODEL
                            planOutcome.plan
                        }
                        is PlanOutcome.Unavailable -> {
                            // 6.1: No LLM API is loaded. Do not silently run keywords —
                            // block with a clear, user-visible reason. The planner only
                            // returns BASIC_FALLBACK here when the user explicitly enabled
                            // the labelled degraded mode.
                            _usesModelPlanning.value = false
                            Log.w(TAG, "Blocking task: ${planOutcome.reason}")
                            fail(task, planOutcome.reason, 0)
                            return@launch
                        }
                        is PlanOutcome.Rejected -> {
                            _usesModelPlanning.value = false
                            if (agentEngine.isFallbackExplicit()) {
                                Log.w(TAG, "Using explicitly enabled fallback planner due to: ${planOutcome.reason}")
                                agentEngine.fallbackPlan(task.userGoal)
                            } else {
                                val reason = "Model plan rejected and basic fallback is disabled: ${planOutcome.reason}"
                                Log.w(TAG, reason)
                                fail(task, reason, 0)
                                return@launch
                            }
                        }
                        null -> {
                            _usesModelPlanning.value = false
                            val fallbackPlan = agentEngine.fallbackPlan(task.userGoal)
                            Log.w(TAG, "Using fallback planner due to: plan timeout or null result")
                            fallbackPlan
                        }
                    }
                }
                _activePlan.value = plan
                completedCount = plan.completedCount()
                Log.i(TAG, "[PLAN] goal=\"${task.userGoal}\" steps=${plan.size} source=${if (_usesModelPlanning.value) "MODEL" else "BASIC"}")

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
                        val webToolSatisfied = selfEvidencingToolSatisfied(lastRequired)
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

                    // 9.12: clear a common blocker (a dialog, ad, rate-limit or consent
                    // wall) before executing, so it is handled without asking. A blocker we
                    // cannot dismiss safely stops the run honestly rather than guessing.
                    if (obstacleRecoveries < MAX_OBSTACLE_RECOVERIES) {
                        val labels = observation.nodes.map { it.bestLabel() }
                        val blocker = ObstacleDetector.detect(
                            ObstacleDetector.ScreenSignals(observation.packageName, labels)
                        )
                        if (blocker != null) {
                            val label = blocker.dismissLabel
                            if (blocker.unresolvable || label == null || !ObstacleDetector.isSafeDismiss(label)) {
                                fail(
                                    task,
                                    "Blocked by ${blocker.kind} (${blocker.explanation}). I won't guess at dismissing it.",
                                    completedCount
                                )
                                return@launch
                            }
                            obstacleRecoveries++
                            recoveryNotes.add("Dismissed ${blocker.kind} via '$label'")
                            Log.i(TAG, "[OBSTACLE] ${blocker.kind} -> tapping '$label'")
                            deviceAutomation.execute(AgentAction.Tap(label, null, null))
                            delay(500)
                            observation = deviceAutomation.observe()
                            stuckDetector.reset()
                        }
                    }

                    val stuck = stuckDetector.recordStep(observation.fingerprint, step.rawActionName)
                    if (stuck.isStuck) {
                        Log.w(TAG, "[STUCK] ${stuck.reason}")
                        _orchestratorState.value = OrchestratorState.Replanning(stuck.reason, stuck.suggestedRecovery)
                        // Immediate cheap unblock first: nudge the screen so the next
                        // observe sees a fresh state, then run the full recovery cycle.
                        when (stuck.suggestedRecovery) {
                            RecoveryStrategy.PRESS_BACK_TO_UNBLOCK -> {
                                deviceAutomation.execute(AgentAction.Back); delay(500)
                            }
                            RecoveryStrategy.SCROLL_TO_DISCOVER -> {
                                deviceAutomation.execute(AgentAction.Scroll("down")); delay(500)
                            }
                            else -> delay(400)
                        }
                        observation = deviceAutomation.observe()
                        // 5.7: a detected loop is a failure signal too — run the bounded
                        // Detect->Diagnose->Replan cycle instead of retrying the same action.
                        val recovered = recoverFromFailure(
                            task = task,
                            plan = plan,
                            failedStep = step,
                            observation = observation,
                            completedCount = completedCount,
                            screenChanged = true,
                            resultMessage = "no progress",
                            resultMethod = "LOOP",
                            verdictDetail = stuck.reason,
                            loopReason = stuck.reason
                        )
                        if (recovered != null) {
                            plan = recovered
                            completedCount = plan.completedCount()
                            consecutiveFailures = 0
                            stuckDetector.reset()
                            delay(300)
                            continue
                        }
                        fail(
                            task,
                            "Stuck in a repeating loop and recovery was exhausted: ${stuck.reason}",
                            completedCount,
                            escalated = true,
                            blockedOn = lastFailureDetail
                        )
                        return@launch
                    }
                    val effectiveStep = step

                    // Hybrid fast path: when the step's intent can be satisfied
                    // deterministically from the current accessibility tree (launch an
                    // installed app, open the search UI, type into an observed field,
                    // press Home/Back, open an observed result), the native action is
                    // executed directly and the model is never invoked. Only when
                    // the fast path cannot decide is the model asked to choose/refresh the
                    // action. Model output, when present, overrides the nominal action.
                    val nudgeUsed = nudgedStepId == effectiveStep.id
                    val fastPath = hybridStepExecutor.decide(
                        plan.steps, plan.steps.indexOf(effectiveStep), observation, nudgeUsed
                    )

                    val decision = if (fastPath is FastPathDecision.ModelReason) {
                        Log.i(TAG, "[MODEL_STEP] ${fastPath.reason}")
                        // 7.4: when the a11y tree is blind (canvas/WebView/game), give the
                        // vision-capable model the screenshot alongside the text prompt.
                        val screenshot = if (observation.nodes.size < 3) captureScreenshotBytes() else null
                        if (screenshot != null) Log.i(TAG, "[MODEL_STEP] tree-miss: sending screenshot to model")
                        withTimeoutOrNull(STEP_DECISION_TIMEOUT_MS) {
                            agentEngine.decideNextAction(
                                goal = task.userGoal,
                                activeSubGoal = effectiveStep.description,
                                screenSummary = decisionScreenSummary(observation, effectiveStep.description),
                                foregroundPackage = observation.packageName,
                                historySummary = historySummary(),
                                lastActionVerification = lastActionResult,
                                remainingStepBudget = maxSteps - completedCount,
                                variableSummary = variableSummary(),
                                screenshotBytes = screenshot
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
                    // instead of dispatching text into nothing. A search-intent step must
                    // target a *search-specific* field: a comment/message box is editable
                    // but must never receive a search query (10.3B).
                    if (action is AgentAction.TypeText) {
                        val searchIntent = effectiveStep.verification == VerificationMethod.RESULTS_PRESENT ||
                            Regex("search|query|results?", RegexOption.IGNORE_CASE)
                                .containsMatchIn("${effectiveStep.description} ${effectiveStep.expectedPostcondition}")
                        val needsSearchInterface = (searchIntent && !observation.hasSearchInputField()) ||
                            !observationHasEditableField(observation)
                        if (needsSearchInterface) {
                            Log.w(TAG, "[GUARD] Search-specific editable field not present; opening the search interface before typing.")
                            _orchestratorState.value = OrchestratorState.Replanning(
                                "No usable search field visible; opening search interface first.",
                                RecoveryStrategy.REPLAN_NEW_APPROACH
                            )
                            action = AgentAction.OpenSearch(action.text)
                        }
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

                    // 9.4: resolve `{{name}}` placeholders from values captured earlier in
                    // this run, so a value read in one app reaches the step that uses it.
                    action = planVariables.substitute(action)

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
                        val webToolSatisfied = selfEvidencingToolSatisfied(lastRequired)
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
                        // 9.4: only a verified read populates its declared variables. The
                        // step's postcondition must hold and the action must actually have
                        // returned captured text, so a failed/empty read never stores a
                        // bogus value a later step would then paste elsewhere.
                        if (effectiveStep.captures.isNotEmpty()) {
                            // Drop any prior values for these names first so a re-run of
                            // the same step can never leave a stale value (9.4 freshness).
                            for (name in effectiveStep.captures) {
                                planVariables.remove(name)
                            }
                            if (execResult.readText != null) {
                                for (name in effectiveStep.captures) {
                                    planVariables.set(
                                        name,
                                        execResult.readText,
                                        source = effectiveStep.rawActionName.ifBlank { action.javaClass.simpleName }
                                    )
                                }
                            }
                        }
                        completedCount++
                        consecutiveFailures = 0
                        stuckDetector.reset()
                        taskDao.updateTask(task.copy(currentStep = completedCount))
                    } else {
                        // Detect -> Diagnose -> Recover (plan 5): classify the failure,
                        // reflect, then rebuild via re-plan/exploration. The step is only
                        // abandoned or retried through that bounded cycle — never blindly
                        // re-executed and never left to spin.
                        // 9.4: a failed capture step must not leave an earlier value
                        // reachable under its variable names, so a later {{name}} step
                        // cannot silently reuse a stale value.
                        for (name in effectiveStep.captures) {
                            planVariables.remove(name)
                        }
                        consecutiveFailures++
                        val recovered = recoverFromFailure(
                            task = task,
                            plan = plan,
                            failedStep = effectiveStep,
                            observation = postObservation,
                            completedCount = completedCount,
                            screenChanged = previousObservation.fingerprint != postObservation.fingerprint,
                            resultMessage = execResult.message,
                            resultMethod = execResult.method,
                            verdictDetail = verdict.detail
                        )
                        if (recovered != null) {
                            plan = recovered
                            completedCount = plan.completedCount()
                            observation = postObservation
                            consecutiveFailures = 0
                            stuckDetector.reset()
                            delay(300)
                            continue
                        }
                        // Recovery exhausted: escalate honestly (5.8) instead of a bare fail.
                        taskDao.updateTask(task.copy(currentStep = completedCount))
                        fail(
                            task,
                            "Step ${attempted.id} (${attempted.rawActionName}) did not satisfy its postcondition and recovery was exhausted: ${verdict.detail}",
                            completedCount,
                            escalated = true,
                            blockedOn = lastFailureDetail
                        )
                        return@launch
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
     * A step whose own completed result is its goal evidence, so no UI postcondition
     * is required at the goal gate. Web tools return a bounded result digest
     * (web_search/read_url), and a read action returns the visible-text digest it
     * captured (9.4) - the read IS the goal for a read-only task.
     */
    private fun selfEvidencingToolSatisfied(step: PlanStep?): Boolean {
        if (step?.status != PlanStepStatus.COMPLETED) return false
        return when (step.action) {
            is AgentAction.WebSearch, is AgentAction.ReadUrl,
            is AgentAction.ReadText, is AgentAction.ReadPageContent,
            is AgentAction.ReadNotifications, is AgentAction.ReadDeviceStatus,
            // Device-control actions act on a system service no screen reflects; a
            // completed, honestly-read-back volume/media/ringer command is its own
            // evidence, exactly like the read actions above.
            is AgentAction.AdjustVolume, is AgentAction.PlayMedia,
            is AgentAction.SetRingerMode, is AgentAction.SetAlarm,
            is AgentAction.SetTimer, is AgentAction.AddCalendarEvent,
            is AgentAction.AddContact, is AgentAction.DialNumber,
            is AgentAction.SendSms, is AgentAction.ShareText,
            is AgentAction.SendEmail, is AgentAction.NavigateTo,
            is AgentAction.OpenAppInfo, is AgentAction.SetTorch,
            // Speech output acts on the platform TTS engine (no screen reflects it), and
            // the executor reports SPOKEN only once the engine accepted the utterance.
            is AgentAction.Speak -> true
            else -> false
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
        if (lastRequired.action is AgentAction.OpenItem) {
            return verifyOpenedItem(lastRequired.action, lastRequired.verification, pre, post)
        }
        val method = when {
            lastRequired.action is AgentAction.OpenApp -> VerificationMethod.FOREGROUND_PACKAGE
            lastRequired.action is AgentAction.Home -> VerificationMethod.HOME_FOREGROUND
            lastRequired.action is AgentAction.ReadPageContent -> VerificationMethod.CONTENT
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
            agentEngine.planTask(
                goal = task.userGoal,
                screenSummary = observation.summary,
                foregroundPackage = observation.packageName,
                historySummary = historySummary(),
                lastActionVerification = failureDetail,
                remainingStepBudget = (task.maxSteps - completedCount).coerceAtLeast(1),
                memorySummary = memoryDigest,
                retrievedContext = retrievedContext,
                lessonsSummary = lessonsDigest,
                variableSummary = variableSummary()
            )
        }
        val newPlan = when (outcome) {
            is PlanOutcome.Planned -> {
                _usesModelPlanning.value = outcome.source == PlanSource.MODEL
                outcome.plan
            }
            // The reasoning already runs through the configured LLM API, so a failed
            // replan falls straight to the explicitly-enabled basic fallback plan.
            is PlanOutcome.Unavailable -> agentEngine.fallbackPlan(task.userGoal)
            is PlanOutcome.Rejected -> agentEngine.fallbackPlan(task.userGoal)
            null -> agentEngine.fallbackPlan(task.userGoal)
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
        // 9.4: a read_text/read_page_content step's success is the text it captured.
        // Its default verification is NONE, which can never be satisfied on its own,
        // so a read that declared no explicit postcondition is satisfied by the
        // non-empty text it returned. Without this the read would be retried forever
        // and its captured value could never reach a later {{name}} step - i.e. the
        // whole cross-app variable feature would be dead for the natural case.
        // read_notifications is the same shape: the notification digest it reads back
        // IS the goal evidence, so it must self-satisfy too (otherwise the goal gate's
        // ReadNotifications allowance is unreachable and every triage read fails).
        // read_device_status is the same shape: the battery/screen/ringer/connectivity
        // digest it reads back is carried as readText and IS the goal evidence.
        if (step.verification == VerificationMethod.NONE &&
            (action is AgentAction.ReadText || action is AgentAction.ReadPageContent ||
                action is AgentAction.ReadNotifications || action is AgentAction.ReadDeviceStatus)
        ) {
            val captured = result.readText
            val what = when (action) {
                is AgentAction.ReadNotifications -> "notification digest"
                is AgentAction.ReadDeviceStatus -> "device status"
                else -> "visible text"
            }
            return if (!captured.isNullOrBlank()) {
                PostconditionVerdict(true, "Read ${captured.length} chars of $what")
            } else {
                PostconditionVerdict(false, "Read action returned no visible text to capture")
            }
        }
        // Device-control actions are their own evidence. A volume change, a media
        // transport command and a notification read all act on a system service that
        // no accessibility screen reflects, so their default NONE verification can
        // never be satisfied from a fresh observation - the step would be retried
        // forever and never complete, exactly the 9.4 read defect. The executor has
        // already reported success honestly (it reads the stream level / playback
        // state back, and returns failure when nothing moved), so a successful result
        // is accepted as the step's evidence. This mirrors selfEvidencingToolSatisfied,
        // which already counts a completed read_notifications as goal evidence.
        if (step.verification == VerificationMethod.NONE &&
            (action is AgentAction.AdjustVolume ||
                action is AgentAction.PlayMedia ||
                action is AgentAction.SetRingerMode ||
                action is AgentAction.SetAlarm ||
                action is AgentAction.SetTimer ||
                action is AgentAction.AddCalendarEvent ||
                action is AgentAction.AddContact ||
                action is AgentAction.DialNumber ||
                action is AgentAction.SendSms ||
                action is AgentAction.ShareText ||
                action is AgentAction.SendEmail ||
                action is AgentAction.NavigateTo ||
                action is AgentAction.OpenAppInfo ||
                action is AgentAction.SetTorch ||
                action is AgentAction.Speak ||
                action is AgentAction.ReadNotifications)
        ) {
            val detail = if (action is AgentAction.ReadNotifications) {
                // The digest is the result's message; the executor caps it and never
                // persists it.
                "Read notifications (${result.message.length} chars)"
            } else {
                action.getDisplayName()
            }
            return PostconditionVerdict(true, "$detail completed: ${result.message}")
        }
        if (action is AgentAction.OpenSearch) {
            // A text-entry step that was re-routed into opening the search interface
            // is progress, not completion: the text still has to be entered.
            if (step.action !is AgentAction.OpenSearch) {
                return PostconditionVerdict(false, "Opened the search interface; text still has to be entered")
            }
            // A changed screen is not proof that search opened: it may be a popup,
            // keyboard, or unrelated navigation. Require a semantic search field.
            return if (post.hasSearchInputField()) {
                PostconditionVerdict(true, "Search-specific input field verified on screen")
            } else {
                PostconditionVerdict(false, "Search interface did not expose a search-specific input field")
            }
        }
        if (action is AgentAction.OpenItem) {
            return verifyOpenedItem(action, step.verification, pre, post, visionDiff(pre, post))
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

    /**
     * Opening a result requires detail/player evidence plus a real transition.
     * A generic screen change or the continued presence of the result label is
     * insufficient because taps can hit ads, menus, or unrelated rows.
     */
    private fun verifyOpenedItem(
        action: AgentAction.OpenItem,
        declared: VerificationMethod = VerificationMethod.SCREEN_CHANGED,
        pre: UiObservation,
        post: UiObservation,
        visionChanged: Boolean = false
    ): PostconditionVerdict {
        val changed = pre.fingerprint != post.fingerprint || visionChanged
        val lower = post.summary.lowercase()
        val target = action.targetText.trim().lowercase()
        val targetPresent = target.isNotBlank() && lower.contains(target)
        val detailSignal = listOf(
            "now playing", "video player", "watching", "pause", "resume",
            "fullscreen", "seek", "conversation", "profile", "details"
        ).any { lower.contains(it) }
        val listSurface = lower.contains("[scrollable]") && !detailSignal
        val opened = changed && targetPresent && detailSignal && !listSurface
        // When the plan asked for playback evidence, opening the row is not enough:
        // the player must actually be in a playback state (10.2/10.3F).
        if (declared == VerificationMethod.PLAYBACK) {
            val playback = verifier.verify(
                method = VerificationMethod.PLAYBACK,
                expectedPostcondition = action.targetText,
                targetPackage = null,
                observationPackage = post.packageName,
                screenChanged = changed,
                screenText = post.summary
            )
            val verified = opened && playback.satisfied
            return PostconditionVerdict(
                verified,
                if (verified) "Opened item and verified playback state"
                else "Playback unverified: opened=$opened playback=${playback.detail}"
            )
        }
        return PostconditionVerdict(
            opened,
            if (opened) "Opened item and verified detail/player surface"
            else "Item open is unverified: changed=$changed targetPresent=$targetPresent detailSignal=$detailSignal"
        )
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
        // 7.10 learn-from-demonstration: capture the step while the user is "showing" a
        // routine, so it can later be saved as a re-runnable skill.
        if (_watching.value) watchedTurns.add(reasoning to action)
    }

    private suspend fun complete(task: TaskEntity, summary: String, completedCount: Int) {
        Log.i(TAG, "[COMPLETE] $summary")
        // 7.10: self-critique of the finished run, appended to the summary so the user
        // (and the next planning prompt via memory) sees what the run actually did.
        val reviewLine = PlanSelfReview.toSummaryLine(
            PlanSelfReview.review(
                stepCount = _activePlan.value?.size ?: completedCount,
                completed = completedCount,
                retried = _activePlan.value?.steps?.count { it.attempts > 1 } ?: 0,
                goalSatisfied = true
            )
        )
        val finalSummary = "$summary $reviewLine"
        narrate(task.userGoal, outcome = "Task complete", detail = summary)
        recordOutcome(task, "COMPLETED", finalSummary, completedCount)
        recordEpisode(task, "COMPLETED")
        taskDao.updateTask(task.copy(status = "COMPLETED", currentStep = completedCount, resultSummary = finalSummary))
        _orchestratorState.value = OrchestratorState.Completed(task.id, finalSummary)
        notifyFinished("Task complete", finalSummary)
        // Only an optionally-enabled, clean run is auto-saved as a reusable skill (4.13).
        maybeAutoSaveSkill(task, finalSummary, completedCount)
        stopExecutionService()
    }

    private suspend fun fail(
        task: TaskEntity,
        reason: String,
        step: Int,
        escalated: Boolean = false,
        blockedOn: String? = null
    ) {
        Log.e(TAG, "[FAIL] $reason")
        narrate(task.userGoal, outcome = "Task failed", detail = reason)
        recordOutcome(task, "FAILED", reason, step)
        recordEpisode(task, "FAILED")
        taskDao.updateTask(task.copy(status = "FAILED", currentStep = step, resultSummary = reason))
        _orchestratorState.value = if (escalated) {
            OrchestratorState.Failed(
                task.id,
                reason,
                escalated = true,
                blockedOn = blockedOn,
                suggestion = escalationSuggestion()
            )
        } else {
            OrchestratorState.Failed(task.id, reason)
        }
        if (escalated) {
            // 5.8: ask the user for help with a clear reason, rather than a silent stop.
            notifyFinished(
                "Task needs your help",
                "${blockedOn ?: reason}\nSuggested: ${escalationSuggestion()}"
            )
        } else {
            notifyFinished("Task failed", reason)
        }
        stopExecutionService()
    }

    /** Best-effort completion notification; never blocks or fails the task outcome. */
    private fun notifyFinished(title: String, summary: String) {
        val notifier = completionNotifier ?: return
        runCatching { notifier.notifyFinished(title, summary) }
    }

    /**
     * 7.17: speaks a short status line in the user's language (Hindi/Roman-Hindi/English)
     * when speech output is enabled. Best-effort and bounded; never blocks the task.
     */
    private fun narrate(goal: String, outcome: String, detail: String) {
        val speech = speechOutput ?: return
        if (!speech.isReady()) return
        val hindi = com.example.voice.LanguageDetector.prefersHindi(goal)
        val outcomeText = if (hindi) hindiOutcome(outcome) else outcome
        val line = if (hindi) "$outcomeText: ${detail.take(120)}" else "$outcome: ${detail.take(160)}"
        runCatching { speech.speak(line, if (hindi) "hi" else "") }
    }

    private fun hindiOutcome(outcome: String): String = when {
        outcome.contains("complete", ignoreCase = true) -> "Kaam poora hua"
        outcome.contains("fail", ignoreCase = true) -> "Kaam poora nahi hua"
        else -> outcome
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

    /**
     * 7.4: captures the current screen as JPEG bytes for the multimodal model, bounded
     * so a full-resolution screenshot cannot blow the prompt budget. Returns null when
     * screenshotting is unavailable (never fabricates an image).
     */
    private suspend fun captureScreenshotBytes(): ByteArray? {
        val bitmap = runCatching { screenAwareness?.captureScreenshot() }.getOrNull() ?: return null
        return runCatching {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    // --- 7.10 learn-from-demonstration -----------------------------------------

    private val _watching = MutableStateFlow(false)
    /** True while the agent is capturing a demonstrated routine. */
    val watching: StateFlow<Boolean> = _watching.asStateFlow()
    private val watchedTurns = mutableListOf<Pair<String, AgentAction>>()

    /** Starts capturing a demonstrated run (plan 4.17). */
    fun startWatching() {
        watchedTurns.clear()
        _watching.value = true
    }

    fun stopWatching() {
        _watching.value = false
    }

    /** True when at least one turn was recorded for a not-yet-saved demonstration. */
    fun hasWatchedTurns(): Boolean = watchedTurns.isNotEmpty()

    /**
     * 7.10: converts the captured demonstration into a saved, re-runnable skill. Only
     * the actions the demonstrated run actually performed are stored; returns the skill
     * name on success, or null when nothing was demonstrated.
     */
    suspend fun saveWatchedSkill(name: String, goal: String): String? {
        if (watchedTurns.isEmpty()) return null
        val steps = watchedTurns.mapIndexed { index, (reasoning, action) ->
            PlanStep(
                id = index + 1,
                sequence = index + 1,
                description = reasoning.ifBlank { action.getDisplayName() },
                action = action,
                rawActionName = TaskPlanSerializer.toolNameFor(action) ?: action.javaClass.simpleName,
                expectedPostcondition = "",
                verification = VerificationMethod.NONE
            )
        }
        val plan = TaskPlan(goal = goal, steps = steps, fromModel = true)
        val json = TaskPlanSerializer.toJson(plan)
        runCatching { database.skillDao().upsertSkill(SavedSkillEntity(name = name, goal = goal, planJson = json)) }
            .onFailure { return null }
        stopWatching()
        return name
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

    // --- Phase 5: autonomy & self-healing helpers -------------------------------

    /**
     * Runs the Detect -> Diagnose -> Backtrack -> Re-plan -> Explore -> Learn recovery
     * cycle for a failed step (plans 5.2-5.10). Returns a rebuilt plan to continue with,
     * or null when the bounded cycle is exhausted — in which case the caller stops
     * honestly (never retrying the same action, never faking success).
     *
     * The failure is *classified* first so the cheapest fitting recovery is chosen; a
     * re-plan (5.5) and bounded exploration (5.6) each consume one budget slot. The
     * abandoned steps are marked SKIPPED rather than re-run (5.5).
     */
    private suspend fun recoverFromFailure(
        task: TaskEntity,
        plan: TaskPlan,
        failedStep: PlanStep,
        observation: UiObservation,
        completedCount: Int,
        screenChanged: Boolean,
        resultMessage: String,
        resultMethod: String,
        verdictDetail: String,
        validationRejected: Boolean = false,
        loopReason: String? = null
    ): TaskPlan? {
        val stepsRemaining = progressBudget.stepsRemaining(completedCount)
        if (stepsRemaining <= 0) {
            lastFailureDetail = "Step budget exhausted"
            return null
        }

        val diagnosis = FailureClassifier.classify(
            success = false,
            resultMessage = resultMessage,
            resultMethod = resultMethod,
            verificationDetail = verdictDetail,
            screenChanged = screenChanged,
            expectedPackage = packageForAction(failedStep.action),
            observedPackage = observation.packageName,
            screenText = observation.summary,
            validationRejected = validationRejected,
            loopDetected = loopReason != null
        )
        lastFailureType = diagnosis.type
        lastFailureDetail = "Step '${failedStep.rawActionName}' failed (${diagnosis.type}): ${diagnosis.reason}"
        Log.w(TAG, "[RECOVERY] ${diagnosis.type} -> ${diagnosis.suggestedRecovery}")

        // 5.4: reflection-on-demand — only here (failure/stall), never on a clean run.
        val reflection = withTimeoutOrNull(REFLECTION_TIMEOUT_MS) {
            agentEngine.reflect(
                ReflectionRequest(
                    goal = task.userGoal,
                    activeSubGoal = failedStep.description,
                    lastAction = "${failedStep.rawActionName} (${failedStep.action.getDisplayName()})",
                    failureReason = diagnosis.reason,
                    screenSummary = observation.summary,
                    foregroundPackage = observation.packageName
                )
            )
        } ?: diagnosis.reason
        recoveryNotes += "reflected on ${diagnosis.type}: ${reflection.take(120)}"

        // 5.3: backtrack to the last known-good surface before retrying.
        if (diagnosis.suggestedRecovery == RecoveryStrategy.PRESS_BACK_TO_UNBLOCK) {
            runCatching { deviceAutomation.execute(AgentAction.Back) }
            delay(400)
        }

        // 5.5: the failed step is abandoned, not re-run, when a new approach is built.
        val abandoned = plan.replace(failedStep.withStatus(PlanStepStatus.SKIPPED))

        // 5.10: degrade in order — re-plan, then explore, then escalate (handled by the
        // caller when this returns null). Exploration is only for a missing target
        // (5.6): a wrong package or a validation rejection is not a discovery problem.
        if (progressBudget.canReplan()) {
            val replanned = attemptReplan(task, abandoned, observation, completedCount, diagnosis.reason)
            if (replanned != null && replanned.nextPending() != null) {
                progressBudget.recordReplan()
                recoveryNotes += "re-planned after ${diagnosis.type}"
                return replanned
            }
        }
        val explorationFits = diagnosis.type == FailureType.TARGET_NOT_FOUND ||
            diagnosis.type == FailureType.NO_SCREEN_CHANGE
        if (explorationFits && progressBudget.canExplore()) {
            progressBudget.recordExplore()
            val probe = withTimeoutOrNull(EXPLORATION_TIMEOUT_MS) {
                agentEngine.exploreNextAction(
                    ExplorationRequest(
                        goal = task.userGoal,
                        missingTarget = failedStep.description,
                        probesUsed = progressBudget.exploresUsed,
                        screenSummary = observation.summary,
                        foregroundPackage = observation.packageName,
                        availableActions = PlannerPromptBuilder.availableActions(
                            toolRegistry,
                            notificationTriageEnabled(),
                            voiceOutputEnabled()
                        )
                    )
                )
            }
            if (probe != null && toolRegistry.validateAction(probe) is ToolValidationResult.Valid) {
                runCatching { deviceAutomation.execute(probe) }
                delay(400)
                recoveryNotes += "explored with ${probe.getDisplayName()}"
                // Continue the plan from the (now-SKIPPED-free) step after the probe.
                return abandoned.replace(failedStep.withStatus(PlanStepStatus.PENDING))
            }
        }
        return null
    }

    /** The package a launch step targets, so WRONG_PACKAGE failures can be classified. */
    private fun packageForAction(action: AgentAction): String? =
        if (action is AgentAction.OpenApp) action.packageName ?: appDirectory.resolvePackage(action.appQuery) else null

    /** The best "what should I try next" hint for an honest escalation (plan 5.8). */
    private fun escalationSuggestion(): String = when (lastFailureType) {
        FailureType.PERMISSION_DIALOG -> "Grant the permission dialog that is blocking the screen, then retry."
        FailureType.TARGET_NOT_FOUND -> "The control I need is not on screen; open the relevant screen manually and retry."
        FailureType.NETWORK_ERROR -> "Check your connection, then retry."
        FailureType.WRONG_PACKAGE -> "A different app is in the foreground; bring the right app forward and retry."
        FailureType.LOGIN_REQUIRED -> "Sign in to the app, then retry."
        FailureType.TIMEOUT -> "The last action was slow; retry, or perform it manually."
        else -> "Perform the step manually, or rephrase the task and retry."
    }

    /** Records a compact episode lesson for the run (plan 5.9). */
    private suspend fun recordEpisode(task: TaskEntity, outcome: String) {
        try {
            val lesson = EpisodeDeriver.lesson(
                outcome = outcome,
                failureDetail = lastFailureDetail,
                recoveriesUsed = progressBudget.replansUsed + progressBudget.exploresUsed,
                finalApproach = recoveryNotes.lastOrNull()
            )
            episodeDao.insertEpisode(
                EpisodeEntity(
                    goal = task.userGoal,
                    outcome = outcome,
                    failureSummary = lastFailureDetail.take(300),
                    lesson = lesson.take(300),
                    recoveriesUsed = progressBudget.replansUsed + progressBudget.exploresUsed
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Episode write failed: ${e.message}")
        }
    }

    /** Bounded digest of lessons from past episodes relevant to [goal] (plan 5.9). */
    private suspend fun loadLessons(goal: String): String = try {
        val episodes = episodeDao.getEpisodesForGoal(goal, EPISODE_LIMIT)
            .ifEmpty { episodeDao.getRecentEpisodes(EPISODE_LIMIT) }
            .filter { it.lesson.isNotBlank() }
        EpisodeDeriver.toPromptBlock(episodes.map { it.goal to it.lesson }, PlannerPromptBuilder.MAX_LESSONS_CHARS)
    } catch (e: Exception) {
        Log.w(TAG, "Lesson load failed: ${e.message}")
        ""
    }

    companion object {
        const val PLAN_TIMEOUT_MS = 60_000L
        const val STEP_DECISION_TIMEOUT_MS = 30_000L
        const val REFLECTION_TIMEOUT_MS = 20_000L
        const val EXPLORATION_TIMEOUT_MS = 20_000L
        const val MAX_CONSECUTIVE_FAILURES = 4

        /**
         * Bounded rebuilds of a stalled plan; each replan costs one model round-trip and
         * re-runs the step's retry budget, so this stays at one to keep total actions
         * bounded (see OrchestratorLoopTest.failedActionTriggersBoundedRecoveryThenStops).
         */
        const val MAX_REPLANS = 1

        /** Bounded exploration probes (plan 5.6): enough to test a couple of routes, not wander. */
        const val MAX_EXPLORES = 2

        const val MEMORY_LIMIT = 8
        const val MEMORY_OUTCOME_LIMIT = 3
        const val EPISODE_LIMIT = 3

        /** Bounded obstacle dismissals per run (plan 9.12), so recovery cannot loop. */
        const val MAX_OBSTACLE_RECOVERIES = 3

        /** 9.4: bounds on the captured-variable summary injected into the planning prompt. */
        const val MAX_VARIABLE_ENTRIES = 6
        const val MAX_VARIABLE_VALUE_CHARS = 120
    }
}
