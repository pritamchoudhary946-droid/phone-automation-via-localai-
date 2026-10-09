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

    override fun isReady(): Boolean = serviceProvider() != null

    override fun unavailabilityReason(): String? =
        if (serviceProvider() == null) {
            "Accessibility Service is not enabled. Enable OmniAgent in Android Settings > Accessibility."
        } else {
            null
        }

    override suspend fun observe(): UiObservation = withContext(Dispatchers.Default) {
        val service = serviceProvider()
            ?: return@withContext UiObservation(null, emptyList(), "Screen unavailable", "unavailable")
        UiObservation.fromSnapshot(service.dumpHierarchy())
    }

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
            else -> ActionResult(false, "UNKNOWN", "Unsupported action for execution layer")
        }
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
