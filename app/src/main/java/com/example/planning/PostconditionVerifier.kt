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
                // A changed fingerprint is only a timing/navigation signal; it is not
                // proof that the requested result surface rendered. Require semantic
                // evidence from the fresh screen. An explicit no-results state is still
                // useful evidence that the search completed, but it must be reported as
                // such so a later open-item step cannot confuse it with a result.
                val hasExplicitResults = lowerText.contains("search results") ||
                    lowerText.contains("top results") ||
                    Regex("\\b(results|videos|channels|watch)\\s*[:(]").containsMatchIn(lowerText)
                val hasNoResults = lowerText.contains("no results") ||
                    lowerText.contains("nothing found") ||
                    lowerText.contains("0 results")
                val scrollableCount = Regex("\\[scrollable\\]").findAll(lowerText).count()
                val hasResultItems = lowerText.contains("views") &&
                    (lowerText.contains("video") || lowerText.contains("channel") || lowerText.contains("watch"))
                val hasStructuredList = scrollableCount >= 2 &&
                    (hasResultItems || lowerText.contains("video") || lowerText.contains("channel") ||
                        lowerText.contains("watch") || lowerText.contains("mix") ||
                        lowerText.contains("radio"))
                val present = hasExplicitResults || hasNoResults || hasResultItems || hasStructuredList
                val detail = when {
                    hasNoResults -> "Search completed with an explicit no-results state"
                    hasExplicitResults -> "Search results evidence present"
                    hasResultItems -> "Result items present"
                    hasStructuredList -> "Structured scrollable result list present"
                    else -> "No results evidence on screen"
                }
                Verdict(present, detail)
            }
            VerificationMethod.TEXT_PRESENT -> {
                val needle = expectedPostcondition.lowercase().trim()
                val present = needle.isNotBlank() && lowerText.contains(needle)
                Verdict(present, if (present) "Expected text present: \"$expectedPostcondition\"" else "Expected text not found")
            }
            VerificationMethod.PLAYBACK -> {
                // A real player/playback surface - controls/title/time/progress - is
                // required. Opening a row is not playback (10.2/10.3F).
                val playbackSignals = listOf(
                    "now playing", "playing", "pause", "resume", "fullscreen",
                    "seek", "00:", "0:0", "/ 0", "watch later", "player"
                )
                val hasSignal = playbackSignals.any { lowerText.contains(it) }
                val hasProgress = Regex("\\d{1,2}:\\d{2}").containsMatchIn(lowerText)
                val hasControls = lowerText.contains("play") || lowerText.contains("pause") ||
                    lowerText.contains("seek") || lowerText.contains("fullscreen")
                val verified = hasSignal && (hasProgress || hasControls)
                Verdict(
                    verified,
                    if (verified) "Playback/player evidence present" else "No playback evidence (player controls/progress) on screen"
                )
            }
            VerificationMethod.CONTENT -> {
                val meaningful = screenText.trim().length >= 40 &&
                    !lowerText.contains("blank") && !lowerText.contains("protected")
                Verdict(meaningful, if (meaningful) "Meaningful page content obtained" else "No meaningful content obtained")
            }
            VerificationMethod.SCREEN_CHANGED ->
                Verdict(screenChanged, if (screenChanged) "Screen changed after action" else "Screen did not change")
            VerificationMethod.NONE -> {
                // NONE is intentionally not a success shortcut. Callers must provide
                // a concrete postcondition or an action-specific verifier; incidental
                // animation/navigation cannot prove task completion.
                Verdict(
                    false,
                    "No structured postcondition was supplied; completion is not verifiable"
                )
            }
        }
    }

    fun availableActions(registry: ToolRegistry): List<String> = registry.getRegisteredTools().map { it.name }

    /**
     * Vision-diff verification (plan 4.3): when the accessibility fingerprint did not
     * change but a coarse before/after screenshot diff shows a real change, treat that
     * as verification evidence. Never upgrades a satisfied verdict's wording, and a
     * missing screenshot (visionChanged=false) never fabricates a change.
     */
    fun applyVisionDiffEvidence(verdict: Verdict, visionChanged: Boolean): Verdict =
        if (!verdict.satisfied && visionChanged) {
            Verdict(true, "${verdict.detail} (vision diff confirmed a real screen change)")
        } else {
            verdict
        }
}
