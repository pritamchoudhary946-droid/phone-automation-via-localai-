package com.example.perception

import com.example.accessibility.UiNode

/**
 * Ranks on-screen elements for a target hint (plan 9.11), so the model sees the
 * most likely targets first instead of a flat, position-ordered dump. This is a
 * pure function of the accessibility tree: no model, no I/O, fully testable.
 *
 * The hint is usually the step's sub-goal or the model's own query text. When the
 * hint is blank, ranking falls back to "how actionable / prominent" an element is,
 * which still surfaces real controls ahead of decorative text.
 */
object ElementRanker {

    data class Ranked(val node: UiNode, val score: Int, val reason: String)

    /**
     * Scores and orders [nodes] against [hint], strongest first. Deterministic:
     * ties keep the original tree order, so the same screen always ranks the same.
     */
    fun rank(
        nodes: List<UiNode>,
        hint: String,
        foregroundPackage: String? = null,
        limit: Int = 25
    ): List<Ranked> {
        val hintTokens = tokenize(hint)
        return nodes
            .filter { it.packageName == null || it.packageName == foregroundPackage }
            .map { node -> score(node, hintTokens) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * A compact, ranked prompt summary. Each line is the node's existing compact
     * form prefixed with its rank, so existing parsers keep working. Falls back to
     * nothing when there are no nodes, letting the caller keep its old summary.
     */
    fun rankedSummary(
        nodes: List<UiNode>,
        hint: String,
        foregroundPackage: String? = null,
        limit: Int = 25
    ): String {
        val ranked = rank(nodes, hint, foregroundPackage, limit)
        if (ranked.isEmpty()) return ""
        return ranked.mapIndexed { index, r ->
            val why = if (r.reason.isNotBlank()) " (${r.reason})" else ""
            r.node.toCompactString(index) + why
        }.joinToString("\n")
    }

    private fun score(node: UiNode, hintTokens: List<String>): Ranked {
        var score = 0
        val reasons = mutableListOf<String>()

        // Exact/partial text match against the hint is the strongest signal.
        val label = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
        if (hintTokens.isNotEmpty() && label.isNotBlank()) {
            val matched = hintTokens.count { label.contains(it) }
            if (matched > 0) {
                score += 60 + matched * 15
                reasons += "matches hint"
            }
        }

        if (node.isEditable || node.isLikelyTextInput()) {
            score += 25
            reasons += "text input"
        }
        if (node.isClickable) {
            score += 20
            reasons += "tappable"
        }
        if (node.isCheckable) {
            score += 10
            reasons += "toggle"
        }
        if (node.isScrollable) {
            score += 5
        }
        // Prefer elements nearer the top of the screen; the target is usually above
        // a long list's noise.
        if (node.bounds.top in 0..400) score += 5
        // Discourage elements we can never safely target.
        if (node.isPassword) score -= 100
        if (!node.isEnabled) score -= 30
        if (label.isBlank() && node.contentDescription.isNullOrBlank()) {
            reasons += "unlabeled"
        }

        return Ranked(node, score, reasons.joinToString(", "))
    }

    private fun tokenize(hint: String): List<String> =
        hint.lowercase().split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 3 }
}
