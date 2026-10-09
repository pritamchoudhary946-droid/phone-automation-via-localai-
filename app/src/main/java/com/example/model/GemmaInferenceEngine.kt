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
            try {
                val existing = runtime
                if (existing == null) {
                    val created = runtimeFactory(modelFile.absolutePath, modelFile.parentFile?.absolutePath)
                    created.initialize()
                    runtime = created
                } else {
                    existing.initialize()
                }

                val ready = runtime?.isReady() == true
                if (ready) {
                    _state.value = EngineState.Ready(runtime?.backendLabel() ?: "CPU")
                    true
                } else {
                    _state.value = EngineState.Failed("Engine did not report ready after initialization")
                    false
                }
            } catch (e: CancellationException) {
                _state.value = EngineState.NotInitialized
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Engine initialization failed", e)
                _state.value = EngineState.Failed(e.message ?: "Unknown initialization error")
                false
            }
        }
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

    /** Small real inference used to prove the runtime works before declaring it ready. */
    suspend fun smokeTest(modelFile: File): Boolean {
        val active = runtime ?: run {
            if (!initialize(modelFile)) return false
            runtime
        } ?: return false
        return try {
            active.smokeTest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Smoke test threw: ${e.message}")
            false
        }
    }

    fun isReady(): Boolean = _state.value is EngineState.Ready && runtime?.isReady() == true

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
    }
}
