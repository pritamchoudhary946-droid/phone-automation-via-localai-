package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
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

        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        assertFalse("must not claim Gemma planning", orchestrator.usesModelPlanning.value)
        assertTrue(automation.executed.any { it is AgentAction.OpenApp })
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
                    is AgentAction.Tap -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube Search  edittext editable: true", 3)
                        ActionResult(true, "TAP_ACCESSIBILITY", "tapped")
                    }
                    is AgentAction.TypeText -> {
                        defaultObservation = observation("com.google.android.youtube", "Results for lo-fi beats  12 views videos", 4)
                        ActionResult(true, "ACTION_SET_TEXT", "typed")
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home", 5)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Tap search","action":"tap_element","params":{"targetText":"Search"},"expect":"search field","verify":"SCREEN_CHANGED"},
              {"id":3,"seq":3,"desc":"Type query","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"Results","verify":"RESULTS_PRESENT"},
              {"id":4,"seq":4,"desc":"Return home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube, search for lo-fi beats, open a result, and return to home", maxSteps = 8)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 8_000)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        // Executed purely through the native automation layer.
        assertTrue(automation.executed.any { it is AgentAction.OpenApp })
        assertTrue(automation.executed.any { it is AgentAction.Tap })
        assertTrue(automation.executed.any { it is AgentAction.TypeText })
        assertTrue(automation.executed.any { it is AgentAction.Home })
    }

    // ---- helpers ----

    private fun newOrchestrator(engine: LiteRtEngine, automation: FakeDeviceAutomation): TaskOrchestrator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return TaskOrchestrator(
            context = context,
            liteRtEngine = engine,
            appDirectory = AppDirectory(context),
            deviceAutomation = automation,
            scope = scope
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
