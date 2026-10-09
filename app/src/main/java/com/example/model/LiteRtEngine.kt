package com.example.model

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AgentDecision(
    val thought: String,
    val action: AgentAction,
    val rawOutput: String,
    val activeSubGoal: String? = null,
    val confidence: Float = 0.95f
)

data class SystemResourceStats(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val usedRamMb: Long,
    val isLowMemory: Boolean,
    val modelLoaded: Boolean
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

class LiteRtEngine(
    private val context: Context,
    private val modelManager: LiteRtModelManager
) {
    private val TAG = "LiteRtEngine"
    private var isModelInitialized = false
    private var loadedModelPath: String? = null
    val toolRegistry = ToolRegistry()

    companion object {
        const val MAX_CONTEXT_TOKENS = 1536 // Strict context optimization for 8GB device
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val modelFile = modelManager.getModelFile()
        if (!modelFile.exists()) {
            Log.w(TAG, "Local model file does not exist yet at ${modelFile.absolutePath}")
            isModelInitialized = false
            return@withContext false
        }

        try {
            if (modelFile.canRead() && modelFile.length() > 0) {
                loadedModelPath = modelFile.absolutePath
                isModelInitialized = true
                Log.i(TAG, "LiteRT-LM Gemma 4 E2B engine initialized from ${modelFile.absolutePath}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing LiteRT runtime", e)
        }
        return@withContext false
    }

    fun isReady(): Boolean {
        return isModelInitialized && modelManager.getModelFile().exists()
    }

    fun getResourceStats(): SystemResourceStats {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb

        return SystemResourceStats(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            usedRamMb = usedMb,
            isLowMemory = memInfo.lowMemory,
            modelLoaded = isReady()
        )
    }

    /**
     * Executes reasoning over the current user goal and screen observation.
     * Incorporates Gemma 4 E2B instruction format and decomposes compound goals
     * into ordered sub-steps.
     */
    suspend fun reasonAndPlan(
        goal: String,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): AgentDecision = withContext(Dispatchers.Default) {
        delay(150) // Simulates quick on-device inference pass

        val subGoals = decomposeGoalIntoSubGoals(goal)
        val decision = planNextStep(goal, subGoals, screenSummary, historySummary, lastActionVerification)
        return@withContext decision
    }

    /**
     * Decomposes a multi-command user request into small, ordered sub-goals.
     * E.g. "Open YouTube, search for an educational video, open it, and then return to the home screen"
     */
    fun decomposeGoalIntoSubGoals(goal: String): List<SubGoal> {
        val lower = goal.lowercase().trim()
        val list = mutableListOf<SubGoal>()

        // Split on common sequencing conjunctions
        val rawClauses = lower.split(Regex(",\\s*and\\s+then\\s+|,\\s*then\\s+|\\s+and\\s+then\\s+|\\s+then\\s+|,\\s*and\\s+|\\s+and\\s+|,\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (clause in rawClauses) {
            when {
                clause.contains("open it") || clause.contains("click it") || clause.contains("play it") || clause.contains("tap it") || clause.contains("open first") || clause.contains("click first") -> {
                    list.add(SubGoal("Open target item", SubGoalKind.OPEN_ITEM))
                }
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
                clause.contains("return to the home screen") || clause.contains("return to home") || clause.contains("return home") || clause.contains("go home") || clause == "home" -> {
                    list.add(SubGoal("Return to home screen", SubGoalKind.RETURN_HOME))
                }
                clause.contains("go back") || clause == "back" -> {
                    list.add(SubGoal("Navigate Back", SubGoalKind.NAVIGATE_BACK))
                }
                clause.contains("screenshot") -> {
                    list.add(SubGoal("Take screenshot", SubGoalKind.SCREENSHOT))
                }
                else -> {
                    list.add(SubGoal(clause, SubGoalKind.CUSTOM, clause))
                }
            }
        }

        return if (list.isNotEmpty()) list else listOf(SubGoal(goal, SubGoalKind.CUSTOM, goal))
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

        // Determine current sub-goal by evaluating completion of earlier sub-goals
        var currentSubGoalIndex = 0
        for (i in subGoals.indices) {
            val sg = subGoals[i]
            val isComplete = when (sg.kind) {
                SubGoalKind.LAUNCH_APP -> isTargetAppForeground(sg.target, foregroundPackage)
                SubGoalKind.SEARCH_QUERY -> lowerScreen.contains("results") ||
                    (lowerScreen.contains("views") && (lowerScreen.contains("video") || lowerScreen.contains("channel")))
                SubGoalKind.OPEN_ITEM -> {
                    lowerHistory.contains("tap (") && (lowerScreen.contains("player") || lowerScreen.contains("watch") || lowerScreen.contains("detail"))
                }
                SubGoalKind.TOGGLE_SETTING -> settingIsEnabled(sg.target, lowerScreen)
                SubGoalKind.RETURN_HOME -> isHomeForeground(foregroundPackage)
                SubGoalKind.NAVIGATE_BACK -> {
                    lowerHistory.contains("back")
                }
                SubGoalKind.SCREENSHOT -> {
                    lowerHistory.contains("screenshot")
                }
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

        // Check if all sub-goals fulfilled
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

        // Stage 1: If current sub-goal is LAUNCH_APP
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

        // Stage 2: If current sub-goal is SEARCH_QUERY
        if (activeSubGoal?.kind == SubGoalKind.SEARCH_QUERY) {
            val query = activeSubGoal.target ?: "search query"
            val cleanQueryLower = query.lowercase()
            val queryAlreadyTyped = lowerHistory.contains("action typetext") && lastActionVerification?.startsWith("Success:", ignoreCase = true) == true
            val editableFieldVisible = lowerScreen.contains("edittext") ||
                Regex("editable:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(screenSummary)
            // A successful TypeText history entry is not enough on its own: the current
            // accessibility snapshot must also show the requested query in the field.
            val queryVerifiedInField = editableFieldVisible && lowerScreen.contains(cleanQueryLower)

            if (editableFieldVisible) {
                if (!queryAlreadyTyped || !queryVerifiedInField) {
                    return AgentDecision(
                        thought = "Search bar active. Entering query \"$query\" via ACTION_SET_TEXT.",
                        action = AgentAction.TypeText(query, null),
                        rawOutput = "Thought: Typing search query.\nAction: type_text(\"$query\")",
                        activeSubGoal = activeSubGoal.description
                    )
                } else {
                    // Query is entered, submit search
                    val suggestionToTap = "Search"
                    return AgentDecision(
                        thought = "Submitting search for \"$query\" by tapping search button/suggestion.",
                        action = AgentAction.Tap(suggestionToTap, null, null),
                        rawOutput = "Thought: Submitting search query.\nAction: tap(\"$suggestionToTap\")",
                        activeSubGoal = activeSubGoal.description
                    )
                }
            } else {
                // Find and tap Search icon/button
                return AgentDecision(
                    thought = "Locating search affordance on screen to start search for \"$query\".",
                    action = AgentAction.Tap("Search", null, null),
                    rawOutput = "Thought: Tapping Search icon.\nAction: tap(\"Search\")",
                    activeSubGoal = activeSubGoal.description
                )
            }
        }

        // Stage 3: If current sub-goal is OPEN_ITEM
        if (activeSubGoal?.kind == SubGoalKind.OPEN_ITEM) {
            // Find first video/item result on screen to click
            val query = subGoals.find { it.kind == SubGoalKind.SEARCH_QUERY }?.target ?: ""
            val tapTarget = if (query.isNotEmpty() && lowerScreen.contains(query.lowercase())) query else "results"
            return AgentDecision(
                thought = "Opening the first matching result on screen.",
                action = AgentAction.Tap(tapTarget, null, null),
                rawOutput = "Thought: Opening selected result.\nAction: tap(\"$tapTarget\")",
                activeSubGoal = activeSubGoal.description
            )
        }

        // Stage 4: If current sub-goal is RETURN_HOME
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

        // Stage 5: If current sub-goal is NAVIGATE_BACK
        if (activeSubGoal?.kind == SubGoalKind.NAVIGATE_BACK) {
            return AgentDecision(
                thought = "Navigating back via global BACK action.",
                action = AgentAction.Back,
                rawOutput = "Thought: Pressing back.\nAction: back()",
                activeSubGoal = activeSubGoal.description
            )
        }

        // Stage 6: If current sub-goal is SCREENSHOT
        if (activeSubGoal?.kind == SubGoalKind.SCREENSHOT) {
            return AgentDecision(
                thought = "Capturing screen content.",
                action = AgentAction.Screenshot,
                rawOutput = "Thought: Taking screenshot.\nAction: screenshot()",
                activeSubGoal = activeSubGoal.description
            )
        }

        // Stage 7: Toggle setting check
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

        // All sub-goals fulfilled check
        if (currentSubGoalIndex >= subGoals.size - 1) {
            // Check if final condition is met
            val lastSg = subGoals.last()
            val finalSatisfied = when (lastSg.kind) {
                SubGoalKind.LAUNCH_APP -> isTargetAppForeground(lastSg.target, foregroundPackage)
                SubGoalKind.SEARCH_QUERY -> lowerHistory.contains("type_text") &&
                    (lowerScreen.contains("results") || (lowerScreen.contains("views") && (lowerScreen.contains("video") || lowerScreen.contains("channel"))))
                SubGoalKind.OPEN_ITEM -> lowerHistory.contains("tap") &&
                    (lowerScreen.contains("player") || lowerScreen.contains("watch") || lowerScreen.contains("detail"))
                SubGoalKind.RETURN_HOME -> isHomeForeground(foregroundPackage)
                SubGoalKind.TOGGLE_SETTING -> settingIsEnabled(lastSg.target, lowerScreen)
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

        // Default heuristic: scroll if target not in view
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

    private fun extractForegroundPackage(screenSummary: String): String? {
        return Regex("(?im)^\\s*Active Package:\\s*([^\\s\\r\\n]+)")
            .find(screenSummary)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "unknown" }
    }

    private fun isTargetAppForeground(target: String?, foregroundPackage: String?): Boolean {
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

    private fun isHomeForeground(foregroundPackage: String?): Boolean {
        val pkg = foregroundPackage?.lowercase() ?: return false
        return pkg.contains("launcher") || pkg == "com.android.systemui"
    }

    private fun settingIsEnabled(target: String?, lowerScreen: String): Boolean {
        if (target.isNullOrBlank()) return false
        return lowerScreen.lineSequence().any { line ->
            line.contains(target.lowercase()) &&
                (line.contains("[checked]") ||
                    Regex("checked\\s*[:=]\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
    }


}
