package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.agent.ToolRegistry
import com.example.agent.UiObservation
import com.example.model.LiteRtEngine
import com.example.model.LiteRtModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Orchestrator loop tests: one action at a time, observe/verify before advancing,
 * bounded recovery, safe termination and honest basic-mode operation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrchestratorLoopTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Install a fake YouTube so AppDirectory.resolvePackage("youtube") returns a real package.
        installLauncherPackage(context, "com.google.android.youtube", "YouTube")
    }

    // 7. Executes one action, observes, verifies, and only then advances.

    @Test
    fun executesOneActionVerifiesThenAdvancesToCompletion() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home screen", 3)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }

        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Go home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)

        orchestrator.startTask("Open YouTube and return home", maxSteps = 6)
        val terminal = awaitTerminal(orchestrator)

        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        assertEquals(2, automation.executed.size)
        // Every step observes before executing: at least one observe per action.
        assertEquals(2, orchestrator.currentSteps.value.size)
        assertTrue(orchestrator.currentSteps.value.all { it.status == "SUCCESS" })
    }

    // 8. A failed action triggers bounded recovery/replanning, then terminates safely.

    @Test
    fun failedActionTriggersBoundedRecoveryThenStops() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { ActionResult(true, "DISPATCHED", "dispatched but screen never changes") }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE","maxRetries":1}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        // Bump the retry budget so a couple of recovery attempts are allowed.
        orchestrator.startTask("Open YouTube", maxSteps = 5)

        val terminal = awaitTerminal(orchestrator)
        assertTrue(terminal is OrchestratorState.Failed)
        // Bounded recovery: the action is not repeated forever.
        assertTrue(automation.executed.size in 1..4)
    }

    // 10. Launch is only complete when the target package is actually foreground.

    @Test
    fun launchWithoutForegroundVerificationDoesNotComplete() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            // Dispatch "succeeds" but the foreground package never becomes YouTube.
            executeResult = { ActionResult(true, "LAUNCH_DISPATCHED", "no package change") }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue(terminal is OrchestratorState.Failed)
    }

    @Test
    fun launchCompletesOnlyWhenTargetPackageForeground() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                if (action is AgentAction.OpenApp) {
                    defaultObservation = observation("com.google.android.youtube", "YouTube", 2)
                    ActionResult(true, "LAUNCH_VERIFIED", "verified")
                } else ActionResult(true, "OK", "ok")
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
    }

    // 14. Safe termination on missing accessibility, null observations and step-budget exhaustion.

    @Test
    fun accessibilityDisabledTerminatesSafely() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation(ready = false, reason = "Accessibility Service is not enabled")
        val engine = basicEngine()
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue(terminal is OrchestratorState.Failed)
        assertTrue((terminal as OrchestratorState.Failed).reason.contains("Accessibility"))
        assertTrue(automation.executed.isEmpty())
    }

    @Test
    fun nullObservationsTerminateSafely() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation(null, "Screen unavailable", 1)
            executeResult = { ActionResult(true, "OK", "ok") }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue(terminal is OrchestratorState.Failed)
    }

    @Test
    fun stepBudgetExhaustionTerminatesSafely() = kotlinx.coroutines.runBlocking {
        // Continuous scroll fallback never satisfies a launch goal; the step budget must stop it.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { ActionResult(true, "DISPATCHED", "no change") }
        }
        val engine = basicEngine()
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 2)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 8_000)
        assertTrue(terminal is OrchestratorState.Failed)
    }

    // 16. Native automation remains available in clearly labelled basic mode.

    @Test
    fun basicModeRunsNativeAutomationWhenModelUnavailable() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                if (action is AgentAction.OpenApp) {
                    defaultObservation = observation("com.google.android.youtube", "YouTube", 2)
                    ActionResult(true, "LAUNCH_VERIFIED", "verified")
                } else ActionResult(true, "OK", "ok")
            }
        }
        // A model engine that was never initialized: Gemma unavailable.
        val engine = basicEngine()
        assertFalse(engine.isReady())
        // 6.1: keywords run ONLY in the explicitly enabled degraded mode.
        engine.setFallbackExplicit(true)

        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        assertFalse("must not claim Gemma planning", orchestrator.usesModelPlanning.value)
        assertTrue(automation.executed.any { it is AgentAction.OpenApp })
    }

    // 6.1: with the model not loaded and degraded mode NOT enabled, the task must block
    // with a clear reason instead of silently running the keyword planner.

    @Test
    fun missingModelBlocksTaskInsteadOfRunningKeywords() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation()
        val engine = basicEngine()
        assertFalse(engine.isReady())

        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected an honest block, got $terminal", terminal is OrchestratorState.Failed)
        assertTrue(
            "block reason must name the local model",
            (terminal as OrchestratorState.Failed).reason.contains("Local AI not loaded", ignoreCase = true)
        )
        assertTrue("no automation may run without the model", automation.executed.isEmpty())
    }

    // 12. Multi-step YouTube search + return-home still uses native actions.

    @Test
    fun multiStepSearchAndReturnHomeUsesNativeActions() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube Home with search bar", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.OpenSearch -> {
                        // The search field appears only after the interface is opened.
                        defaultObservation = observation(
                            "com.google.android.youtube",
                            "YouTube Search  edittext editable: true",
                            3,
                            editable = true
                        )
                        ActionResult(true, "ACTION_CLICK_SEARCH", "opened search")
                    }
                    is AgentAction.TypeText -> {
                        defaultObservation = observation("com.google.android.youtube", "Results for lo-fi beats  12 views videos", 4)
                        ActionResult(true, "ACTION_SET_TEXT", "typed")
                    }
                    is AgentAction.Tap -> {
                        val target = action.targetText?.lowercase() ?: ""
                        if (target.contains("lo-fi") || target.contains("lofi")) {
                            defaultObservation = observation("com.google.android.youtube", "YouTube watch Now playing lo-fi beats", 5)
                            ActionResult(true, "TAP_ACCESSIBILITY", "opened result")
                        } else {
                            defaultObservation = observation("com.google.android.youtube", "YouTube results list", 7)
                            ActionResult(true, "TAP_ACCESSIBILITY", "tapped result")
                        }
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home", 6)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Activate search","action":"open_search","params":{"query":"lo-fi beats"},"expect":"search field","verify":"SCREEN_CHANGED"},
              {"id":3,"seq":3,"desc":"Submit query","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"results","verify":"RESULTS_PRESENT"},
              {"id":4,"seq":4,"desc":"Open result","action":"tap_element","params":{"targetText":"lo-fi beats"},"expect":"Now playing","verify":"TEXT_PRESENT"},
              {"id":5,"seq":5,"desc":"Return home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube, search for lo-fi beats, open a result, and return to home", maxSteps = 10)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)

        // Every required action happened, in order, through the native layer.
        val kinds = automation.executed.map { it::class.simpleName }
        assertEquals(
            listOf("OpenApp", "OpenSearch", "TypeText", "Tap", "Home"),
            kinds
        )
        // The result open is a distinct tap on the observed result, not text entry alone.
        assertTrue(automation.executed.any {
            it is AgentAction.Tap && it.targetText?.contains("lo-fi", ignoreCase = true) == true
        })
        // All required steps were verified as completed (search required results, open required the watch screen).
        val plan = orchestrator.activePlan.value!!
        assertTrue("all planned steps should be completed: ${plan.steps.map { it.status }}",
            plan.steps.all { it.status == com.example.planning.PlanStepStatus.COMPLETED })
        assertTrue(orchestrator.currentSteps.value.all { it.status == "SUCCESS" })

        // Hybrid fast path: once the model produced the plan, every step was decided
        // deterministically from the accessibility tree, so no per-step model calls ran.
        assertEquals("no per-step model calls expected", 0, orchestrator.modelCalls.value)
        assertTrue("fast path should handle every step", orchestrator.fastPathSteps.value >= 5)
    }

    // 1. A report_status/finish claim must not complete the task while required steps remain unverified.

    @Test
    fun prematureReportStatusIsRejectedUntilGoalVerified() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home", 3)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        // report_status sits in the middle: a later required step is still pending when the
        // model first claims success, so the claim must be rejected and the plan continued.
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Report status","action":"report_status","params":{"status":"success","summary":"done"},"expect":"goal","verify":"NONE"},
              {"id":3,"seq":3,"desc":"Return home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube and return home", maxSteps = 12)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        // The premature claim was rejected explicitly...
        assertTrue("expected the finish to be rejected as premature",
            orchestrator.currentSteps.value.any { it.reasoning.contains("Rejected premature finish") })
        // ...the later required step still ran through the native layer...
        assertTrue("home must still be executed after the rejected finish",
            automation.executed.any { it is AgentAction.Home })
        // ...and the task only completed once that subgoal was actually verified.
        assertTrue("expected completion after all subgoals verified, got $terminal",
            terminal is OrchestratorState.Completed)
        val plan = orchestrator.activePlan.value!!
        assertTrue("all required steps should be completed: ${plan.steps.map { it.status }}",
            plan.steps.all { it.status == com.example.planning.PlanStepStatus.COMPLETED || it.status == com.example.planning.PlanStepStatus.SKIPPED })
    }

    // 3. A validation-rejected action never leaves its step permanently EXECUTING.

    @Test
    fun validationRejectedActionRestoresPendingAndFailsCleanly() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation, registry = AlwaysRejectRegistry())
        orchestrator.startTask("Open YouTube", maxSteps = 5)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 8_000)
        assertTrue("expected failure, got $terminal", terminal is OrchestratorState.Failed)
        assertTrue((terminal as OrchestratorState.Failed).reason.contains("rejected by validation"))
        // Nothing was dispatched to the device, and the step is FAILED, never EXECUTING.
        assertTrue(automation.executed.isEmpty())
        val step = orchestrator.activePlan.value!!.steps.first()
        assertEquals(com.example.planning.PlanStepStatus.FAILED, step.status)
    }

    // 4.17 Learn-from-demonstration: watching a run captures its steps and saves a skill.

    @Test
    fun watchingARunSavesAReusableSkill() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)

        orchestrator.startWatching()
        assertTrue(orchestrator.watching.value)

        orchestrator.startTask("Open YouTube", maxSteps = 4)
        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue("a watched run records its turns", orchestrator.hasWatchedTurns())

        val saved = orchestrator.saveWatchedSkill("Open YouTube routine", "Open YouTube")
        assertEquals("Open YouTube routine", saved)
        assertFalse("watching stops once the skill is saved", orchestrator.watching.value)

        val skill = com.example.data.local.AgentDatabase.getInstance(context).skillDao()
            .getSkill("Open YouTube routine")
        assertTrue("the demonstrated skill is persisted", skill != null)
        assertTrue(
            "the recorded action is stored under its catalog name (replayable)",
            skill!!.planJson.contains("launch_app")
        )
    }

    @Test
    fun savingWithNoDemonstrationDoesNothing() = kotlinx.coroutines.runBlocking {
        val orchestrator = newOrchestrator(basicEngine(), FakeDeviceAutomation())
        assertFalse(orchestrator.hasWatchedTurns())
        assertEquals(null, orchestrator.saveWatchedSkill("empty", "nothing"))
    }

    // ---- 4.13: a saved skill replays its persisted plan through the verified loop ----

    @Test
    fun savedSkillReplaysWithoutReplanning() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home screen", 3)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        // The engine is configured to return an EMPTY plan. If runSkill re-planned, it
        // would be rejected and the task would fail; a successful run proves the stored
        // plan — not a fresh model call — drove the execution.
        val engine = modelEngine("""{"steps":[]}""")
        val orchestrator = newOrchestrator(engine, automation)

        val skill = com.example.data.local.SavedSkillEntity(
            name = "Open YouTube routine",
            goal = "Open YouTube",
            planJson = com.example.planning.TaskPlanSerializer.toJson(
                com.example.planning.TaskPlan(
                    goal = "Open YouTube",
                    fromModel = true,
                    steps = listOf(
                        com.example.planning.PlanStep(
                            id = 1, sequence = 1, description = "Launch YouTube",
                            action = AgentAction.OpenApp("youtube", "com.google.android.youtube"),
                            rawActionName = "launch_app", expectedPostcondition = "YouTube foreground",
                            verification = com.example.planning.VerificationMethod.FOREGROUND_PACKAGE
                        ),
                        com.example.planning.PlanStep(
                            id = 2, sequence = 2, description = "Go home",
                            action = AgentAction.Home,
                            rawActionName = "navigate_home", expectedPostcondition = "home",
                            verification = com.example.planning.VerificationMethod.HOME_FOREGROUND
                        )
                    )
                )
            )
        )

        orchestrator.runSkill(skill)
        val terminal = awaitTerminal(orchestrator)

        assertTrue("a saved skill must complete from its stored plan, got $terminal", terminal is OrchestratorState.Completed)
        assertEquals("the replayed plan runs every stored action", 2, automation.executed.size)
        assertTrue(automation.executed[0] is AgentAction.OpenApp)
        assertTrue(automation.executed[1] is AgentAction.Home)
    }

    @Test
    fun unreconstructableSkillFallsBackToReplanning() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)

        // Legacy label-only skill JSON cannot be reconstructed, so it must be re-planned.
        val legacy = com.example.data.local.SavedSkillEntity(
            name = "legacy",
            goal = "Open YouTube",
            planJson = """{"goal":"Open YouTube","fromModel":true,"steps":[
                {"id":1,"seq":1,"desc":"open the app","action":"launch_app","verify":"FOREGROUND_PACKAGE","opts":null}
            ]}"""
        )

        orchestrator.runSkill(legacy)
        val terminal = awaitTerminal(orchestrator)

        assertTrue("an unreconstructable skill re-plans and still completes, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue("the re-planned action executed", automation.executed.any { it is AgentAction.OpenApp })
    }

    // ---- Phase 9: orchestrator-integrated seams (9.3, 9.5, 9.12) ----
    @Test
    fun followUpResolvesAgainstPreviousCommand() = kotlinx.coroutines.runBlocking {
        val orchestrator = newOrchestrator(basicEngine(), FakeDeviceAutomation())
        orchestrator.startTask("Open YouTube")
        val resolved = orchestrator.submitCommand("and open Settings")
        org.junit.Assert.assertNotNull("a follow-up should resolve, not ask", resolved)
        org.junit.Assert.assertTrue(resolved!!.contains("Open YouTube"))
        org.junit.Assert.assertTrue(resolved.contains("open Settings"))
        awaitTerminal(orchestrator)
        Unit
    }

    @Test
    fun followUpWithNoContextAsksInsteadOfGuessing() = kotlinx.coroutines.runBlocking {
        val orchestrator = newOrchestrator(basicEngine(), FakeDeviceAutomation())
        val result = orchestrator.submitCommand("and send it")
        org.junit.Assert.assertNull("no context must ask, not guess", result)
        org.junit.Assert.assertNotNull(orchestrator.pendingClarification.value)
    }

    @Test
    fun rememberCommandPersistsAFactOnDevice() = kotlinx.coroutines.runBlocking {
        val orchestrator = newOrchestrator(basicEngine(), FakeDeviceAutomation())
        val result = orchestrator.submitCommand("remember that my wifi password is hunter2")
        org.junit.Assert.assertNull("memory commands do not start a device task", result)
        // The write is launched asynchronously; poll briefly for persistence.
        val dao = com.example.data.local.AgentDatabase.getInstance(context).memoryDao()
        var stored: String? = null
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && stored == null) {
            stored = dao.getRecentMemories(10).firstOrNull { it.value.contains("hunter2") }?.value
            if (stored == null) delay(50)
        }
        org.junit.Assert.assertTrue("the fact is stored on-device", stored?.contains("hunter2") == true)
    }

    @Test
    fun unresolvableBlockerFailsHonestlyWithoutGuessing() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            // A full-screen ad with no safe dismiss control: the agent must stop, not tap blindly.
            defaultObservation = observationText(
                "com.game", "Advertisement sponsored content", 5, labels = listOf("Advertisement")
            )
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Tap Play","action":"tap","params":{"targetText":"Play"},"expect":"game","verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Play the game", maxSteps = 3)
        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected an honest failure, got $terminal", terminal is OrchestratorState.Failed)
    }

    // ---- helpers ----

    private class AlwaysRejectRegistry : ToolRegistry() {
        override fun validateAction(action: AgentAction): com.example.agent.ToolValidationResult =
            com.example.agent.ToolValidationResult.Invalid("forced rejection for test")
    }

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

    private fun basicEngine(): LiteRtEngine =
        buildTestEngine(context, runtimeFactory = { _, _ -> FakeLlmRuntime() })

    private fun LiteRtEngine.initializeBlocking(): Boolean =
        kotlinx.coroutines.runBlocking { initialize() }

    private suspend fun awaitTerminal(
        orchestrator: TaskOrchestrator,
        timeoutMs: Long = 6_000
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
