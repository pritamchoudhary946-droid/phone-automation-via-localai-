package com.example.planning

/**
 * Self-critique of a finished plan (plan 4.8). Deterministic and offline: the
 * planner reflects on what the run actually did so it can avoid repeating waste
 * (redundant steps, retried steps) next time, without a second model call.
 */
object PlanSelfReview {

    data class Review(val score: Int, val notes: List<String>)

    /**
     * @param stepCount number of steps in the final plan
     * @param completed steps that reached COMPLETED
     * @param retried steps that needed more than one attempt
     * @param goalSatisfied whether the goal-level postcondition was verified
     */
    fun review(stepCount: Int, completed: Int, retried: Int, goalSatisfied: Boolean): Review {
        val notes = mutableListOf<String>()
        if (!goalSatisfied) notes += "goal postcondition was not verified"
        if (retried > 0) notes += "$retried step(s) needed retries"
        if (stepCount > 0 && completed < stepCount) {
            notes += "${stepCount - completed} step(s) were left incomplete"
        }
        val score = (if (goalSatisfied) 70 else 0) + completed.coerceAtLeast(0) * 5 - retried * 5
        return Review(score.coerceIn(0, 100), notes)
    }

    /** One bounded line suitable for appending to the task summary. */
    fun toSummaryLine(review: Review): String {
        if (review.notes.isEmpty()) return "Self-review: clean run (score ${review.score})."
        return "Self-review (score ${review.score}): ${review.notes.joinToString("; ")}."
    }
}
