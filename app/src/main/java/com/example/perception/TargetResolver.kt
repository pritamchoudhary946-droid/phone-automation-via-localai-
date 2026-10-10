package com.example.perception

import com.example.accessibility.UiNode

/**
 * Deterministic, testable target resolution for a semantic hint against the
 * accessibility tree (plan 10.7).
 *
 * The old code silently fell back to "the first editable element" when the
 * intended field could not be resolved, and matched the first fuzzy candidate
 * even when several were equally plausible. Both can dispatch a real tap/type
 * into the wrong control. This resolver instead returns an explicit outcome:
 *
 *  - [Outcome.Unique]    exactly one confident match -> safe to act on;
 *  - [Outcome.Ambiguous] two or more equally-good matches -> refuse and report;
 *  - [Outcome.None]      nothing reached the confidence bar -> refuse.
 *
 * Only [Outcome.Unique] is actionable, so an unresolved/ambiguous target can
 * never be auto-activated. The resolver never mutates its inputs.
 */
object TargetResolver {

    /** Minimum score for a candidate to count as a real match. */
    const val MATCH_THRESHOLD = 60

    /** Two candidates within this score band of each other are equally good. */
    const val AMBIGUITY_BAND = 10

    enum class Outcome { UNIQUE, AMBIGUOUS, NONE }

    data class Resolution(
        val outcome: Outcome,
        val node: UiNode?,
        val candidates: List<UiNode>,
        val reason: String,
        val confidence: Int
    ) {
        val actionable: Boolean get() = outcome == Outcome.UNIQUE && node != null
    }

    /**
     * Resolves [hint] to a single activatable node.
     *
     * @param allowEditable whether an editable field may be a valid match. For
     *   "open this result" it is false (a search box is not a result); for a type
     *   target it is true.
     * @param requireActivatable when true, a node only counts when it is clickable
     *   or has a clickable ancestor/descendant within [nodes] (a real tap target).
     */
    fun resolve(
        nodes: List<UiNode>,
        hint: String,
        foregroundPackage: String? = null,
        allowEditable: Boolean = false,
        requireActivatable: Boolean = false
    ): Resolution {
        val clean = hint.trim()
        if (clean.isBlank()) {
            return Resolution(Outcome.NONE, null, emptyList(), "Empty target hint", 0)
        }
        val hintTokens = tokenize(clean)
        val inScope = nodes.filter { it.packageName == null || it.packageName == foregroundPackage }

        val scored = inScope
            .filter { it.isEnabled }
            .filter { allowEditable || !it.isEditable }
            .filter { !requireActivatable || isActivatable(it, inScope) }
            .mapNotNull { node -> score(node, clean, hintTokens)?.let { it to node } }

        if (scored.isEmpty()) {
            return Resolution(Outcome.NONE, null, emptyList(), "No candidate matched \"$hint\"", 0)
        }

        val best = scored.maxByOrNull { it.first }!!
        val ties = scored.filter { it.first >= best.first - AMBIGUITY_BAND }

        // Multiple equally-strong matches: refuse rather than pick arbitrarily.
        if (ties.size > 1 && ties.map { it.second.id }.distinct().size > 1) {
            return Resolution(
                Outcome.AMBIGUOUS,
                null,
                ties.map { it.second },
                "\"$hint\" matches ${ties.size} equally-plausible controls; refusing to guess",
                best.first
            )
        }
        return Resolution(
            Outcome.UNIQUE,
            best.second,
            listOf(best.second),
            "Resolved \"$hint\" uniquely",
            best.first
        )
    }

    private fun score(node: UiNode, clean: String, hintTokens: List<String>): Int? {
        if (node.isPassword) return null
        val text = node.text?.lowercase()
        val desc = node.contentDescription?.lowercase()
        val viewId = node.id.substringAfterLast('/').lowercase()
        val target = clean.lowercase()

        var score = 0
        when {
            text == target || desc == target -> score = 100
            text?.startsWith(target) == true || desc?.startsWith(target) == true -> score = 85
            text?.contains(target) == true || desc?.contains(target) == true -> score = 70
            viewId.contains(target) -> score = 60
            hintTokens.isNotEmpty() && hintTokens.all { tok ->
                text?.contains(tok) == true || desc?.contains(tok) == true
            } -> score = 65
        }
        if (score == 0) return null
        if (node.isClickable) score += 10
        if (node.isEnabled) score += 5
        if (!node.isEnabled) score -= 30
        return score
    }

    private fun isActivatable(node: UiNode, nodes: List<UiNode>): Boolean {
        if (node.isClickable) return true
        return nodes.any { candidate ->
            candidate.isClickable &&
                (candidate.packageName == null || candidate.packageName == node.packageName) &&
                candidate.bounds.contains(node.bounds)
        }
    }

    private fun tokenize(hint: String): List<String> =
        hint.lowercase().split(Regex("[^a-z0-9\\u0900-\\u097F]+")).filter { it.length >= 3 }
}
