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
     * Converts the snapshot into a token-efficient JSON representation for the Gemma model.
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

    fun findScrollable(): UiNode? {
        return nodes.find { it.isScrollable }
    }

    /**
     * Fingerprint hash for stuck detection
     */
    fun getFingerprint(): String {
        return "${packageName}_" + nodes.take(10).joinToString(";") {
            "${it.text}_${it.contentDescription}_${it.bounds.left}_${it.bounds.top}"
        }
    }
}
