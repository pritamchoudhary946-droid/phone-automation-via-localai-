package com.example.model

/**
 * Thin, injectable seam over a text-generation runtime.
 *
 * The production implementation is a remote API client ([ApiLlmRuntime]) that speaks
 * the OpenAI / Anthropic / Gemini request shapes, so the app reasons through whichever
 * provider the user configures with their own key. Tests inject fakes so the planning
 * pipeline can be exercised deterministically without any network.
 *
 * Implementations must be safe to call only after [initialize] succeeded, and
 * [generate] must never be invoked on the main thread by callers.
 */
interface LlmRuntime : AutoCloseable {

    /** Verifies the runtime is usable (e.g. an API key is configured). Idempotent. */
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
     * Human-readable backend label, e.g. "CPU" or "GPU".
     */
    fun backendLabel(): String

    override fun close()
}

/** Raised for any genuine inference failure so callers can degrade honestly. */
class LlmInferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
