package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentAction
import com.example.model.ApiInferenceEngine
import com.example.model.ApiLlmRuntime
import com.example.model.EngineState
import com.example.model.LlmInferenceException
import com.example.model.LlmProvider
import com.example.model.LlmProviderConfig
import com.example.planning.PlanOutcome
import com.example.planning.PlanParseResult
import com.example.planning.PlanSource
import com.example.planning.StepDecision
import com.example.planning.TaskPlanParser
import com.example.planning.VerificationMethod
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for the API-backed inference seam: the planner consumes real
 * generated output, the engine reports honest unavailable states, invalid model output
 * is rejected, and each supported provider wire format is built and parsed correctly.
 *
 * No test touches the network: [FakeLlmRuntime] stands in for the engine, and
 * MockWebServer serves small synthetic provider payloads to the real [ApiLlmRuntime].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiLlmIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // 1. The engine consumes runtime output rather than canned text.

    @Test
    fun inferenceEngineConsumesRuntimeOutputNotCannedText() = runBlocking {
        val fake = FakeLlmRuntime(output = "hello-from-the-real-runtime")
        val engine = ApiInferenceEngine(runtimeFactory = { fake })
        assertTrue(engine.initialize())

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
        val engine = buildTestEngine(context, runtimeFactory = { fake })
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
        assertEquals(PlanSource.MODEL, planned.source)
        assertEquals(1, planned.plan.steps.size)
        assertTrue(planned.plan.steps.first().action is AgentAction.OpenApp)
        // Proves the model output, not the heuristic, produced the plan.
        assertTrue(fake.prompts.first().contains("Open YouTube"))
    }

    @Test
    fun stepDecisionIsValidatedAgainstApprovedCatalog() = runBlocking {
        val fake = FakeLlmRuntime(output = """{"thought":"tap the entry","action":"tap_element","params":{"targetText":"lo-fi beats"}}""")
        val engine = buildTestEngine(context, runtimeFactory = { fake })
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
        val engine = buildTestEngine(context, runtimeFactory = { fake })
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

    // 2. An unconfigured engine produces an honest not-ready state, never fake AI success.

    @Test
    fun unconfiguredEngineYieldsNotReadyAndBasicFallback() = runBlocking {
        val engine = buildTestEngine(context, runtimeFactory = { FakeLlmRuntime() })
        assertFalse(engine.isReady())
        assertTrue(engine.engineState() is EngineState.NotInitialized)

        // 6.1: with no LLM API loaded, the default is to BLOCK — never silently run
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
        val engine = ApiInferenceEngine(runtimeFactory = { FakeLlmRuntime(readyAfterInitialize = false) })
        val ok = engine.initialize()
        assertFalse(ok)
        assertTrue(engine.state.value is EngineState.Failed)
        assertFalse(engine.isReady())
    }

    @Test
    fun generationFailureReturnsNullInsteadOfFakeOutput() = runBlocking {
        val fake = FakeLlmRuntime(throwOnGenerate = true)
        val engine = ApiInferenceEngine(runtimeFactory = { fake })
        assertTrue(engine.initialize())
        assertNull(engine.generate("p", null, 32))
    }

    @Test
    fun failedSmokeTestLeavesEngineUnavailable() = runBlocking {
        val fake = FakeLlmRuntime(smokeOk = false)
        val engine = ApiInferenceEngine(runtimeFactory = { fake })
        // ensureReady loads once, then runs the probe; a failing probe marks the
        // engine unavailable (and never leaves it claiming ready).
        assertFalse(engine.ensureReady())
        assertFalse(engine.isReady())
        assertTrue(engine.state.value is EngineState.Failed)
    }

    // 5. Invalid model output / unknown actions rejected; bounded repair then fallback.

    @Test
    fun invalidPlanOutputFallsBackAfterBoundedRepair() = runBlocking {
        val fake = FakeLlmRuntime(output = "I am not JSON at all")
        val engine = buildTestEngine(context, runtimeFactory = { fake })
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
        val engine = buildTestEngine(context, runtimeFactory = { fake })
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
        assertEquals(PlanSource.MODEL, outcome.source)
        assertEquals(4, plan.steps.size)
        assertEquals(listOf(1, 2, 3, 4), plan.steps.map { it.sequence })
        assertTrue(plan.steps[0].action is AgentAction.OpenApp)
        assertEquals(VerificationMethod.FOREGROUND_PACKAGE, plan.steps[0].verification)
        assertTrue(plan.steps[3].action is AgentAction.Home)
    }

    // 3. Each provider wire format is built and parsed correctly.

    @Test
    fun openAiRuntimeBuildsAndParsesItsWireFormat() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"openai-answer"}}]}""")
        )
        server.start()
        try {
            val runtime = runtimeFor(server, LlmProvider.OPENAI)
            val out = runtime.generate("hello", "sys", 32)
            assertEquals("openai-answer", out)

            val recorded = server.takeRequest()
            assertEquals("/v1/chat/completions", recorded.path)
            assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
            assertTrue(body.contains("hello"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicRuntimeBuildsAndParsesItsWireFormat() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"content":[{"type":"text","text":"claude-answer"}]}""")
        )
        server.start()
        try {
            val runtime = runtimeFor(server, LlmProvider.ANTHROPIC)
            val out = runtime.generate("hello", "sys", 32)
            assertEquals("claude-answer", out)

            val recorded = server.takeRequest()
            assertEquals("/v1/messages", recorded.path)
            assertEquals("test-key", recorded.getHeader("x-api-key"))
            assertTrue(recorded.getHeader("anthropic-version")!!.isNotBlank())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun geminiRuntimeBuildsAndParsesItsWireFormat() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"gemini-answer"}]}}]}""")
        )
        server.start()
        try {
            val runtime = runtimeFor(server, LlmProvider.GEMINI)
            val out = runtime.generate("hello", "sys", 32)
            assertEquals("gemini-answer", out)

            val recorded = server.takeRequest()
            assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", recorded.path)
            assertEquals("test-key", recorded.getHeader("x-goog-api-key"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun nonSuccessStatusIsReportedAsInferenceFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        server.start()
        try {
            val runtime = runtimeFor(server, LlmProvider.OPENAI)
            var failed = false
            try {
                runtime.generate("hello", null, 16)
            } catch (e: LlmInferenceException) {
                failed = true
                assertTrue(e.message!!.contains("401"))
            }
            assertTrue("a non-2xx response must throw, never fabricate output", failed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unconfiguredRuntimeRefusesToGenerate() = runBlocking {
        val config = LlmProviderConfig(context).apply { clear() }
        val runtime = ApiLlmRuntime(config)
        var failed = false
        try {
            runtime.initialize()
        } catch (e: LlmInferenceException) {
            failed = true
        }
        assertTrue("nothing must be sent when no API key is configured", failed)
        assertFalse(runtime.isReady())
    }

    // 15. Engine shutdown releases the runtime.

    @Test
    fun shutdownReleasesRuntimeAndResetsState() = runBlocking {
        val fake = FakeLlmRuntime()
        val engine = ApiInferenceEngine(runtimeFactory = { fake })
        assertTrue(engine.initialize())
        engine.shutdown()
        assertTrue(fake.closed)
        assertFalse(engine.isReady())
        assertTrue(engine.state.value is EngineState.NotInitialized)
    }

    // The requested output-token limit reaches the runtime for both plan and one-step generation.

    @Test
    fun plannerSendsBoundedOutputTokenLimitToRuntime() = runBlocking {
        val planJson =
            """{"steps":[{"id":1,"seq":1,"desc":"Launch YouTube","action":"launch_app","params":{"appQuery":"youtube"},"expect":"YouTube foreground","verify":"FOREGROUND_PACKAGE"}]}"""
        val fake = FakeLlmRuntime(output = planJson)
        val engine = buildTestEngine(context, runtimeFactory = { fake }, maxOutputTokens = 768)
        assertTrue(engine.initialize())

        engine.planTask(
            goal = "Open YouTube",
            screenSummary = "Active Package: com.android.launcher",
            foregroundPackage = "com.android.launcher",
            historySummary = "",
            lastActionVerification = null,
            remainingStepBudget = 10
        )
        assertEquals(
            "full-plan generation must be bounded to the configured limit",
            768, fake.requestedMaxOutputTokens.first()
        )
    }

    @Test
    fun stepDecisionUsesSmallerBoundedOutputTokens() = runBlocking {
        val fake = FakeLlmRuntime(
            output = """{"thought":"tap search","action":"tap_element","params":{"targetText":"Search"}}"""
        )
        val engine = buildTestEngine(context, runtimeFactory = { fake })
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
        assertEquals(
            "one-step decision must use the smaller bounded budget",
            256, fake.requestedMaxOutputTokens.first()
        )
    }

    // ---- helpers ----

    /**
     * Points [provider]'s base URL at [server], preserving the provider's default path
     * prefix (e.g. `/v1`) so the request path can be asserted against the real shape.
     */
    private fun runtimeFor(server: MockWebServer, provider: LlmProvider): ApiLlmRuntime {
        val prefix = java.net.URI(provider.defaultBaseUrl).path.orEmpty().trimEnd('/')
        val base = server.url("/").toString().trimEnd('/') + prefix
        val config = LlmProviderConfig(context).apply {
            clear()
            save(provider, "test-key", base, provider.defaultModel)
        }
        return ApiLlmRuntime(config)
    }
}
