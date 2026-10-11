package com.example.agent

import com.example.accessibility.AgentAccessibilityService
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.notifications.NotificationBus
import com.example.notifications.NotificationSource
import com.example.perception.TargetResolver
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

    /**
     * Search-specific input detection. A generic editable node is not enough:
     * message, comment, caption and login fields are valid text inputs but are
     * unsafe targets for an open-search step.
     */
    fun hasSearchInputField(): Boolean =
        nodes.any { node ->
            if (node.packageName != null && node.packageName != packageName) return@any false
            if (!node.isLikelyTextInput()) return@any false
            val haystack = listOfNotNull(
                node.text,
                node.contentDescription,
                node.id.substringAfterLast('/'),
                node.className
            ).joinToString(" ").lowercase()
            val searchSignal = listOf("search", "query", "searchview").any { haystack.contains(it) }
            val negativeSignal = listOf("message", "comment", "caption", "reply", "password").any { haystack.contains(it) }
            searchSignal || (node.isFocused && !negativeSignal && node.className.lowercase().contains("search"))
        }

    /**
     * Resolves [hint] to a single actionable target using [TargetResolver] (10.7).
     * Only a [TargetResolver.Outcome.UNIQUE] result is actionable; ambiguous or
     * unmatched hints return a non-actionable resolution the caller must report.
     */
    fun resolveTarget(
        hint: String,
        allowEditable: Boolean = false,
        requireActivatable: Boolean = false
    ): TargetResolver.Resolution =
        TargetResolver.resolve(
            nodes = nodes,
            hint = hint,
            foregroundPackage = packageName,
            allowEditable = allowEditable,
            requireActivatable = requireActivatable
        )
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
    private val serviceProvider: () -> AgentAccessibilityService? = { AgentAccessibilityService.instance },
    private val notificationSource: NotificationSource = NotificationBus,
    private val ocrReader: com.example.perception.OcrReader = com.example.perception.UnavailableOcrReader,
    private val visionGrounder: com.example.perception.VisionGrounder = com.example.perception.UnavailableVisionGrounder,
    // 9.9: reading posted notifications is the smart-triage capability, so it is gated
    // by the opt-in flag. Default OFF means the flag is only read when a caller passes a
    // live supplier; the built-in path wires the real setting.
    private val triageEnabled: () -> Boolean = { false },
    // Device audio is controlled through the system AudioManager, not the accessibility
    // tree, so the volume primitive is resolved before the a11y-service gate (like
    // ReadNotifications). A caller that does not wire a controller gets an honest
    // "unavailable" result rather than a silent no-op.
    private val audioController: AudioController? = null,
    // Media transport is likewise service-free: it dispatches a standard media key
    // through the AudioManager to the active media session, so it is resolved before
    // the a11y-service gate (a UI tap cannot reach an arbitrary player's transport).
    // An unwired controller gets an honest "unavailable" result, never a silent no-op.
    private val mediaController: MediaController? = null
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
            "Accessibility Service is not enabled. Enable Jarvis in Android Settings > Accessibility."
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
    private suspend fun composeObservation(snapshot: UiTreeSnapshot): UiObservation {
        val base = UiObservation.fromSnapshot(snapshot)
        // 7.2/7.3: when the settled tree is blind (no nodes at all), fall back to OCR
        // over a bounded screenshot so a canvas/WebView surface still yields text the
        // planner can reason about. Only attempted when there is nothing in the tree.
        if (base.nodes.isNotEmpty()) return base
        val service = serviceProvider() ?: return base
        val bitmap = try { service.takeScreenshotFallback() } catch (e: Exception) { null }
        if (bitmap == null) return base
        val text = try { ocrReader.readText(bitmap) } catch (e: Exception) { "" }
        if (text.isBlank()) return base
        return base.copy(summary = (base.summary + "\n[OCR]\n" + text).trim())
    }

    override suspend fun execute(action: AgentAction): ActionResult = withContext(Dispatchers.Default) {
        // Notification triage is independent of the accessibility tree: it reads the
        // bounded NotificationBus digest, so it is resolved before the service gate
        // (otherwise an opted-in read would be misreported as NO_SERVICE when the a11y
        // service happens to be down).
        if (action is AgentAction.ReadNotifications) {
            // 9.9: consent before capability. Refuse honestly when triage is off.
            if (!triageEnabled()) {
                return@withContext ActionResult(
                    false,
                    "TRIAGE_DISABLED",
                    "Notification triage is off. Enable it in Settings to summarise notifications."
                )
            }
            return@withContext readNotificationDigest()
        }
        // Device audio is likewise service-free: it changes a system stream through the
        // AudioManager, so it is resolved before the a11y-service gate. This is the media
        // control primitive the planner advertises; without it the model could plan a
        // volume change that the execution layer silently refused as UNKNOWN.
        if (action is AgentAction.AdjustVolume) {
            val stream = AudioStream.fromKey(action.stream)
                ?: return@withContext ActionResult(false, "INVALID_STREAM", "Unknown audio stream '${action.stream}'")
            val controller = audioController
                ?: return@withContext ActionResult(false, "AUDIO_UNAVAILABLE", "Device audio control is not available")
            val result = controller.adjust(stream, action.steps, action.mute)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Media transport control is the playback primitive behind "pause the music" /
        // "next track": a standard media key to the active session, service-free like the
        // audio stream above.
        if (action is AgentAction.PlayMedia) {
            val transport = MediaTransport.fromKey(action.transport)
                ?: return@withContext ActionResult(false, "INVALID_TRANSPORT", "Unknown media transport '${action.transport}'")
            val controller = mediaController
                ?: return@withContext ActionResult(false, "MEDIA_UNAVAILABLE", "Media control is not available")
            val result = controller.control(transport)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
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
                if (r.success) {
                    ActionResult(true, r.method, "Tap succeeded via ${r.method}")
                } else {
                    // 7.2: the tree could not represent the target. Try vision grounding
                    // (OCR-backed) once and tap the located point instead of giving up.
                    val grounded = visionTap(action.targetText, service, uiChanged)
                    if (grounded != null) {
                        grounded
                    } else {
                        ActionResult(false, r.method, r.failureReason ?: "Tap failed")
                    }
                }
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
            is AgentAction.ClearText -> {
                // ACTION_SET_TEXT with an empty value empties the field and is verified
                // the same way as a normal entry (the field must read back empty).
                val r = service.performTextInput("", action.targetText)
                ActionResult(
                    success = r.success,
                    method = "CLEAR_TEXT",
                    message = if (r.success) "Cleared '${action.targetText ?: "focused field"}'" else (r.failureReason ?: "Clear field failed")
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
            is AgentAction.FindElement -> executeFindElement(action, service)
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
            else -> ActionResult(
                false,
                "UNSUPPORTED_ACTION",
                "Action '${action.getDisplayName()}' has no native executor; the model must not have been offered it"
            )
        }
    }

    /**
     * 9.9: reads the bounded, on-device notification digest. Kept free of the a11y
     * service so triage works (and is testable) without a bound accessibility tree.
     */
    private fun readNotificationDigest(): ActionResult {
        val items = notificationSource.recent(NotificationSource.DEFAULT_LIMIT)
        return ActionResult(
            success = items.isNotEmpty(),
            method = "READ_NOTIFICATIONS",
            message = if (items.isEmpty()) {
                "No recent notifications available (grant notification access to enable this)"
            } else {
                items.joinToString(" | ") { "${it.packageName}: ${it.title} — ${it.text}".take(120) }
            }
        )
    }

    /**
     * 7.2: one bounded vision-grounding attempt when the a11y tree cannot represent
     * the target. Takes a screenshot, asks the grounder to locate the label, and taps
     * the point via the normal coordinate path. Returns null (never blind-taps) when no
     * grounder is configured or the label cannot be located.
     */
    private suspend fun visionTap(
        targetText: String?,
        service: AgentAccessibilityService,
        uiChanged: suspend () -> Boolean
    ): ActionResult? {
        if (targetText.isNullOrBlank()) return null
        if (visionGrounder is com.example.perception.UnavailableVisionGrounder) return null
        val bitmap = try { service.takeScreenshotFallback() } catch (e: Exception) { null } ?: return null
        val point = try { visionGrounder.locate(targetText, bitmap) } catch (e: Exception) { null } ?: return null
        val r = service.performTap(null, point.x, point.y, uiChanged)
        return if (r.success) {
            ActionResult(true, "VISION_GROUNDED", "Located '$targetText' via vision at (${point.x}, ${point.y})")
        } else {
            null
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
        return when (val reveal = revealByScrolling(service, action.targetText, action.direction, action.maxSwipes)) {
            is RevealResult.AlreadyVisible ->
                ActionResult(true, "ALREADY_VISIBLE", "'${action.targetText}' is already visible")
            is RevealResult.Revealed ->
                ActionResult(true, "SCROLL_UNTIL_FOUND", "Found '${action.targetText}' after ${reveal.swipes} swipe(s)")
            is RevealResult.Exhausted ->
                ActionResult(false, "SCROLL_EXHAUSTED", reveal.reason)
        }
    }

    /**
     * The general "find and press" primitive: reveal [AgentAction.FindElement.targetText]
     * (scrolling if necessary, bounded) and tap it once from a fresh observation, so a
     * target that is scrolled off-screen is reachable in a single step. A tap that is
     * dispatched but cannot be confirmed is reported honestly rather than as success.
     */
    private suspend fun executeFindElement(
        action: AgentAction.FindElement,
        service: AgentAccessibilityService
    ): ActionResult {
        val reveal = revealByScrolling(service, action.targetText, action.direction, action.maxSwipes)
        if (reveal is RevealResult.Exhausted) {
            return ActionResult(false, "FIND_EXHAUSTED", reveal.reason)
        }
        // Verify the tap against the screen state at the moment of the tap: a dispatched
        // gesture that leaves the screen identical is not proof the element was activated.
        val before = service.dumpHierarchy().getFingerprint()
        val tap = service.performTap(action.targetText, null, null) {
            delay(250)
            service.dumpHierarchy().getFingerprint() != before
        }
        if (!tap.success) {
            return ActionResult(false, tap.method, tap.failureReason ?: "Found '${action.targetText}' but could not tap it")
        }
        return ActionResult(
            success = true,
            method = "FIND_TAP_${tap.method}",
            message = "Found and tapped '${action.targetText}' via ${tap.method}"
        )
    }

    private sealed class RevealResult {
        object AlreadyVisible : RevealResult()
        data class Revealed(val swipes: Int) : RevealResult()
        data class Exhausted(val reason: String) : RevealResult()
    }

    /**
     * Reveals a label by scrolling [direction] up to [maxSwipes] times. Stops as soon
     * as the label is visible, and stops early when a swipe no longer moves the screen
     * (the end of the list has been reached), so a missing label cannot scroll forever.
     */
    private suspend fun revealByScrolling(
        service: AgentAccessibilityService,
        targetText: String,
        direction: String,
        maxSwipes: Int
    ): RevealResult {
        if (service.isTextVisible(targetText)) return RevealResult.AlreadyVisible
        var swipes = 0
        while (swipes < maxSwipes) {
            val before = service.dumpHierarchy().getFingerprint()
            service.scroll(direction)
            delay(350)
            if (service.isTextVisible(targetText)) return RevealResult.Revealed(swipes + 1)
            val after = service.dumpHierarchy().getFingerprint()
            swipes++
            if (before == after) {
                return RevealResult.Exhausted("Reached the end of the list without finding '$targetText'")
            }
        }
        return RevealResult.Exhausted("'$targetText' not found after $maxSwipes swipes")
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
