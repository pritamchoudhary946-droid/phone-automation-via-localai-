package com.example.planning

import com.example.agent.AgentAction

/**
 * A small typed variable store for a single task (plan 9.4).
 *
 * Lets a value produced by one step be reused by a later step in another app -
 * e.g. read a price, then paste it into Notes. Pure and deterministic so it is
 * unit-testable; values are tenant-scoped to one run and never persisted.
 *
 * Steps may reference a variable with the placeholder syntax `{{name}}` in an
 * action's string fields; [substitute] resolves those placeholders against the
 * current store just before execution.
 *
 * Each value records the step [source] it came from and when it was captured, so
 * a later consumer can tell where a value originated and how fresh it is. Values
 * whose text looks like a secret (a payment-card number, an OTP/PIN/password
 * value) are refused rather than stored, so sensitive content never reaches a
 * later step or the planning prompt (plan 9.4 "no leak of sensitive content").
 */
class PlanVariableStore {

    /** One captured value with its provenance and capture time. */
    data class Variable(val value: String, val source: String, val capturedAtMs: Long)

    private val values = LinkedHashMap<String, Variable>()

    /**
     * Stores [value] under [name]. Returns false (and stores nothing) when the
     * name normalises to nothing or the value looks like sensitive content.
     * [nowMs] is injectable so freshness is testable without a wall clock.
     */
    fun set(
        name: String,
        value: String,
        source: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val key = normalize(name)
        if (key.isEmpty()) return false
        if (looksSensitive(value)) return false
        values[key] = Variable(value, source, nowMs)
        return true
    }

    fun get(name: String): String? = values[normalize(name)]?.value

    /** The full captured value (with provenance) for [name], or null. */
    fun variable(name: String): Variable? = values[normalize(name)]

    fun contains(name: String): Boolean = values.containsKey(normalize(name))

    fun remove(name: String): Boolean = values.remove(normalize(name)) != null

    fun asMap(): Map<String, String> = values.mapValues { it.value.value }

    fun clear() = values.clear()

    /** Replaces every `{{name}}` placeholder in [template] with its stored value. */
    fun substitute(template: String): String {
        if (values.isEmpty() || !template.contains(PLACEHOLDER_OPEN)) return template
        var result = template
        for ((name, variable) in values) {
            result = result.replace("$PLACEHOLDER_OPEN$name$PLACEHOLDER_CLOSE", variable.value)
        }
        return result
    }

    /** Returns a copy of [action] with all placeholders resolved. */
    fun substitute(action: AgentAction): AgentAction = when (action) {
        is AgentAction.TypeText -> action.copy(text = substitute(action.text))
        is AgentAction.ClearText -> action.copy(targetText = action.targetText?.let(::substitute))
        is AgentAction.Tap -> action.copy(targetText = action.targetText?.let(::substitute))
        is AgentAction.Submit -> action.copy(targetText = action.targetText?.let(::substitute))
        is AgentAction.OpenUrl -> action.copy(url = substitute(action.url))
        is AgentAction.DeepLink -> action.copy(uri = substitute(action.uri))
        is AgentAction.WebSearch -> action.copy(query = substitute(action.query))
        is AgentAction.ReadUrl -> action.copy(url = substitute(action.url))
        is AgentAction.OpenItem -> action.copy(targetText = substitute(action.targetText))
        is AgentAction.ScrollUntilFound -> action.copy(targetText = substitute(action.targetText))
        is AgentAction.FindElement -> action.copy(targetText = substitute(action.targetText))
        is AgentAction.WaitForText -> action.copy(targetText = substitute(action.targetText))
        is AgentAction.CopyText -> action.copy(targetText = action.targetText?.let(::substitute))
        is AgentAction.PasteText -> action.copy(targetText = action.targetText?.let(::substitute))
        is AgentAction.LongPress -> action.copy(targetText = action.targetText?.let(::substitute))
        is AgentAction.FinishTask -> action.copy(summary = substitute(action.summary))
        else -> action
    }

    companion object {
        const val PLACEHOLDER_OPEN = "{{"
        const val PLACEHOLDER_CLOSE = "}}"

        /** A stable placeholder token for a variable, e.g. `{{price}}`. */
        fun placeholder(name: String): String = "$PLACEHOLDER_OPEN${normalize(name)}$PLACEHOLDER_CLOSE"

        fun normalize(name: String): String =
            name.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')

        /** True when [text] contains at least one `{{...}}` placeholder. */
        fun hasPlaceholder(text: String): Boolean =
            text.contains(PLACEHOLDER_OPEN) && text.contains(PLACEHOLDER_CLOSE)

        /**
         * Heuristic guard so a captured value that looks like a secret (a 13-19
         * digit payment-card number, or a `label: value` password/passcode/OTP/PIN
         * line) is never stored, substituted, or summarised into a prompt.
         * Deliberately conservative: a bare short digit run is NOT refused, because
         * a captured price/quantity/year is a legitimate value; only an
         * unambiguous card shape or an explicit secret label trips the guard.
         */
        fun looksSensitive(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            // Card-like: 13-19 digits, optionally grouped by spaces/hyphens.
            val compact = trimmed.replace(Regex("[ -]"), "")
            if (compact.length in 13..19 && compact.all { it.isDigit() }) return true
            // Labelled secrets anywhere in the text.
            if (SENSITIVE_LABEL.containsMatchIn(trimmed)) return true
            return false
        }

        private val SENSITIVE_LABEL = Regex(
            "(?i)\\b(otp|pin|password|passcode|passwd|cvv|cvc|credit\\s*card|card\\s*number|secret)\\b\\s*[:=]?\\s*\\S+"
        )
    }
}
