package com.example.agent

/**
 * Bounded multi-turn conversational memory (plan 9.3).
 *
 * Keeps just enough of the previous exchange (the last goal and the last app it
 * touched) that a natural follow-up like "and send it" or "do the same for Ravi"
 * can be resolved into a self-contained goal before planning. Pure and
 * deterministic so it is fully unit-testable; no model call.
 */
class ConversationMemory {

    data class Turn(val goal: String, val lastApp: String?)

    /** What a new command resolves to against the retained context. */
    sealed class Resolution {
        /** A self-contained goal the planner can run as-is. */
        data class Resolved(val goal: String) : Resolution()

        /** A follow-up with no retained context: ask instead of guessing. */
        data class NeedsContext(val reason: String) : Resolution()

        /** Not a follow-up; run the command unchanged. */
        data class Standalone(val goal: String) : Resolution()
    }

    var lastTurn: Turn? = null
        private set

    fun remember(goal: String, lastApp: String?) {
        lastTurn = Turn(goal, lastApp)
    }

    fun clear() {
        lastTurn = null
    }

    /**
     * Resolves [command] against the retained turn. A leading continuation word
     * ("and ...", "then ...", "also ...") or an explicit "same/again/that" cue is
     * treated as a follow-up and expanded with the previous goal. Anything else is
     * standalone. A follow-up with no retained turn returns [Resolution.NeedsContext]
     * so the app asks a clarifying question rather than inventing a target.
     */
    fun resolve(command: String): Resolution {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return Resolution.NeedsContext("I didn't catch that - what should I do?")
        if (!isFollowUp(trimmed)) return Resolution.Standalone(trimmed)
        val previous = lastTurn
            ?: return Resolution.NeedsContext(
                "I'm not sure what to continue - what should I do first?"
            )
        val expanded = buildString {
            append(previous.goal.trim().trimEnd('.'))
            append("; then ")
            append(stripContinuation(trimmed))
        }
        return Resolution.Resolved(expanded)
    }

    companion object {
        private val FOLLOW_UP_PREFIXES = listOf(
            "and ", "then ", "also ", "now ", "and then ", "aur ", "phir "
        )
        private val FOLLOW_UP_CUES = listOf(
            "same for", "do the same", "same thing", "again", "that one", "it too", "as well"
        )

        fun isFollowUp(command: String): Boolean {
            val c = command.trim().lowercase()
            if (FOLLOW_UP_PREFIXES.any { c.startsWith(it) }) return true
            return FOLLOW_UP_CUES.any { c.contains(it) }
        }

        /** Removes a leading continuation word so the resolved goal reads naturally. */
        fun stripContinuation(command: String): String {
            var c = command.trim()
            for (prefix in FOLLOW_UP_PREFIXES) {
                if (c.lowercase().startsWith(prefix)) {
                    c = c.substring(prefix.length).trim()
                    break
                }
            }
            return c
        }
    }
}
