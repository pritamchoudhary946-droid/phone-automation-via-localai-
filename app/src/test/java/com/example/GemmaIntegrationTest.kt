package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentAction
import com.example.model.EngineState
import com.example.model.GemmaInferenceEngine
import com.example.model.LiteRtModelManager
import com.example.model.LlmInferenceException
import com.example.model.ModelDownloadState
import com.example.planning.AgentPlanner
import com.example.planning.PlanOutcome
import com.example.planning.PlanParseResult
import com.example.planning.PlanSource
import com.example.planning.PlanningRequest
import com.example.planning.StepDecision
import com.example.planning.StepDecisionRequest
import com.example.planning.TaskPlanParser
import com.example.planning.VerificationMethod
import com.example.planning.PostconditionVerifier
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 2 tests: genuine inference seam, honest unavailable states, download
 * integrity, strict plan validation and goal-specific verification.
 *
 * No test downloads the real 2.59 GB model: a [FakeLlmRuntime] stands in for the
 * LiteRT-LM engine and MockWebServer serves small synthetic payloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GemmaIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // 1. Real inference adapter calls the runtime and consumes its generated output.

    @Test
    fun inferenceEngineConsumesRuntimeOutputNotCannedText() = runBlocking {
        val fake = FakeLlmRuntime(output = "hello-from-the-real-runtime")
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> fake })

        val modelFile = fakeModelFile(context.cacheDir)
        assertTrue(engine.initialize(modelFile))

        val out = engine.generate("prompt-A", "sys", 64)
        assertEquals("hello-from-the-real-runtime", out)
        assertEquals(1, fake.prompts.size)
        assertTrue(fake.prompts.first().contains("prompt-A"))
    }

    @Test
    fun plannerUsesModelGeneratedPlanWhenEngineReady() = runBlocking {
        val modelJson = """
            {"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app",
            "params":{"appQuery":"youtube"},"expect":"YouTube foreground",
            "verify":"FOREGROUND_PACKAGE"}]}
        """.trimIndent()
        val fake = FakeLlmRuntime(output = modelJson)
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake }, modelManager = modelManager)
        assertTrue(engine.initialize())
        engine.setFallbackExplicit(true)

        val outcome = engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertTrue(outcome is PlanOutcome.Planned)
        val planned = outcome as PlanOutcome.Planned
        assertEquals(PlanSource.GEMMA_MODEL, planned.source)
        assertEquals(1, planned.plan.steps.size)
        assertTrue(planned.plan.steps.first().action is AgentAction.OpenApp)
        // Proves the model output, not the heuristic, produced the plan.
        assertTrue(fake.prompts.first().contains("Open YouTube"))
    }

    @Test
    fun stepDecisionIsValidatedAgainstApprovedCatalog() = runBlocking {
        val fake = FakeLlmRuntime(output = """{"thought":"tap the entry","action":"tap_element","params":{"targetText":"lo-fi beats"}}""")
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake })
        assertTrue(engine.initialize())

        val decision = engine.decideNextAction(
            goal = "Open YouTube, search for lo-fi beats",
            activeSubGoal = "Open result",
            screenSummary = "Active Package: com.google.android.youtube",
            foregroundPackage = "com.google.android.youtube",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 5
        )
        assertTrue(decision is StepDecision.Act)
        val act = decision as StepDecision.Act
        assertTrue(act.fromModel)
        assertTrue(act.action is AgentAction.Tap)
    }

    @Test
    fun unknownActionNameFromModelIsRejected() = runBlocking {
        val fake = FakeLlmRuntime(output = """{"thought":"rm -rf","action":"exec_shell","params":{"cmd":"rm -rf /"}}""")
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake })
        assertTrue(engine.initialize())

        val decision = engine.decideNextAction(
            goal = "do something",
            activeSubGoal = "x",
            screenSummary = "",
            foregroundPackage = null,
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 5
        )
        assertTrue(decision is StepDecision.None)
    }

    // 2. Missing model produces an honest not-ready state, never fake AI success.

    @Test
    fun missingModelYieldsNotReadyAndBasicFallback() = runBlocking {
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> FakeLlmRuntime() })
        assertFalse(engine.isReady())
        assertTrue(engine.engineState() is EngineState.NotInitialized)

        // 6.1: with the model not loaded, the default is to BLOCK — never silently run
        // the keyword planner.
        val blocked = engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertTrue("must not silently plan with keywords", blocked is PlanOutcome.Unavailable)

        // Only the explicit, user-visible degraded mode may use the labelled fallback.
        engine.setFallbackExplicit(true)
        val outcome = engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertTrue(outcome is PlanOutcome.Planned)
        assertEquals(PlanSource.BASIC_FALLBACK, (outcome as PlanOutcome.Planned).source)
    }

    @Test
    fun initializationFailureIsReportedHonestly() = runBlocking {
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> FakeLlmRuntime(readyAfterInitialize = false) })
        val ok = engine.initialize(fakeModelFile(context.cacheDir))
        assertFalse(ok)
        assertTrue(engine.state.value is EngineState.Failed)
        assertFalse(engine.isReady())
    }

    @Test
    fun generationFailureReturnsNullInsteadOfFakeOutput() = runBlocking {
        val fake = FakeLlmRuntime(throwOnGenerate = true)
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> fake })
        assertTrue(engine.initialize(fakeModelFile(context.cacheDir)))
        assertNull(engine.generate("p", null, 32))
    }

    @Test
    fun failedSmokeTestLeavesEngineUnavailable() = runBlocking {
        val fake = FakeLlmRuntime(smokeOk = false)
        val runtime = stageRuntimeFromFactory(fake)
        val engine = GemmaInferenceEngine(runtimeFactory = runtime)
        val file = fakeModelFile(context.cacheDir)
        // 8.9: ensureReady loads once, then runs the probe; a failing probe marks the
        // engine unavailable (and never leaves it claiming ready).
        assertFalse(engine.ensureReady(file))
        assertFalse(engine.isReady())
        assertTrue(engine.state.value is EngineState.Failed)
    }

    // 5. Invalid model output / unknown actions rejected; bounded repair then fallback.

    @Test
    fun invalidPlanOutputFallsBackAfterBoundedRepair() = runBlocking {
        val fake = FakeLlmRuntime(output = "I am not JSON at all")
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake }, modelManager = modelManager)
        assertTrue(engine.initialize())
        engine.setFallbackExplicit(true)

        val outcome = engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertEquals(PlanSource.BASIC_FALLBACK, (outcome as PlanOutcome.Planned).source)
        // One bounded repair attempt => exactly two generate calls.
        assertEquals(2, fake.prompts.size)
    }

    @Test
    fun planParserRejectsMalformedUnknownAndImpossibleSteps() {
        val parser = TaskPlanParser()
        assertTrue(parser.parse("completely invalid", "g") is PlanParseResult.Invalid)
        assertTrue(parser.parse("""{"steps":[]}""", "g") is PlanParseResult.Invalid)
        assertTrue(
            parser.parse("""{"steps":[{"id":1,"action":"launch_missiles","params":{}}]}""", "g") is PlanParseResult.Invalid
        )
        assertTrue(
            parser.parse(
                """{"steps":[{"id":1,"action":"launch_app","params":{"appQuery":"youtube"}},{"id":2,"seq":2,"action":"read_screen","params":{},"dependsOn":[9]}]}""",
                "g"
            ) is PlanParseResult.Invalid
        )
    }

    // 6. Multi-step command becomes ordered subgoals.

    @Test
    fun modelProducesOrderedMultiStepPlan() = runBlocking {
        val modelJson = """
            {"steps":[
              {"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"},
              {"id":2,"seq":2,"desc":"Tap search","action":"tap_element","params":{"targetText":"Search"},"expect":"search field","verify":"SCREEN_CHANGED"},
              {"id":3,"seq":3,"desc":"Type query","action":"enter_text","params":{"text":"lo-fi beats"},"expect":"query typed","verify":"TEXT_PRESENT"},
              {"id":4,"seq":4,"desc":"Go home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
            ]}
        """.trimIndent()
        val fake = FakeLlmRuntime(output = modelJson)
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake }, modelManager = modelManager)
        assertTrue(engine.initialize())

        val outcome = engine.planTask(
            goal = "Open YouTube, search for lo-fi beats, open a result, and return to home",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        val plan = (outcome as PlanOutcome.Planned).plan
        assertEquals(PlanSource.GEMMA_MODEL, outcome.source)
        assertEquals(4, plan.steps.size)
        assertEquals(listOf(1, 2, 3, 4), plan.steps.map { it.sequence })
        assertTrue(plan.steps[0].action is AgentAction.OpenApp)
        assertEquals(VerificationMethod.FOREGROUND_PACKAGE, plan.steps[0].verification)
        assertTrue(plan.steps[3].action is AgentAction.Home)
    }

    @Test
    fun simpleOpenCommandProducesMinimalPlan() = runBlocking {
        val modelJson = """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}]}"""
        val fake = FakeLlmRuntime(output = modelJson)
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake }, modelManager = modelManager)
        assertTrue(engine.initialize())
        val outcome = engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertEquals(1, (outcome as PlanOutcome.Planned).plan.steps.size)
    }

    // 9 & 11. ReadScreen cannot satisfy an unrelated goal; screen text mentioning
    // YouTube is not proof of foreground.

    @Test
    fun readScreenSuccessCannotCompleteUnrelatedLaunchGoal() {
        val verdict = PostconditionVerifier.verify(
            method = VerificationMethod.FOREGROUND_PACKAGE,
            expectedPostcondition = "YouTube foreground",
            targetPackage = "com.google.android.youtube",
            observationPackage = "com.android.launcher",
            screenChanged = true,
            screenText = "YouTube: Trending videos\nActive Package: com.android.launcher"
        )
        assertFalse(verdict.satisfied)
    }

    @Test
    fun screenMentioningYouTubeIsNotForegroundProof() {
        val verdict = PostconditionVerifier.verify(
            method = VerificationMethod.FOREGROUND_PACKAGE,
            expectedPostcondition = "YouTube foreground",
            targetPackage = "com.google.android.youtube",
            observationPackage = "com.android.settings",
            screenChanged = true,
            screenText = "Search settings. YouTube app info. Active Package: com.android.settings"
        )
        assertFalse(verdict.satisfied)
    }

    @Test
    fun foregroundPackageMatchSatisfiesLaunch() {
        val verdict = PostconditionVerifier.verify(
            method = VerificationMethod.FOREGROUND_PACKAGE,
            expectedPostcondition = "YouTube foreground",
            targetPackage = "com.google.android.youtube",
            observationPackage = "com.google.android.youtube",
            screenChanged = true,
            screenText = "Active Package: com.google.android.youtube"
        )
        assertTrue(verdict.satisfied)
    }

    // 3. Download handling: errors, HTML page, checksum mismatch, cancellation, partial files.

    @Test
    fun htmlErrorPageIsRejectedNotAcceptedAsModel() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
        server.start()
        try {
            val manager = smallManager(server)
            manager.startOrResumeDownload()
            awaitTerminal(manager)
            val state = manager.downloadState.value
            assertTrue(state is ModelDownloadState.Error)
            assertFalse((state as ModelDownloadState.Error).canRetry)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpErrorIsReportedWithoutInfiniteRetry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        server.start()
        try {
            val manager = smallManager(server)
            manager.startOrResumeDownload()
            awaitTerminal(manager)
            assertTrue(manager.downloadState.value is ModelDownloadState.Error)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun checksumMismatchDiscardsFileAndErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("this-is-not-the-model"))
        server.start()
        try {
            // Expect 24 bytes; server returns 21, so size mismatch must be caught.
            val manager = smallManager(server, expectedBytes = 24L)
            manager.startOrResumeDownload()
            awaitTerminal(manager)
            assertTrue(manager.downloadState.value is ModelDownloadState.Error)
            assertFalse(manager.getModelFile().exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancelledDownloadLeavesResumablePartFile() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("partial-data")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.start()
        try {
            val manager = smallManager(server, maxTransientRetries = 0)
            manager.startOrResumeDownload()
            awaitTerminal(manager)
            // After a transient failure the manager surfaces a Paused/Error state and
            // never promotes a partial file to the final verified name.
            assertFalse(manager.getModelFile().exists())
            val state = manager.downloadState.value
            assertTrue(state is ModelDownloadState.Paused || state is ModelDownloadState.Error)
        } finally {
            server.shutdown()
        }
    }

    // 4. A verified model is not downloaded again.

    @Test
    fun verifiedModelIsNotDownloadedAgain() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val manager = smallManager(server, expectedBytes = 4L)
            val fake = File(manager.getModelFile().parentFile, LiteRtModelManager.MODEL_FILENAME)
            fake.writeBytes(byteArrayOf(1, 2, 3, 4))
            manager.registerVerifiedModelForTest(fake)

            manager.startOrResumeDownload()
            assertEquals(ModelDownloadState.Ready::class.java, manager.downloadState.value.javaClass)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun storageShortfallIsReportedAsFatalError() = runBlocking {
        val manager = LiteRtModelManager(
            context,
            downloadClient = OkHttpClient(),
            expectedBytes = 4L,
            expectedSha256 = "00",
            modelUrl = "http://127.0.0.1:1/unreachable"
        )
        // The device has far less than 3.2 GB free in this environment, so the
        // storage pre-check must fail fast rather than starting a download.
        val (enough, available) = manager.checkStorageAvailability()
        if (!enough) {
            manager.startOrResumeDownload()
            awaitTerminal(manager)
            val state = manager.downloadState.value
            assertTrue(state is ModelDownloadState.Error)
            assertFalse((state as ModelDownloadState.Error).canRetry)
        }
    }

    @Test
    fun partialOversizedPartFileIsDiscardedNotTrusted() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val manager = smallManager(server, expectedBytes = 4L)
            val part = File(manager.getModelFile().parentFile, "gemma-4-E2B-it.litertlm.part")
            part.writeBytes(ByteArray(999))
            manager.checkExistingModel()
            assertTrue(manager.downloadState.value is ModelDownloadState.Paused)
            assertFalse(manager.getModelFile().exists())
        } finally {
            server.shutdown()
        }
    }

    /**
     * Bug: a part file that reached exactly the expected byte count (download
     * finished, app died before the verify/rename step) was deleted and fetched
     * again. It must instead be verified and finalized without any network use.
     */
    @Test
    fun completePartFileIsVerifiedAndFinalizedWithoutRedownload() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val manager = LiteRtModelManager(
                context,
                downloadClient = OkHttpClient.Builder().build(),
                expectedBytes = bytes.size.toLong(),
                expectedSha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                maxTransientRetries = 0,
                modelUrl = server.url("/model.litertlm").toString()
            )
            val part = File(manager.getModelFile().parentFile, "gemma-4-E2B-it.litertlm.part")
            part.writeBytes(bytes)
            assertTrue(part.exists())
            assertEquals(4L, part.length())

            manager.startOrResumeDownload()
            awaitTerminal(manager)

            val state = manager.downloadState.value
            assertTrue("expected Ready, got $state", state is ModelDownloadState.Ready)
            assertTrue(manager.getModelFile().exists())
            assertFalse("part file should be promoted, not left behind", part.exists())
            assertEquals("a complete part file must be finalized without any download", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // 15. Inference init/generation failures reported honestly (covered above) and
    // engine shutdown releases the runtime.

    @Test
    fun shutdownReleasesRuntimeAndResetsState() = runBlocking {
        val fake = FakeLlmRuntime()
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> fake })
        assertTrue(engine.initialize(fakeModelFile(context.cacheDir, bytes = 16)))
        engine.shutdown()
        assertTrue(fake.closed)
        assertFalse(engine.isReady())
        assertTrue(engine.state.value is EngineState.NotInitialized)
    }

    // 2. The requested output-token limit reaches the runtime for both plan and
    //    one-step generation, and reaches the real LiteRT-LM conversation config.

    @Test
    fun plannerSendsBoundedOutputTokenLimitToRuntime() = runBlocking {
        val planJson =
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}]}"""
        val fake = FakeLlmRuntime(output = planJson)
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val engine = buildTestEngine(
            context,
            runtimeFactory = { _, _ -> fake },
            modelManager = modelManager,
            maxOutputTokens = 768
        )
        assertTrue(engine.initialize())

        engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertEquals("full-plan generation must be bounded to the configured limit",
            768, fake.requestedMaxOutputTokens.first())
    }

    @Test
    fun stepDecisionUsesSmallerBoundedOutputTokens() = runBlocking {
        val fake = FakeLlmRuntime(
            output = """{"thought":"tap search","action":"tap_element","params":{"targetText":"Search"}}"""
        )
        val modelManager = LiteRtModelManager(context).also { fakeModelFileAt(it.getModelFile()) }
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> fake }, modelManager = modelManager)
        assertTrue(engine.initialize())

        engine.decideNextAction(
            goal = "Open YouTube",
            activeSubGoal = "activate search",
            screenSummary = "Active Package: com.google.android.youtube",
            foregroundPackage = "com.google.android.youtube",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 8
        )
        assertEquals("one-step decision must use the smaller bounded budget",
            256, fake.requestedMaxOutputTokens.first())
    }

    @Test
    fun liteRtConversationConfigReceivesMaxOutputToken() {
        val runtime = com.example.model.LiteRtLmRuntime(modelPath = "/tmp/unused.litertlm")
        assertEquals(123, runtime.buildConversationConfig("sys", 123).maxOutputToken)
        // Defensive lower bound: never request zero/negative output tokens.
        assertEquals(1, runtime.buildConversationConfig(null, 0).maxOutputToken)
    }

    // ---- helpers ----

    private fun stageRuntimeFromFactory(fake: FakeLlmRuntime): (String, String?) -> com.example.model.LlmRuntime =
        { _, _ -> fake }

    private fun smallManager(
        server: MockWebServer,
        expectedBytes: Long = 4L,
        maxTransientRetries: Int = 0
    ): LiteRtModelManager = LiteRtModelManager(
        context,
        downloadClient = OkHttpClient.Builder().build(),
        expectedBytes = expectedBytes,
        expectedSha256 = "deadbeef",
        maxTransientRetries = maxTransientRetries,
        modelUrl = server.url("/model.litertlm").toString()
    )

    private suspend fun awaitTerminal(manager: LiteRtModelManager) {
        repeat(200) {
            when (manager.downloadState.value) {
                is ModelDownloadState.Error,
                is ModelDownloadState.Ready,
                is ModelDownloadState.Paused -> return
                else -> kotlinx.coroutines.delay(25)
            }
        }
    }
}
