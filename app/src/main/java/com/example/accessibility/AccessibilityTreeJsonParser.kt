package com.example.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-performance parser that converts an Android [AccessibilityNodeInfo] tree
 * into a simplified, token-efficient JSON representation optimized for the Gemma model.
 *
 * Focuses strictly on extracting actionable elements:
 * - Clickable elements (buttons, links, clickable items)
 * - Input-ready elements (EditTexts, search fields, text boxes)
 * - Scrollable elements (lists, scroll views, pagers)
 * - Checkable elements (switches, checkboxes, radio buttons)
 * - Meaningful text/headers providing necessary semantic context
 */
class AccessibilityTreeJsonParser(
    private val maxElements: Int = 40,
    private val includeInformationalText: Boolean = true
) {

    data class ParsedElement(
        val index: Int,
        val id: String?,
        val type: String,
        val label: String,
        val centerX: Int,
        val centerY: Int,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isFocused: Boolean
    ) {
        fun toJsonObject(): JSONObject {
            val json = JSONObject()
            json.put("idx", index)
            if (!id.isNullOrBlank()) {
                json.put("id", id)
            }
            json.put("type", type)
            json.put("label", label)
            json.put("center", JSONArray(listOf(centerX, centerY)))
            json.put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))

            val flags = JSONObject()
            if (isClickable) flags.put("clickable", true)
            if (isEditable) flags.put("editable", true)
            if (isScrollable) flags.put("scrollable", true)
            if (isCheckable) flags.put("checked", isChecked)
            if (isFocused) flags.put("focused", true)

            if (flags.length() > 0) {
                json.put("flags", flags)
            }
            return json
        }
    }

    /**
     * Parses the active [AccessibilityNodeInfo] root into a simplified JSON string.
     * Safely traverses child nodes and recycles them to prevent memory leaks.
     */
    fun parseToJson(rootNode: AccessibilityNodeInfo?, indentSpaces: Int = 0): String {
        if (rootNode == null) {
            return JSONObject().apply {
                put("package", "unknown")
                put("element_count", 0)
                put("elements", JSONArray())
            }.toString()
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val elements = mutableListOf<ParsedElement>()

        try {
            traverseAndExtract(rootNode, elements, depth = 0, path = "0")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val rootJson = JSONObject().apply {
            put("package", packageName)
            put("element_count", elements.size)

            val array = JSONArray()
            for (element in elements.take(maxElements)) {
                array.put(element.toJsonObject())
            }
            put("elements", array)
        }

        return if (indentSpaces > 0) rootJson.toString(indentSpaces) else rootJson.toString()
    }

    /**
     * Parses an existing [UiTreeSnapshot] into simplified JSON representation.
     */
    fun parseSnapshotToJson(snapshot: UiTreeSnapshot, indentSpaces: Int = 0): String {
        val rootJson = JSONObject().apply {
            put("package", snapshot.packageName ?: "unknown")
            put("timestamp", snapshot.timestamp)

            val array = JSONArray()
            val filteredNodes = snapshot.nodes.filter { isActionableNode(it) }

            filteredNodes.take(maxElements).forEachIndexed { index, node ->
                val type = resolveElementType(node.className, node.isEditable, node.isClickable, node.isScrollable, node.isCheckable)
                val label = node.bestLabel()

                val element = ParsedElement(
                    index = index,
                    id = node.id.takeIf { !it.startsWith("node_") }?.substringAfterLast('/'),
                    type = type,
                    label = label,
                    centerX = node.bounds.centerX(),
                    centerY = node.bounds.centerY(),
                    bounds = node.bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isFocused = node.isFocused
                )
                array.put(element.toJsonObject())
            }

            put("element_count", array.length())
            put("elements", array)
        }

        return if (indentSpaces > 0) rootJson.toString(indentSpaces) else rootJson.toString()
    }

    private fun traverseAndExtract(
        node: AccessibilityNodeInfo,
        list: MutableList<ParsedElement>,
        depth: Int,
        path: String
    ) {
        if (depth > 22 || list.size >= maxElements) return

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        val isEditable = node.isEditable
        val isCheckable = node.isCheckable
        val className = node.className?.toString() ?: ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hasText = !text.isNullOrBlank() || !contentDesc.isNullOrBlank()
        val isActionable = isClickable || isScrollable || isEditable || isCheckable
        val isVisible = bounds.width() > 0 && bounds.height() > 0

        if (isVisible && (isActionable || (includeInformationalText && hasText))) {
            val label = when {
                !text.isNullOrBlank() -> text
                !contentDesc.isNullOrBlank() -> contentDesc
                else -> className.substringAfterLast('.')
            }

            val type = resolveElementType(className, isEditable, isClickable, isScrollable, isCheckable)
            val resourceId = node.viewIdResourceName?.substringAfterLast('/')

            val element = ParsedElement(
                index = list.size,
                id = resourceId,
                type = type,
                label = label,
                centerX = bounds.centerX(),
                centerY = bounds.centerY(),
                bounds = bounds,
                isClickable = isClickable,
                isEditable = isEditable,
                isScrollable = isScrollable,
                isCheckable = isCheckable,
                isChecked = node.isChecked,
                isFocused = node.isFocused
            )
            list.add(element)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndExtract(child, list, depth + 1, "$path/$i")
            child.recycle()
        }
    }

    private fun isActionableNode(node: UiNode): Boolean {
        val isActionable = node.isClickable || node.isScrollable || node.isEditable || node.isCheckable
        val hasMeaningfulText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        return isActionable || (includeInformationalText && hasMeaningfulText)
    }

    private fun resolveElementType(
        className: String,
        isEditable: Boolean,
        isClickable: Boolean,
        isScrollable: Boolean,
        isCheckable: Boolean
    ): String {
        val lowerClass = className.lowercase()
        return when {
            isEditable || lowerClass.contains("edittext") -> "input"
            isCheckable || lowerClass.contains("checkbox") || lowerClass.contains("switch") -> "toggle"
            lowerClass.contains("button") || isClickable -> "button"
            isScrollable || lowerClass.contains("recyclerview") || lowerClass.contains("scrollview") -> "scrollable"
            lowerClass.contains("image") -> "image"
            else -> "text"
        }
    }
}
