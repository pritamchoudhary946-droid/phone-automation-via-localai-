package com.example.agent

import android.content.Context
import android.util.Log
import com.example.accessibility.AgentAccessibilityService
import com.example.accessibility.UiTreeSnapshot
import com.example.data.local.AgentDatabase
import com.example.data.local.CheckpointEntity
import com.example.data.local.TaskEntity
import com.example.data.local.TaskStepEntity
import com.example.model.LiteRtEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    data class Planning(val stepIndex: Int, val goal: String, val activeSubGoal: String? = null) : OrchestratorState()
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

data class StepExecutionOutput(
    val success: Boolean,
    val method: String,
    val message: String
)

class TaskOrchestrator(
    private val context: Context,
    private val liteRtEngine: LiteRtEngine,
    private val appDirectory: AppDirectory
) {
    private val TAG = "TaskOrchestrator"
    private val database = AgentDatabase.getInstance(context)
    private val taskDao = database.taskDao()
    private val taskStepDao = database.taskStepDao()
    private val checkpointDao = database.checkpointDao()

    private val stuckDetector = StuckLoopDetector()
    private val riskEvaluator = RiskEvaluator()
    val toolRegistry = ToolRegistry()

    private val _orchestratorState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val orchestratorState: StateFlow<OrchestratorState> = _orchestratorState.asStateFlow()

    private val _currentTask = MutableStateFlow<TaskEntity?>(null)
    val currentTask: StateFlow<TaskEntity?> = _currentTask.asStateFlow()

    private val _currentSteps = MutableStateFlow<List<TaskStepEntity>>(emptyList())
    val currentSteps: StateFlow<List<TaskStepEntity>> = _currentSteps.asStateFlow()

    private var executionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var pendingRiskAction: AgentAction? = null
    private var pendingRiskTaskId: String? = null

    // Configurable timeout and limits
    var stepTimeoutMs: Long = 15_000L
    var defaultMaxSteps: Int = 25

    fun startTask(goal: String, maxSteps: Int = defaultMaxSteps) {
        if (executionJob?.isActive == true) {
            cancelCurrentTask()
        }

        val taskId = UUID.randomUUID().toString()
        val newTask = TaskEntity(
            id = taskId,
            userGoal = goal,
            status = "RUNNING",
            currentStep = 0,
            maxSteps = maxSteps
        )

        _currentTask.value = newTask
        _currentSteps.value = emptyList()
        stuckDetector.reset()

        scope.launch {
            taskDao.insertTask(newTask)
            runAgentLoop(newTask)
        }
    }

    private fun runAgentLoop(task: TaskEntity) {
        executionJob = scope.launch {
            var currentStepIndex = task.currentStep
            val maxSteps = task.maxSteps
            var lastVerificationResult: String? = null
            var lastActionName = ""

            // Anti-loop tracker: records failure counts per action signature
            val failedActionTracker = mutableMapOf<String, Int>()

            try {
                while (currentStepIndex < maxSteps) {
                    var a11yService = AgentAccessibilityService.instance
                    var readinessAttempts = 0
                    while (a11yService == null && readinessAttempts < 10) {
                        delay(300)
                        a11yService = AgentAccessibilityService.instance
                        readinessAttempts++
                    }
                    if (a11yService == null) {
                        val failReason = "Accessibility Service did not become ready within 3 seconds. Enable OmniAgent in Android Settings > Accessibility."
                        taskDao.updateTaskStatus(task.id, "FAILED")
                        _orchestratorState.value = OrchestratorState.Failed(task.id, failReason)
                        return@launch
                    }

                    _orchestratorState.value = OrchestratorState.Planning(currentStepIndex, task.userGoal)

                    // Step 1: UI Observation
                    val uiSnapshot = a11yService.dumpHierarchy()
                    val screenSummary = uiSnapshot.toPromptSummary()
                    val screenFingerprint = uiSnapshot.getFingerprint()

                    // Step 2: Stuck loop detection
                    val stuckStatus = stuckDetector.recordStep(screenFingerprint, lastActionName)
                    if (stuckStatus.isStuck) {
                        Log.w(TAG, "[STUCK] Stuck loop detected: ${stuckStatus.reason}")
                        _orchestratorState.value = OrchestratorState.Replanning(stuckStatus.reason, stuckStatus.suggestedRecovery)

                        when (stuckStatus.suggestedRecovery) {
                            RecoveryStrategy.SCROLL_TO_DISCOVER -> {
                                a11yService.scroll("down")
                                delay(600)
                            }
                            RecoveryStrategy.PRESS_BACK_TO_UNBLOCK -> {
                                a11yService.pressBack()
                                delay(600)
                            }
                            RecoveryStrategy.ASK_USER_HELP -> {
                                _orchestratorState.value = OrchestratorState.Failed(task.id, "Stuck in repeating loop: ${stuckStatus.reason}")
                                return@launch
                            }
                            else -> {
                                delay(500)
                            }
                        }
                    }

                    // Step 3: Gemma Reasoning & Planning
                    val historySummary = _currentSteps.value.takeLast(4).joinToString("\n") {
                        "Step ${it.stepIndex}: Action ${it.actionType} (${it.actionPayloadJson}) -> ${it.verificationResult}"
                    }

                    val decision = liteRtEngine.reasonAndPlan(
                        goal = task.userGoal,
                        screenSummary = screenSummary,
                        historySummary = historySummary,
                        lastActionVerification = lastVerificationResult
                    )

                    var action = decision.action
                    val actionKey = action.getDisplayName()

                    // Step 3b: Validate action via ToolRegistry
                    when (val valResult = toolRegistry.validateAction(action)) {
                        is ToolValidationResult.Invalid -> {
                            Log.e(TAG, "[VALIDATION_ERROR] Tool validation failed: ${valResult.reason}")
                            val stepEntity = TaskStepEntity(
                                taskId = task.id,
                                stepIndex = currentStepIndex,
                                reasoning = "Tool validation failed for action: ${action.getDisplayName()}",
                                actionType = action.javaClass.simpleName,
                                actionPayloadJson = action.getDisplayName(),
                                observationSummary = "Invalid parameters: ${valResult.reason}",
                                verificationResult = "Failed: ${valResult.reason}",
                                status = "FAILED"
                            )
                            taskStepDao.insertStep(stepEntity)
                            _currentSteps.value = _currentSteps.value + stepEntity
                            currentStepIndex++
                            continue
                        }
                        is ToolValidationResult.Valid -> {}
                    }

                    // Step 6 Rule: Never retry the identical failed action indefinitely; trigger recovery/replan.
                    val previousFailures = failedActionTracker[actionKey] ?: 0
                    if (previousFailures >= 1) {
                        Log.w(TAG, "[ANTI_LOOP] Action '$actionKey' has failed before ($previousFailures times). Triggering recovery.")

                        if (action is AgentAction.Tap && action.targetText?.equals("Search", ignoreCase = true) == true) {
                            // Recovery A: Look up search node coordinates directly from current tree
                            val searchNode = uiSnapshot.findNodeByText("Search")
                            if (searchNode != null) {
                                Log.i(TAG, "[RECOVERY] Retrying Search with coordinate bounds: (${searchNode.bounds.centerX()}, ${searchNode.bounds.centerY()})")
                                action = AgentAction.Tap(null, searchNode.bounds.centerX(), searchNode.bounds.centerY())
                            } else {
                                // Recovery B: If in YouTube, launch native search intent directly
                                val targetQuery = extractQueryFromGoal(task.userGoal)
                                if (targetQuery != null && (uiSnapshot.packageName?.contains("youtube") == true || task.userGoal.lowercase().contains("youtube"))) {
                                    Log.i(TAG, "[RECOVERY] Launching native YouTube search intent for '$targetQuery'")
                                    appDirectory.launchSearch(targetQuery, "com.google.android.youtube")
                                    delay(1200)
                                    currentStepIndex++
                                    continue
                                }
                            }
                        } else {
                            // Generic replanning recovery
                            _orchestratorState.value = OrchestratorState.Replanning(
                                reason = "Action '$actionKey' failed previously. Choosing alternative path.",
                                recoveryStrategy = RecoveryStrategy.REPLAN_NEW_APPROACH
                            )
                            if (uiSnapshot.findScrollable() != null) {
                                a11yService.scroll("down")
                            } else {
                                a11yService.pressBack()
                            }
                            delay(600)
                        }
                    }

                    lastActionName = action.getDisplayName()

                    // Step 4: Risk Assessment Check (Sensitive / Consequential actions)
                    val risk = riskEvaluator.evaluate(action, screenSummary)
                    if (risk.isRisky) {
                        pendingRiskAction = action
                        pendingRiskTaskId = task.id
                        taskDao.updateTask(task.copy(
                            status = "WAITING_CONFIRMATION",
                            requiresRiskConfirmation = true,
                            pendingRiskActionJson = action.getDisplayName()
                        ))
                        _orchestratorState.value = OrchestratorState.WaitingConfirmation(
                            taskId = task.id,
                            riskType = risk.riskType,
                            warningMessage = risk.warningMessage,
                            pendingAction = action
                        )
                        return@launch
                    }

                    // Step 5: Check completion or termination
                    if (action is AgentAction.FinishTask) {
                        taskDao.updateTask(task.copy(
                            status = "COMPLETED",
                            currentStep = currentStepIndex,
                            resultSummary = action.summary
                        ))
                        _orchestratorState.value = OrchestratorState.Completed(task.id, action.summary)
                        return@launch
                    }

                    if (action is AgentAction.FailTask) {
                        taskDao.updateTask(task.copy(
                            status = "FAILED",
                            currentStep = currentStepIndex,
                            resultSummary = action.reason
                        ))
                        _orchestratorState.value = OrchestratorState.Failed(task.id, action.reason)
                        return@launch
                    }

                    // Step 6: Action Execution with Timeout Guard
                    _orchestratorState.value = OrchestratorState.Executing(currentStepIndex, action, decision.thought)

                    val actionOutput = withTimeoutOrNull(stepTimeoutMs) {
                        executeAtomicAction(action, a11yService, uiSnapshot, task.userGoal)
                    } ?: StepExecutionOutput(
                        success = false,
                        method = "TIMEOUT",
                        message = "Step execution timed out after ${stepTimeoutMs}ms"
                    )

                    delay(400) // Yield for UI update and transition

                    // Track failures to prevent indefinite repeating
                    if (!actionOutput.success) {
                        failedActionTracker[actionKey] = previousFailures + 1
                    } else {
                        failedActionTracker.remove(actionKey)
                    }

                    // Step 7: Action Verification
                    val postSnapshot = a11yService.dumpHierarchy()
                    val verification = verifyStepOutcome(action, uiSnapshot, postSnapshot, actionOutput)
                    lastVerificationResult = verification

                    // Step 8: Persist Step & Checkpoint with privacy filter
                    val sanitizedSummary = sanitizeSensitiveData(screenSummary.take(250))
                    val sanitizedPayload = sanitizeSensitiveData(action.getDisplayName())

                    val stepEntity = TaskStepEntity(
                        taskId = task.id,
                        stepIndex = currentStepIndex,
                        reasoning = decision.thought,
                        actionType = action.javaClass.simpleName,
                        actionPayloadJson = sanitizedPayload,
                        observationSummary = sanitizedSummary,
                        verificationResult = verification,
                        status = if (actionOutput.success) "SUCCESS" else "FAILED"
                    )

                    taskStepDao.insertStep(stepEntity)
                    _currentSteps.value = _currentSteps.value + stepEntity

                    val checkpoint = CheckpointEntity(
                        taskId = task.id,
                        stepIndex = currentStepIndex,
                        snapshotJson = "Step $currentStepIndex reached [${action.javaClass.simpleName}]"
                    )
                    checkpointDao.saveCheckpoint(checkpoint)

                    currentStepIndex++
                    taskDao.updateTask(task.copy(currentStep = currentStepIndex))
                }

                // Reached max steps
                val failure = "Task did not satisfy its goal within the $maxSteps step limit."
                taskDao.updateTask(task.copy(status = "FAILED", resultSummary = failure))
                _orchestratorState.value = OrchestratorState.Failed(task.id, failure)

            } catch (e: CancellationException) {
                Log.i(TAG, "Task cancelled or paused.")
            } catch (e: Exception) {
                Log.e(TAG, "Task execution error", e)
                taskDao.updateTaskStatus(task.id, "FAILED")
                _orchestratorState.value = OrchestratorState.Failed(task.id, "Error: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun executeAtomicAction(
        action: AgentAction,
        a11y: AgentAccessibilityService,
        currentSnapshot: UiTreeSnapshot,
        userGoal: String
    ): StepExecutionOutput = withContext(Dispatchers.Default) {
        val preFingerprint = currentSnapshot.getFingerprint()
        val prePackage = currentSnapshot.packageName

        val verifyUiChanged: suspend () -> Boolean = {
            delay(250)
            val current = a11y.dumpHierarchy()
            val fingerprintDiff = current.getFingerprint() != preFingerprint
            val packageDiff = current.packageName != prePackage
            fingerprintDiff || packageDiff
        }

        return@withContext when (action) {
            is AgentAction.OpenApp -> {
                val targetPkg = action.packageName ?: appDirectory.resolvePackage(action.appQuery)
                if (targetPkg != null) {
                    val launched = appDirectory.launchApp(targetPkg)
                    StepExecutionOutput(
                        success = launched,
                        method = "LAUNCH_INTENT",
                        message = if (launched) "Launched $targetPkg" else "Failed to launch $targetPkg"
                    )
                } else {
                    StepExecutionOutput(
                        success = false,
                        method = "RESOLVE_APP",
                        message = "App '${action.appQuery}' not found on device"
                    )
                }
            }
            is AgentAction.Tap -> {
                val tapResult = a11y.performTap(action.targetText, action.x, action.y, verifyUiChanged)
                StepExecutionOutput(
                    success = tapResult.success,
                    method = tapResult.method,
                    message = if (tapResult.success) {
                        "Tap succeeded via ${tapResult.method} (${tapResult.bounds?.toShortString() ?: "target"})"
                    } else {
                        tapResult.failureReason ?: "Target element could not be found or clicked"
                    }
                )
            }
            is AgentAction.TypeText -> {
                val textResult = a11y.performTextInput(action.text, action.targetNodeId)
                StepExecutionOutput(
                    success = textResult.success,
                    method = textResult.method,
                    message = if (textResult.success) {
                        "Typed \"${action.text}\" into field via ${textResult.method}"
                    } else {
                        textResult.failureReason ?: "Failed to set text into input field"
                    }
                )
            }
            is AgentAction.Scroll -> {
                val scrolled = a11y.scroll(action.direction)
                StepExecutionOutput(success = scrolled, method = "SCROLL", message = "Scrolled ${action.direction}")
            }
            is AgentAction.Back -> {
                val backed = a11y.pressBack()
                StepExecutionOutput(success = backed, method = "GLOBAL_BACK", message = "Pressed Back")
            }
            is AgentAction.Home -> {
                val homed = a11y.pressHome()
                StepExecutionOutput(success = homed, method = "GLOBAL_HOME", message = "Pressed Home")
            }
            is AgentAction.Wait -> {
                delay(action.durationMs)
                StepExecutionOutput(success = true, method = "WAIT", message = "Waited ${action.durationMs}ms")
            }
            is AgentAction.ReadScreen -> {
                StepExecutionOutput(success = true, method = "READ", message = "Screen hierarchy refreshed")
            }
            is AgentAction.Screenshot -> {
                val bmp = a11y.takeScreenshotFallback()
                StepExecutionOutput(
                    success = bmp != null,
                    method = "SCREENSHOT",
                    message = if (bmp != null) "Screenshot captured successfully" else "Screenshot fallback captured or completed"
                )
            }
            else -> StepExecutionOutput(success = false, method = "UNKNOWN", message = "Unhandled action type")
        }
    }

    private fun verifyStepOutcome(
        action: AgentAction,
        pre: UiTreeSnapshot,
        post: UiTreeSnapshot,
        executionOutput: StepExecutionOutput
    ): String {
        if (!executionOutput.success) {
            return "Failed: ${executionOutput.message} [method: ${executionOutput.method}]"
        }

        val screenChanged = pre.getFingerprint() != post.getFingerprint()
        val packageChanged = pre.packageName != post.packageName

        return when (action) {
            is AgentAction.OpenApp -> {
                if (packageChanged && post.packageName != null) {
                    "Success: Switched package to ${post.packageName}"
                } else {
                    "Failed: Expected foreground package was not verified for ${action.appQuery}; observed ${post.packageName ?: "unknown"}"
                }
            }
            is AgentAction.Tap -> {
                if (screenChanged) {
                    "Success: ${executionOutput.method} altered screen state"
                } else {
                    "Failed: ${executionOutput.method} did not produce an observable UI change"
                }
            }
            is AgentAction.TypeText -> {
                if (screenChanged) {
                    "Success: Text \"${action.text}\" injected into input field"
                } else {
                    "Success: ${executionOutput.message}"
                }
            }
            is AgentAction.Scroll -> {
                if (screenChanged) "Success: Scrolled to new viewport" else "End of scrollable region"
            }
            else -> "Success: ${executionOutput.message}"
        }
    }

    private fun sanitizeSensitiveData(input: String): String {
        return input
            .replace(Regex("(?i)password[=:\\s]+[\\S]+"), "password=********")
            .replace(Regex("(?i)cvv[=:\\s]+[\\d]{3,4}"), "cvv=***")
            .replace(Regex("(?i)pin[=:\\s]+[\\d]{4,6}"), "pin=****")
            .replace(Regex("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14})\\b"), "CARD_NUMBER_REDACTED")
    }

    private fun extractQueryFromGoal(goal: String): String? {
        val patterns = listOf("search for ", "search ", "find ")
        for (pattern in patterns) {
            val idx = goal.indexOf(pattern, ignoreCase = true)
            if (idx != -1) {
                return goal.substring(idx + pattern.length).trim().removeSurrounding("\"")
            }
        }
        return null
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
                val a11y = AgentAccessibilityService.instance
                if (a11y != null) {
                    val currentSnapshot = a11y.dumpHierarchy()
                    executeAtomicAction(action, a11y, currentSnapshot, task.userGoal)
                }
                runAgentLoop(task.copy(currentStep = task.currentStep + 1))
            } else {
                taskDao.updateTask(task.copy(status = "CANCELLED", resultSummary = "User rejected risky action"))
                _orchestratorState.value = OrchestratorState.Failed(taskId, "User rejected action: ${action.getDisplayName()}")
            }
        }
    }

    fun pauseCurrentTask() {
        executionJob?.cancel()
        executionJob = null
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
            runAgentLoop(task)
        }
    }

    fun cancelCurrentTask() {
        executionJob?.cancel()
        executionJob = null
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
}
