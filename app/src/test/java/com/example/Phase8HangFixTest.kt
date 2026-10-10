package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.EngineState
import com.example.model.GemmaInferenceEngine
import com.example.model.MemoryTrimPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 8.9 (phone hang) / 8.10 (revert the GPU/CPU loader changes) regression tests.
 *
 * The reported hang came from the loader: two concurrent `initialize()` calls each
 * loaded a ~2.6 GB runtime (the second overwriting the first), an unconditional
 * shutdown on every memory trim caused a load/unload thrash, the GPU-primary attempt
 * doubled the init work, and the smoke probe re-ran on every re-open. These tests pin
 * the single-flight, close-before-replace and probe-once behaviour using fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase8HangFixTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun concurrentInitializeLoadsExactlyOneRuntime() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var factoryCalls = 0
        val engine = GemmaInferenceEngine(
            runtimeFactory = { _, _ ->
                factoryCalls++
                // Signal that a load is in progress, then block so the second caller can
                // pass the pre-lock guard while this first caller still holds the lock.
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                FakeLlmRuntime()
            }
        )
        val file = fakeModelFile(context.cacheDir)

        val (r1, r2) = coroutineScope {
            val first = async(Dispatchers.Default) { engine.initialize(file) }
            assertTrue("first load should have started", entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { engine.initialize(file) }
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
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> factoryCalls++; FakeLlmRuntime() })
        val file = fakeModelFile(context.cacheDir)

        assertTrue(engine.initialize(file))
        assertTrue(engine.initialize(file))
        assertTrue(engine.initialize(file))

        assertEquals("repeated init while ready must not reload", 1, factoryCalls)
        assertEquals(EngineState.Ready("CPU"), engine.state.value)
    }

    @Test
    fun shutdownClosesRuntimeAndAllowsAFreshLoad() = runBlocking {
        val first = FakeLlmRuntime()
        var factoryCalls = 0
        val engine = GemmaInferenceEngine(
            runtimeFactory = { _, _ -> factoryCalls++; if (factoryCalls == 1) first else FakeLlmRuntime() }
        )
        val file = fakeModelFile(context.cacheDir)

        assertTrue(engine.initialize(file))
        engine.shutdown()
        assertTrue("shutdown must close the runtime", first.closed)
        assertFalse(engine.isReady())

        assertTrue(engine.initialize(file))
        assertEquals(2, factoryCalls)
    }

    @Test
    fun ensureReadyRunsTheSmokeProbeOnlyOnce() = runBlocking {
        val fake = FakeLlmRuntime()
        var factoryCalls = 0
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> factoryCalls++; fake })
        val file = fakeModelFile(context.cacheDir)

        assertTrue(engine.ensureReady(file))
        assertTrue(engine.ensureReady(file))
        assertTrue(engine.ensureReady(file))

        assertEquals("the smoke probe must run once, not on every re-open", 1, fake.smokeTestCount)
        assertEquals("and the engine must load once", 1, factoryCalls)
    }

    @Test
    fun ensureReadyProbesAgainAfterAMemoryTrimDropsTheEngine() = runBlocking {
        val fakes = mutableListOf<FakeLlmRuntime>()
        val engine = GemmaInferenceEngine(runtimeFactory = { _, _ -> FakeLlmRuntime().also { fakes.add(it) } })
        val file = fakeModelFile(context.cacheDir)

        assertTrue(engine.ensureReady(file))
        assertEquals(1, fakes.sumOf { it.smokeTestCount })

        // A genuine critical-pressure trim releases the engine and re-arms the probe.
        engine.shutdown()
        assertTrue(engine.ensureReady(file))
        assertEquals("a fresh load is re-verified", 2, fakes.sumOf { it.smokeTestCount })
    }

    // --- 8.9: memory-trim policy (no load/unload thrash) --------------------------

    @Test
    fun ordinaryAndBackgroundTrimsKeepTheEngine() {
        // A cached/background process is routinely trimmed at these levels; tearing the
        // engine down here is what produced the multi-GB reload spike.
        assertFalse(MemoryTrimPolicy.shouldReleaseEngine(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(MemoryTrimPolicy.shouldReleaseEngine(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertFalse(MemoryTrimPolicy.shouldReleaseEngine(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertFalse(MemoryTrimPolicy.shouldReleaseEngine(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun onlyGenuineCriticalPressureReleasesTheEngine() {
        assertTrue(MemoryTrimPolicy.shouldReleaseEngine(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
