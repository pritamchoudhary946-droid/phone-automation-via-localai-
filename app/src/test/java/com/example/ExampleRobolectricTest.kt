package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.RecoveryStrategy
import com.example.agent.RiskEvaluator
import com.example.agent.StuckLoopDetector
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import com.example.data.local.AgentDatabase
import com.example.data.local.CheckpointEntity
import com.example.data.local.TaskEntity
import com.example.data.local.TaskStepEntity
import com.example.model.LiteRtEngine
import com.example.model.LiteRtModelManager
import com.example.model.SubGoalKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private fun installLauncherPackage(context: Context, packageName: String, label: String) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = label
        }
        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = appInfo
        }
        val packageManager = context.packageManager
        shadowOf(packageManager).installPackage(packageInfo)

        val activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.MainActivity"
            applicationInfo = appInfo
            exported = true
        }
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            this.activityInfo = activityInfo
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    @Test
    fun `read string from context matches app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OmniAgent", appName)
    }

    @Test
    fun `tool registry validates valid agent actions`() {
        val registry = ToolRegistry()

        val validApp = AgentAction.OpenApp("youtube", "com.google.android.youtube")
        assertTrue(registry.validateAction(validApp) is ToolValidationResult.Valid)

        val validTap = AgentAction.Tap("Search", null, null)
        assertTrue(registry.validateAction(validTap) is ToolValidationResult.Valid)

        val validCoordTap = AgentAction.Tap(null, 500, 800)
        assertTrue(registry.validateAction(validCoordTap) is ToolValidationResult.Valid)

        val validType = AgentAction.TypeText("Android Dev", null)
        assertTrue(registry.validateAction(validType) is ToolValidationResult.Valid)

        val validScroll = AgentAction.Scroll("down")
        assertTrue(registry.validateAction(validScroll) is ToolValidationResult.Valid)

        val validWait = AgentAction.Wait(1000L)
        assertTrue(registry.validateAction(validWait) is ToolValidationResult.Valid)

        assertTrue(registry.validateAction(AgentAction.Home) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.Back) is ToolValidationResult.Valid)
    }

    @Test
    fun `tool registry rejects invalid action arguments`() {
        val registry = ToolRegistry()

        // Blank app query and package
        val invalidApp = AgentAction.OpenApp("", null)
        val resApp = registry.validateAction(invalidApp)
        assertTrue(resApp is ToolValidationResult.Invalid)

        // Missing target and negative coordinates
        val invalidTap = AgentAction.Tap(null, -10, 50)
        val resTap = registry.validateAction(invalidTap)
        assertTrue(resTap is ToolValidationResult.Invalid)

        // Empty text
        val invalidType = AgentAction.TypeText("", null)
        val resType = registry.validateAction(invalidType)
        assertTrue(resType is ToolValidationResult.Invalid)

        // Invalid scroll direction
        val invalidScroll = AgentAction.Scroll("diagonal")
        val resScroll = registry.validateAction(invalidScroll)
        assertTrue(resScroll is ToolValidationResult.Invalid)

        // Out of bounds wait duration
        val invalidWait = AgentAction.Wait(60_000L)
        val resWait = registry.validateAction(invalidWait)
        assertTrue(resWait is ToolValidationResult.Invalid)
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
    fun `risk evaluator detects sending messages or posts`() {
        val evaluator = RiskEvaluator()
        val sendAction = AgentAction.Tap("Send Message to Group", null, null)
        val assessment = evaluator.evaluate(sendAction, "Chat Conversation")
        assertTrue(assessment.isRisky)
        assertEquals("External Communication", assessment.riskType)
    }

    @Test
    fun `risk evaluator allows harmless navigation actions`() {
        val evaluator = RiskEvaluator()
        val harmlessAction = AgentAction.Tap("Explore Videos", null, null)
        val assessment = evaluator.evaluate(harmlessAction, "Video Feed")
        assertFalse(assessment.isRisky)
    }

    @Test
    fun `stuck loop detector flags consecutive identical actions and suggests progressive recovery`() {
        val detector = StuckLoopDetector()
        val fingerprint = "com.google.android.youtube_search_button"
        val actionName = "Tap: 'Search'"

        val firstStep = detector.recordStep(fingerprint, actionName)
        assertFalse(firstStep.isStuck)

        val secondStep = detector.recordStep(fingerprint, actionName)
        assertFalse(secondStep.isStuck)

        val thirdStep = detector.recordStep(fingerprint, actionName)
        assertTrue(thirdStep.isStuck)
        assertEquals(RecoveryStrategy.SCROLL_TO_DISCOVER, thirdStep.suggestedRecovery)

        val fourthStep = detector.recordStep(fingerprint, actionName)
        assertTrue(fourthStep.isStuck)
        assertEquals(RecoveryStrategy.PRESS_BACK_TO_UNBLOCK, fourthStep.suggestedRecovery)
    }

    @Test
    fun `app directory resolves package aliases accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric starts with no third-party apps installed. Model installed apps and
        // launcher activities explicitly rather than weakening production package checks.
        listOf(
            "com.google.android.youtube" to "YouTube",
            "com.android.chrome" to "Chrome",
            "com.android.settings" to "Settings",
            "com.whatsapp" to "WhatsApp",
            "com.google.android.apps.maps" to "Maps",
            "com.android.camera" to "Camera",
            "com.google.android.GoogleCamera" to "Google Camera"
        ).forEach { (packageName, label) -> installLauncherPackage(context, packageName, label) }

        val appDirectory = AppDirectory(context)

        val yt = appDirectory.resolvePackage("youtube")
        assertNotNull(yt)
        assertTrue(yt!!.contains("youtube", ignoreCase = true))

        val chrome = appDirectory.resolvePackage("chrome")
        assertNotNull(chrome)
        assertTrue(chrome!!.contains("chrome", ignoreCase = true))

        val settings = appDirectory.resolvePackage("settings")
        assertNotNull(settings)
        assertTrue(settings!!.contains("settings", ignoreCase = true))

        val whatsapp = appDirectory.resolvePackage("whatsapp")
        assertNotNull(whatsapp)
        assertTrue(whatsapp!!.contains("whatsapp", ignoreCase = true))

        val maps = appDirectory.resolvePackage("maps")
        assertNotNull(maps)
        assertTrue(maps!!.contains("maps", ignoreCase = true))

        val camera = appDirectory.resolvePackage("camera")
        assertNotNull(camera)
        assertTrue(camera!!.contains("camera", ignoreCase = true))
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
    fun `litert engine decomposes multi-step sequential goals correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LiteRtModelManager(context)
        val engine = LiteRtEngine(context, modelManager)

        val complexGoal = "Open YouTube, search for educational video, open it, and then return to the home screen"
        val subGoals = engine.decomposeGoalIntoSubGoals(complexGoal)

        assertTrue(subGoals.size >= 4)
        assertEquals(SubGoalKind.LAUNCH_APP, subGoals[0].kind)
        assertEquals("youtube", subGoals[0].target)

        assertEquals(SubGoalKind.SEARCH_QUERY, subGoals[1].kind)
        assertTrue(subGoals[1].target!!.contains("educational video"))

        assertEquals(SubGoalKind.OPEN_ITEM, subGoals[2].kind)
        assertEquals(SubGoalKind.RETURN_HOME, subGoals[3].kind)
    }

    @Test
    fun `litert engine plans full youtube search sequence correctly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LiteRtModelManager(context)
        val engine = LiteRtEngine(context, modelManager)

        val goal = "Open YouTube and search for lo-fi beats"

        // Phase 1: On Home screen -> decides to open YouTube
        val step1 = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.android.launcher\nHome screen",
            historySummary = "",
            lastActionVerification = null
        )
        assertTrue(step1.action is AgentAction.OpenApp)
        assertEquals("youtube", (step1.action as AgentAction.OpenApp).appQuery)

        // Phase 2: Inside YouTube with Search icon visible -> decides to tap Search
        val step2 = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.google.android.youtube\n- Button [Search] (clickable: true)",
            historySummary = "Step 0: Action OpenApp (youtube) -> Success",
            lastActionVerification = "Success: Launched youtube"
        )
        assertTrue(step2.action is AgentAction.Tap)
        assertEquals("Search", (step2.action as AgentAction.Tap).targetText)

        // Phase 3: In Search edit text -> types query via ACTION_SET_TEXT
        val step3 = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.google.android.youtube\n- EditText [input] \"Search YouTube\" (focused: true, editable: true)",
            historySummary = "Step 1: Action Tap (Search) -> Success",
            lastActionVerification = "Success: Tap completed"
        )
        assertTrue(step3.action is AgentAction.TypeText)
        assertEquals("lo-fi beats", (step3.action as AgentAction.TypeText).text)

        // Phase 4: Query is typed in EditText -> submits search
        val step4 = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.google.android.youtube\n- EditText [input] \"lo-fi beats\" (focused: true, editable: true)",
            historySummary = "Step 2: Action TypeText (\"lo-fi beats\") -> Success",
            lastActionVerification = "Success: Injected query"
        )
        assertTrue(step4.action is AgentAction.Tap)
        assertFalse((step4.action as AgentAction.Tap).targetText == "lo-fi beats")
        assertEquals("Search", (step4.action as AgentAction.Tap).targetText)

        // Regression: Search YouTube label alone must not be treated as an editable field.
        val searchLabelScreen = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.google.android.youtube\\n- Button [Search YouTube] (clickable: true)",
            historySummary = "",
            lastActionVerification = null
        )
        assertTrue(searchLabelScreen.action is AgentAction.Tap)
        assertEquals("Search", (searchLabelScreen.action as AgentAction.Tap).targetText)

        // Phase 5: Search results visible -> verifies and completes task
        val step5 = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.google.android.youtube\n- Text \"Lofi Beats 24/7 Radio Stream\"\n- Text \"1.2M views • Lofi Girl channel\"\n- RecyclerView [results]",
            historySummary = "Step 2: Action TypeText (\"lo-fi beats\") -> Success\nStep 3: Action Tap (lo-fi beats) -> Success",
            lastActionVerification = "Success: Results loaded"
        )
        assertTrue(step5.action is AgentAction.FinishTask)
        assertTrue((step5.action as AgentAction.FinishTask).summary.contains("lo-fi beats"))
    }

    @Test
    fun `litert engine plans settings dark mode toggle correctly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LiteRtModelManager(context)
        val engine = LiteRtEngine(context, modelManager)

        val goal = "Open Settings and turn on Dark mode"

        // In Settings screen with Dark mode visible -> taps Dark mode
        val decision = engine.reasonAndPlan(
            goal = goal,
            screenSummary = "Active Package: com.android.settings\n- Switch [Dark mode] (checked: false, clickable: true)",
            historySummary = "Step 0: Action OpenApp (settings) -> Success",
            lastActionVerification = "Success: In Settings"
        )
        assertTrue(decision.action is AgentAction.Tap)
        assertTrue((decision.action as AgentAction.Tap).targetText?.contains("Dark mode", ignoreCase = true) == true)
    }

    @Test
    fun `database task and audit log clearing works completely`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AgentDatabase.getInstance(context)

        val taskId = "test_task_audit_1"
        db.taskDao().insertTask(TaskEntity(id = taskId, userGoal = "Test Goal", status = "COMPLETED"))
        db.taskStepDao().insertStep(TaskStepEntity(taskId = taskId, stepIndex = 0, reasoning = "Reason", actionType = "Tap", actionPayloadJson = "Tap: Search", observationSummary = "Obs", verificationResult = "Success", status = "SUCCESS"))
        db.checkpointDao().saveCheckpoint(CheckpointEntity(taskId = taskId, stepIndex = 0, snapshotJson = "Snap"))

        assertNotNull(db.taskDao().getTaskById(taskId))
        assertTrue(db.taskStepDao().getStepsForTaskList(taskId).isNotEmpty())
        assertNotNull(db.checkpointDao().getCheckpoint(taskId))

        // Clear all audit logs
        db.taskDao().clearAllTasks()
        db.taskStepDao().clearAllSteps()
        db.checkpointDao().clearAllCheckpoints()

        assertEquals(null, db.taskDao().getTaskById(taskId))
        assertTrue(db.taskStepDao().getStepsForTaskList(taskId).isEmpty())
        assertEquals(null, db.checkpointDao().getCheckpoint(taskId))
    }
}
