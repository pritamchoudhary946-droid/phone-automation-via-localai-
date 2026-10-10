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

    /**
     * Multimodal generation (plan 7.4): sends [imageBytes] (e.g. a PNG/JPEG screenshot)
     * alongside [prompt] so the vision-capable model can ground a target the
     * accessibility tree cannot represent. Default delegates to the text path so
     * text-only runtimes stay unchanged and never fabricate an image-aware decision.
     */
    suspend fun generateWithImage(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray
    ): String = generate(prompt, systemInstruction, maxOutputTokens)

    /**
     * Performs a tiny real generation to prove the engine can serve requests end to end.
     *
     * Returns false ONLY for a real failure (the runtime is not ready or generation
     * threw). A blank result from a small probe is NOT a failure: a healthy model can
     * legitimately answer a tiny prompt with an empty string (e.g. thinking-token-only
     * output), so an empty-but-no-exception probe counts as usable. Implementations do
     * their own bounded retry before giving up (plan 8.1).
     */
    suspend fun smokeTest(): Boolean

    /** Human-readable backend label, e.g. "CPU" or "GPU". */
    fun backendLabel(): String

    override fun close()
}

/** Raised for any genuine inference failure so callers can degrade honestly. */
class LlmInferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
