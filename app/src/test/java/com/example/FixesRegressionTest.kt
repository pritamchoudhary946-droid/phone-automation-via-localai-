package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.UiNode
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.DeviceAutomation
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.agent.UiObservation
import com.example.accessibility.UiTreeSnapshot
import com.example.model.EngineState
import com.example.model.GemmaInferenceEngine
import com.example.model.LiteRtEngine
import com.example.model.LiteRtModelManager
import com.example.model.LlmInferenceException
import com.example.model.LlmRuntime
import com.example.planning.PlannerPromptBuilder
import com.example.planning.PlanningRequest
import com.example.planning.PlanOutcome
import com.example.planning.PlanSource
import com.example.planning.StepDecisionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the bugs fixed in the model/logic audit. Each test pins a
 * specific defect so it cannot silently return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixesRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Bug: alias resolution used the FIRST alias whose name appeared anywhere in the
     * query. With overlapping aliases ("music" vs "youtube music") that picks the
     * wrong, shorter match. Resolve the LONGEST matching alias instead.
     */
    @Test
    fun aliasResolutionPrefersLongestMatchNotSubstring() {
        installLauncherPackage(context, "com.google.android.youtube", "YouTube")
        installLauncherPackage(context, "com.google.android.apps.youtube.music", "YouTube Music")
        installLauncherPackage(context, "com.spotify.music", "Spotify")
        val directory = AppDirectory(context)

        // "music" is a substring of "youtube music"; the longer alias must win.
        assertEquals("com.google.android.apps.youtube.music", directory.resolvePackage("open youtube music"))
        // A plain "music" request still resolves via the shorter alias.
        assertEquals("com.spotify.music", directory.resolvePackage("open music"))

        // "store" is a substring of "play store": "play store" must win.
        installLauncherPackage(context, "com.android.vending", "Play Store")
        assertEquals("com.android.vending", directory.resolvePackage("open the play store"))
    }

    /** Bug: dynamic resolution could miss an app whose label matched. Resolve by label. */
    @Test
    fun dynamicResolutionFindsAppByLabel() {
        installLauncherPackage(context, "com.example.widgetron", "Widgetron")
        val directory = AppDirectory(context)
        assertEquals("com.example.widgetron", directory.resolvePackage("widgetron"))
    }

    /** Bug: empty/blank queries must not resolve to a random installed app. */
    @Test
    fun blankQueryResolvesToNull() {
        installLauncherPackage(context, "com.whatsapp", "WhatsApp")
        val directory = AppDirectory(context)
        assertEquals(null, directory.resolvePackage("   "))
    }

    /**
     * Bug: the "repair" attempt sent the identical prompt again, so a malformed
     * response was never actually corrected. The second prompt must carry an explicit
     * correction block derived from the rejected output.
     */
    @Test
    fun malformedPlanTriggersRealRepairPromptThenSucceeds() = runBlocking {
        val goodPlan =
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}]}"""
        val runtime = SequenceLlmRuntime(listOf("not json at all", goodPlan))
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val inference = GemmaInferenceEngine(runtimeFactory = { _, _ -> runtime })
        val engine = buildTestEngineWith(context, inference, modelManager)
        assertTrue(engine.initialize())

        val outcome = engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertTrue(outcome is PlanOutcome.Planned)
        assertEquals(PlanSource.GEMMA_MODEL, (outcome as PlanOutcome.Planned).source)
        assertEquals(2, runtime.prompts.size)
        assertTrue(
            "repair prompt must explain the rejection",
            runtime.prompts[1].contains("CORRECTION REQUIRED")
        )
        // The two prompts must differ, otherwise the repair was a no-op.
        assertTrue(runtime.prompts[0] != runtime.prompts[1])
    }

    @Test
    fun repairNoteIncludesReasonAndRejectedOutput() {
        val note = PlannerPromptBuilder.buildRepairNote("""{"bad":true}""", "Unknown action 'fly'")
        assertTrue(note.contains("Unknown action 'fly'"))
        assertTrue(note.contains("CORRECTION REQUIRED"))
        assertTrue(note.contains("""{"bad":true}"""))
    }

    @Test
    fun planningPromptIncludesActionParameterCatalog() {
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "Open YouTube",
                desiredOutcome = "Open YouTube",
                foregroundPackage = "com.android.launcher",
                screenSummary = "Home",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = listOf("launch_app", "enter_text")
            )
        )
        assertTrue(prompt.contains("Action parameters:"))
        assertTrue(prompt.contains("launch_app"))
        assertTrue(prompt.contains("enter_text"))
    }

    /**
     * Bug: the disabled state of an interactive control was omitted from the screen
     * summary, so the model could not tell a greyed-out control from an active one.
     */
    @Test
    fun compactSummaryFlagsDisabledInteractiveNodes() {
        val disabled = node(isEnabled = false, isClickable = true).toCompactString(0)
        assertTrue(disabled.contains("[disabled]"))
        val enabled = node(isEnabled = true, isClickable = true).toCompactString(0)
        assertTrue(!enabled.contains("[disabled]"))
    }

    /** Bug: the context window was left unbounded; a bounded value must be pinned. */
    @Test
    fun engineContextWindowIsBounded() {
        assertEquals(2048, com.example.model.LiteRtLmRuntime.MAX_CONTEXT_TOKENS)
    }

    /**
     * Bug: confirming a risky action re-entered the loop from `currentStep + 1`,
     * skipping the step whose action had never actually run. After approval the
     * approved action must execute and the task must continue to completion.
     */
    @Test
    fun approvingRiskyActionExecutesItAndDoesNotSkipSteps() = runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.Tap -> {
                        defaultObservation = observation("com.shop.app", "Order confirmed", 2)
                        ActionResult(true, "TAP_ACCESSIBILITY", "tapped")
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home screen", 3)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = buildTestEngine(
            context,
            runtimeFactory = { _, _ ->
                FakeLlmRuntime(
                    output = """{"steps":[
                      {"id":1,"seq":1,"desc":"Tap buy","action":"tap_element","params":{"targetText":"Buy now"},"expect":"order","verify":"SCREEN_CHANGED"},
                      {"id":2,"seq":2,"desc":"Go home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
                    ]}"""
                )
            }
        ).also { it.initializeBlocking() }

        val orchestrator = TaskOrchestrator(
            context = context,
            liteRtEngine = engine,
            appDirectory = AppDirectory(context),
            deviceAutomation = automation,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )

        orchestrator.startTask("Buy now then go home", maxSteps = 8)

        // The risky tap must pause for confirmation before anything is dispatched.
        val awaiting = awaitState(orchestrator) { it is OrchestratorState.WaitingConfirmation }
        assertTrue("expected WaitingConfirmation, got ${orchestrator.orchestratorState.value}", awaiting is OrchestratorState.WaitingConfirmation)
        assertTrue("nothing should be dispatched before confirmation", automation.executed.isEmpty())

        orchestrator.confirmRiskAction(true)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 8_000)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        // The approved tap actually ran, and the following Home step was not skipped.
        assertTrue("approved tap must execute", automation.executed.any { it is AgentAction.Tap })
        assertTrue("home step must still run", automation.executed.any { it is AgentAction.Home })
        val plan = orchestrator.activePlan.value!!
        assertTrue(
            "all required steps completed: ${plan.steps.map { it.status }}",
            plan.steps.all { it.status == com.example.planning.PlanStepStatus.COMPLETED }
        )
    }

    // ---- helpers ----

    private fun node(isEnabled: Boolean, isClickable: Boolean): UiNode = UiNode(
        id = "node_0",
        text = "Buy now",
        contentDescription = null,
        className = "android.widget.Button",
        packageName = "com.shop.app",
        bounds = android.graphics.Rect(0, 0, 100, 50),
        isClickable = isClickable,
        isScrollable = false,
        isEditable = false,
        isCheckable = false,
        isChecked = false,
        isFocused = false,
        isEnabled = isEnabled
    )

    private fun buildTestEngineWith(
        context: Context,
        inference: GemmaInferenceEngine,
        modelManager: LiteRtModelManager
    ): LiteRtEngine {
        val planner = com.example.planning.AgentPlanner(
            inferenceEngine = inference,
            modelManager = modelManager
        )
        return LiteRtEngine(context, modelManager, inference, planner)
    }

    private fun LiteRtEngine.initializeBlocking(): Boolean {
        val engine = this
        return runBlocking { engine.initialize() }
    }

    private suspend fun awaitState(
        orchestrator: TaskOrchestrator,
        timeoutMs: Long = 4_000,
        predicate: (OrchestratorState) -> Boolean
    ): OrchestratorState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = orchestrator.orchestratorState.value
            if (predicate(state)) return state
            delay(50)
        }
        return orchestrator.orchestratorState.value
    }

    private suspend fun awaitTerminal(
        orchestrator: TaskOrchestrator,
        timeoutMs: Long = 6_000
    ): OrchestratorState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = orchestrator.orchestratorState.value
            if (state is OrchestratorState.Completed || state is OrchestratorState.Failed) return state
            delay(50)
        }
        return orchestrator.orchestratorState.value
    }

    /** Returns a scripted output per call so the repair attempt can be observed. */
    private class SequenceLlmRuntime(private val outputs: List<String>) : LlmRuntime {
        val prompts = mutableListOf<String>()
        private var initialized = false

        override suspend fun initialize() { initialized = true }
        override fun isReady(): Boolean = initialized
        override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String {
            val index = prompts.size
            prompts.add(prompt)
            if (index >= outputs.size) throw LlmInferenceException("no more scripted outputs")
            return outputs[index]
        }
        override suspend fun smokeTest(): Boolean = true
        override fun backendLabel(): String = "CPU"
        override fun close() { initialized = false }
    }
}
