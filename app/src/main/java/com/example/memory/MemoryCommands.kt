package com.example.memory

/**
 * Parses explicit personal-memory commands (plan 9.5): "remember that ...",
 * "forget ...", "what do you know about ...", plus Roman-Hindi equivalents.
 * Pure and deterministic so the phrasing can be unit-tested without the model.
 */
object MemoryCommands {

    sealed class Command {
        /** Store [value] under the normalized [key]. */
        data class Remember(val key: String, val value: String) : Command()
        /** Remove the fact whose key (or free text) matches [query]. */
        data class Forget(val query: String) : Command()
        /** Answer from the store what is known about [query]. */
        data class Recall(val query: String) : Command()
        /** Not a memory command; the goal runs normally. */
        data class NotAMemoryCommand(val goal: String) : Command()
    }

    private val REMEMBER_PREFIXES = listOf(
        "remember that ", "remember ", "note that ", "keep in mind that ",
        "yaad rakho ki ", "yaad rakho ", "yaad rakhna "
    )
    private val FORGET_PREFIXES = listOf(
        "forget that ", "forget ", "delete memory ", "erase memory ",
        "bhool jao ki ", "bhool jao ", "bhool ja "
    )
    private val RECALL_PREFIXES = listOf(
        "what do you know about ", "what do you know about", "what do you remember about ",
        "what do you know ", "tell me about ", "kya jaante ho ", "kya yaad hai "
    )

    fun parse(goal: String): Command {
        val trimmed = goal.trim()
        val lower = trimmed.lowercase()

        for (prefix in REMEMBER_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val fact = trimmed.substring(prefix.length).trim()
                return if (fact.isEmpty()) {
                    Command.NotAMemoryCommand(trimmed)
                } else {
                    Command.Remember(keyOf(fact), fact)
                }
            }
        }
        for (prefix in FORGET_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val target = trimmed.substring(prefix.length).trim()
                if (target.isNotEmpty()) return Command.Forget(target)
            }
        }
        for (prefix in RECALL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val target = trimmed.substring(prefix.length).trim()
                if (target.isNotEmpty()) return Command.Recall(target)
            }
        }
        return Command.NotAMemoryCommand(trimmed)
    }

    /**
     * A stable, human-meaningful key for a remembered fact: the leading subject of
     * the fact (e.g. "my meeting is at 5" -> "my_meeting"). Collisions simply update
     * the same fact, which is the desired "re-learning" behaviour.
     */
    fun keyOf(fact: String): String {
        val cleaned = fact.lowercase()
            .replace(Regex("^(that |ki )"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val key = words.take(4).joinToString("_")
        return key.ifBlank { "fact" }
    }
}
