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
