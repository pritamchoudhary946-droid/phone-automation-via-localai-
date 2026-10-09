package com.example.model

import com.example.agent.AgentAction
import com.example.agent.ForegroundPackageVerifier
import com.example.planning.PlanStep
import com.example.planning.PlanStepStatus
import com.example.planning.TaskPlan
import com.example.planning.VerificationMethod

data class AgentDecision(
    val thought: String,
    val action: AgentAction,
    val rawOutput: String,
    val activeSubGoal: String? = null,
    val confidence: Float = 0.95f
)

enum class SubGoalKind {
    LAUNCH_APP,
    SEARCH_QUERY,
    OPEN_ITEM,
    TOGGLE_SETTING,
    RETURN_HOME,
    NAVIGATE_BACK,
    SCREENSHOT,
    CUSTOM
}

data class SubGoal(
    val description: String,
    val kind: SubGoalKind,
    val target: String? = null
)

/**
 * Rule-based planner used ONLY as a clearly labelled basic fallback when the
 * local Gemma model is unavailable. This is not the AI path: it is deterministic
 * pattern matching so the previously working native automation stays usable.
 *
 * Every decision produced here is tagged as fallback by [AgentPlanner]; the app
 * must never report Gemma planning when this class produced the plan.
 */
class HeuristicPlanner {

    /**
     * Decomposes a multi-command user request into small, ordered sub-goals.
     * E.g. "Open YouTube, search for an educational video, open it, and then return to the home screen"
     */
    fun decomposeGoalIntoSubGoals(goal: String): List<SubGoal> {
        val lower = goal.lowercase().trim()
        val list = mutableListOf<SubGoal>()

        val rawClauses = lower.split(Regex(",\\s*and\\s+then\\s+|,\\s*then\\s+|\\s+and\\s+then\\s+|\\s+then\\s+|,\\s*and\\s+|\\s+and\\s+|,\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (clause in rawClauses) {
            when {
                clause.contains("open it") || clause.contains("click it") || clause.contains("play it") || clause.contains("tap it") || clause.contains("open first") || clause.contains("click first") ->
                    list.add(SubGoal("Open target item", SubGoalKind.OPEN_ITEM))
                clause.startsWith("open ") || clause.startsWith("launch ") -> {
                    val app = clause.removePrefix("open ").removePrefix("launch ").trim()
                    list.add(SubGoal("Open $app", SubGoalKind.LAUNCH_APP, app))
                }
                clause.startsWith("search for ") || clause.startsWith("search ") || clause.startsWith("find ") -> {
                    val q = clause.removePrefix("search for ").removePrefix("search ").removePrefix("find ").trim()
                    list.add(SubGoal("Search for \"$q\"", SubGoalKind.SEARCH_QUERY, q))
                }
                clause.contains("turn on ") || clause.contains("enable ") || clause.contains("toggle ") -> {
                    val f = clause.replace("turn on ", "").replace("enable ", "").replace("toggle ", "").trim()
                    list.add(SubGoal("Toggle $f", SubGoalKind.TOGGLE_SETTING, f))
                }
                clause.contains("return to the home screen") || clause.contains("return to home") || clause.contains("return home") || clause.contains("go home") || clause == "home" ->
                    list.add(SubGoal("Return to home screen", SubGoalKind.RETURN_HOME))
                clause.contains("go back") || clause == "back" ->
                    list.add(SubGoal("Navigate Back", SubGoalKind.NAVIGATE_BACK))
                clause.contains("screenshot") ->
                    list.add(SubGoal("Take screenshot", SubGoalKind.SCREENSHOT))
                else -> list.add(SubGoal(clause, SubGoalKind.CUSTOM, clause))
            }
        }

        return if (list.isNotEmpty()) list else listOf(SubGoal(goal, SubGoalKind.CUSTOM, goal))
    }

    /** Builds a nominal fallback plan. Actions are re-decided each iteration from fresh screen state. */
    fun toFallbackPlan(goal: String): TaskPlan {
        val subGoals = decomposeGoalIntoSubGoals(goal)
        val steps = mutableListOf<PlanStep>()
        var id = 1
        for (sg in subGoals) {
            when (sg.kind) {
                SubGoalKind.SEARCH_QUERY -> {
                    val query = sg.target ?: ""
                    // A search needs the search UI opened before text can be entered:
                    // the entry field is often not visible right after launch.
                    steps.add(
                        PlanStep(
                            id = id++,
                            sequence = steps.size + 1,
                            description = "Open search interface",
                            action = AgentAction.OpenSearch(query),
                            rawActionName = "basic_fallback",
                            expectedPostcondition = "Search interface open",
                            verification = VerificationMethod.SCREEN_CHANGED,
                            status = PlanStepStatus.PENDING
                        )
                    )
                    steps.add(
                        PlanStep(
                            id = id++,
                            sequence = steps.size + 1,
                            description = "Search for \"$query\"",
                            action = AgentAction.TypeText(query, null),
                            rawActionName = "basic_fallback",
                            expectedPostcondition = query,
                            verification = VerificationMethod.RESULTS_PRESENT,
                            status = PlanStepStatus.PENDING
                        )
                    )
                }
                else -> steps.add(
                    PlanStep(
                        id = id++,
                        sequence = steps.size + 1,
                        description = sg.description,
                        action = nominalActionFor(sg),
                        rawActionName = "basic_fallback",
                        expectedPostcondition = sg.description,
                        verification = verificationFor(sg.kind),
                        status = PlanStepStatus.PENDING
                    )
                )
            }
        }
        return TaskPlan(goal = goal, steps = steps, fromModel = false)
    }

    private fun nominalActionFor(subGoal: SubGoal): AgentAction = when (subGoal.kind) {
        SubGoalKind.LAUNCH_APP -> AgentAction.OpenApp(subGoal.target ?: "", null)
        SubGoalKind.SEARCH_QUERY -> AgentAction.OpenSearch(subGoal.target)
        SubGoalKind.OPEN_ITEM -> AgentAction.OpenItem(subGoal.target ?: "results")
        SubGoalKind.TOGGLE_SETTING -> AgentAction.Tap(subGoal.target ?: "", null, null)
        SubGoalKind.RETURN_HOME -> AgentAction.Home
        SubGoalKind.NAVIGATE_BACK -> AgentAction.Back
        SubGoalKind.SCREENSHOT -> AgentAction.Screenshot
        SubGoalKind.CUSTOM -> AgentAction.ReadScreen
    }

    private fun verificationFor(kind: SubGoalKind): VerificationMethod = when (kind) {
        SubGoalKind.LAUNCH_APP -> VerificationMethod.FOREGROUND_PACKAGE
        SubGoalKind.SEARCH_QUERY -> VerificationMethod.RESULTS_PRESENT
        SubGoalKind.OPEN_ITEM -> VerificationMethod.SCREEN_CHANGED
        SubGoalKind.TOGGLE_SETTING -> VerificationMethod.TEXT_PRESENT
        SubGoalKind.RETURN_HOME -> VerificationMethod.HOME_FOREGROUND
        else -> VerificationMethod.NONE
    }

    fun reasonAndPlan(
        goal: String,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): AgentDecision {
        val subGoals = decomposeGoalIntoSubGoals(goal)
        return planNextStep(goal, subGoals, screenSummary, historySummary, lastActionVerification)
    }

    private fun planNextStep(
        overallGoal: String,
        subGoals: List<SubGoal>,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): AgentDecision {
        val lowerScreen = screenSummary.lowercase()
        val lowerHistory = historySummary.lowercase()
        val foregroundPackage = extractForegroundPackage(screenSummary)

        var currentSubGoalIndex = 0
        for (i in subGoals.indices) {
            val sg = subGoals[i]
            val isComplete = when (sg.kind) {
                SubGoalKind.LAUNCH_APP -> isTargetAppForeground(sg.target, foregroundPackage)
                SubGoalKind.SEARCH_QUERY -> lowerScreen.contains("results") ||
                    (lowerScreen.contains("views") && (lowerScreen.contains("video") || lowerScreen.contains("channel")))
                SubGoalKind.OPEN_ITEM ->
                    lowerHistory.contains("tap (") && (lowerScreen.contains("player") || lowerScreen.contains("watch") || lowerScreen.contains("detail"))
                SubGoalKind.TOGGLE_SETTING -> settingIsEnabled(sg.target, lowerScreen)
                SubGoalKind.RETURN_HOME -> isHomeForeground(foregroundPackage)
                SubGoalKind.NAVIGATE_BACK -> lowerHistory.contains("back")
                SubGoalKind.SCREENSHOT -> lowerHistory.contains("screenshot")
                SubGoalKind.CUSTOM -> false
            }

            if (isComplete && i < subGoals.size - 1) {
                currentSubGoalIndex = i + 1
            } else {
                currentSubGoalIndex = i
                break
            }
        }

        val activeSubGoal = subGoals.getOrNull(currentSubGoalIndex)

        val lastSg = subGoals.last()
        val allSubGoalsComplete = when (lastSg.kind) {
            SubGoalKind.LAUNCH_APP -> isTargetAppForeground(lastSg.target, foregroundPackage)
            SubGoalKind.RETURN_HOME -> isHomeForeground(foregroundPackage)
            SubGoalKind.SEARCH_QUERY -> (lowerHistory.contains("type_text") || lowerHistory.contains("tap")) &&
                (lowerScreen.contains("results") || (lowerScreen.contains("views") && (lowerScreen.contains("video") || lowerScreen.contains("channel"))))
            SubGoalKind.OPEN_ITEM -> lowerHistory.contains("tap") &&
                (lowerScreen.contains("player") || lowerScreen.contains("watch") || lowerScreen.contains("detail"))
            SubGoalKind.TOGGLE_SETTING -> settingIsEnabled(lastSg.target, lowerScreen)
            else -> false
        }

        if (allSubGoalsComplete && (currentSubGoalIndex >= subGoals.size - 1)) {
            val summaryText = "Successfully executed ${subGoals.size} sequential actions for: \"$overallGoal\""
            return AgentDecision(
                thought = "All planned sub-goals have been executed and verified.",
                action = AgentAction.FinishTask(summaryText),
                rawOutput = "Thought: Task complete.\nAction: finish_task(\"$summaryText\")",
                activeSubGoal = "Complete"
            )
        }

        if (activeSubGoal?.kind == SubGoalKind.LAUNCH_APP) {
            val appToOpen = activeSubGoal.target ?: "youtube"
            val appAlreadyOpen = isTargetAppForeground(appToOpen, foregroundPackage)
            if (!appAlreadyOpen) {
                return AgentDecision(
                    thought = "Sub-goal: Launching target app '$appToOpen'.",
                    action = AgentAction.OpenApp(appToOpen, null),
                    rawOutput = "Thought: Opening $appToOpen to begin.\nAction: open_app(\"$appToOpen\")",
                    activeSubGoal = activeSubGoal.description
                )
            }
        }

        if (activeSubGoal?.kind == SubGoalKind.SEARCH_QUERY) {
            val query = activeSubGoal.target ?: "search query"
            val cleanQueryLower = query.lowercase()
            val queryAlreadyTyped = lowerHistory.contains("action typetext") && lastActionVerification?.startsWith("Success:", ignoreCase = true) == true
            val editableFieldVisible = isEditableFieldVisible(screenSummary)
            val queryVerifiedInField = editableFieldVisible &&
                screenSummary.lineSequence().any { it.contains(cleanQueryLower, ignoreCase = true) }

            if (editableFieldVisible) {
                if (!queryAlreadyTyped || !queryVerifiedInField) {
                    return AgentDecision(
                        thought = "Search bar active. Entering query \"$query\" via ACTION_SET_TEXT.",
                        action = AgentAction.TypeText(query, null),
                        rawOutput = "Thought: Typing search query.\nAction: type_text(\"$query\")",
                        activeSubGoal = activeSubGoal.description
                    )
                } else {
                    return AgentDecision(
                        thought = "Submitting search for \"$query\" by tapping search button/suggestion.",
                        action = AgentAction.Tap("Search", null, null),
                        rawOutput = "Thought: Submitting search query.\nAction: tap(\"Search\")",
                        activeSubGoal = activeSubGoal.description
                    )
                }
            } else {
                // The entry field is not on screen yet: open the search interface
                // first (fresh node lookup) instead of typing into nothing.
                return AgentDecision(
                    thought = "Search field not visible. Opening the search interface via open_search.",
                    action = AgentAction.OpenSearch(query),
                    rawOutput = "Thought: Opening search interface.\nAction: open_search(\"$query\")",
                    activeSubGoal = activeSubGoal.description
                )
            }
        }

        if (activeSubGoal?.kind == SubGoalKind.OPEN_ITEM) {
            val query = subGoals.find { it.kind == SubGoalKind.SEARCH_QUERY }?.target ?: ""
            val tapTarget = if (query.isNotEmpty() && lowerScreen.contains(query.lowercase())) query else "results"
            return AgentDecision(
                thought = "Opening the first matching result on screen.",
                action = AgentAction.OpenItem(tapTarget),
                rawOutput = "Thought: Opening selected result.\nAction: open_item(\"$tapTarget\")",
                activeSubGoal = activeSubGoal.description
            )
        }

        if (activeSubGoal?.kind == SubGoalKind.RETURN_HOME) {
            if (!isHomeForeground(foregroundPackage)) {
                return AgentDecision(
                    thought = "Returning to the home screen via global HOME action.",
                    action = AgentAction.Home,
                    rawOutput = "Thought: Navigating home.\nAction: home()",
                    activeSubGoal = activeSubGoal.description
                )
            }
        }

        if (activeSubGoal?.kind == SubGoalKind.NAVIGATE_BACK) {
            return AgentDecision(
                thought = "Navigating back via global BACK action.",
                action = AgentAction.Back,
                rawOutput = "Thought: Pressing back.\nAction: back()",
                activeSubGoal = activeSubGoal.description
            )
        }

        if (activeSubGoal?.kind == SubGoalKind.SCREENSHOT) {
            return AgentDecision(
                thought = "Capturing screen content.",
                action = AgentAction.Screenshot,
                rawOutput = "Thought: Taking screenshot.\nAction: screenshot()",
                activeSubGoal = activeSubGoal.description
            )
        }

        if (activeSubGoal?.kind == SubGoalKind.TOGGLE_SETTING) {
            val feature = activeSubGoal.target ?: ""
            if (feature.isNotEmpty() && lowerScreen.contains(feature.lowercase())) {
                return AgentDecision(
                    thought = "Toggling setting '$feature'.",
                    action = AgentAction.Tap(feature, null, null),
                    rawOutput = "Thought: Toggling $feature.\nAction: tap(\"$feature\")",
                    activeSubGoal = activeSubGoal.description
                )
            }
        }

        if (currentSubGoalIndex >= subGoals.size - 1) {
            val lastSg2 = subGoals.last()
            val finalSatisfied = when (lastSg2.kind) {
                SubGoalKind.LAUNCH_APP -> isTargetAppForeground(lastSg2.target, foregroundPackage)
                SubGoalKind.SEARCH_QUERY -> lowerHistory.contains("type_text") &&
                    (lowerScreen.contains("results") || (lowerScreen.contains("views") && (lowerScreen.contains("video") || lowerScreen.contains("channel"))))
                SubGoalKind.OPEN_ITEM -> lowerHistory.contains("tap") &&
                    (lowerScreen.contains("player") || lowerScreen.contains("watch") || lowerScreen.contains("detail"))
                SubGoalKind.RETURN_HOME -> isHomeForeground(foregroundPackage)
                SubGoalKind.TOGGLE_SETTING -> settingIsEnabled(lastSg2.target, lowerScreen)
                else -> false
            }

            if (finalSatisfied) {
                val summaryText = "Successfully executed ${subGoals.size} sequential actions for: \"$overallGoal\""
                return AgentDecision(
                    thought = "All planned sub-goals have been executed and verified.",
                    action = AgentAction.FinishTask(summaryText),
                    rawOutput = "Thought: Task complete.\nAction: finish_task(\"$summaryText\")",
                    activeSubGoal = "Complete"
                )
            }
        }

        val lowerGoal = overallGoal.lowercase()
        if (lowerGoal.contains("scroll") || lowerScreen.contains("scrollable: true")) {
            return AgentDecision(
                thought = "Content extends past viewport. Scrolling down to reveal more elements.",
                action = AgentAction.Scroll("down"),
                rawOutput = "Thought: Scrolling down.\nAction: scroll(\"down\")",
                activeSubGoal = activeSubGoal?.description
            )
        }

        return AgentDecision(
            thought = "Reading active accessibility tree to identify next interaction.",
            action = AgentAction.ReadScreen,
            rawOutput = "Thought: Reading screen hierarchy.\nAction: read_screen()",
            activeSubGoal = activeSubGoal?.description
        )
    }

    fun extractForegroundPackage(screenSummary: String): String? {
        return Regex("(?im)^\\s*Active Package:\\s*([A-Za-z0-9._]+)")
            .find(screenSummary)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "unknown" }
    }

    fun isTargetAppForeground(target: String?, foregroundPackage: String?): Boolean {
        if (target.isNullOrBlank() || foregroundPackage.isNullOrBlank()) return false
        val targetKey = target.lowercase().replace(Regex("[^a-z0-9]"), "")
        val observed = foregroundPackage.lowercase()
        val aliases = mapOf(
            "youtube" to setOf("com.google.android.youtube"),
            "chrome" to setOf("com.android.chrome"),
            "browser" to setOf("com.android.chrome"),
            "settings" to setOf("com.android.settings"),
            "whatsapp" to setOf("com.whatsapp"),
            "maps" to setOf("com.google.android.apps.maps"),
            "spotify" to setOf("com.spotify.music"),
            "gmail" to setOf("com.google.android.gm"),
            "playstore" to setOf("com.android.vending")
        )
        val knownPackages = aliases[targetKey]
        return if (knownPackages != null) observed in knownPackages else {
            observed.contains(targetKey) && !observed.startsWith("com.example.")
        }
    }

    fun isHomeForeground(foregroundPackage: String?): Boolean {
        return ForegroundPackageVerifier.isHomePackage(foregroundPackage)
    }

    fun settingIsEnabled(target: String?, lowerScreen: String): Boolean {
        if (target.isNullOrBlank()) return false
        return lowerScreen.lineSequence().any { line ->
            line.contains(target.lowercase()) &&
                (line.contains("[checked]") ||
                    Regex("checked\\s*[:=]\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
    }

    /**
     * True when the screen summary exposes an editable/input control. Recognises
     * both the structured hints ([Input], "editable: true", EditText class) and the
     * plain edittext marker, so it works against real accessibility summaries.
     */
    fun isEditableFieldVisible(screenSummary: String): Boolean {
        val lower = screenSummary.lowercase()
        return lower.contains("[input]") ||
            lower.contains("edittext") ||
            Regex("editable\\s*[:=]\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(screenSummary)
    }
}
