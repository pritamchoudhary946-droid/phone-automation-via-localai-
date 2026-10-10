package com.example.agent

import com.example.accessibility.AgentAccessibilityService
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A fresh, bounded observation of the current screen. */
data class UiObservation(
    val packageName: String?,
    val nodes: List<UiNode>,
    val summary: String,
    val fingerprint: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromSnapshot(snapshot: UiTreeSnapshot, maxNodes: Int = 30): UiObservation = UiObservation(
            packageName = snapshot.packageName,
            nodes = snapshot.nodes,
            summary = snapshot.toPromptSummary(maxNodes),
            fingerprint = snapshot.getFingerprint()
        )
    }

    /**
     * True when [target] is (or sits inside) a tappable control on this screen.
     *
     * Android list rows usually expose a clickable container with a separate,
     * non-clickable text label, so a bare `isClickable` check on the labelled node
     * rejects real, tappable results. This treats a node as activatable when it is
     * clickable, or when its bounds contain a clickable node. Editable fields are
     * excluded for "open this item" decisions unless [allowEditable] is set, so a
     * search box is not mistaken for a result.
     */
    fun hasActivatableLabel(target: String, allowEditable: Boolean = false): Boolean {
        if (target.isBlank()) return false
        val labelled = nodes.filter { it.packageName == null || it.packageName == packageName }
            .filter { it.containsText(target) }
            .filter { allowEditable || !it.isEditable }
        return labelled.any { node ->
            if (node.isClickable) return@any true
            nodes.any { candidate ->
                candidate.isClickable &&
                    (candidate.packageName == null || candidate.packageName == packageName) &&
                    candidate.bounds.contains(node.bounds)
            }
        }
    }

    /**
     * True when a usable text-entry field is visible, using the platform's
     * `isEditable` flag or the [UiNode.isLikelyTextInput] heuristic (WebViews and
     * custom composers often omit the flag).
     */
    fun hasTextInputField(): Boolean =
        nodes.any { (it.packageName == null || it.packageName == packageName) && it.isLikelyTextInput() }
}

/** Outcome of dispatching a single native action. */
data class ActionResult(
    val success: Boolean,
    val method: String,
    val message: String
)

/**
 * Execution layer boundary. The planning brain never touches Android APIs
 * directly; it can only request these approved capabilities. This seam is
 * injectable so the orchestrator is fully unit-testable.
 */
interface DeviceAutomation {
    fun isReady(): Boolean

    /** Non-blocking reason the automation layer is unavailable, if any. */
    fun unavailabilityReason(): String?

    suspend fun observe(): UiObservation

    suspend fun execute(action: AgentAction): ActionResult
}

/** Real implementation backed by the existing [AgentAccessibilityService]. */
class AndroidDeviceAutomation(
    private val appDirectory: AppDirectory,
    private val serviceProvider: () -> AgentAccessibilityService? = { AgentAccessibilityService.instance }
) : DeviceAutomation {

    companion object {
        /** Poll interval while waiting for the screen fingerprint to settle. */
        private const val SETTLE_POLL_MS = 150L

        /** Upper bound on how long to wait for the screen to settle before observing. */
        private const val SETTLE_BUDGET_MS = 900L
    }

    override fun isReady(): Boolean = serviceProvider() != null

    override fun unavailabilityReason(): String? =
        if (serviceProvider() == null) {
            "Accessibility Service is not enabled. Enable OmniAgent in Android Settings > Accessibility."
        } else {
            null
        }

    override suspend fun observe(): UiObservation = withContext(Dispatchers.Default) {
        settleAndCompose()
    }

    /**
     * Reads the hierarchy repeatedly until its fingerprint stops changing or
     * [SETTLE_BUDGET_MS] elapses, then composes the final observation. This replaces a
     * single fixed-delay read: a slow surface is waited out (bounded), while an
     * already-stable screen returns after one poll.
     */
    private suspend fun settleAndCompose(): UiObservation {
        val service = serviceProvider()
            ?: return UiObservation(null, emptyList(), "Screen unavailable", "unavailable")
        var snapshot = service.dumpHierarchy()
        val deadline = System.currentTimeMillis() + SETTLE_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            delay(SETTLE_POLL_MS)
            val next = service.dumpHierarchy()
            if (next.getFingerprint() == snapshot.getFingerprint()) break
            snapshot = next
        }
        return composeObservation(snapshot)
    }

    /**
     * Composes the observation from the settled snapshot. Kept as a seam so extra
     * perception (page text, screenshot grounding) can be layered in here without
     * touching the settle loop.
     */
    private fun composeObservation(snapshot: UiTreeSnapshot): UiObservation =
        UiObservation.fromSnapshot(snapshot)

    override suspend fun execute(action: AgentAction): ActionResult = withContext(Dispatchers.Default) {
        val service = serviceProvider()
            ?: return@withContext ActionResult(false, "NO_SERVICE", "Accessibility Service unavailable")

        val preFingerprint = service.dumpHierarchy().getFingerprint()
        val uiChanged: suspend () -> Boolean = {
            delay(250)
            service.dumpHierarchy().getFingerprint() != preFingerprint
        }

        when (action) {
            is AgentAction.OpenApp -> executeOpenApp(action, service)
            is AgentAction.Tap -> {
                val r = service.performTap(action.targetText, action.x, action.y, uiChanged)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Tap succeeded via ${r.method}" else (r.failureReason ?: "Tap failed")
                )
            }
            is AgentAction.OpenItem -> {
                // Reacquire the node from the live tree by its label; never reuse a stale node.
                val r = service.performTap(action.targetText, null, null, uiChanged)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Opened item '${action.targetText}' via ${r.method}" else (r.failureReason ?: "Open item failed")
                )
            }
            is AgentAction.TypeText -> {
                val r = service.performTextInput(action.text, action.targetNodeId)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Typed \"${action.text}\" via ${r.method}" else (r.failureReason ?: "Text input failed")
                )
            }
            is AgentAction.OpenSearch -> {
                val r = service.openSearchInterface(action.query)
                if (r.success) {
                    ActionResult(true, r.method, r.message)
                } else if (!action.query.isNullOrBlank()) {
                    // Bounded fallback: the accessibility tree exposed no search
                    // affordance. Use the app's own ACTION_SEARCH intent (existing
                    // supported capability) rather than tapping blind coordinates.
                    val foreground = service.dumpHierarchy().packageName
                    val launched = foreground != null && appDirectory.launchSearch(action.query, foreground)
                    if (launched) {
                        delay(800)
                        ActionResult(true, "NATIVE_SEARCH_INTENT", "Opened search via ACTION_SEARCH for '${action.query}'")
                    } else {
                        ActionResult(false, r.method, r.message)
                    }
                } else {
                    ActionResult(false, r.method, r.message)
                }
            }
            is AgentAction.Scroll -> ActionResult(service.scroll(action.direction), "SCROLL", "Scrolled ${action.direction}")
            is AgentAction.LongPress -> {
                val r = service.performLongPress(action.targetText.orEmpty(), action.durationMs)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Long-pressed '${action.targetText}' via ${r.method}" else (r.failureReason ?: "Long press failed")
                )
            }
            is AgentAction.ScrollUntilFound -> executeScrollUntilFound(action, service)
            is AgentAction.ReadText -> {
                val text = service.readVisibleText()
                ActionResult(
                    success = text.isNotBlank(),
                    method = "READ_TEXT",
                    message = if (text.isNotBlank()) "Read ${text.length} chars of visible text" else "No visible text found on screen"
                )
            }
            is AgentAction.WaitForText -> {
                val deadline = System.currentTimeMillis() + action.timeoutMs
                var found = service.isTextVisible(action.targetText)
                while (!found && System.currentTimeMillis() < deadline) {
                    delay(250)
                    found = service.isTextVisible(action.targetText)
                }
                ActionResult(
                    success = found,
                    method = "WAIT_FOR_TEXT",
                    message = if (found) "Text '${action.targetText}' appeared" else "Text '${action.targetText}' did not appear within ${action.timeoutMs}ms"
                )
            }
            is AgentAction.OpenNotifications -> ActionResult(service.openNotifications(), "GLOBAL_NOTIFICATIONS", "Opened notification shade")
            is AgentAction.OpenQuickSettings -> ActionResult(service.openQuickSettings(), "GLOBAL_QUICK_SETTINGS", "Opened quick settings")
            is AgentAction.PressRecents -> ActionResult(service.pressRecents(), "GLOBAL_RECENTS", "Opened recents")
            is AgentAction.Back -> ActionResult(service.pressBack(), "GLOBAL_BACK", "Pressed Back")
            is AgentAction.Home -> ActionResult(service.pressHome(), "GLOBAL_HOME", "Pressed Home")
            is AgentAction.Wait -> {
                delay(action.durationMs)
                ActionResult(true, "WAIT", "Waited ${action.durationMs}ms")
            }
            is AgentAction.ReadScreen -> ActionResult(true, "READ", "Screen hierarchy refreshed")
            is AgentAction.Screenshot -> {
                val bmp = service.takeScreenshotFallback()
                ActionResult(bmp != null, "SCREENSHOT", if (bmp != null) "Screenshot captured" else "Screenshot unavailable")
            }
            is AgentAction.Submit -> {
                val r = service.performSubmit(action.targetText)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Submitted via ${r.method}" else (r.failureReason ?: "Submit failed")
                )
            }
            is AgentAction.OpenUrl -> {
                val ok = appDirectory.openUrl(action.url)
                if (ok) delay(800)
                ActionResult(ok, "OPEN_URL", if (ok) "Opened URL ${action.url}" else "No activity handled ${action.url}")
            }
            is AgentAction.DeepLink -> {
                val ok = appDirectory.openDeepLink(action.uri)
                if (ok) delay(800)
                ActionResult(ok, "DEEP_LINK", if (ok) "Opened deep link ${action.uri}" else "No activity handled ${action.uri}")
            }
            is AgentAction.Drag -> {
                val r = service.performDrag(action.fromText, action.toText)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Dragged '${action.fromText}' -> '${action.toText}'" else (r.failureReason ?: "Drag failed")
                )
            }
            is AgentAction.CopyText -> {
                val ok = service.performCopy(action.targetText)
                ActionResult(ok, "COPY", if (ok) "Copied text from '${action.targetText ?: "focused field"}'" else "Copy action unavailable")
            }
            is AgentAction.PasteText -> {
                val ok = service.performPaste()
                ActionResult(ok, "PASTE", if (ok) "Pasted clipboard into focused field" else "Paste action unavailable")
            }
            is AgentAction.ReadPageContent -> {
                val text = service.readPageContent()
                ActionResult(
                    success = text.isNotBlank(),
                    method = "READ_PAGE_CONTENT",
                    message = if (text.isNotBlank()) "Extracted ${text.length} chars of page content" else "No page content found"
                )
            }
            else -> ActionResult(false, "UNKNOWN", "Unsupported action for execution layer")
        }
    }

    /**
     * Scrolls the current surface until [AgentAction.ScrollUntilFound.targetText]
     * becomes visible. Bounded by `maxSwipes` so a missing label cannot loop forever.
     * Stops early when a swipe no longer changes the screen (end of list reached).
     */
    private suspend fun executeScrollUntilFound(
        action: AgentAction.ScrollUntilFound,
        service: AgentAccessibilityService
    ): ActionResult {
        if (service.isTextVisible(action.targetText)) {
            return ActionResult(true, "ALREADY_VISIBLE", "'${action.targetText}' is already visible")
        }
        var swipes = 0
        while (swipes < action.maxSwipes) {
            val before = service.dumpHierarchy().getFingerprint()
            service.scroll(action.direction)
            delay(350)
            if (service.isTextVisible(action.targetText)) {
                return ActionResult(true, "SCROLL_UNTIL_FOUND", "Found '${action.targetText}' after ${swipes + 1} swipe(s)")
            }
            val after = service.dumpHierarchy().getFingerprint()
            swipes++
            if (before == after) {
                // The list did not move: further swipes in this direction are pointless.
                return ActionResult(false, "SCROLL_EXHAUSTED", "Reached the end of the list without finding '${action.targetText}'")
            }
        }
        return ActionResult(false, "SCROLL_EXHAUSTED", "'${action.targetText}' not found after ${action.maxSwipes} swipes")
    }

    private suspend fun executeOpenApp(
        action: AgentAction.OpenApp,
        service: AgentAccessibilityService
    ): ActionResult {
        val targetPkg = action.packageName ?: appDirectory.resolvePackage(action.appQuery)
        if (targetPkg == null) {
            return ActionResult(false, "RESOLVE_APP", "App '${action.appQuery}' not found on device")
        }
        if (ForegroundPackageVerifier.matches(targetPkg, service.dumpHierarchy().packageName)) {
            return ActionResult(true, "FOREGROUND_ALREADY_ACTIVE", "Verified $targetPkg already foregrounded; launch skipped")
        }
        if (!appDirectory.launchApp(targetPkg)) {
            return ActionResult(false, "LAUNCH_INTENT", "Launch intent dispatch failed for $targetPkg")
        }
        // Cold starts on a mid-range device regularly take longer than one second, so
        // allow a wider bounded window before declaring the launch unverified.
        val observed = ForegroundPackageVerifier.awaitForegroundPackage(
            targetPackage = targetPkg,
            attempts = 8,
            delayMillis = 300L
        ) { service.dumpHierarchy().packageName }
        return if (observed != null) {
            ActionResult(true, "LAUNCH_VERIFIED", "Verified foreground package $observed after launch")
        } else {
            ActionResult(false, "LAUNCH_UNVERIFIED", "Launch dispatched for $targetPkg but foreground package was not verified")
        }
    }
}
