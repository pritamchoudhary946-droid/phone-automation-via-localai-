package com.example.perception

import android.graphics.Bitmap
import com.example.accessibility.AgentAccessibilityService
import com.example.accessibility.ScreenEventState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Continuous, event-driven screen awareness.
 *
 * Wraps the accessibility service's [ScreenEventState] stream so the agent loop and
 * future monitors can react to window/content changes instead of polling, and adds a
 * bounded screenshot capture seam. Injectable so tests never need a real device.
 */
interface ScreenAwareness {
    /** Emits whenever the foreground package or an accessibility event changes. */
    val events: Flow<ScreenEventState>

    /** Latest known event state, or null before the first event. */
    val latest: StateFlow<ScreenEventState?>

    /** Bounded, best-effort screenshot for vision grounding/OCR; null when unavailable. */
    suspend fun captureScreenshot(): Bitmap?
}

/** Production [ScreenAwareness] backed by the live accessibility service. */
class AccessibilityScreenAwareness(
    private val serviceProvider: () -> AgentAccessibilityService? = { AgentAccessibilityService.instance }
) : ScreenAwareness {

    private val _latest = MutableStateFlow<ScreenEventState?>(null)
    override val latest: StateFlow<ScreenEventState?> = _latest.asStateFlow()

    override val events: Flow<ScreenEventState>
        get() = serviceProvider()?.screenEvents ?: emptyFlow()

    override suspend fun captureScreenshot(): Bitmap? = serviceProvider()?.takeScreenshotFallback()
}
