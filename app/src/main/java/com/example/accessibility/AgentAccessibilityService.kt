package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ScreenEventState(
    val activePackageName: String? = null,
    val eventType: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11yService"
        var instance: AgentAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean {
            return instance != null
        }

        fun isAccessibilitySettingsEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expectedServiceName = "${context.packageName}/${AgentAccessibilityService::class.java.canonicalName}"
            return enabledServices.contains(expectedServiceName) ||
                    enabledServices.contains(AgentAccessibilityService::class.java.simpleName)
        }
    }

    private val _screenEvents = MutableStateFlow(ScreenEventState())
    val screenEvents: StateFlow<ScreenEventState> = _screenEvents.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentAccessibilityService connected and ready for interaction layer.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        _screenEvents.value = ScreenEventState(
            activePackageName = pkg ?: _screenEvents.value.activePackageName,
            eventType = event.eventType,
            timestamp = System.currentTimeMillis()
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted by system.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "AgentAccessibilityService destroyed.")
    }

    // ==========================================
    // 1. Screen UI Tree Reading & Semantic Dump
    // ==========================================

    /**
     * Dumps the active window accessibility tree into structured, filtered UiNodes.
     * Prunes zero-area and invisible decorative nodes to conserve context.
     */
    fun dumpHierarchy(maxDepth: Int = 20, maxNodes: Int = 80): UiTreeSnapshot {
        val root = rootInActiveWindow ?: return UiTreeSnapshot(null, emptyList())
        val packageName = root.packageName?.toString()
        val collectedNodes = mutableListOf<UiNode>()

        try {
            traverseNode(
                node = root,
                list = collectedNodes,
                depth = 0,
                maxDepth = maxDepth,
                maxNodes = maxNodes,
                pathPrefix = "0"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping UI hierarchy", e)
        } finally {
            root.recycle()
        }

        return UiTreeSnapshot(packageName, collectedNodes)
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        list: MutableList<UiNode>,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        pathPrefix: String
    ) {
        if (depth > maxDepth || list.size >= maxNodes) return

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        val isEditable = node.isEditable
        val isCheckable = node.isCheckable
        val isEnabled = node.isEnabled
        val isPassword = node.isPassword

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hasMeaningfulContent = !text.isNullOrBlank() || !contentDesc.isNullOrBlank()
        val isInteractive = isClickable || isScrollable || isEditable || isCheckable

        if ((hasMeaningfulContent || isInteractive) && bounds.width() > 0 && bounds.height() > 0) {
            val uiNode = UiNode(
                id = node.viewIdResourceName ?: "node_${list.size}",
                text = text,
                contentDescription = contentDesc,
                className = node.className?.toString() ?: "",
                packageName = node.packageName?.toString(),
                bounds = bounds,
                hierarchyPath = pathPrefix,
                isClickable = isClickable,
                isScrollable = isScrollable,
                isEditable = isEditable,
                isCheckable = isCheckable,
                isChecked = node.isChecked,
                isFocused = node.isFocused,
                isEnabled = isEnabled,
                isPassword = isPassword
            )
            list.add(uiNode)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(
                node = child,
                list = list,
                depth = depth + 1,
                maxDepth = maxDepth,
                maxNodes = maxNodes,
                pathPrefix = "$pathPrefix/$i"
            )
            child.recycle()
        }
    }

    // ==========================================
    // 2. Click Handling (Coordinates & Semantic)
    // ==========================================

    /**
     * Dispatches a tap gesture at exact screen coordinates.
     */
    suspend fun clickCoordinates(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Dispatches a long-press gesture at coordinates.
     */
    suspend fun longClickCoordinates(x: Float, y: Float, durationMs: Long = 800): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Finds a node by text and clicks it.
     * Tries node action click -> parent action click -> coordinate tap fallback.
     */
    suspend fun clickByText(targetText: String, fuzzy: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val matches = root.findAccessibilityNodeInfosByText(targetText)
            for (node in matches) {
                if (node.isClickable) {
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (success) return true
                } else {
                    val clickableParent = findFirstClickableParent(node)
                    if (clickableParent != null) {
                        val success = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickableParent.recycle()
                        node.recycle()
                        if (success) return true
                    }
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    node.recycle()
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    }
                }
            }
        } finally {
            root.recycle()
        }
        return false
    }

    /**
     * Finds a node by resource view ID and clicks it.
     */
    suspend fun clickById(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val matches = root.findAccessibilityNodeInfosByViewId(resourceId)
            for (node in matches) {
                if (node.isClickable) {
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (success) return true
                } else {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    node.recycle()
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    }
                }
            }
        } finally {
            root.recycle()
        }
        return false
    }

    private fun findFirstClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            val next = current.parent
            current.recycle()
            current = next
        }
        return null
    }

    // ==========================================
    // 3. Text Input Handling
    // ==========================================

    /**
     * Sets text into the currently focused or first editable element.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focusedNode.recycle()
                return success
            }

            val editableNode = findFirstEditable(root)
            if (editableNode != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                editableNode.recycle()
                return success
            }
        } finally {
            root.recycle()
        }
        return false
    }

    /**
     * Types text into a specific target node by resource ID or text.
     */
    suspend fun typeTextIntoNode(targetIdentifier: String, text: String, clearFirst: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            var targetNode: AccessibilityNodeInfo? = null

            // Try by ID first
            val byId = root.findAccessibilityNodeInfosByViewId(targetIdentifier)
            if (byId.isNotEmpty()) {
                targetNode = byId.first()
            } else {
                // Try by text
                val byText = root.findAccessibilityNodeInfosByText(targetIdentifier)
                if (byText.isNotEmpty()) {
                    targetNode = byText.first()
                }
            }

            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val bounds = Rect()
                targetNode.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }

                if (clearFirst) {
                    val emptyArgs = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyArgs)
                }

                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                targetNode.recycle()
                return success
            }
        } finally {
            root.recycle()
        }
        return typeText(text)
    }

    /**
     * Clears text in active input field.
     */
    fun clearText(): Boolean {
        return typeText("")
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        return null
    }

    // ==========================================
    // 4. Scroll & Gesture Handling
    // ==========================================

    /**
     * Dispatches a directional scroll gesture across the active screen.
     */
    suspend fun scroll(direction: String): Boolean = suspendCancellableCoroutine { cont ->
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val path = Path()
        when (direction.lowercase()) {
            "down" -> {
                path.moveTo(width / 2, height * 0.75f)
                path.lineTo(width / 2, height * 0.25f)
            }
            "up" -> {
                path.moveTo(width / 2, height * 0.25f)
                path.lineTo(width / 2, height * 0.75f)
            }
            "left" -> {
                path.moveTo(width * 0.8f, height / 2)
                path.lineTo(width * 0.2f, height / 2)
            }
            "right" -> {
                path.moveTo(width * 0.2f, height / 2)
                path.lineTo(width * 0.8f, height / 2)
            }
            else -> {
                path.moveTo(width / 2, height * 0.75f)
                path.lineTo(width / 2, height * 0.25f)
            }
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * Arbitrary swipe gesture from (startX, startY) to (endX, endY).
     */
    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 250
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)

        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    // ==========================================
    // 5. Global Navigation Actions
    // ==========================================

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    // ==========================================
    // 6. Screenshot Fallback (Android 11+ / API 30+)
    // ==========================================

    suspend fun takeScreenshotFallback(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(copy)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } else {
            if (cont.isActive) cont.resume(null)
        }
    }
}
