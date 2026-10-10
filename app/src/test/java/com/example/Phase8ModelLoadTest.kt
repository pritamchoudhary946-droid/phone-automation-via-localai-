package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.EngineState
import com.example.model.GemmaInferenceEngine
import com.example.model.LlmInferenceException
import com.example.model.runTolerantSmokeProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8 tests for the headline "model downloads but won't load" bug (8.1) and the
 * honest surface of the real failure (8.2). Uses [FakeLlmRuntime] so no 2.59 GB
 * model is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase8ModelLoadTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun primaryFailureFallsBackToCpuRuntimeAndBecomesReady() = runBlocking {
        val cpu = FakeLlmRuntime(backend = "CPU")
        var primaryCalls = 0
        var cpuCalls = 0
        val engine = GemmaInferenceEngine(
            runtimeFactory = { _, _ -> primaryCalls++; FakeLlmRuntime(readyAfterInitialize = false) },
            cpuRuntimeFactory = { _, _ -> cpuCalls++; cpu }
        )

        assertTrue(engine.initialize(fakeModelFile(context.cacheDir)))
        assertEquals(1, primaryCalls)
        assertEquals(1, cpuCalls)
        assertTrue(engine.isReady())
        assertEquals(EngineState.Ready("CPU"), engine.state.value)
    }

    @Test
    fun bothAttemptsFailReportsTheRealReason() = runBlocking {
        val engine = GemmaInferenceEngine(
            runtimeFactory = { _, _ -> throw LlmInferenceException("primary boom: unsupported file format") },
            cpuRuntimeFactory = { _, _ -> throw LlmInferenceException("cpu boom: out of memory") }
        )

        assertFalse(engine.initialize(fakeModelFile(context.cacheDir)))
        val state = engine.state.value
        assertTrue(state is EngineState.Failed)
        // The last (CPU-fallback) error is the one surfaced, not a generic placeholder.
        assertTrue((state as EngineState.Failed).message.contains("cpu boom"))
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
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> neverReady })

        assertFalse(engine.initialize(fakeModelFile(context.cacheDir)))
        val state = engine.state.value
        assertTrue(state is EngineState.Failed)
        assertTrue((state as EngineState.Failed).message.contains("did not report ready"))
    }

    @Test
    fun throwingRuntimeSurfacesItsMessage() = runBlocking {
        val engine = GemmaInferenceEngine(
            runtimeFactory = { _, _ -> throw LlmInferenceException("native init exploded") }
        )

        assertFalse(engine.initialize(fakeModelFile(context.cacheDir)))
        assertTrue((engine.state.value as EngineState.Failed).message.contains("native init exploded"))
    }

    @Test
    fun reportUnavailableClosesRuntimeAndExposesReason() = runBlocking {
        val fake = FakeLlmRuntime()
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> fake })
        assertTrue(engine.initialize(fakeModelFile(context.cacheDir)))
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
