package com.example.planning

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
                val pkg = observationPackage?.lowercase()
                val onHome = pkg != null && (pkg.contains("launcher") || pkg == "com.android.systemui")
                Verdict(onHome, if (onHome) "Home launcher is foreground" else "Launcher not foreground (${observationPackage ?: "unknown"})")
            }
            VerificationMethod.RESULTS_PRESENT -> {
                val present = lowerText.contains("results") ||
                    (lowerText.contains("views") && (lowerText.contains("video") || lowerText.contains("channel")))
                Verdict(present, if (present) "Search results evidence present" else "No results evidence on screen")
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
