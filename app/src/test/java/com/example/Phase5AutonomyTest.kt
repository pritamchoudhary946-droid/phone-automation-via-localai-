package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.FailureClassifier
import com.example.agent.FailureType
import com.example.agent.OrchestratorState
import com.example.agent.ProgressBudget
import com.example.agent.RecoveryStrategy
import com.example.agent.StuckLoopDetector
import com.example.agent.TaskOrchestrator
import com.example.agent.ToolRegistry
import com.example.model.LiteRtEngine
import com.example.planning.EpisodeDeriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5 — autonomy & self-healing (plans 5.1-5.10): deterministic failure
 * diagnosis, honest progress degradation, richer loop detection, episodic lessons,
 * and a bounded recovery cycle that escalates instead of faking success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase5AutonomyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installLauncherPackage(context, "com.google.android.youtube", "YouTube")
    }

    // --- 5.2 Failure classification -------------------------------------------

    @Test
    fun classifierNamesTimeoutBeforeScreenChange() {
        val d = FailureClassifier.classify(
            success = false,
            resultMessage = "TIMEOUT",
            resultMethod = "TIMEOUT",
            verificationDetail = "Step execution timed out",
            screenChanged = false,
            expectedPackage = null,
            observedPackage = "com.android.launcher",
            screenText = "Home"
        )
        assertEquals(FailureType.TIMEOUT, d.type)
    }

    @Test
    fun classifierDetectsWrongPackageOnLaunch() {
        val d = FailureClassifier.classify(
            success = false,
            resultMessage = "LAUNCH_DISPATCHED",
            resultMethod = "LAUNCH",
            verificationDetail = "Expected com.google.android.youtube but observed com.android.launcher",
            screenChanged = true,
            expectedPackage = "com.google.android.youtube",
            observedPackage = "com.android.launcher",
            screenText = "Home"
        )
        assertEquals(FailureType.WRONG_PACKAGE, d.type)
        assertEquals(RecoveryStrategy.REPLAN_NEW_APPROACH, d.suggestedRecovery)
    }

    @Test
    fun classifierDetectsMissingTargetAndSuggestsDiscovery() {
        val d = FailureClassifier.classify(
            success = false,
            resultMessage = "ok",
            resultMethod = "TAP_ACCESSIBILITY",
            verificationDetail = "target 'Search' not found on screen",
            screenChanged = false,
            expectedPackage = null,
            observedPackage = "com.google.android.youtube",
            screenText = "feed"
        )
        assertEquals(FailureType.TARGET_NOT_FOUND, d.type)
        assertEquals(RecoveryStrategy.SCROLL_TO_DISCOVER, d.suggestedRecovery)
    }

    @Test
    fun classifierReportsLoopAndValidationRejection() {
        val loop = FailureClassifier.classify(
            success = false, resultMessage = "no progress", resultMethod = "LOOP",
            verificationDetail = "repeated", screenChanged = true,
            expectedPackage = null, observedPackage = "p", screenText = "s",
            loopDetected = true
        )
        assertEquals(FailureType.LOOP_DETECTED, loop.type)

        val rejected = FailureClassifier.classify(
            success = false, resultMessage = "x", resultMethod = "VALIDATION",
            verificationDetail = "bad action", screenChanged = true,
            expectedPackage = null, observedPackage = "p", screenText = "s",
            validationRejected = true
        )
        assertEquals(FailureType.VALIDATION_REJECTED, rejected.type)
    }

    @Test
    fun classifierRequiresBothPermissionAndDialogSignals() {
        // Ordinary settings text containing "permission"/"allow" but no dialog cue must
        // NOT be misread as a blocking permission dialog (regression: `&&` binds tighter
        // than `||`, so the old unparenthesised condition matched any screen containing
        // the bare word "permission").
        val settings = FailureClassifier.classify(
            success = false,
            resultMessage = "ok",
            resultMethod = "TAP_ACCESSIBILITY",
            verificationDetail = "target 'Search' not found on screen",
            screenChanged = false,
            expectedPackage = null,
            observedPackage = "com.android.settings",
            screenText = "App permissions\nCamera permission is allowed for this app\nSearch settings"
        )
        assertEquals(FailureType.TARGET_NOT_FOUND, settings.type)

        // A real permission dialog (grant affordance + consent cue) is still detected.
        val dialog = FailureClassifier.classify(
            success = false,
            resultMessage = "ok",
            resultMethod = "TAP_ACCESSIBILITY",
            verificationDetail = "no change",
            screenChanged = false,
            expectedPackage = null,
            observedPackage = "com.android.permissioncontroller",
            screenText = "Allow YouTube to access your photos?\nWhile using the app  Don't allow"
        )
        assertEquals(FailureType.PERMISSION_DIALOG, dialog.type)
        assertEquals(RecoveryStrategy.PRESS_BACK_TO_UNBLOCK, dialog.suggestedRecovery)
    }

    // --- 5.10 Progress budget degradation --------------------------------------

    @Test
    fun progressBudgetDegradesInOrderThenEscalates() {
        val budget = ProgressBudget(maxSteps = 10, maxReplans = 1, maxExplores = 1)
        assertEquals(ProgressBudget.Stage.REPLAN, budget.nextStageAfterFailure(stepsRemaining = 5))
        budget.recordReplan()
        assertEquals(ProgressBudget.Stage.EXPLORE, budget.nextStageAfterFailure(stepsRemaining = 5))
        budget.recordExplore()
        assertEquals(ProgressBudget.Stage.ESCALATE, budget.nextStageAfterFailure(stepsRemaining = 5))
        // No budget left never returns REPLAN/EXPLORE again.
        assertFalse(budget.canReplan())
        assertFalse(budget.canExplore())
    }

    @Test
    fun progressBudgetStopsWhenStepsExhausted() {
        val budget = ProgressBudget(maxSteps = 2, maxReplans = 1, maxExplores = 1)
        assertEquals(ProgressBudget.Stage.STOP, budget.nextStageAfterFailure(stepsRemaining = 0))
        assertTrue(budget.exhausted(stepsRemaining = 0))
    }

    // --- 5.7 Longer-cycle loop detection ---------------------------------------

    @Test
    fun stuckDetectorCatchesRepeatedStateActionPairEvenAcrossLongerCycles() {
        val detector = StuckLoopDetector()
        // A 3-state cycle where the same (state, action) pair recurs, but no single
        // action repeats consecutively and the screen never freezes: only the pair
        // counter can see this dead end.
        detector.recordStep("fp1", "a")
        detector.recordStep("fp2", "b")
        detector.recordStep("fp3", "c")
        detector.recordStep("fp1", "a")
        detector.recordStep("fp2", "b")
        detector.recordStep("fp3", "c")
        val last = detector.recordStep("fp1", "a")
        assertTrue("repeated (state, action) pair must be detected as stuck", last.isStuck)
        assertEquals(RecoveryStrategy.REPLAN_NEW_APPROACH, last.suggestedRecovery)
    }

    @Test
    fun stuckDetectorResetClearsPairHistory() {
        val detector = StuckLoopDetector()
        repeat(2) { detector.recordStep("same", "tap") }
        detector.reset()
        val status = detector.recordStep("same", "tap")
        assertFalse(status.isStuck)
    }

    // --- 5.9 Episodic lessons ---------------------------------------------------

    @Test
    fun episodeDeriverNamesTheFailureAndRecovery() {
        assertTrue(EpisodeDeriver.lesson("FAILED", "wrong package", 0, null).contains("Blocked"))
        assertTrue(EpisodeDeriver.lesson("COMPLETED", "", 2, "scroll down").contains("Recovered"))
        assertTrue(EpisodeDeriver.lesson("COMPLETED", "", 0, null).contains("Clean run"))
    }

    @Test
    fun episodePromptBlockIsBounded() {
        val block = EpisodeDeriver.toPromptBlock(
            listOf("goal one" to "lesson one", "goal two" to "lesson two"),
            maxChars = 400
        )
        assertTrue(block.contains("goal one"))
        assertTrue(block.length <= 400)
    }

    // --- 5.4/5.6 Reflection & exploration seams --------------------------------

    @Test
    fun reflectionUsesTheModelWhenReadyAndFallsBackWhenNot() = kotlinx.coroutines.runBlocking {
        val request = com.example.planning.ReflectionRequest(
            goal = "Open YouTube",
            activeSubGoal = "Launch YouTube",
            lastAction = "launch_app",
            failureReason = "wrong package",
            screenSummary = "Home",
            foregroundPackage = "com.android.launcher"
        )
        val modelEngine = modelEngine("The app did not come forward; re-launch it.")
        assertEquals("The app did not come forward; re-launch it.", modelEngine.reflect(request))

        val basic = basicEngine()
        assertFalse(basic.isReady())
        assertEquals("wrong package", basic.reflect(request))
    }

    @Test
    fun explorationReturnsModelProbeWhenValidAndScrollOtherwise() = kotlinx.coroutines.runBlocking {
        val request = com.example.planning.ExplorationRequest(
            goal = "Open YouTube",
            missingTarget = "Search field",
            probesUsed = 0,
            screenSummary = "YouTube home",
            foregroundPackage = "com.google.android.youtube",
            availableActions = listOf("scroll", "tap_element")
        )
        val modelEngine = modelEngine("""{"thought":"look up","action":"scroll","params":{"direction":"up"}}""")
        val probe = modelEngine.exploreNextAction(request)
        assertTrue(probe is AgentAction.Scroll && probe.direction == "up")

        // A model that returns no usable action still yields a bounded, safe probe.
        val basic = basicEngine()
        val fallback = basic.exploreNextAction(request)
        assertTrue(fallback is AgentAction.Scroll && fallback.direction == "down")
    }

    @Test
    fun planningPromptCarriesPastLessons() {
        val prompt = com.example.planning.PlannerPromptBuilder.buildPlanningPrompt(
            com.example.planning.PlanningRequest(
                userCommand = "Open YouTube",
                desiredOutcome = "YouTube open",
                foregroundPackage = "com.android.launcher",
                screenSummary = "Home",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 5,
                availableActions = listOf("launch_app"),
                lessonsSummary = "- For \"Open YouTube\": Recovered after 1 attempt; 'scroll down' worked."
            )
        )
        assertTrue("lessons must be injected into the planning prompt", prompt.contains("Lessons from similar past tasks"))
        assertTrue(prompt.contains("scroll down"))
    }

    // --- 5.1/5.7 Integration: a run that cannot progress escalates, not fakes ----

    @Test
    fun unrecoverableRunEscalatesHonestlyInsteadOfFakingSuccess() = kotlinx.coroutines.runBlocking {
        // Dispatch always "succeeds" but the screen never advances, so the launch
        // postcondition can never be verified.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { ActionResult(true, "LAUNCH_DISPATCHED", "no package change") }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 6)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        assertTrue("expected failure, got $terminal", terminal is OrchestratorState.Failed)
        val failed = terminal as OrchestratorState.Failed
        assertTrue("an exhausted recovery must be escalated honestly", failed.escalated)
        assertNotNull("escalation must explain what it was blocked on", failed.blockedOn)
        assertNotNull("escalation must suggest a next move", failed.suggestion)
    }

    // ---- helpers ----

    private fun newOrchestrator(
        engine: LiteRtEngine,
        automation: FakeDeviceAutomation,
        registry: ToolRegistry = ToolRegistry()
    ): TaskOrchestrator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return TaskOrchestrator(
            context = context,
            liteRtEngine = engine,
            appDirectory = AppDirectory(context),
            deviceAutomation = automation,
            scope = scope,
            toolRegistry = registry
        )
    }

    private fun modelEngine(json: String): LiteRtEngine =
        buildTestEngine(context, runtimeFactory = { _, _ -> FakeLlmRuntime(output = json) }).also { it.initializeBlocking() }

    private fun basicEngine(): LiteRtEngine = buildTestEngine(context)

    private fun LiteRtEngine.initializeBlocking(): Boolean =
        kotlinx.coroutines.runBlocking { initialize() }

    private suspend fun awaitTerminal(
        orchestrator: TaskOrchestrator,
        timeoutMs: Long = 8_000
    ): OrchestratorState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = orchestrator.orchestratorState.value
            when (state) {
                is OrchestratorState.Completed, is OrchestratorState.Failed -> return state
                else -> delay(50)
            }
        }
        return orchestrator.orchestratorState.value
    }
}
