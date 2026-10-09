package com.example.model

/**
 * Thin, injectable seam over a local text-generation runtime.
 *
 * The production implementation wraps the official LiteRT-LM Kotlin API
 * ([com.google.ai.edge.litertlm.Engine]). Tests inject fakes so the planning
 * pipeline can be exercised deterministically without a 2.59 GB model.
 *
 * Implementations must be safe to call only after [initialize] succeeded, and
 * [generate] must never be invoked on the main thread by callers.
 */
interface LlmRuntime : AutoCloseable {

    /** Loads the model and native runtime. Idempotent; safe to call more than once. */
    suspend fun initialize()

    /** True once the underlying engine is loaded and able to serve requests. */
    fun isReady(): Boolean

    /**
     * Runs a single bounded generation and returns the raw model text.
     * @throws LlmInferenceException when initialization, generation or the runtime fails.
     */
    suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String

    /** Performs a tiny real generation to prove the engine can serve requests end to end. */
    suspend fun smokeTest(): Boolean

    /** Human-readable backend label, e.g. "CPU" or "GPU". */
    fun backendLabel(): String

    override fun close()
}

/** Raised for any genuine inference failure so callers can degrade honestly. */
class LlmInferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
