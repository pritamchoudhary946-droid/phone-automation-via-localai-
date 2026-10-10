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
 */
class GemmaInferenceEngine(
    private val runtimeFactory: (String, String?) -> LlmRuntime,
    /**
     * Optional CPU-only runtime factory used for the single bounded retry (plan 8.1).
     * GPU-capable builds are known to fail `Engine.initialize()` ("unsupported file
     * format" / vision-signature mismatch / GPU delegate OOM) on some devices; when
     * the primary attempt fails we drop to CPU once before reporting failure. Null
     * means "retry with the primary factory" (keeps every existing two-arg call site).
     */
    private val cpuRuntimeFactory: ((String, String?) -> LlmRuntime)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val mutex = Mutex()
    private var runtime: LlmRuntime? = null

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

        _state.value = EngineState.Loading
        return mutex.withLock {
            // Attempt 1: the primary (GPU-capable) runtime. Attempt 2 (plan 8.1): a
            // bounded single retry on a fresh, CPU-only runtime, because a GPU delegate
            // failure or a transient native init error is recoverable on CPU.
            var lastError: Throwable? = null
            for (attempt in 0 until MAX_INIT_ATTEMPTS) {
                val factory = if (attempt == 0) runtimeFactory else (cpuRuntimeFactory ?: runtimeFactory)
                try {
                    val created = factory(modelFile.absolutePath, modelFile.parentFile?.absolutePath)
                    created.initialize()
                    if (created.isReady()) {
                        runtime = created
                        _state.value = EngineState.Ready(created.backendLabel())
                        if (attempt > 0) {
                            Log.i(TAG, "Engine recovered on CPU-fallback retry (attempt ${attempt + 1})")
                        }
                        return@withLock true
                    }
                    runCatching { created.close() }
                    lastError = IllegalStateException("Engine did not report ready after initialization")
                } catch (e: CancellationException) {
                    _state.value = EngineState.NotInitialized
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Engine initialization attempt ${attempt + 1} failed", e)
                    lastError = e
                }
            }
            runtime = null
            _state.value = EngineState.Failed(describeInitFailure(lastError))
            false
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

    /** Small real inference used to prove the runtime works before declaring it ready. */
    suspend fun smokeTest(modelFile: File): Boolean {
        val active = runtime ?: run {
            if (!initialize(modelFile)) {
                Log.w(TAG, "Smoke test failed: could not initialize engine")
                return false
            }
            runtime
        } ?: return false
        return try {
            val result = active.smokeTest()
            if (!result) {
                Log.w(TAG, "Smoke test failed: underlying runtime smoke test failed")
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Smoke test threw: ${e.message}")
            false
        }
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
            _state.value = EngineState.NotInitialized
        }
    }

    companion object {
        private const val TAG = "GemmaInferenceEngine"
        // Primary attempt + one bounded CPU-only retry (plan 8.1).
        private const val MAX_INIT_ATTEMPTS = 2
        private const val MAX_REASON_CHARS = 300
    }
}
