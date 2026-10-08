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
import java.util.UUID

sealed class OrchestratorState {
    object Idle : OrchestratorState()
    data class Planning(val stepIndex: Int, val goal: String) : OrchestratorState()
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

    fun startTask(goal: String) {
        if (executionJob?.isActive == true) {
            cancelCurrentTask()
        }

        val taskId = UUID.randomUUID().toString()
        val newTask = TaskEntity(
            id = taskId,
            userGoal = goal,
            status = "RUNNING",
            currentStep = 0,
            maxSteps = 20
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

            try {
                while (currentStepIndex < maxSteps) {
                    // Check if Accessibility Service is enabled
                    val a11yService = AgentAccessibilityService.instance
                    if (a11yService == null) {
                        val failReason = "Accessibility Service is not active. Please enable OmniAgent in Android Accessibility settings."
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
                        Log.w(TAG, "Stuck detected: ${stuckStatus.reason}")
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
                    val historySummary = _currentSteps.value.takeLast(3).joinToString("\n") {
                        "Step ${it.stepIndex}: Action ${it.actionType} -> ${it.verificationResult}"
                    }

                    val decision = liteRtEngine.reasonAndPlan(
                        goal = task.userGoal,
                        screenSummary = screenSummary,
                        historySummary = historySummary,
                        lastActionVerification = lastVerificationResult
                    )

                    val action = decision.action
                    lastActionName = action.getDisplayName()

                    // Step 4: Risk Assessment Check
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

                    // Step 6: Action Execution
                    _orchestratorState.value = OrchestratorState.Executing(currentStepIndex, action, decision.thought)

                    val actionSuccess = executeAtomicAction(action, a11yService)
                    delay(700) // Yield for UI update and animation

                    // Step 7: Action Verification
                    val postSnapshot = a11yService.dumpHierarchy()
                    val verification = verifyStepOutcome(action, uiSnapshot, postSnapshot, actionSuccess)
                    lastVerificationResult = verification

                    // Step 8: Persist Step & Checkpoint
                    val stepEntity = TaskStepEntity(
                        taskId = task.id,
                        stepIndex = currentStepIndex,
                        reasoning = decision.thought,
                        actionType = action.javaClass.simpleName,
                        actionPayloadJson = action.getDisplayName(),
                        observationSummary = screenSummary.take(250),
                        verificationResult = verification,
                        status = if (actionSuccess) "SUCCESS" else "FAILED"
                    )

                    taskStepDao.insertStep(stepEntity)
                    _currentSteps.value = _currentSteps.value + stepEntity

                    val checkpoint = CheckpointEntity(
                        taskId = task.id,
                        stepIndex = currentStepIndex,
                        snapshotJson = "Step $currentStepIndex reached"
                    )
                    checkpointDao.saveCheckpoint(checkpoint)

                    currentStepIndex++
                    taskDao.updateTask(task.copy(currentStep = currentStepIndex))
                }

                // Reached max steps
                taskDao.updateTask(task.copy(status = "COMPLETED", resultSummary = "Reached maximum step limit ($maxSteps)"))
                _orchestratorState.value = OrchestratorState.Completed(task.id, "Completed maximum execution steps.")

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
        a11y: AgentAccessibilityService
    ): Boolean = withContext(Dispatchers.Default) {
        return@withContext when (action) {
            is AgentAction.OpenApp -> {
                val targetPkg = action.packageName ?: appDirectory.resolvePackage(action.appQuery)
                if (targetPkg != null) {
                    appDirectory.launchApp(targetPkg)
                } else {
                    false
                }
            }
            is AgentAction.Tap -> {
                if (action.targetText != null) {
                    a11y.clickByText(action.targetText)
                } else if (action.x != null && action.y != null) {
                    a11y.clickCoordinates(action.x.toFloat(), action.y.toFloat())
                } else {
                    false
                }
            }
            is AgentAction.TypeText -> {
                a11y.typeText(action.text)
            }
            is AgentAction.Scroll -> {
                a11y.scroll(action.direction)
            }
            is AgentAction.Back -> {
                a11y.pressBack()
            }
            is AgentAction.Home -> {
                a11y.pressHome()
            }
            is AgentAction.Wait -> {
                delay(action.durationMs)
                true
            }
            is AgentAction.ReadScreen -> {
                true
            }
            is AgentAction.Screenshot -> {
                val bmp = a11y.takeScreenshotFallback()
                bmp != null
            }
            else -> false
        }
    }

    private fun verifyStepOutcome(
        action: AgentAction,
        pre: UiTreeSnapshot,
        post: UiTreeSnapshot,
        executionResult: Boolean
    ): String {
        if (!executionResult) return "Execution failed (gesture or intent unhandled)"

        val changed = pre.getFingerprint() != post.getFingerprint()
        return when (action) {
            is AgentAction.OpenApp -> {
                if (post.packageName != null && post.packageName != pre.packageName) {
                    "Success: Switched package to ${post.packageName}"
                } else {
                    "Pending: Package transition loading"
                }
            }
            is AgentAction.Tap -> {
                if (changed) "Success: Screen state changed after tap" else "Unchanged: Tap did not alter visible elements"
            }
            is AgentAction.TypeText -> {
                if (changed) "Success: Input field updated" else "Input verified"
            }
            is AgentAction.Scroll -> {
                if (changed) "Success: New elements scrolled into view" else "End of scrollable region"
            }
            else -> "Success"
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
                // Execute confirmed action and continue
                val a11y = AgentAccessibilityService.instance
                if (a11y != null) {
                    executeAtomicAction(action, a11y)
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
