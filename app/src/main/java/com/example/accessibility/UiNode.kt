package com.example.accessibility

import android.graphics.Rect

/**
 * Structured semantic model representing an interactive or information-bearing
 * element extracted from the Android Accessibility node tree.
 */
data class UiNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val packageName: String?,
    val bounds: Rect,
    val hierarchyPath: String = "",
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean = true,
    val isPassword: Boolean = false
) {
    companion object {
        /**
         * Substrings that, when they appear in a node's text/description/id, suggest a
         * text-input field. Includes Hindi/Hinglish hints because the target user
         * issues commands in both languages.
         */
        val INPUT_HINTS = listOf(
            "search", "query", "type", "message", "composer", "input",
            "enter ", "ask ", "reply", "comment", "write", "caption", "note",
            "kya", "likho", "likhein", "search karein"
        )
    }

    fun bestLabel(): String {
        return when {
            !text.isNullOrBlank() -> text
            !contentDescription.isNullOrBlank() -> contentDescription
            else -> className.substringAfterLast('.')
        }
    }

    fun containsText(query: String, ignoreCase: Boolean = true): Boolean {
        val cleanQuery = query.trim()
        val textMatch = text?.contains(cleanQuery, ignoreCase = ignoreCase) == true
        val descMatch = contentDescription?.contains(cleanQuery, ignoreCase = ignoreCase) == true
        return textMatch || descMatch
    }

    /**
     * Heuristic likelihood that this node is a text-input field, even when the
     * platform does not flag `isEditable` (common in WebViews and some custom
     * composers). Deterministic and side-effect free so it is unit-testable.
     *
     * Signals, by weight: editable (definitive), class name, input-ish hints in
     * text/description/id, and focus. Password fields are never auto-targeted.
     */
    fun inputLikelihood(): Int {
        if (isPassword) return -200
        var score = 0
        if (isEditable) score += 100
        if (classLooksEditable()) score += 50
        if (hasInputHint()) score += 30
        if (isFocused) score += 20
        return score
    }

    /** True when [inputLikelihood] reaches the threshold for a type target. */
    fun isLikelyTextInput(): Boolean {
        if (isPassword) return false
        if (isEditable) return true
        if (classLooksEditable()) return true
        // A hint-only node counts only when it is focused or not a plain clickable
        // button, so a "Search" action button is not mistaken for the search field.
        return hasInputHint() && (isFocused || !isClickable)
    }

    private fun classLooksEditable(): Boolean {
        val cls = className.lowercase()
        return cls.contains("edittext") ||
            cls.contains("autocompletetextview") ||
            cls.contains("searchview") ||
            cls.contains("textfield") ||
            cls.contains("inputfield")
    }

    private fun hasInputHint(): Boolean {
        val hay = listOfNotNull(text, contentDescription, id.substringAfterLast('/'))
            .joinToString(" ")
            .lowercase()
        if (hay.isBlank()) return false
        return INPUT_HINTS.any { hay.contains(it) }
    }

    fun toCompactString(index: Int): String {
        val label = when {
            !text.isNullOrBlank() -> "\"$text\""
            !contentDescription.isNullOrBlank() -> "\"$contentDescription\""
            else -> "<${className.substringAfterLast('.')}>"
        }
        val type = when {
            isEditable -> "[Input]"
            isClickable -> "[Button/Clickable]"
            isScrollable -> "[Scrollable]"
            isCheckable -> if (isChecked) "[Checked]" else "[Unchecked]"
            else -> "[Text]"
        }
        val boundsStr = "(${bounds.centerX()}, ${bounds.centerY()})"
        val idStr = if (id.isNotBlank() && !id.startsWith("node_")) " id=\"${id.substringAfterLast('/')}\"" else ""
        // Tell the model when an interactive control is disabled so it does not waste a
        // step trying to activate a greyed-out element.
        val disabledStr = if (!isEnabled && (isClickable || isEditable || isCheckable)) " [disabled]" else ""
        return "[$index] $type $label$idStr at $boundsStr$disabledStr"
    }
}

/**
 * Immutable snapshot of the entire visible screen hierarchy at a single point in time.
 */
data class UiTreeSnapshot(
    val packageName: String?,
    val nodes: List<UiNode>,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toPromptSummary(maxNodes: Int = 30): String {
        if (nodes.isEmpty()) {
            return "Screen is currently blank or protected (Package: ${packageName ?: "unknown"})."
        }
        val sb = StringBuilder()
        sb.append("Active Package: ${packageName ?: "unknown"}\n")
        sb.append("Visible Elements (${nodes.size} total):\n")
        nodes.take(maxNodes).forEachIndexed { i, node ->
            sb.append(node.toCompactString(i)).append("\n")
        }
        if (nodes.size > maxNodes) {
            sb.append("... (${nodes.size - maxNodes} more elements omitted for brevity)\n")
        }
        return sb.toString().trim()
    }

    /**
     * Converts the snapshot into a token-efficient JSON representation for the model.
     */
    fun toJson(maxElements: Int = 30, indentSpaces: Int = 0): String {
        return AccessibilityTreeJsonParser(maxElements = maxElements).parseSnapshotToJson(this, indentSpaces)
    }

    fun findNodeByText(text: String, fuzzy: Boolean = true): UiNode? {
        val clean = text.trim()
        // 1. Exact match
        nodes.find { it.text?.equals(clean, ignoreCase = true) == true || it.contentDescription?.equals(clean, ignoreCase = true) == true }?.let { return it }

        // 2. Fuzzy match
        if (fuzzy) {
            nodes.find { it.containsText(clean, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    fun findNodeById(id: String): UiNode? {
        return nodes.find { it.id == id || it.id.substringAfterLast('/') == id }
    }

    fun findNodeAt(x: Int, y: Int): UiNode? {
        return nodes.find { it.bounds.contains(x, y) }
    }

    fun findFirstEditable(): UiNode? {
        return nodes.find { it.isEditable }
    }

    /**
     * Best text-entry target by [UiNode.inputLikelihood], even when the platform
     * does not flag `isEditable`. Returns null when nothing reaches the threshold.
     */
    fun findFirstTextInput(): UiNode? {
        return nodes.filter { it.isLikelyTextInput() }
            .maxByOrNull { it.inputLikelihood() }
    }

    fun findScrollable(): UiNode? {
        return nodes.find { it.isScrollable }
    }

    /**
     * Fingerprint for stuck detection.
     *
     * Hashes the whole visible tree (bounded by the dump's node cap) rather than the
     * first few nodes, and includes every signal that actually changes between two
     * distinct screens: text, description, class, clickable/scrollable/checked state
     * and bounds. Using only the first ten nodes made two different screens that
     * shared a static header (e.g. a scrolled list) hash identically, which produced
     * false "stuck" verdicts and spurious user-help escalations.
     */
    fun getFingerprint(): String {
        return buildString {
            append(packageName ?: "unknown")
            for (node in nodes) {
                append('|')
                append(node.text ?: "")
                append('\u0001')
                append(node.contentDescription ?: "")
                append('\u0001')
                append(node.className)
                append('\u0001')
                append(node.bounds.left).append(',')
                append(node.bounds.top).append(',')
                append(node.bounds.right).append(',')
                append(node.bounds.bottom)
                append('\u0001')
                append(if (node.isClickable) 'c' else '-')
                append(if (node.isScrollable) 's' else '-')
                append(if (node.isChecked) 'x' else '-')
                append(if (node.isEnabled) 'e' else '-')
            }
        }
    }
}
