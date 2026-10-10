package com.example.agent

/**
 * Detects common mid-task blockers on the live screen (plan 9.12) and suggests a
 * bounded recovery, so a dialog / ad / permission wall is handled without asking
 * the user and without inventing success. Pure and deterministic so it is
 * unit-testable from a list of visible labels.
 *
 * The caller is responsible for actually performing the suggested dismiss action
 * and for re-observing afterwards; this only decides *what* is blocking and *how*
 * to clear it. A detected blocker that cannot be cleared safely is reported as
 * [Blocker.Kind.UNRESOLVABLE] and the task fails honestly.
 */
object ObstacleDetector {

    /** A bounded list of the visible (clickable-or-labelled) strings on screen. */
    data class ScreenSignals(
        val packageName: String?,
        val labels: List<String>
    ) {
        fun containsAny(vararg needles: String): Boolean =
            labels.any { label -> needles.any { label.contains(it, ignoreCase = true) } }
    }

    enum class Kind { PERMISSION, AD, UPDATE_PROMPT, RATE_LIMIT, COOKIE_CONSENT, UNRESOLVABLE }

    /**
     * One blocker finding. [dismissLabel] is the control to tap (when a safe one
     * exists); [unresolvable] means the task should stop honestly.
     */
    data class Blocker(
        val kind: Kind,
        val dismissLabel: String?,
        val explanation: String,
        val unresolvable: Boolean = false
    )

    private val DISMISS_WORDS = listOf("allow", "ok", "got it", "continue", "accept", "agree",
        "dismiss", "close", "not now", "later", "skip", "no thanks")
    private val DENY_WORDS = listOf("deny", "don't allow", "cancel", "no, thanks")

    /** Returns the first blocker worth handling, or null when the screen is clear. */
    fun detect(signals: ScreenSignals): Blocker? {
        // Permission dialogs: prefer the least-invasive dismissal, never "deny" a
        // permission the task may actually need - the caller decides; we surface it.
        if (signals.containsAny("allow", "while using the app") &&
            signals.containsAny("permission", "access", "deny", "don't allow")
        ) {
            val label = firstPresent(signals, listOf("while using the app", "allow", "ok"))
            return Blocker(
                Kind.PERMISSION,
                label,
                "A permission dialog is asking for access."
            )
        }

        // Rate-limit / too-many-requests walls.
        if (signals.containsAny("too many requests", "try again later", "rate limit", "slow down")) {
            return Blocker(
                Kind.RATE_LIMIT,
                firstPresent(signals, listOf("ok", "got it", "dismiss", "close")),
                "The app is rate-limiting requests."
            )
        }

        // Full-screen ads: only cleared when an obvious close/dismiss control exists.
        if (signals.containsAny("skip ad", "skip", "close ad", "advertisement", "sponsored")) {
            val label = firstPresent(signals, listOf("skip ad", "skip", "close ad", "close", "x"))
            return Blocker(
                Kind.AD,
                label,
                "A full-screen ad is covering the app.",
                unresolvable = label == null
            )
        }

        // Update / "what's new" prompts.
        if (signals.containsAny("update available", "what's new", "new version", "update now")) {
            return Blocker(
                Kind.UPDATE_PROMPT,
                firstPresent(signals, listOf("not now", "later", "skip", "close")),
                "An update prompt is in the way."
            )
        }

        // Cookie / consent banners.
        if (signals.containsAny("cookie", "consent", "privacy choices", "manage options") &&
            signals.containsAny("accept", "agree", "got it")
        ) {
            return Blocker(
                Kind.COOKIE_CONSENT,
                firstPresent(signals, listOf("accept", "agree", "got it")),
                "A consent banner is covering the screen."
            )
        }
        return null
    }

    /** Words that must never be used to auto-dismiss (they decline needed access). */
    fun isSafeDismiss(label: String?): Boolean {
        val l = label?.lowercase() ?: return false
        return DISMISS_WORDS.any { l.contains(it) } && DENY_WORDS.none { l.contains(it) }
    }

    private fun firstPresent(signals: ScreenSignals, candidates: List<String>): String? =
        candidates.firstOrNull { c -> signals.labels.any { it.contains(c, ignoreCase = true) } }
}
