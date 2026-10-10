package com.example.model

/**
 * Optional on-device summarizer (plan 4.7). On a device that exposes AICore/Gemini
 * Nano it produces short summaries; everywhere else it is an honest no-op that
 * reports it is unavailable, so callers fall back to the E2B model or to plain text.
 * Nothing here talks to the network.
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
 * E2B-backed summarizer (plan 7.12). This build has no AICore/Nano runtime, so rather
 * than leave a dead `NanoSummarizer` seam wired into the app, the summarizer delegates
 * to the already-loaded local E2B engine. It is used for short, non-critical
 * summarization only; when the engine is not ready it returns null and the caller keeps
 * its plain text. Nothing leaves the device.
 */
class EngineNanoSummarizer(
    private val generate: suspend (prompt: String, systemInstruction: String?, maxOutputTokens: Int) -> String?,
    private val ready: () -> Boolean
) : NanoSummarizer {

    override fun isAvailable(): Boolean = ready()

    override suspend fun summarize(text: String, maxChars: Int): String? {
        if (!ready()) return null
        if (text.isBlank()) return null
        val bounded = text.take(4000)
        val out = try {
            generate(
                "Summarize the following in at most ${maxChars.coerceAtLeast(40)} characters, " +
                    "one plain sentence, no preamble:\n$bounded",
                "You are a terse on-device summarizer.",
                160
            )?.trim()
        } catch (e: Exception) {
            null
        }
        return out?.takeIf { it.isNotBlank() }?.take(maxChars)
    }

    override val backendLabel: String = "E2B_SUMMARY"
}
