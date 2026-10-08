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
import kotlinx.coroutines.delay
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

data class TapResult(
    val success: Boolean,
    val method: String, // "ACTION_CLICK_NODE", "ACTION_CLICK_PARENT", "DISPATCH_GESTURE", "FAILED"
    val failureReason: String? = null,
    val bounds: Rect? = null,
    val nodeFound: Boolean = false
)

data class TextInputResult(
    val success: Boolean,
    val method: String, // "ACTION_SET_TEXT", "FOCUS_THEN_SET_TEXT", "TAP_AND_SET_TEXT", "FAILED"
    val failureReason: String? = null,
    val targetNodeFound: Boolean = false
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
    // 2. Real Tap Execution (Robust Multi-Stage)
    // ==========================================

    /**
     * Executes a real tap conforming to the strict action executor specification:
     * 1. Find the target AccessibilityNodeInfo (inspecting text, contentDescription, and viewId).
     * 2. If clickable, use node.performAction(ACTION_CLICK) first.
     * 3. Verify the UI changed via [verifyUiChanged].
     * 4. Only if ACTION_CLICK is unavailable/fails, fallback to dispatchGesture() using the node bounds.
     * 5. Log the exact failure reason from the AccessibilityService callback.
     */
    suspend fun performTap(
        targetText: String?,
        x: Int? = null,
        y: Int? = null,
        verifyUiChanged: suspend () -> Boolean
    ): TapResult {
        Log.i(TAG, "[TAP_START] Executing tap for target='$targetText' at ($x, $y)")

        val root = rootInActiveWindow
        if (root == null) {
            val reason = "Accessibility root window is null (app transitioning or display secured)."
            Log.e(TAG, "[TAP_ERROR] $reason")
            return TapResult(success = false, method = "FAILED", failureReason = reason)
        }

        val targetSearch = findTargetNodeInfo(root, targetText, x, y)
        val targetNode = targetSearch.first
        val targetBounds = targetSearch.second

        if (targetNode == null && targetBounds == null) {
            root.recycle()
            val reason = "Target element '${targetText ?: "($x, $y)"}' not found in active window."
            Log.e(TAG, "[TAP_ERROR] $reason")
            return TapResult(success = false, method = "FAILED", failureReason = reason, nodeFound = false)
        }

        try {
            // Stage 1 & 2: If node is clickable, attempt ACTION_CLICK directly
            if (targetNode != null && targetNode.isClickable) {
                Log.i(TAG, "[TAP_STAGE_1] Target node '${targetText ?: targetNode.viewIdResourceName}' is clickable. Attempting ACTION_CLICK.")
                val clickDispatched = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickDispatched) {
                    delay(300)
                    // Stage 3: Verify the UI changed
                    val uiChanged = verifyUiChanged()
                    if (uiChanged) {
                        Log.i(TAG, "[TAP_SUCCESS] ACTION_CLICK succeeded and UI change was verified.")
                        return TapResult(success = true, method = "ACTION_CLICK_NODE", bounds = targetBounds, nodeFound = true)
                    } else {
                        Log.w(TAG, "[TAP_RETRY] ACTION_CLICK returned true but UI did not change after 300ms. Checking parent/dispatchGesture fallback.")
                    }
                } else {
                    Log.w(TAG, "[TAP_RETRY] targetNode.performAction(ACTION_CLICK) returned false.")
                }
            }

            // Stage 2b: Try clickable parent if available
            if (targetNode != null) {
                val clickableParent = findFirstClickableParent(targetNode)
                if (clickableParent != null) {
                    Log.i(TAG, "[TAP_STAGE_2] Attempting ACTION_CLICK on clickable parent of '${targetText ?: targetNode.viewIdResourceName}'.")
                    val parentClicked = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (parentClicked) {
                        delay(300)
                        val uiChanged = verifyUiChanged()
                        clickableParent.recycle()
                        if (uiChanged) {
                            Log.i(TAG, "[TAP_SUCCESS] Parent ACTION_CLICK succeeded and UI change verified.")
                            return TapResult(success = true, method = "ACTION_CLICK_PARENT", bounds = targetBounds, nodeFound = true)
                        } else {
                            Log.w(TAG, "[TAP_RETRY] Parent ACTION_CLICK returned true but UI did not change.")
                        }
                    } else {
                        clickableParent.recycle()
                    }
                }
            }

            // Stage 4: Only if ACTION_CLICK is unavailable/fails, fallback to dispatchGesture() using node bounds
            val clickX = (x ?: targetBounds?.centerX())?.toFloat()
            val clickY = (y ?: targetBounds?.centerY())?.toFloat()

            if (clickX == null || clickY == null) {
                val reason = "Failed to determine tap coordinates for '${targetText ?: "unknown"}'. Node bounds invalid."
                Log.e(TAG, "[TAP_ERROR] $reason")
                return TapResult(success = false, method = "FAILED", failureReason = reason, nodeFound = targetNode != null)
            }

            Log.i(TAG, "[TAP_STAGE_4] ACTION_CLICK unavailable or ineffective. Falling back to dispatchGesture at ($clickX, $clickY) using node bounds.")
            val (gestureCompleted, failureReason) = dispatchGestureWithResult(clickX, clickY)

            if (!gestureCompleted) {
                // Stage 5: Log the exact failure reason from the AccessibilityService callback
                val errorMsg = failureReason ?: "dispatchGesture callback returned cancelled or rejected by system."
                Log.e(TAG, "[TAP_CALLBACK_FAILURE] $errorMsg")
                return TapResult(success = false, method = "DISPATCH_GESTURE", failureReason = errorMsg, bounds = targetBounds, nodeFound = true)
            }

            delay(350)
            val uiChangedAfterGesture = verifyUiChanged()
            if (uiChangedAfterGesture) {
                Log.i(TAG, "[TAP_SUCCESS] dispatchGesture completed at ($clickX, $clickY) and UI change verified.")
                return TapResult(success = true, method = "DISPATCH_GESTURE", bounds = targetBounds, nodeFound = true)
            } else {
                val warningMsg = "dispatchGesture executed at ($clickX, $clickY) but screen content remained identical."
                Log.w(TAG, "[TAP_WARNING] $warningMsg")
                return TapResult(success = true, method = "DISPATCH_GESTURE", failureReason = warningMsg, bounds = targetBounds, nodeFound = true)
            }

        } finally {
            targetNode?.recycle()
            root.recycle()
        }
    }

    /**
     * Finds the target AccessibilityNodeInfo by inspecting text, contentDescription, and viewId,
     * or by matching point coordinates.
     */
    private fun findTargetNodeInfo(
        root: AccessibilityNodeInfo,
        targetText: String?,
        x: Int?,
        y: Int?
    ): Pair<AccessibilityNodeInfo?, Rect?> {
        val cleanTarget = targetText?.trim()?.lowercase()

        // 1. If target text is provided, perform deep semantic search
        if (!cleanTarget.isNullOrBlank()) {
            var bestNode: AccessibilityNodeInfo? = null
            var bestBounds: Rect? = null
            var highestScore = -1

            fun evaluateCandidate(node: AccessibilityNodeInfo) {
                val text = node.text?.toString()?.lowercase()
                val desc = node.contentDescription?.toString()?.lowercase()
                val viewId = node.viewIdResourceName?.lowercase()

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) return

                var score = 0
                when {
                    text == cleanTarget || desc == cleanTarget -> score = 100
                    text?.startsWith(cleanTarget) == true || desc?.startsWith(cleanTarget) == true -> score = 80
                    text?.contains(cleanTarget) == true || desc?.contains(cleanTarget) == true -> score = 60
                    viewId?.contains(cleanTarget) == true -> score = 50
                }

                if (score > 0) {
                    if (node.isClickable) score += 10
                    if (node.isEnabled) score += 5

                    if (score > highestScore) {
                        highestScore = score
                        bestNode?.recycle()
                        bestNode = AccessibilityNodeInfo.obtain(node)
                        bestBounds = bounds
                    }
                }
            }

            fun searchTree(node: AccessibilityNodeInfo) {
                evaluateCandidate(node)
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    searchTree(child)
                    child.recycle()
                }
            }

            searchTree(root)
            if (bestNode != null) {
                return Pair(bestNode, bestBounds)
            }
        }

        // 2. Coordinate fallback search
        if (x != null && y != null) {
            var bestNode: AccessibilityNodeInfo? = null
            var bestBounds: Rect? = null
            var smallestArea = Int.MAX_VALUE

            fun searchByCoords(node: AccessibilityNodeInfo) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.contains(x, y)) {
                    val area = bounds.width() * bounds.height()
                    if (area in 1 until smallestArea) {
                        smallestArea = area
                        bestNode?.recycle()
                        bestNode = AccessibilityNodeInfo.obtain(node)
                        bestBounds = bounds
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    searchByCoords(child)
                    child.recycle()
                }
            }

            searchByCoords(root)
            if (bestNode != null) {
                return Pair(bestNode, bestBounds)
            }
        }

        return Pair(null, null)
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

    /**
     * Dispatches a gesture and captures the exact failure reason from callback.
     */
    suspend fun dispatchGestureWithResult(x: Float, y: Float): Pair<Boolean, String?> = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "[GESTURE_CALLBACK] Gesture completed successfully at ($x, $y)")
                    if (cont.isActive) cont.resume(Pair(true, null))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    val reason = "dispatchGesture cancelled by system window manager (touch conflict or display transition at ($x, $y))"
                    Log.e(TAG, "[GESTURE_CALLBACK] $reason")
                    if (cont.isActive) cont.resume(Pair(false, reason))
                }
            }, null)

            if (!dispatched) {
                val reason = "AccessibilityService.dispatchGesture returned false (service not ready, display off, or gesture rejected by system)"
                Log.e(TAG, "[GESTURE] $reason")
                if (cont.isActive) cont.resume(Pair(false, reason))
            }
        } catch (e: Exception) {
            val reason = "Exception dispatching gesture at ($x, $y): ${e.localizedMessage}"
            Log.e(TAG, "[GESTURE] $reason", e)
            if (cont.isActive) cont.resume(Pair(false, reason))
        }
    }

    // ==========================================
    // 3. Text Input Handling (Focus then SetText)
    // ==========================================

    /**
     * Executes real text input conforming to the specification:
     * Focus the editable node and use ACTION_SET_TEXT before any coordinate gesture.
     */
    suspend fun performTextInput(
        text: String,
        targetIdentifier: String? = null
    ): TextInputResult {
        Log.i(TAG, "[TEXT_INPUT_START] Typing \"$text\" into target '${targetIdentifier ?: "focused/editable"}'")

        val root = rootInActiveWindow
        if (root == null) {
            val reason = "Active window is null when attempting text input."
            Log.e(TAG, "[TEXT_INPUT_ERROR] $reason")
            return TextInputResult(success = false, method = "FAILED", failureReason = reason)
        }

        try {
            var editableNode: AccessibilityNodeInfo? = null

            // 1. Try finding currently focused input element
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                editableNode = focused
                Log.i(TAG, "[TEXT_INPUT] Found already focused input node.")
            }

            // 2. If not found or target specified, search for target/editable node
            if (editableNode == null) {
                editableNode = findTargetEditableNode(root, targetIdentifier)
            }

            if (editableNode == null) {
                val reason = "No editable node found on screen to set text into."
                Log.e(TAG, "[TEXT_INPUT_ERROR] $reason")
                return TextInputResult(success = false, method = "FAILED", failureReason = reason, targetNodeFound = false)
            }

            // Step 1: Focus the editable node
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            // Step 2: Use ACTION_SET_TEXT before any coordinate gesture
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setSuccess = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (setSuccess) {
                Log.i(TAG, "[TEXT_INPUT_SUCCESS] ACTION_SET_TEXT succeeded without coordinate gestures.")
                editableNode.recycle()
                return TextInputResult(success = true, method = "ACTION_SET_TEXT", targetNodeFound = true)
            } else {
                Log.w(TAG, "[TEXT_INPUT_WARNING] ACTION_SET_TEXT returned false. Attempting tap bounds to summon IME then retry.")
            }

            // Fallback: tap bounds to summon keyboard, then retry ACTION_SET_TEXT
            val bounds = Rect()
            editableNode.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                dispatchGestureWithResult(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                delay(300)
                val retrySuccess = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                editableNode.recycle()
                if (retrySuccess) {
                    Log.i(TAG, "[TEXT_INPUT_SUCCESS] ACTION_SET_TEXT succeeded after summoning focus.")
                    return TextInputResult(success = true, method = "TAP_AND_SET_TEXT", targetNodeFound = true)
                }
            } else {
                editableNode.recycle()
            }

            return TextInputResult(success = false, method = "FAILED", failureReason = "ACTION_SET_TEXT failed to update input field.", targetNodeFound = true)

        } finally {
            root.recycle()
        }
    }

    private fun findTargetEditableNode(root: AccessibilityNodeInfo, targetIdentifier: String?): AccessibilityNodeInfo? {
        val clean = targetIdentifier?.trim()?.lowercase()

        var candidate: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo) {
            if (node.isEditable) {
                if (clean.isNullOrBlank()) {
                    if (candidate == null) candidate = AccessibilityNodeInfo.obtain(node)
                } else {
                    val text = node.text?.toString()?.lowercase()
                    val desc = node.contentDescription?.toString()?.lowercase()
                    val id = node.viewIdResourceName?.lowercase()
                    if (text?.contains(clean) == true || desc?.contains(clean) == true || id?.contains(clean) == true) {
                        candidate?.recycle()
                        candidate = AccessibilityNodeInfo.obtain(node)
                    } else if (candidate == null) {
                        candidate = AccessibilityNodeInfo.obtain(node)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
                child.recycle()
            }
        }

        search(root)
        return candidate
    }

    // ==========================================
    // 4. Directional Scrolling & Navigation
    // ==========================================

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

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

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
