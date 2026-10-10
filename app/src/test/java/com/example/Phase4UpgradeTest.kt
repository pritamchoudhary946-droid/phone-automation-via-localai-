package com.example

import android.graphics.Bitmap
import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import com.example.automation.AutomationGate
import com.example.automation.AutomationTrigger
import com.example.data.local.KnowledgeDao
import com.example.data.local.KnowledgeNoteEntity
import com.example.memory.rag.RagScorer
import com.example.memory.rag.TokenOverlapRetriever
import com.example.notifications.AgentNotification
import com.example.notifications.NotificationBus
import com.example.notifications.NotificationSource
import com.example.perception.OcrReader
import com.example.perception.ScreenshotDiffer
import com.example.perception.UnavailableOcrReader
import com.example.perception.UnavailableVisionGrounder
import com.example.perception.VisionPoint
import com.example.planning.PlannerPromptBuilder
import com.example.planning.PlanSelfReview
import com.example.planning.PlanningRequest
import com.example.privileged.ShizukuGate
import com.example.voice.OptInWakeWordDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the Jarvis Phase 4 upgrade modules: safe-by-default access levels, the
 * opt-in wake word, vision/OCR fallbacks, bounded notification reading, offline RAG,
 * scheduled-automation gating, and the README-discoverable no-op backends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase4UpgradeTest {

    // --- Plan 4.4: bounded notification reading ---

    @Test
    fun notificationBusIsBoundedAndReadable() {
        NotificationBus.clear()
        repeat(30) { i ->
            NotificationBus.offer(AgentNotification("com.app", "Title $i", "Body $i", i.toLong(), false))
        }
        val recent = NotificationBus.recent(limit = 5)
        assertEquals(5, recent.size)
        // Most-recent first.
        assertEquals("Title 29", recent.first().title)
        NotificationBus.clear()
        assertTrue(NotificationBus.recent().isEmpty())
    }

    @Test
    fun readNotificationsIsAnApprovedTool() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.ReadNotifications) is ToolValidationResult.Valid)
        assertTrue(PlannerPromptBuilder.availableActions(registry).contains(ToolRegistry.TOOL_READ_NOTIFICATIONS))
    }

    // --- Plan 4.1/4.2/4.3: vision + OCR fallbacks ---

    @Test
    fun unavailablePerceptionBackendsAreHonestNoOps() = runBlocking {
        assertNull(UnavailableVisionGrounder.locate("tap play", Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
        assertEquals("", UnavailableOcrReader.readText(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
        assertEquals(0, VisionPoint(0, 0).x)
    }

    @Test
    fun screenshotDifferDetectsChangeAndIgnoresMissingFrames() {
        val a = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.WHITE)
        assertTrue(ScreenshotDiffer.changed(a, b))
        // Identical frames are not a change.
        assertFalse(ScreenshotDiffer.changed(a, a))
        // Missing screenshots never fabricate a change.
        assertFalse(ScreenshotDiffer.changed(a, null))
        assertFalse(ScreenshotDiffer.changed(null, null))
    }

    // --- Plan 4.10: opt-in wake word ---

    @Test
    fun wakeWordNeverListensWithoutOptInOrPermission() {
        val noOptIn = OptInWakeWordDetector(optIn = false, hasMicPermission = { true })
        noOptIn.start()
        assertFalse(noOptIn.isEnabled())
        assertFalse(noOptIn.isListening())

        val noPerm = OptInWakeWordDetector(optIn = true, hasMicPermission = { false })
        noPerm.start()
        assertFalse(noPerm.isListening())

        val armed = OptInWakeWordDetector(optIn = true, hasMicPermission = { true })
        armed.start()
        assertTrue(armed.isListening())
        armed.stop()
        assertFalse(armed.isListening())
    }

    // --- Plan 4.12: privileged actions refuse unless Shizuku is running ---

    @Test
    fun shizukuGateRequiresBothOptInAndRunningBinder() {
        assertFalse(ShizukuGate.permit(optIn = false, shizukuRunning = true))
        assertFalse(ShizukuGate.permit(optIn = true, shizukuRunning = false))
        assertTrue(ShizukuGate.permit(optIn = true, shizukuRunning = true))
    }

    // --- Plan 4.14: scheduled automations are off by default ---

    @Test
    fun automationsAreOffUnlessBothSwitchesAreOn() {
        assertFalse(AutomationGate.isSchedulable(masterEnabled = false, automationEnabled = true))
        assertFalse(AutomationGate.isSchedulable(masterEnabled = true, automationEnabled = false))
        assertTrue(AutomationGate.isSchedulable(masterEnabled = true, automationEnabled = true))
        // WorkManager's periodic floor is 15 minutes.
        assertEquals(15L, AutomationGate.intervalMinutes(1))
        assertEquals(60L, AutomationGate.intervalMinutes(60))
        assertEquals(AutomationTrigger.INTERVAL, "INTERVAL")
    }

    // --- Plan 4.15: offline retrieval over the user's own notes ---

    @Test
    fun ragScorerRanksBySharedTokens() {
        val query = RagScorer.tokenize("book a taxi to the airport")
        val taxi = RagScorer.tokenize("my favourite taxi app is Uber")
        val unrelated = RagScorer.tokenize("buy milk and bread")
        assertTrue(RagScorer.score(query, taxi) > RagScorer.score(query, unrelated))
        assertEquals(0, RagScorer.score(query, unrelated))
    }

    @Test
    fun retrieverReturnsOnlyRelevantNotes() = runBlocking {
        val dao = InMemoryKnowledgeDao()
        val retriever = TokenOverlapRetriever(dao)
        retriever.remember("note", "always book the airport taxi with Uber")
        retriever.remember("note", "the wifi password is hunter2")

        val hits = retriever.retrieve("taxi to airport", limit = 3)
        assertEquals(1, hits.size)
        assertTrue(hits.first().content.contains("Uber"))
        // An empty/stopword query retrieves nothing rather than everything.
        assertTrue(retriever.retrieve("the a of", limit = 3).isEmpty())
    }

    // --- Plan 4.9: retrieved context reaches the planning prompt, bounded ---

    @Test
    fun retrievedContextIsIncludedAndBoundedInThePrompt() {
        val request = PlanningRequest(
            userCommand = "book my usual taxi",
            desiredOutcome = "book my usual taxi",
            foregroundPackage = "com.uber",
            screenSummary = "screen",
            completedStepsSummary = "",
            lastActionResult = null,
            remainingStepBudget = 10,
            availableActions = listOf("launch_app"),
            retrievedContext = "x".repeat(5_000)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(request)
        assertTrue(prompt.contains("Relevant notes"))
        // The injected block must be truncated to the documented bound.
        assertFalse(prompt.contains("x".repeat(PlannerPromptBuilder.MAX_RETRIEVED_CHARS + 1)))
    }

    // --- Plan 4.8: deterministic self-review ---

    @Test
    fun selfReviewRewardsCleanRunsAndFlagsWaste() {
        val clean = PlanSelfReview.review(stepCount = 3, completed = 3, retried = 0, goalSatisfied = true)
        assertTrue(clean.notes.isEmpty())
        assertTrue(clean.score > 0)

        val messy = PlanSelfReview.review(stepCount = 4, completed = 2, retried = 2, goalSatisfied = false)
        assertTrue(messy.score < clean.score)
        assertTrue(PlanSelfReview.toSummaryLine(messy).contains("retries"))
    }

    // --- Plan 4.13: skills serialize to validated, non-executable JSON ---

    @Test
    fun planSerializesToSkillJson() {
        val plan = com.example.planning.TaskPlan(
            goal = "open youtube",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1,
                    sequence = 1,
                    description = "open the app",
                    action = AgentAction.OpenApp("YouTube", "com.google.android.youtube"),
                    rawActionName = "launch_app",
                    expectedPostcondition = "youtube foreground",
                    verification = com.example.planning.VerificationMethod.FOREGROUND_PACKAGE
                )
            )
        )
        val json = com.example.planning.TaskPlanSerializer.toJson(plan)
        assertTrue(json.contains("\"goal\":\"open youtube\""))
        assertTrue(json.contains("launch_app"))
        assertTrue(json.contains("FOREGROUND_PACKAGE"))
    }
}

/** Minimal in-memory [KnowledgeDao] so retrieval can be tested without Room. */
private class InMemoryKnowledgeDao : KnowledgeDao {
    private val notes = mutableListOf<KnowledgeNoteEntity>()
    private var nextId = 1L

    override suspend fun insertNote(note: KnowledgeNoteEntity) {
        notes.add(note.copy(id = nextId++))
    }

    override suspend fun getRecentNotes(limit: Int) = notes.takeLast(limit).reversed()
    override suspend fun getAllNotes() = notes.toList()
    override suspend fun clearNotes() = notes.clear()
}
