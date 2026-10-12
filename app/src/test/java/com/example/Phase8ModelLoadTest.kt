package com.example

import com.example.model.ApiInferenceEngine
import com.example.model.EngineState
import com.example.model.LlmInferenceException
import com.example.model.runTolerantSmokeProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the API-backed engine lifecycle: a single load becomes ready, failures
 * surface their real reason, and an unavailable engine is reported honestly. Uses
 * [FakeLlmRuntime] so no network or on-device model is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase8ModelLoadTest {

    @Test
    fun singleLoadBecomesReady() = runBlocking {
        val fake = FakeLlmRuntime(backend = "CPU")
        var factoryCalls = 0
        val engine = ApiInferenceEngine(
            runtimeFactory = { factoryCalls++; fake }
        )

        assertTrue(engine.initialize())
        assertEquals("one client load, no two-attempt scheme", 1, factoryCalls)
        assertTrue(engine.isReady())
        assertEquals(EngineState.Ready("CPU"), engine.state.value)
    }

    @Test
    fun initFailureReportsTheRealReason() = runBlocking {
        val engine = ApiInferenceEngine(
            runtimeFactory = { throw LlmInferenceException("no API key configured") }
        )

        assertFalse(engine.initialize())
        val state = engine.state.value
        assertTrue(state is EngineState.Failed)
        // The real init error is surfaced, not a generic placeholder.
        assertTrue((state as EngineState.Failed).message.contains("no API key configured"))
        assertFalse(engine.isReady())
    }

    @Test
    fun runtimeThatNeverReportsReadyYieldsHonestReason() = runBlocking {
        val neverReady = object : com.example.model.LlmRuntime {
            override suspend fun initialize() = Unit
            override fun isReady(): Boolean = false
            override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String = ""
            override suspend fun smokeTest(): Boolean = false
            override fun backendLabel(): String = "CPU"
            override fun close() = Unit
        }
        val engine = ApiInferenceEngine(runtimeFactory = { neverReady })

        assertFalse(engine.initialize())
        val state = engine.state.value
        assertTrue(state is EngineState.Failed)
        assertTrue((state as EngineState.Failed).message.contains("did not report ready"))
    }

    @Test
    fun throwingRuntimeSurfacesItsMessage() = runBlocking {
        val engine = ApiInferenceEngine(
            runtimeFactory = { throw LlmInferenceException("native init exploded") }
        )

        assertFalse(engine.initialize())
        assertTrue((engine.state.value as EngineState.Failed).message.contains("native init exploded"))
    }

    @Test
    fun reportUnavailableClosesRuntimeAndExposesReason() = runBlocking {
        val fake = FakeLlmRuntime()
        val engine = ApiInferenceEngine(runtimeFactory = { fake })
        assertTrue(engine.initialize())
        assertTrue(engine.isReady())

        engine.reportUnavailable("smoke test answered nothing")

        assertEquals(EngineState.Failed("smoke test answered nothing"), engine.state.value)
        assertFalse(engine.isReady())
        assertTrue(fake.closed)
    }

    // --- 8.1 (verified cause): the SMOKE PROBE must tolerate an empty answer -----

    @Test
    fun emptySmokeAnswerIsTreatedAsUsableNotFailure() = runBlocking {
        // The exact reported bug: the model loads, the tiny probe answers with an
        // empty string, and the old code flipped the engine to Failed and closed a
        // perfectly good runtime. An empty-but-no-exception answer is SUCCESS.
        var calls = 0
        val ok = runTolerantSmokeProbe(probe = { calls++; "" })
        assertTrue("empty answer must count as usable", ok)
        assertEquals("empty answer should not trigger a retry", 1, calls)
    }

    @Test
    fun nonBlankSmokeAnswerSucceedsOnFirstAttempt() = runBlocking {
        var calls = 0
        val ok = runTolerantSmokeProbe(probe = { calls++; "ready" })
        assertTrue(ok)
        assertEquals(1, calls)
    }

    @Test
    fun probeThatThrowsOnEveryAttemptIsTheOnlyRealFailure() = runBlocking {
        var calls = 0
        val ok = runTolerantSmokeProbe(attempts = 2) { calls++; throw LlmInferenceException("boom") }
        assertFalse("repeated exception is a real failure", ok)
        assertEquals(2, calls)
    }

    @Test
    fun transientThrowThenEmptyAnswerEventuallySucceeds() = runBlocking {
        var calls = 0
        val ok = runTolerantSmokeProbe(attempts = 3) {
            calls++
            if (calls == 1) throw LlmInferenceException("cold start hiccup")
            ""
        }
        assertTrue("a transient throw followed by an empty answer is usable", ok)
        assertEquals(2, calls)
    }
}
