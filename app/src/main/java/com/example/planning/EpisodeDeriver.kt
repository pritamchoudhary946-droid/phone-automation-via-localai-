package com.example.planning

/**
 * Turns a finished (or recovered) run into a compact lesson for episodic memory
 * (plan 5.9). Deterministic and offline so it is unit-testable; the orchestrator
 * stores the result and injects the most relevant lessons into future prompts.
 */
object EpisodeDeriver {

    /**
     * @param outcome COMPLETED or FAILED
     * @param failureDetail what (if anything) went wrong on the way
     * @param recoveriesUsed how many re-plans/explorations the run needed
     * @param finalApproach the action that finally worked, if the task completed
     */
    fun lesson(
        outcome: String,
        failureDetail: String,
        recoveriesUsed: Int,
        finalApproach: String?
    ): String {
        val failed = outcome.equals("FAILED", ignoreCase = true)
        return when {
            failed && failureDetail.isNotBlank() ->
                "Blocked: ${failureDetail.take(160)}. A different approach is needed next time."
            !failed && recoveriesUsed > 0 && !finalApproach.isNullOrBlank() ->
                "Recovered after $recoveriesUsed attempt(s); '${finalApproach.take(120)}' worked."
            !failed && recoveriesUsed > 0 ->
                "Recovered after $recoveriesUsed attempt(s)."
            else ->
                "Clean run; the original approach worked."
        }
    }

    /** One line per episode, bounded, suitable for the planning prompt. */
    fun toPromptBlock(lessons: List<Pair<String, String>>, maxChars: Int): String =
        lessons.joinToString("\n") { (goal, lesson) ->
            "- For \"${goal.take(80)}\": ${lesson.take(160)}"
        }.take(maxChars)
}
