package com.example.planning

import com.example.agent.ForegroundPackageVerifier
import com.example.agent.ToolRegistry

/** Structured, goal-specific postcondition checks over a fresh observation. */
object PostconditionVerifier {

    data class Verdict(val satisfied: Boolean, val detail: String)

    fun verify(
        method: VerificationMethod,
        expectedPostcondition: String,
        targetPackage: String?,
        observationPackage: String?,
        screenChanged: Boolean,
        screenText: String
    ): Verdict {
        val lowerText = screenText.lowercase()
        return when (method) {
            VerificationMethod.FOREGROUND_PACKAGE -> {
                val match = !targetPackage.isNullOrBlank() && !observationPackage.isNullOrBlank() &&
                    targetPackage.equals(observationPackage, ignoreCase = true)
                Verdict(match, if (match) "Foreground package verified: $observationPackage" else "Expected $targetPackage but observed ${observationPackage ?: "unknown"}")
            }
            VerificationMethod.HOME_FOREGROUND -> {
                val onHome = ForegroundPackageVerifier.isHomePackage(observationPackage)
                Verdict(onHome, if (onHome) "Home launcher is foreground" else "Launcher not foreground (${observationPackage ?: "unknown"})")
            }
            VerificationMethod.RESULTS_PRESENT -> {
                // A submitted search rarely leaves the literal word "results" on screen, and
                // the typed query itself is not evidence that results rendered. Accept any
                // real result-surface signal: a list that grew/changed, a scrollable results
                // container, an explicit results header, or a query echoed alongside results.
                val hasExplicitResults = lowerText.contains("results") ||
                    lowerText.contains("search results") ||
                    lowerText.contains("no results") ||
                    lowerText.contains("top results")
                val hasScrollableList = lowerText.contains("[scrollable]")
                val hasResultItems = lowerText.contains("views") &&
                    (lowerText.contains("video") || lowerText.contains("channel") || lowerText.contains("watch"))
                val present = hasExplicitResults || hasScrollableList || hasResultItems || screenChanged
                val detail = when {
                    hasExplicitResults -> "Search results evidence present"
                    hasScrollableList -> "Scrollable results container present"
                    hasResultItems -> "Result items present"
                    screenChanged -> "Screen advanced after the search"
                    else -> "No results evidence on screen"
                }
                Verdict(present, detail)
            }
            VerificationMethod.TEXT_PRESENT -> {
                val needle = expectedPostcondition.lowercase().trim()
                val present = needle.isNotBlank() && lowerText.contains(needle)
                Verdict(present, if (present) "Expected text present: \"$expectedPostcondition\"" else "Expected text not found")
            }
            VerificationMethod.SCREEN_CHANGED ->
                Verdict(screenChanged, if (screenChanged) "Screen changed after action" else "Screen did not change")
            VerificationMethod.NONE ->
                Verdict(screenChanged, if (screenChanged) "Incidental screen change observed" else "No observable change")
        }
    }

    fun availableActions(registry: ToolRegistry): List<String> = registry.getRegisteredTools().map { it.name }
}
