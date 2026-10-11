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
 */
class PlanVariableStore {

    private val values = LinkedHashMap<String, String>()

    fun set(name: String, value: String) {
        val key = normalize(name)
        if (key.isEmpty()) return
        values[key] = value
    }

    fun get(name: String): String? = values[normalize(name)]

    fun contains(name: String): Boolean = values.containsKey(normalize(name))

    fun remove(name: String): Boolean = values.remove(normalize(name)) != null

    fun asMap(): Map<String, String> = values.toMap()

    fun clear() = values.clear()

    /** Replaces every `{{name}}` placeholder in [template] with its stored value. */
    fun substitute(template: String): String {
        if (values.isEmpty() || !template.contains(PLACEHOLDER_OPEN)) return template
        var result = template
        for ((name, value) in values) {
            result = result.replace("$PLACEHOLDER_OPEN$name$PLACEHOLDER_CLOSE", value)
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
    }
}
