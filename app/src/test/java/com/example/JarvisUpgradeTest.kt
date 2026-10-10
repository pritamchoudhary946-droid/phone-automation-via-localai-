package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.UiNode
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.FastPathDecision
import com.example.agent.HybridStepExecutor
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import com.example.agent.UiObservation
import com.example.model.LiteRtEngine
import com.example.planning.PlanStep
import com.example.planning.PlannerPromptBuilder
import com.example.planning.VerificationMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the Jarvis upgrade: the wider action vocabulary (submit / open_url / drag /
 * clipboard / page-content / web tools), the WebView-aware input heuristic, and the
 * user/task memory that feeds back into planning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisUpgradeTest {

    private lateinit var context: Context
    private lateinit var executor: HybridStepExecutor
    private val registry = ToolRegistry()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        executor = HybridStepExecutor(AppDirectory(context))
    }

    // --- Validation + catalogue ---

    @Test
    fun newActionsAreValidated() {
        assertTrue(registry.validateAction(AgentAction.Submit(null)) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.OpenUrl("https://example.com")) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.OpenUrl("")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.OpenUrl("file:///etc/passwd")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.DeepLink("spotify:artist:x")) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.DeepLink("")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.Drag("a", "b")) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.Drag(" ", "b")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.CopyText(null)) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.PasteText(null)) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.ReadPageContent) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.WebSearch("weather")) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.WebSearch("")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.ReadUrl("https://a.com")) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.ReadUrl("ftp://a.com")) is ToolValidationResult.Invalid)
    }

    @Test
    fun newToolsAreAdvertisedInThePrompt() {
        val actions = PlannerPromptBuilder.availableActions(registry)
        listOf(
            ToolRegistry.TOOL_SUBMIT,
            ToolRegistry.TOOL_OPEN_URL,
            ToolRegistry.TOOL_DEEP_LINK,
            ToolRegistry.TOOL_DRAG,
            ToolRegistry.TOOL_COPY_TEXT,
            ToolRegistry.TOOL_PASTE_TEXT,
            ToolRegistry.TOOL_READ_PAGE_CONTENT,
            ToolRegistry.TOOL_WEB_SEARCH,
            ToolRegistry.TOOL_READ_URL
        ).forEach { tool ->
            assertTrue("advertised actions are missing $tool", actions.contains(tool))
        }

        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            com.example.planning.PlanningRequest(
                userCommand = "test",
                desiredOutcome = "test",
                foregroundPackage = "com.android.launcher",
                screenSummary = "Home",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        listOf("submit", "open_url", "deep_link", "drag", "copy_text", "paste_text",
            "read_page_content", "web_search", "read_url").forEach { tool ->
            assertTrue("prompt catalogue is missing $tool", prompt.contains(tool))
        }
    }

    // --- WebView-aware input discovery ---

    @Test
    fun nonEditableEditTextClassCountsAsTextInput() {
        // A WebView/custom composer often reports isEditable=false but exposes an
        // EditText class; the heuristic must still let the fast path type into it.
        val steps = listOf(step(1, AgentAction.TypeText("query", null), VerificationMethod.TEXT_PRESENT))
        val node = node(text = null, id = "web:id/input", pkg = "com.example.browser",
            clickable = false, editable = false, className = "android.widget.EditText")
        val decision = executor.decide(steps, 0, obs("com.example.browser", listOf(node)), nudgeUsed = false)
        assertTrue("expected Execute, got $decision", decision is FastPathDecision.Execute)
    }

    @Test
    fun plainTextNodeIsNotATextInput() {
        val steps = listOf(step(1, AgentAction.TypeText("query", null), VerificationMethod.TEXT_PRESENT))
        val node = node(text = "label", id = "id/tv", pkg = "com.example.app", clickable = true)
        val decision = executor.decide(steps, 0, obs("com.example.app", listOf(node)), nudgeUsed = false)
        assertTrue("expected ModelReason, got $decision", decision is FastPathDecision.ModelReason)
    }

    // --- Fast path for the new actions ---

    @Test
    fun submitIsDeterministic() {
        val steps = listOf(step(1, AgentAction.Submit(null), VerificationMethod.SCREEN_CHANGED))
        val decision = executor.decide(steps, 0, obs("com.example.app", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
        assertTrue((decision as FastPathDecision.Execute).action is AgentAction.Submit)
    }

    @Test
    fun openUrlIsDeterministic() {
        val steps = listOf(step(1, AgentAction.OpenUrl("https://example.com"), VerificationMethod.SCREEN_CHANGED))
        val decision = executor.decide(steps, 0, obs("com.example.app", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
    }

    @Test
    fun readPageContentIsDeterministic() {
        val steps = listOf(step(1, AgentAction.ReadPageContent, VerificationMethod.NONE))
        val decision = executor.decide(steps, 0, obs("com.example.app", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
    }

    @Test
    fun pasteDefersWithoutEditableFieldButExecutesWithOne() {
        val steps = listOf(step(1, AgentAction.PasteText(null), VerificationMethod.SCREEN_CHANGED))
        val empty = executor.decide(steps, 0, obs("com.example.app", emptyList()), nudgeUsed = false)
        assertTrue("expected ModelReason, got $empty", empty is FastPathDecision.ModelReason)

        val field = node(null, "id/edit", "com.example.app", editable = true)
        val withField = executor.decide(steps, 0, obs("com.example.app", listOf(field)), nudgeUsed = false)
        assertTrue("expected Execute, got $withField", withField is FastPathDecision.Execute)
    }

    @Test
    fun dragExecutesOnlyWhenBothEndpointsAreVisible() {
        val steps = listOf(step(1, AgentAction.Drag("Card A", "Card B"), VerificationMethod.SCREEN_CHANGED))
        val both = listOf(
            node("Card A", "id/a", "com.example.app", clickable = true),
            node("Card B", "id/b", "com.example.app", clickable = true)
        )
        assertTrue(executor.decide(steps, 0, obs("com.example.app", both), false) is FastPathDecision.Execute)

        val onlyFrom = executor.decide(steps, 0, obs("com.example.app", listOf(both[0])), false)
        assertTrue("expected ModelReason, got $onlyFrom", onlyFrom is FastPathDecision.ModelReason)
    }

    // --- In-loop web tool + memory (orchestrator end-to-end) ---

    @Test
    fun webSearchCompletesWithHonestDigestWhenNoGatewayConfigured() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { ActionResult(true, "OK", "ok") }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Search the web","action":"web_search","params":{"query":"weather"},"expect":"digest","verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("look up the weather", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        // The web step is not a device action: nothing should have been dispatched.
        assertTrue("no device action expected, got ${automation.executed}", automation.executed.isEmpty())
        // The verification recorded must be the honest "unavailable" digest, not a faked result.
        assertTrue(
            "expected an honest unavailable digest",
            orchestrator.currentSteps.value.any { it.verificationResult.contains("unavailable", ignoreCase = true) }
        )
    }

    @Test
    fun completedTaskIsRememberedAndFedBackIntoPlanning() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                if (action is AgentAction.OpenApp) {
                    defaultObservation = observation("com.android.settings", "Settings", 2)
                }
                ActionResult(true, "OK", "ok")
            }
        }
        val runtime = FakeLlmRuntime(
            output = """{"steps":[{"id":1,"seq":1,"desc":"Launch Settings","action":"launch_app","params":{"appQuery":"settings"},"expect":"Settings foreground","verify":"FOREGROUND_PACKAGE"}]}"""
        )
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> runtime }).also {
            kotlinx.coroutines.runBlocking { it.initialize() }
        }
        installLauncherPackage(context, "com.android.settings", "Settings")

        val orchestrator = newOrchestrator(engine, automation)
        val goal = "open settings"
        orchestrator.rememberPreference("preferred_music_app", "Spotify", "PREFERENCE")

        orchestrator.startTask(goal, maxSteps = 4)
        val first = awaitTerminal(orchestrator)
        assertTrue("expected completion, got $first", first is OrchestratorState.Completed)

        // A fresh orchestrator (same DB, same engine) starts from Idle, so awaitTerminal
        // cannot see a stale Completed state from the previous run.
        runtime.prompts.clear()
        val second = newOrchestrator(engine, automation)
        second.startTask(goal, maxSteps = 4)
        val secondTerminal = awaitTerminal(second)
        assertTrue("expected completion, got $secondTerminal", secondTerminal is OrchestratorState.Completed)

        assertTrue(
            "planning prompt should carry the remembered preference",
            runtime.prompts.any { it.contains("Spotify") }
        )
        assertTrue(
            "planning prompt should carry the past similar task outcome",
            runtime.prompts.any { it.contains("past similar task") }
        )
    }

    // --- helpers ---

    private var fp = 0
    private fun obs(pkg: String?, nodes: List<UiNode>): UiObservation =
        UiObservation(pkg, nodes, "Active Package: ${pkg ?: "unknown"}", fingerprint = "fp_${pkg}_${fp++}")

    private fun step(id: Int, action: AgentAction, verify: VerificationMethod): PlanStep = PlanStep(
        id = id,
        sequence = id,
        description = action.getDisplayName(),
        action = action,
        rawActionName = action.javaClass.simpleName,
        expectedPostcondition = "post",
        verification = verify
    )

    private fun node(
        text: String?,
        id: String,
        pkg: String?,
        clickable: Boolean = false,
        editable: Boolean = false,
        className: String? = null
    ): UiNode = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        className = className ?: if (editable) "android.widget.EditText" else "android.widget.TextView",
        packageName = pkg,
        bounds = Rect(0, 0, 200, 80),
        isClickable = clickable,
        isScrollable = false,
        isEditable = editable,
        isCheckable = false,
        isChecked = false,
        isFocused = editable
    )

    private fun modelEngine(json: String): LiteRtEngine =
        buildTestEngine(context, runtimeFactory = { _, _ -> FakeLlmRuntime(output = json) }).also {
            kotlinx.coroutines.runBlocking { it.initialize() }
        }

    private fun newOrchestrator(engine: LiteRtEngine, automation: FakeDeviceAutomation): TaskOrchestrator =
        TaskOrchestrator(
            context = context,
            liteRtEngine = engine,
            appDirectory = AppDirectory(context),
            deviceAutomation = automation,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            toolRegistry = ToolRegistry()
        )

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
