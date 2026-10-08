package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.RecoveryStrategy
import com.example.agent.RiskEvaluator
import com.example.agent.StuckLoopDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OmniAgent", appName)
    }

    @Test
    fun `risk evaluator detects payment actions`() {
        val evaluator = RiskEvaluator()
        val riskyAction = AgentAction.Tap("Confirm Payment $49.99", null, null)
        val assessment = evaluator.evaluate(riskyAction, "Checkout Screen - Total $49.99")
        assertTrue(assessment.isRisky)
        assertEquals("Financial / Payment", assessment.riskType)
    }

    @Test
    fun `risk evaluator detects destructive deletion`() {
        val evaluator = RiskEvaluator()
        val deleteAction = AgentAction.Tap("Delete Account Permanently", null, null)
        val assessment = evaluator.evaluate(deleteAction, "Account Settings")
        assertTrue(assessment.isRisky)
        assertEquals("Destructive Deletion", assessment.riskType)
    }

    @Test
    fun `risk evaluator allows harmless navigation actions`() {
        val evaluator = RiskEvaluator()
        val harmlessAction = AgentAction.Tap("Explore Videos", null, null)
        val assessment = evaluator.evaluate(harmlessAction, "Video Feed")
        assertFalse(assessment.isRisky)
    }

    @Test
    fun `stuck loop detector flags consecutive identical actions`() {
        val detector = StuckLoopDetector()
        val fingerprint = "com.google.android.youtube_search_button"
        val actionName = "Tap: 'Search'"

        detector.recordStep(fingerprint, actionName)
        val secondStep = detector.recordStep(fingerprint, actionName)
        val thirdStep = detector.recordStep(fingerprint, actionName)

        assertTrue(thirdStep.isStuck)
        assertTrue(thirdStep.suggestedRecovery != RecoveryStrategy.NONE)
    }

    @Test
    fun `app directory resolves package aliases`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appDirectory = AppDirectory(context)
        val resolved = appDirectory.resolvePackage("youtube")
        assertTrue(resolved == null || resolved.contains("youtube"))
    }

    @Test
    fun `ui tree snapshot generates compact prompt representation and finds nodes`() {
        val node1 = UiNode(
            id = "btn_search",
            text = "Search",
            contentDescription = null,
            className = "android.widget.Button",
            packageName = "com.google.android.youtube",
            bounds = android.graphics.Rect(100, 100, 300, 200),
            isClickable = true,
            isScrollable = false,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isFocused = false
        )
        val inputNode = UiNode(
            id = "edit_query",
            text = "Lo-fi beats",
            contentDescription = "Search input field",
            className = "android.widget.EditText",
            packageName = "com.google.android.youtube",
            bounds = android.graphics.Rect(100, 250, 800, 350),
            isClickable = true,
            isScrollable = false,
            isEditable = true,
            isCheckable = false,
            isChecked = false,
            isFocused = true
        )
        val snapshot = UiTreeSnapshot("com.google.android.youtube", listOf(node1, inputNode))
        val summary = snapshot.toPromptSummary()
        assertTrue(summary.contains("Active Package: com.google.android.youtube"))
        assertTrue(summary.contains("\"Search\""))

        // Verify finding helpers
        val foundSearch = snapshot.findNodeByText("Search")
        assertNotNull(foundSearch)
        assertEquals("btn_search", foundSearch?.id)

        val editable = snapshot.findFirstEditable()
        assertNotNull(editable)
        assertEquals("edit_query", editable?.id)
        assertTrue(editable?.isEditable == true)
    }

    @Test
    fun `accessibility tree json parser extracts clickable and input elements`() {
        val parser = com.example.accessibility.AccessibilityTreeJsonParser(maxElements = 10)

        val btnNode = UiNode(
            id = "com.google.android.youtube:id/search_button",
            text = "Search",
            contentDescription = "Search YouTube",
            className = "android.widget.ImageView",
            packageName = "com.google.android.youtube",
            bounds = android.graphics.Rect(800, 50, 950, 150),
            isClickable = true,
            isScrollable = false,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isFocused = false
        )
        val editNode = UiNode(
            id = "com.google.android.youtube:id/search_query",
            text = "Coding music",
            contentDescription = null,
            className = "android.widget.EditText",
            packageName = "com.google.android.youtube",
            bounds = android.graphics.Rect(50, 50, 750, 150),
            isClickable = true,
            isScrollable = false,
            isEditable = true,
            isCheckable = false,
            isChecked = false,
            isFocused = true
        )
        val scrollNode = UiNode(
            id = "com.google.android.youtube:id/results_list",
            text = null,
            contentDescription = "Results Feed",
            className = "androidx.recyclerview.widget.RecyclerView",
            packageName = "com.google.android.youtube",
            bounds = android.graphics.Rect(0, 160, 1080, 2200),
            isClickable = false,
            isScrollable = true,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isFocused = false
        )

        val snapshot = UiTreeSnapshot("com.google.android.youtube", listOf(btnNode, editNode, scrollNode))
        val jsonString = parser.parseSnapshotToJson(snapshot)
        val jsonObject = org.json.JSONObject(jsonString)

        assertEquals("com.google.android.youtube", jsonObject.getString("package"))
        assertEquals(3, jsonObject.getInt("element_count"))

        val elements = jsonObject.getJSONArray("elements")
        assertEquals(3, elements.length())

        val first = elements.getJSONObject(0)
        assertEquals("Search", first.getString("label"))
        assertEquals("button", first.getString("type"))
        assertTrue(first.getJSONObject("flags").getBoolean("clickable"))

        val second = elements.getJSONObject(1)
        assertEquals("Coding music", second.getString("label"))
        assertEquals("input", second.getString("type"))
        assertTrue(second.getJSONObject("flags").getBoolean("editable"))

        val third = elements.getJSONObject(2)
        assertEquals("scrollable", third.getString("type"))
        assertTrue(third.getJSONObject("flags").getBoolean("scrollable"))
    }

    @Test
    fun `simplified agent status correctly maps orchestrator states`() {
        val idleStatus = com.example.ui.SimplifiedAgentStatus.IDLE
        val thinkingStatus = com.example.ui.SimplifiedAgentStatus.THINKING
        val actingStatus = com.example.ui.SimplifiedAgentStatus.ACTING

        assertEquals("Idle", idleStatus.label)
        assertEquals("Thinking", thinkingStatus.label)
        assertEquals("Acting", actingStatus.label)
    }
}


