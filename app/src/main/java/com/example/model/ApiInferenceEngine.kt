package com.example.model

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Honest lifecycle state for the remote reasoning engine. */
sealed class EngineState {
    object NotInitialized : EngineState()
    object Loading : EngineState()
    data class Ready(val backend: String) : EngineState()
    data class Failed(val message: String) : EngineState()
}

/**
 * Owns exactly one [LlmRuntime] instance, loads it off the UI thread, guards it against
 * concurrent generation, and reports failures honestly so the app can fall back to a
 * clearly labelled basic mode instead of pretending the model ran.
 *
 * The runtime is now a remote API client ([ApiLlmRuntime]), so "loading" is just
 * verifying the user supplied a provider and key — there is no multi-GB model to fetch.
 * The single-flight and close-before-replace guarantees are kept anyway, so a retry tap
 * racing the startup path can never leave two clients alive.
 */
class ApiInferenceEngine(
    private val runtimeFactory: () -> LlmRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val mutex = Mutex()
    private var runtime: LlmRuntime? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.NotInitialized)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Initializes the engine once. Repeated calls while ready are no-ops.
     * @return true when the engine is ready for inference.
     */
    suspend fun initialize(): Boolean {
        if (_state.value is EngineState.Ready && runtime?.isReady() == true) return true

        return mutex.withLock {
            // Single-flight: re-check INSIDE the lock. The early guard above is checked
            // before acquiring it, so two concurrent callers (the MainActivity effect and
            // a Retry tap) both pass it; without this re-check they would each build a
            // client and the second assignment would overwrite (leak) the first.
            if (_state.value is EngineState.Ready && runtime?.isReady() == true) return@withLock true

            _state.value = EngineState.Loading
            try {
                val created = runtimeFactory()
                created.initialize()
                if (!created.isReady()) {
                    runCatching { created.close() }
                    runtime = null
                    _state.value = EngineState.Failed("Engine did not report ready after initialization")
                    return@withLock false
                }
                // Close any previous runtime before replacing it so two clients can
                // never be alive at once.
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
     * Multimodal generation: sends a screenshot image alongside the prompt when the
     * runtime supports it. Returns null (never fabricates) when not ready.
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
     * Prepares the engine for inference: verifies a provider and key are configured and
     * reports ready. There is deliberately NO load-time test prompt.
     *
     * A one-word probe is a poor readiness gate: a live provider is slow on its first
     * call and may legitimately answer a tiny prompt with an empty string, so gating on
     * it produced false "engine not available" failures right after a user added a valid
     * key. A real defect (bad key, unreachable endpoint) surfaces on the first actual
     * generation and is degraded honestly there via [generate] and [reportUnavailable].
     */
    suspend fun ensureReady(): Boolean = initialize()

    fun isReady(): Boolean = _state.value is EngineState.Ready && runtime?.isReady() == true

    /**
     * Marks the engine honestly unavailable with a real reason (e.g. no API key is
     * configured, or the first real generation failed), so the UI can show *why*
     * instead of a silent "not loaded".
     */
    fun reportUnavailable(reason: String) {
        runCatching { runtime?.close() }
        runtime = null
        _state.value = EngineState.Failed(reason)
    }

    /** Releases the engine and client resources. Safe to call on backgrounding/shutdown. */
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
        private const val TAG = "ApiInferenceEngine"
        private const val MAX_REASON_CHARS = 300
    }
}
