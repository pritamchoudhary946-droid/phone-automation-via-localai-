package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.model.HeuristicPlanner
import com.example.model.SubGoalKind
import com.example.planning.PlanParseResult
import com.example.planning.PlannerPromptBuilder
import com.example.planning.StepDecisionRequest
import com.example.planning.TaskPlanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 6 - Reasoning & Reliability Rescue. These tests pin the concrete fixes for
 * the reported defects: the model must actually plan (6.1), the keyword fallback must
 * never "open an app then scroll forever" (6.2), Hindi/Roman-Hindi messaging must
 * decompose into real subgoals (6.3/6.4), malformed JSON must be repaired rather than
 * killing the task (6.18), near-miss params must coerce (6.19), the observation handed
 * to the model must be fresh structured JSON (6.13/6.14), and the dead Web Skills
 * surface is gone while the in-loop web tools remain (6.10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase6ReliabilityTest {

    // --- 6.2: the generic scroll/read fallback is gone -------------------------

    @Test
    fun unmappableCommandNeverFallsThroughToScroll() {
        val planner = HeuristicPlanner()
        // A command the keyword planner cannot decompose must NOT become a scroll/read.
        val decision = planner.reasonAndPlan(
            goal = "just do the thing",
            screenSummary = "Active Package: com.android.launcher\nHome screen",
            historySummary = "",
            lastActionVerification = null
        )
        assertFalse("must not scroll as a generic fallback", decision.action is AgentAction.Scroll)
        assertFalse("must not read-screen as a generic fallback", decision.action is AgentAction.ReadScreen)
        assertTrue("must report it cannot plan", decision.action is AgentAction.FailTask)
    }

    // --- 6.3/6.4: Roman-Hindi messaging decomposes into real subgoals ----------

    @Test
    fun romanHindiMessagingDecomposesIntoRealSubgoals() {
        val subs = HeuristicPlanner().decomposeGoalIntoSubGoals("WhatsApp pe Prateek ko message karo")
        val kinds = subs.map { it.kind }
        assertTrue("must launch the messaging app: $kinds", SubGoalKind.LAUNCH_APP in kinds)
        assertTrue("must open app search: $kinds", SubGoalKind.OPEN_SEARCH in kinds)
        assertTrue("must type the contact: $kinds", SubGoalKind.TYPE_CONTACT in kinds)
        assertTrue("must open the chat: $kinds", SubGoalKind.OPEN_ITEM in kinds)
        assertEquals("WhatsApp", subs.first { it.kind == SubGoalKind.LAUNCH_APP }.target)
        assertEquals("Prateek", subs.first { it.kind == SubGoalKind.TYPE_CONTACT }.target)
    }

    @Test
    fun englishMessagingDecomposesWithAppSlot() {
        val intent = HeuristicPlanner().parseMessagingIntent("message Prateek on WhatsApp")
        assertTrue("should parse as messaging", intent != null)
        assertEquals("Prateek", intent!!.contact)
        assertEquals("WhatsApp", intent.app)
    }

    @Test
    fun messagingWithBodyAddsTypeAndSubmitSubgoals() {
        val subs = HeuristicPlanner().decomposeGoalIntoSubGoals("WhatsApp pe Prateek ko message bolo hello there")
        val kinds = subs.map { it.kind }
        assertTrue("must type the message body: $kinds", SubGoalKind.TYPE_MESSAGE in kinds)
        assertTrue("must submit: $kinds", SubGoalKind.SUBMIT in kinds)
    }

    @Test
    fun nonMessagingCommandIsNotMisclassified() {
        assertTrue(HeuristicPlanner().parseMessagingIntent("Open YouTube and search for lo-fi beats") == null)
    }

    // --- 6.18: robust JSON output + repair -------------------------------------

    @Test
    fun parserRepairsTrailingCommasAndCodeFences() {
        val raw = """
            ```json
            {"steps":[
              {"id":1,"seq":1,"desc":"Launch","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube","verify":"FOREGROUND_PACKAGE"},
            ]}
            ```
        """.trimIndent()
        val result = TaskPlanParser().parse(raw, "Open YouTube")
        assertTrue("trailing comma + fence must parse: $result", result is PlanParseResult.Success)
    }

    @Test
    fun parserRepairsSingleQuotedJson() {
        val raw = "{'steps':[{'id':1,'seq':1,'desc':'Home','action':'navigate_home','params':{},'expect':'home','verify':'HOME_FOREGROUND'}]}"
        val result = TaskPlanParser().parse(raw, "Go home")
        assertTrue("single quotes must be repaired: $result", result is PlanParseResult.Success)
    }

    // --- 6.19: typed params with coercion + aliases -----------------------------

    @Test
    fun parserCoercesAliasesAndScalarValues() {
        // "app" alias for appQuery; "search" step with the wrong param name is repaired.
        val raw = """{"steps":[{"id":1,"seq":1,"desc":"Launch","action":"launch_app","params":{"app":"youtube"},"verify":"FOREGROUND_PACKAGE"}]}"""
        val result = TaskPlanParser().parse(raw, "Open YouTube")
        assertTrue(result is PlanParseResult.Success)
        val action = (result as PlanParseResult.Success).plan.steps.first().action
        assertEquals("youtube", (action as AgentAction.OpenApp).appQuery)
    }

    @Test
    fun parserCoercesNumericAndBooleanScalarsToStrings() {
        val raw = """{"steps":[{"id":1,"seq":1,"desc":"Tap","action":"tap_element","params":{"targetText":3},"verify":"SCREEN_CHANGED"}]}"""
        val result = TaskPlanParser().parse(raw, "tap 3")
        assertTrue("numeric scalar must coerce, not reject: $result", result is PlanParseResult.Success)
        assertEquals("3", ((result as PlanParseResult.Success).plan.steps.first().action as AgentAction.Tap).targetText)
    }

    // --- 6.13/6.14/6.16: fresh observation + bounded history in the step prompt -

    @Test
    fun stepDecisionPromptCarriesTheFreshScreenAndLastResult() {
        val prompt = PlannerPromptBuilder.buildStepDecisionPrompt(
            StepDecisionRequest(
                userCommand = "Open YouTube",
                activeSubGoal = "Open search",
                foregroundPackage = "com.google.android.youtube",
                screenSummary = "- [0] Button \"Search\" at (970,100)",
                completedStepsSummary = "Step 0: OpenApp -> Success",
                lastActionResult = "Failed: search field not found",
                remainingStepBudget = 5,
                availableActions = listOf("open_search", "enter_text")
            )
        )
        assertTrue("must include the fresh screen", prompt.contains("Search"))
        assertTrue("must include the last result", prompt.contains("search field not found"))
        assertTrue("must warn against repeating a failed move", prompt.contains("Do not repeat a failed action"))
        assertTrue("must decide from the current screen", prompt.contains("CURRENT screen"))
    }

    // --- 6.10: the dead Web Skills surface is gone, web tools remain -----------

    @Test
    fun webSkillsTabRemovedButInLoopWebToolsRemain() {
        assertTrue(
            "Web Skills tab must be removed",
            AgentTab.values().none { it.name == "WEB_SKILLS" }
        )
        val registry = ToolRegistry()
        assertTrue("web_search must remain an in-loop tool", registry.getTool(ToolRegistry.TOOL_WEB_SEARCH) != null)
        assertTrue("read_url must remain an in-loop tool", registry.getTool(ToolRegistry.TOOL_READ_URL) != null)
    }
}
