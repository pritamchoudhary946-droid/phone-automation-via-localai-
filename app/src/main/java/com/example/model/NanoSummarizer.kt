package com.example.model

/**
 * Optional summarizer (plan 4.7). On a device that exposes AICore/Gemini Nano it
 * produces short summaries; everywhere else it is an honest no-op that reports it is
 * unavailable, so callers fall back to the configured LLM API or to plain text.
 */
interface NanoSummarizer {
    /** True only when a real on-device Nano backend is present on this device. */
    fun isAvailable(): Boolean

    /**
     * Summarizes [text] into at most [maxChars]. Returns null when unavailable or when
     * the backend cannot summarize, so the caller keeps its own behaviour.
     */
    suspend fun summarize(text: String, maxChars: Int = 280): String?

    val backendLabel: String

    companion object {
        /** Largest input handed to a summarizer, so a huge page cannot swamp the request. */
        const val MAX_INPUT_CHARS = 8000

        /**
         * The one summarisation prompt both the real [ApiNanoSummarizer] and the offline
         * `summarize_text` executor (which drives the engine directly) use, so a digest
         * produced in the agent loop matches a digest produced by [summarize] exactly.
         */
        fun promptFor(text: String, maxChars: Int): String =
            "Summarize the following in at most ${maxChars.coerceAtLeast(40)} characters, " +
                "one plain sentence, no preamble:\n${text.take(MAX_INPUT_CHARS)}"

        /** The system instruction the summarisation request carries. */
        const val SYSTEM_INSTRUCTION = "You are a terse summarizer."
    }
}

/**
 * Default backend: this build ships no AICore integration, so it reports itself as
 * unavailable rather than pretending to summarize. Callers that need a summary keep
 * their existing non-Nano path.
 */
object UnavailableNanoSummarizer : NanoSummarizer {
    override fun isAvailable(): Boolean = false
    override suspend fun summarize(text: String, maxChars: Int): String? = null
    override val backendLabel: String = "NANO_UNAVAILABLE"
}

/**
 * API-backed summarizer (plan 7.12). This build has no AICore/Nano runtime, so rather
 * than leave a dead `NanoSummarizer` seam wired into the app, the summarizer delegates
 * to the configured remote LLM engine. It is used for short, non-critical
 * summarization only; when the engine is not ready it returns null and the caller keeps
 * its plain text.
 */
class ApiNanoSummarizer(
    private val generate: suspend (prompt: String, systemInstruction: String?, maxOutputTokens: Int) -> String?,
    private val ready: () -> Boolean
) : NanoSummarizer {

    override fun isAvailable(): Boolean = ready()

    override suspend fun summarize(text: String, maxChars: Int): String? {
        if (!ready()) return null
        if (text.isBlank()) return null
        val out = try {
            generate(
                NanoSummarizer.promptFor(text, maxChars),
                NanoSummarizer.SYSTEM_INSTRUCTION,
                160
            )?.trim()
        } catch (e: Exception) {
            null
        }
        return out?.takeIf { it.isNotBlank() }?.take(maxChars)
    }

    override val backendLabel: String = "API_SUMMARY"
}
