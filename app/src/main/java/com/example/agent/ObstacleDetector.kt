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
        // Permission dialogs: a runtime request always offers a way to GRANT *and* a way
        // to DENY (or the canonical "While using the app / Don't allow" button set).
        // Requiring the deny affordance is what separates a real request from an ordinary
        // Settings > Permissions surface, which shows "Permissions" and an "Allow access
        // to ..." row but no deny control. The old keyword rule matched that screen, so
        // the agent auto-tapped its "Allow" row - derailing the task and potentially
        // granting access the user never asked for (4A false positive).
        //
        // A detected runtime permission dialog is UNRESOLVABLE on purpose (3.5, master
        // prompt): granting access is the user's decision, so the agent must never tap a
        // grant control on the user's behalf - not even "While using the app". The caller
        // stops the run honestly and asks the user to grant the permission themselves.
        val hasDenyChoice = signals.containsAny("deny", "don't allow", "do not allow")
        val hasGrantChoice = signals.containsAny("allow", "while using", "only this time", "grant")
        if (hasDenyChoice && hasGrantChoice) {
            return Blocker(
                Kind.PERMISSION,
                dismissLabel = null,
                explanation = "A runtime permission dialog is asking for access; granting it is the user's decision.",
                unresolvable = true
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

        // Full-screen ads: require a real ad cue. A generic "Skip"/"Close"/"X" control is
        // an ordinary app affordance (onboarding, tutorial, dialog), NOT an ad cue, so it
        // must never be auto-tapped as if it dismissed an ad (4A: false positive on any
        // screen that merely had a "Skip" button). The dismiss control is matched loosely
        // (a bare "x" is deliberately excluded - it substring-matches unrelated labels).
        if (signals.containsAny("advertisement", "sponsored", "skip ad", "close ad", "dismiss ad")) {
            val label = firstPresent(signals, listOf("skip ad", "close ad", "dismiss ad", "skip", "close"))
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

    /** The actual on-screen label that matches the first candidate, not the candidate
     *  token itself - the caller taps this text, so it must be the real label. */
    private fun firstPresent(signals: ScreenSignals, candidates: List<String>): String? =
        candidates.firstNotNullOfOrNull { c ->
            signals.labels.firstOrNull { it.contains(c, ignoreCase = true) }
        }
}
