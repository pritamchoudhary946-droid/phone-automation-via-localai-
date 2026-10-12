package com.example

import com.example.model.ApiInferenceEngine
import com.example.model.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the engine loader's concurrency and lifecycle guarantees.
 *
 * The original hang came from the loader: two concurrent `initialize()` calls each
 * built a runtime (the second overwriting the first), and the smoke probe re-ran on
 * every re-open. These tests pin the single-flight, close-before-replace and
 * probe-once behaviour using fakes. The guarantees are kept for the API client too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase8HangFixTest {

    @Test
    fun concurrentInitializeLoadsExactlyOneRuntime() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var factoryCalls = 0
        val engine = ApiInferenceEngine(
            runtimeFactory = {
                factoryCalls++
                // Signal that a load is in progress, then block so the second caller can
                // pass the pre-lock guard while this first caller still holds the lock.
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                FakeLlmRuntime()
            }
        )

        val (r1, r2) = coroutineScope {
            val first = async(Dispatchers.Default) { engine.initialize() }
            assertTrue("first load should have started", entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { engine.initialize() }
            // Let the second caller reach the mutex and suspend on it.
            delay(100)
            release.countDown()
            first.await() to second.await()
        }

        assertTrue(r1)
        assertTrue(r2)
        assertEquals("two concurrent initializers must create exactly ONE runtime", 1, factoryCalls)
        assertTrue(engine.isReady())
    }

    @Test
    fun repeatedInitializeWhileReadyIsANoOp() = runBlocking {
        var factoryCalls = 0
        val engine = ApiInferenceEngine(runtimeFactory = { factoryCalls++; FakeLlmRuntime() })

        assertTrue(engine.initialize())
        assertTrue(engine.initialize())
        assertTrue(engine.initialize())

        assertEquals("repeated init while ready must not reload", 1, factoryCalls)
        assertEquals(EngineState.Ready("CPU"), engine.state.value)
    }

    @Test
    fun shutdownClosesRuntimeAndAllowsAFreshLoad() = runBlocking {
        val first = FakeLlmRuntime()
        var factoryCalls = 0
        val engine = ApiInferenceEngine(
            runtimeFactory = { factoryCalls++; if (factoryCalls == 1) first else FakeLlmRuntime() }
        )

        assertTrue(engine.initialize())
        engine.shutdown()
        assertTrue("shutdown must close the runtime", first.closed)
        assertFalse(engine.isReady())

        assertTrue(engine.initialize())
        assertEquals(2, factoryCalls)
    }

    @Test
    fun ensureReadyRunsTheSmokeProbeOnlyOnce() = runBlocking {
        val fake = FakeLlmRuntime()
        var factoryCalls = 0
        val engine = ApiInferenceEngine(runtimeFactory = { factoryCalls++; fake })

        assertTrue(engine.ensureReady())
        assertTrue(engine.ensureReady())
        assertTrue(engine.ensureReady())

        assertEquals("the smoke probe must run once, not on every re-open", 1, fake.smokeTestCount)
        assertEquals("and the engine must load once", 1, factoryCalls)
    }

    @Test
    fun ensureReadyProbesAgainAfterAShutdownDropsTheEngine() = runBlocking {
        val fakes = mutableListOf<FakeLlmRuntime>()
        val engine = ApiInferenceEngine(runtimeFactory = { FakeLlmRuntime().also { fakes.add(it) } })

        assertTrue(engine.ensureReady())
        assertEquals(1, fakes.sumOf { it.smokeTestCount })

        // A shutdown releases the engine and re-arms the probe.
        engine.shutdown()
        assertTrue(engine.ensureReady())
        assertEquals("a fresh load is re-verified", 2, fakes.sumOf { it.smokeTestCount })
    }
}
