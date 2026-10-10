package com.example.voice

/**
 * Lightweight, offline language detection (plan 7.17). Not a full i18n framework:
 * it recognises Devanagari script and common Roman-Hindi words so status/TTS
 * messages can be phrased in the user's language end-to-end.
 */
object LanguageDetector {

    enum class Language { ENGLISH, HINDI, ROMAN_HINDI }

    private val devanagari = Regex("[\\u0900-\\u097F]")

    private val romanHindiWords = setOf(
        "karo", "kar", "kholo", "khol", "bhejo", "bhej", "likho", "likh",
        "dhundho", "dhoondo", "dhundo", "pe", "par", "ko", "ghar", "band",
        "chalu", "chalau", "on", "off", "hai", "haan", "nahi", "mera", "meri",
        "bhai", "didi", "bhejna", "karna", "band karo", "chalao", "lagao"
    )

    /** True when the text contains Devanagari characters. */
    fun isHindi(text: String): Boolean = devanagari.containsMatchIn(text)

    /** Detects the dominant language of [text]; defaults to ENGLISH. */
    fun detect(text: String): Language {
        if (text.isBlank()) return Language.ENGLISH
        if (isHindi(text)) return Language.HINDI
        val tokens = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        val hits = tokens.count { it in romanHindiWords }
        // One distinctive Roman-Hindi marker is enough for a short command
        // ("wifi band karo"), but require a real signal, not a chance substring.
        return if (hits >= 1 && hits * 3 >= tokens.size) Language.ROMAN_HINDI else Language.ENGLISH
    }

    /** True when [text] is Hindi or Roman-Hindi, i.e. the assistant should answer in Hindi. */
    fun prefersHindi(text: String): Boolean = detect(text) != Language.ENGLISH
}
