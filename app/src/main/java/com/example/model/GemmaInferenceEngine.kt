package com.example.model

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Honest lifecycle state for the on-device reasoning engine. */
sealed class EngineState {
    object NotInitialized : EngineState()
    object Loading : EngineState()
    data class Ready(val backend: String) : EngineState()
    data class Failed(val message: String) : EngineState()
}

/**
 * Owns exactly one [LlmRuntime] instance, loads it off the UI thread, guards it
 * against concurrent generation, and reports failures honestly so the app can
 * fall back to a clearly labelled basic mode instead of pretending Gemma ran.
 *
 * 8.9/8.10: the loader is CPU-first and single-flight. There is no GPU-primary +
 * CPU two-attempt scheme, because on some devices a failed GPU delegate doubles
 * the ~2.6 GB init work and thrashes memory; [runtimeFactory] is the one load path.
 */
class GemmaInferenceEngine(
    private val runtimeFactory: (String, String?) -> LlmRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val mutex = Mutex()
    private var runtime: LlmRuntime? = null
    /** True once a load+smoke cycle has completed, so the probe does not re-run. */
    private val smokeVerified = AtomicBoolean(false)

    private val _state = MutableStateFlow<EngineState>(EngineState.NotInitialized)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Initializes the engine against [modelFile] once. Repeated calls while ready are no-ops.
     * @return true when the engine is ready for inference.
     */
    suspend fun initialize(modelFile: File): Boolean {
        if (_state.value is EngineState.Ready && runtime?.isReady() == true) return true
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            _state.value = EngineState.Failed("Model file is missing or empty")
            return false
        }

        return mutex.withLock {
            // 8.9 single-flight: re-check INSIDE the lock. The early guard above is
            // checked before acquiring it, so two concurrent callers (e.g. the
            // MainActivity LaunchedEffect and a Retry tap) both pass it; without this
            // re-check they would each load a ~2.6 GB runtime and the second assignment
            // would overwrite (leak) the first — the reported phone hang.
            if (_state.value is EngineState.Ready && runtime?.isReady() == true) return@withLock true

            _state.value = EngineState.Loading
            try {
                val created = runtimeFactory(modelFile.absolutePath, modelFile.parentFile?.absolutePath)
                created.initialize()
                if (!created.isReady()) {
                    runCatching { created.close() }
                    runtime = null
                    _state.value = EngineState.Failed("Engine did not report ready after initialization")
                    return@withLock false
                }
                // Close any previous runtime before replacing it so two engines can
                // never be alive at once (the core of the 8.9 memory fix).
                runCatching { runtime?.close() }
                runtime = created
                _state.value = EngineState.Ready(created.backendLabel())
                true
            } catch (e: CancellationException) {
                _state.value = EngineState.NotInitialized
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Engine initialization failed", e)
                runtime = null
                _state.value = EngineState.Failed(describeInitFailure(e))
                false
            }
        }
    }

    /** Turns a raw init exception into a short, actionable, user-visible reason. */
    private fun describeInitFailure(error: Throwable?): String {
        val raw = error?.message?.takeIf { it.isNotBlank() } ?: "Unknown initialization error"
        return if (raw.length <= MAX_REASON_CHARS) raw else raw.substring(0, MAX_REASON_CHARS) + "…"
    }

    /** Runs a bounded real inference. Returns null (and marks failure) instead of faking output. */
    suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String? =
        withContext(Dispatchers.IO) {
            val active = runtime
            if (active == null || !active.isReady()) {
                return@withContext null
            }
            mutex.withLock {
                try {
                    active.generate(prompt, systemInstruction, maxOutputTokens)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Generation failed: ${e.message}")
                    if (!active.isReady()) {
                        _state.value = EngineState.Failed(e.message ?: "Generation failed")
                    }
                    null
                }
            }
        }

    /**
     * Multimodal generation (plan 7.4): sends a screenshot image alongside the prompt
     * when the runtime supports it. Returns null (never fabricates) when not ready.
     */
    suspend fun generateWithImage(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray
    ): String? = withContext(Dispatchers.IO) {
        val active = runtime
        if (active == null || !active.isReady()) return@withContext null
        mutex.withLock {
            try {
                active.generateWithImage(prompt, systemInstruction, maxOutputTokens, imageBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Multimodal generation failed: ${e.message}")
                null
            }
        }
    }

    /**
     * 8.9: loads the engine once, then runs the smoke probe ONCE, cheaply, and
     * remembers it. Subsequent calls are cheap no-ops, so re-opening the app (or a
     * model-ready transition re-triggering init) does not re-run a real generation on
     * an already-hot device. Returns true only when the engine is ready and the probe
     * passed; a failure is reported honestly via [reportUnavailable].
     */
    suspend fun ensureReady(modelFile: File): Boolean {
        if (!initialize(modelFile)) return false
        if (smokeVerified.get()) return true
        val active = runtime ?: return false
        val passed = try {
            active.smokeTest().also {
                if (!it) Log.w(TAG, "Smoke test failed: underlying runtime smoke test failed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Smoke test threw: ${e.message}")
            false
        }
        if (passed) {
            smokeVerified.set(true)
            return true
        }
        reportUnavailable("The model loaded but did not answer a test prompt (load or memory failure)")
        return false
    }

    fun isReady(): Boolean = _state.value is EngineState.Ready && runtime?.isReady() == true

    /**
     * Marks the engine honestly unavailable with a real reason (plan 8.2). Used when the
     * model loaded but a post-load check (e.g. the smoke test) failed, so the UI can show
     * *why* instead of a silent "not loaded".
     */
    fun reportUnavailable(reason: String) {
        runCatching { runtime?.close() }
        runtime = null
        smokeVerified.set(false)
        _state.value = EngineState.Failed(reason)
    }

    /** Releases the engine and native resources. Safe to call on backgrounding/shutdown. */
    fun shutdown() {
        try {
            runtime?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error unloading engine: ${e.message}")
        } finally {
            runtime = null
            smokeVerified.set(false)
            _state.value = EngineState.NotInitialized
        }
    }

    companion object {
        private const val TAG = "GemmaInferenceEngine"
        private const val MAX_REASON_CHARS = 300
    }
}
