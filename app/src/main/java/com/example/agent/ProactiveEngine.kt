package com.example.agent

import com.example.notifications.AgentNotification
import com.example.perception.BatteryGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Proactive awareness (plan 9.1). Notices the current context and *offers* help
 * without being asked - but only when the user opted in, the battery guard allows
 * it, and something concrete is worth surfacing. It never acts on its own, never
 * captures the screen, and never contacts the network.
 *
 * [evaluate] is pure and deterministic so the rule set is unit-testable; [Runner]
 * wires it to a bounded, battery-aware polling loop.
 */
object ProactiveEngine {

    /** A bounded, on-device snapshot of the current context. */
    data class Context(
        val foregroundPackage: String?,
        val notifications: List<AgentNotification>,
        val minuteOfDay: Int
    )

    data class Suggestion(val text: String, val reason: String)

    private val MESSAGING_HINTS = listOf("whatsapp", "telegram", "messenger", "sms", "signal")

    /**
     * Returns a single suggestion worth showing, or null when there is nothing new
     * or useful. Never returns more than one so the user is not nagged.
     */
    fun evaluate(context: Context): Suggestion? {
        val unread = context.notifications

        // A pile-up of messaging notifications is the most useful nudge.
        val messaging = unread.filter { n ->
            MESSAGING_HINTS.any { n.packageName.lowercase().contains(it) }
        }
        if (messaging.size >= 2) {
            return Suggestion(
                text = "You have ${messaging.size} new messages. Want me to open them?",
                reason = "unread messaging notifications"
            )
        }
        if (unread.size >= 4) {
            return Suggestion(
                text = "You have ${unread.size} unread notifications. Want a quick summary?",
                reason = "many unread notifications"
            )
        }

        // An evening check-in is only offered late in the day, once.
        val hour = context.minuteOfDay / 60
        if (hour >= 20 && context.foregroundPackage == null) {
            return Suggestion(
                text = "It's getting late - want a summary of today before you wind down?",
                reason = "evening idle"
            )
        }
        return null
    }

    /**
     * Bounded, battery-aware runner. [shouldRun] is the opt-in gate; [sampleContext]
     * and [battery] are read each tick. A suggestion is emitted at most once per tick
     * and never repeated verbatim back-to-back.
     */
    class Runner(
        private val shouldRun: () -> Boolean,
        private val battery: () -> BatteryGuard.Decision,
        private val sampleContext: suspend () -> Context,
        private val onSuggestion: (Suggestion) -> Unit,
        private val pollIntervalMs: Long = 45_000L
    ) {
        @Volatile
        private var running = false
        private var lastEmitted: String? = null

        /** True only while the loop is active AND the opt-in gate is on. */
        val isActive: Boolean get() = running && shouldRun()

        fun start(scope: CoroutineScope) {
            if (running) return
            running = true
            scope.launch {
                while (running) {
                    tick()
                    delay(pollIntervalMs)
                }
            }
        }

        fun stop() {
            running = false
        }

        /** One evaluation step; exposed so tests can drive it without a coroutine. */
        suspend fun tick() {
            if (!shouldRun()) return
            if (!battery().allowed) return
            val suggestion = evaluate(sampleContext()) ?: return
            if (suggestion.text == lastEmitted) return
            lastEmitted = suggestion.text
            onSuggestion(suggestion)
        }
    }
}
