package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.SearchAffordanceHeuristics
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import com.example.agent.UiObservation
import com.example.model.HeuristicPlanner
import com.example.model.AgentEngine
import com.example.planning.PlanParseResult
import com.example.planning.PlanStepStatus
import com.example.planning.TaskPlanParser
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
 * Regression tests for the YouTube search failure: a search field is not always
 * visible right after launch, so text entry must be preceded by opening the search
 * interface from fresh accessibility nodes. Nothing here may claim text entry
 * succeeded unless it actually did, and results must be verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchRecoveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installLauncherPackage(context, "com.google.android.youtube", "YouTube")
    }

    // --- 1. Search icon visible before opening search ---------------------------

    @Test
    fun fallbackPlanOpensSearchBeforeTypingTheQuery() {
        val plan = HeuristicPlanner().toFallbackPlan(
            "Open YouTube, search for lo-fi beats, open a result, and return to home"
        )
        val openSearchIdx = plan.steps.indexOfFirst { it.action is AgentAction.OpenSearch }
        val typeIdx = plan.steps.indexOfFirst { it.action is AgentAction.TypeText }

        assertTrue("fallback plan must open the search interface", openSearchIdx >= 0)
        assertTrue("fallback plan must contain a text entry step", typeIdx >= 0)
        assertTrue("open_search must come before enter_text", openSearchIdx < typeIdx)
        // The open_search step still carries the query for the native-intent fallback.
        assertTrue((plan.steps[openSearchIdx].action as AgentAction.OpenSearch).query?.contains("lo-fi") == true)
    }

    @Test
    fun heuristicPicksOpenSearchWhenOnlyASearchIconIsVisible() {
        val decision = HeuristicPlanner().reasonAndPlan(
            goal = "Open YouTube and search for lo-fi beats",
            screenSummary = "Active Package: com.google.android.youtube\n" +
                "[0] [Button/Clickable] \"Search\" id=\"search_button\" at (970, 100)",
            historySummary = "Step 0: Action OpenApp (youtube) -> Success",
            lastActionVerification = "Success: verified foreground package"
        )
        assertTrue(
            "a visible search icon (no editable field) must lead to open_search, got ${decision.action}",
            decision.action is AgentAction.OpenSearch
        )
        assertEquals("lo-fi beats", (decision.action as AgentAction.OpenSearch).query)
    }

    // --- 4. No editable node ----------------------------------------------------

    @Test
    fun heuristicRecognisesEditableFieldOnlyWhenPresent() {
        val planner = HeuristicPlanner()
        assertFalse(planner.isEditableFieldVisible("Active Package: com.google.android.youtube\n[0] [Button/Clickable] \"Search\""))
        assertTrue(planner.isEditableFieldVisible("Active Package: com.google.android.youtube\n[0] [Input] \"\" at (500, 100)"))
        assertTrue(planner.isEditableFieldVisible("EditText editable: true"))
    }

    // --- 3. Editable field appearing after tapping search ----------------------

    @Test
    fun heuristicTypesIntoFieldAfterItAppears() {
        val decision = HeuristicPlanner().reasonAndPlan(
            goal = "Open YouTube and search for lo-fi beats",
            screenSummary = "Active Package: com.google.android.youtube\n[0] [Input] \"\" id=\"search_edit\" at (500, 100)",
            historySummary = "Step 1: Action OpenSearch -> Success",
            lastActionVerification = "Success: search field now present on screen"
        )
        assertTrue("editable field present must lead to text entry, got ${decision.action}", decision.action is AgentAction.TypeText)
        assertEquals("lo-fi beats", (decision.action as AgentAction.TypeText).text)
    }

    // --- Search affordance ranking (fresh-node lookup, no blind coordinates) ----

    @Test
    fun searchAffordanceScoringPrefersEditableAndSearchLabelledNodes() {
        // Unrelated node never qualifies.
        assertEquals(0, SearchAffordanceHeuristics.scoreSearchAffordance("Home", null, "id/home", true, true, false))

        // A search icon/button qualifies.
        val searchIcon = SearchAffordanceHeuristics.scoreSearchAffordance("Search", null, "id/search_button", true, true, false)
        assertTrue(searchIcon > 0)

        // A content-description match qualifies even without text.
        val byDesc = SearchAffordanceHeuristics.scoreSearchAffordance(null, "Search videos", "com.google.android.youtube:id/action_search", true, true, false)
        assertTrue(byDesc > 0)

        // An editable search field ranks highest.
        val editable = SearchAffordanceHeuristics.scoreSearchAffordance(null, null, "id/search_edit_text", true, true, true)
        assertTrue(editable > searchIcon)
    }

    // --- Parser + validation accept the new capability --------------------------

    @Test
    fun parserAndRegistryAcceptOpenSearch() {
        val parser = TaskPlanParser()
        val result = parser.parse(
            """{"steps":[{"id":1,"seq":1,"desc":"Open search","action":"open_search","params":{"query":"lo-fi beats"},"verify":"SCREEN_CHANGED"}]}""",
            "g"
        )
        assertTrue(result is PlanParseResult.Success)
        val action = (result as PlanParseResult.Success).plan.steps.first().action
        assertTrue(action is AgentAction.OpenSearch)
        assertEquals("lo-fi beats", (action as AgentAction.OpenSearch).query)
        assertTrue(ToolRegistry().validateAction(action) is ToolValidationResult.Valid)
        assertTrue(ToolRegistry().getTool("open_search") != null)
    }

    // --- Orchestrator safety net: TypeText without an editable node -------------

    @Test
    fun orchestratorOpensSearchBeforeTypingWhenNoEditableNodeExists() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        // YouTube launched but the entry field is NOT yet visible.
                        defaultObservation = observation("com.google.android.youtube", "YouTube Home with search bar", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.OpenSearch -> {
                        // Opening search exposes the editable field.
                        defaultObservation = editableObservation("com.google.android.youtube", fieldText = "lo-fi beats", seed = 3)
                        ActionResult(true, "ACTION_CLICK_SEARCH", "opened search")
                    }
                    is AgentAction.TypeText -> {
                        defaultObservation = observation("com.google.android.youtube", "Search results: lo-fi beats 12 views video", 4)
                        ActionResult(true, "ACTION_SET_TEXT+IME_SEARCH", "typed and submitted")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        // A model plan that (incorrectly) goes straight to enter_text with no open_search.
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Search lo-fi beats","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"results","verify":"RESULTS_PRESENT"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube, search for lo-fi beats", maxSteps = 8)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)

        // The guard opened search first, then entered text — never a bare failed type.
        val kinds = automation.executed.map { it::class.simpleName }
        val openIdx = kinds.indexOf("OpenSearch")
        val typeIdx = kinds.indexOf("TypeText")
        assertTrue("open_search must be executed, got $kinds", openIdx >= 0)
        assertTrue("enter_text must be executed, got $kinds", typeIdx >= 0)
        assertTrue("open_search must precede enter_text, got $kinds", openIdx < typeIdx)
    }

    // --- No editable node AND no search affordance -> bounded, honest failure ----

    @Test
    fun noEditableNodeAndNoSearchAffordanceFailsHonestly() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube Home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.OpenSearch ->
                        ActionResult(false, "FAILED", "No search affordance or editable field exposed on the current screen.")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Search","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"results","verify":"RESULTS_PRESENT","maxRetries":0}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube, search for lo-fi beats", maxSteps = 6)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        assertTrue("must fail rather than fake success, got $terminal", terminal is OrchestratorState.Failed)
        // No text was ever entered, so the search step can never be completed.
        assertTrue(automation.executed.none { it is AgentAction.TypeText })
        val searchStep = orchestrator.activePlan.value!!.steps.first { it.rawActionName == "enter_text" }
        assertFalse("search step must not be completed", searchStep.status == PlanStepStatus.COMPLETED)
    }

    // --- Successful text entry and submission with verified results -----------

    @Test
    fun searchWithOpenSearchStepTypesSubmitsAndVerifiesResults() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube Home with search bar", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.OpenSearch -> {
                        defaultObservation = editableObservation("com.google.android.youtube", seed = 3)
                        ActionResult(true, "ACTION_CLICK_SEARCH", "opened search")
                    }
                    is AgentAction.TypeText -> {
                        defaultObservation = observation("com.google.android.youtube", "Search results: lo-fi beats 1.2M views video", 4)
                        ActionResult(true, "ACTION_SET_TEXT+IME_SEARCH", "typed and submitted")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Open search","action":"open_search","params":{"query":"lo-fi beats"},"expect":"search field","verify":"SCREEN_CHANGED"},
              {"id":3,"seq":3,"desc":"Search lo-fi beats","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"results","verify":"RESULTS_PRESENT"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube, search for lo-fi beats", maxSteps = 8)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        assertTrue("expected completion, got $terminal", terminal is OrchestratorState.Completed)
        assertEquals(
            listOf("OpenApp", "OpenSearch", "TypeText"),
            automation.executed.map { it::class.simpleName }
        )
        val plan = orchestrator.activePlan.value!!
        assertTrue("all steps must be completed: ${plan.steps.map { it.status }}",
            plan.steps.all { it.status == PlanStepStatus.COMPLETED })
    }

    // --- Text entry that never produces results must not be reported as success --

    @Test
    fun textEnteredWithoutResultsDoesNotComplete() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.google.android.youtube", "YouTube Home", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.TypeText -> ActionResult(true, "ACTION_SET_TEXT", "typed but screen unchanged")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        // Observation never becomes editable and never shows results.
        val engine = modelEngine(
            """{"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Search","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"results","verify":"RESULTS_PRESENT","maxRetries":1}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open YouTube, search for lo-fi beats", maxSteps = 6)

        val terminal = awaitTerminal(orchestrator, timeoutMs = 10_000)
        assertTrue("no results means no completion, got $terminal", terminal is OrchestratorState.Failed)
    }

    // ---- helpers ----

    private fun newOrchestrator(engine: AgentEngine, automation: FakeDeviceAutomation): TaskOrchestrator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return TaskOrchestrator(
            context = context,
            agentEngine = engine,
            appDirectory = AppDirectory(context),
            deviceAutomation = automation,
            scope = scope,
            toolRegistry = ToolRegistry()
        )
    }

    private fun modelEngine(json: String): AgentEngine =
        buildTestEngine(context, runtimeFactory = { FakeLlmRuntime(output = json) })
            .also { kotlinx.coroutines.runBlocking { it.initialize() } }

    private fun editableObservation(pkg: String?, fieldText: String = "", seed: Int): UiObservation {
        val node = UiNode(
            id = "com.google.android.youtube:id/search_edit_text",
            text = fieldText,
            contentDescription = "Search query",
            className = "android.widget.EditText",
            packageName = pkg,
            bounds = Rect(0, 0, 500, 100),
            isClickable = true,
            isScrollable = false,
            isEditable = true,
            isCheckable = false,
            isChecked = false,
            isFocused = true
        )
        return UiObservation.fromSnapshot(UiTreeSnapshot(pkg, listOf(node), timestamp = seed.toLong()))
    }

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
