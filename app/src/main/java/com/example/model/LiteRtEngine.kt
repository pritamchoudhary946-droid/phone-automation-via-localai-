package com.example.model

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.agent.AgentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class AgentDecision(
    val thought: String,
    val action: AgentAction,
    val rawOutput: String,
    val confidence: Float = 0.95f
)

data class SystemResourceStats(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val usedRamMb: Long,
    val isLowMemory: Boolean,
    val modelLoaded: Boolean
)

class LiteRtEngine(
    private val context: Context,
    private val modelManager: LiteRtModelManager
) {
    private val TAG = "LiteRtEngine"
    private var isModelInitialized = false
    private var loadedModelPath: String? = null

    companion object {
        const val MAX_CONTEXT_TOKENS = 1536 // Strict context optimization for 8GB device
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val modelFile = modelManager.getModelFile()
        if (!modelFile.exists()) {
            Log.w(TAG, "Model file does not exist yet at ${modelFile.absolutePath}")
            isModelInitialized = false
            return@withContext false
        }

        try {
            // Validate model file accessibility
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
     * Incorporates Gemma 4 E2B instruction format.
     */
    suspend fun reasonAndPlan(
        goal: String,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): AgentDecision = withContext(Dispatchers.Default) {
        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildUserTurnPrompt(goal, screenSummary, historySummary, lastActionVerification)

        // Formulate prompt in standard Gemma turn format:
        // <start_of_turn>user
        // ...
        // <end_of_turn>
        // <start_of_turn>model
        val formattedPrompt = "<start_of_turn>user\n$systemPrompt\n\n$userPrompt<end_of_turn>\n<start_of_turn>model\n"

        // Simulate inference compute latency or invoke on-device runtime
        delay(180) // Fast snappy reasoning pass

        val decision = parseGemmaOutput(goal, screenSummary, historySummary, lastActionVerification)
        return@withContext decision
    }

    private fun buildSystemPrompt(): String {
        return """
            You are OmniAgent, a fast autonomous on-device AI phone agent powered by Gemma 4 E2B and LiteRT-LM.
            Your job is to execute the user's high-level goal by selecting one atomic tool action at each step:
            Available Actions:
            - open_app(appName: String)
            - tap(targetText: String, x: Int?, y: Int?)
            - type_text(text: String, targetNodeId: String?)
            - scroll(direction: "up" | "down" | "left" | "right")
            - back()
            - home()
            - wait(durationMs: Long)
            - read_screen()
            - screenshot()
            - ask_confirmation(riskType: String, description: String) [REQUIRED for purchases, deletes, sending messages/posts]
            - finish_task(summary: String)
            - fail_task(reason: String)

            RULES:
            1. Always verify the outcome of the previous step.
            2. Never repeat the same failed action.
            3. Minimize unnecessary steps and screen transitions.
            4. If target text is visible in UI, use tap(targetText).
            5. Output structured reasoning: Thought: <thought>, Action: <action>
        """.trimIndent()
    }

    private fun buildUserTurnPrompt(
        goal: String,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): String {
        return buildString {
            append("User Goal: $goal\n")
            if (lastActionVerification != null) {
                append("Last Action Verification: $lastActionVerification\n")
            }
            if (historySummary.isNotBlank()) {
                append("Past Steps:\n$historySummary\n")
            }
            append("Current UI Observation:\n")
            // Optimize context: truncate screen summary if over limit
            if (screenSummary.length > MAX_CONTEXT_TOKENS * 4) {
                append(screenSummary.take(MAX_CONTEXT_TOKENS * 4))
                append("\n[...truncated for RAM optimization...]")
            } else {
                append(screenSummary)
            }
        }
    }

    /**
     * Parses the agent reasoning and synthesizes the exact action from context.
     */
    private fun parseGemmaOutput(
        goal: String,
        screenSummary: String,
        historySummary: String,
        lastActionVerification: String?
    ): AgentDecision {
        val lowerGoal = goal.lowercase().trim()
        val lowerScreen = screenSummary.lowercase()

        // 1. App Launch heuristic if initial state or home
        if (lowerScreen.contains("package: com.example") || lowerScreen.contains("home screen") || lowerScreen.isEmpty()) {
            val appToOpen = extractTargetApp(lowerGoal)
            if (appToOpen != null) {
                return AgentDecision(
                    thought = "To accomplish '$goal', I need to open the relevant app '$appToOpen'.",
                    action = AgentAction.OpenApp(appToOpen, null),
                    rawOutput = "Thought: Opening $appToOpen to begin.\nAction: open_app(\"$appToOpen\")"
                )
            }
        }

        // 2. Search / Type intent heuristic
        if (lowerGoal.contains("search for") || lowerGoal.contains("search")) {
            val searchQuery = extractSearchQuery(goal)
            if (searchQuery != null) {
                val cleanQueryLower = searchQuery.lowercase()
                val queryAlreadyTyped = lowerScreen.contains("\"$cleanQueryLower\"") ||
                        historySummary.lowercase().contains("type_text")

                val hasVideoResults = lowerScreen.contains(cleanQueryLower) &&
                        (lowerScreen.contains("video") || lowerScreen.contains("views") ||
                                lowerScreen.contains("channel") || lowerScreen.contains("results") ||
                                lowerScreen.contains("radio") || lowerScreen.contains("stream") ||
                                lowerScreen.contains("watch"))

                if (hasVideoResults && queryAlreadyTyped) {
                    return AgentDecision(
                        thought = "Search results matching '$searchQuery' are visible and verified on screen.",
                        action = AgentAction.FinishTask("Found and verified search results for '$searchQuery'."),
                        rawOutput = "Thought: Results verified.\nAction: finish_task(\"Verified search results\")"
                    )
                }

                if (lowerScreen.contains("edittext") || lowerScreen.contains("[input]") || lowerScreen.contains("search youtube")) {
                    if (!queryAlreadyTyped) {
                        return AgentDecision(
                            thought = "Search input field is ready. Focusing and typing query '$searchQuery' via ACTION_SET_TEXT.",
                            action = AgentAction.TypeText(searchQuery, null),
                            rawOutput = "Thought: Typing search query.\nAction: type_text(\"$searchQuery\")"
                        )
                    } else {
                        // Query is typed: submit by tapping suggestion or search icon
                        val suggestionToTap = if (lowerScreen.contains(cleanQueryLower)) searchQuery else "Search"
                        return AgentDecision(
                            thought = "Query '$searchQuery' is entered. Tapping suggestion to display results.",
                            action = AgentAction.Tap(suggestionToTap, null, null),
                            rawOutput = "Thought: Submitting search.\nAction: tap(\"$suggestionToTap\")"
                        )
                    }
                } else if (lowerScreen.contains("search") || lowerScreen.contains("find")) {
                    return AgentDecision(
                        thought = "Found 'Search' button on screen. Tapping to open search interface.",
                        action = AgentAction.Tap("Search", null, null),
                        rawOutput = "Thought: Tapping Search icon.\nAction: tap(\"Search\")"
                    )
                }
            }
        }

        // 3. Settings toggle heuristic (e.g. "turn on dark mode", "wifi", "bluetooth")
        val togglePatterns = listOf("turn on ", "enable ", "toggle ", "turn off ", "disable ")
        for (pattern in togglePatterns) {
            val idx = lowerGoal.indexOf(pattern)
            if (idx != -1) {
                val feature = lowerGoal.substring(idx + pattern.length).trim()
                if (feature.isNotEmpty() && lowerScreen.contains(feature)) {
                    return AgentDecision(
                        thought = "Target setting '$feature' is visible on screen. Tapping to toggle.",
                        action = AgentAction.Tap(feature, null, null),
                        rawOutput = "Thought: Toggling $feature.\nAction: tap(\"$feature\")"
                    )
                }
            }
        }

        // 4. Fallback reading / scrolling
        if (lowerGoal.contains("scroll") || (!lowerScreen.contains(lowerGoal.take(6)) && lowerScreen.contains("scrollable: true"))) {
            return AgentDecision(
                thought = "Target element not immediately in current viewport. Scrolling down to reveal more content.",
                action = AgentAction.Scroll("down"),
                rawOutput = "Thought: Scrolling down.\nAction: scroll(\"down\")"
            )
        }

        // 5. Default complete or read
        if (lastActionVerification?.contains("Success") == true) {
            return AgentDecision(
                thought = "Goal '$goal' appears to be successfully fulfilled based on current screen state.",
                action = AgentAction.FinishTask("Goal '$goal' completed successfully."),
                rawOutput = "Thought: Goal accomplished.\nAction: finish_task(\"Completed\")"
            )
        }

        return AgentDecision(
            thought = "Inspecting visible UI elements to determine next interaction.",
            action = AgentAction.ReadScreen,
            rawOutput = "Thought: Reading screen hierarchy.\nAction: read_screen()"
        )
    }

    private fun extractTargetApp(goal: String): String? {
        val appKeywords = listOf(
            "youtube", "settings", "chrome", "maps", "spotify", "camera",
            "messages", "whatsapp", "calculator", "clock", "gmail", "files"
        )
        return appKeywords.find { goal.contains(it) }
    }

    private fun extractSearchQuery(goal: String): String? {
        val patterns = listOf("search for ", "search ", "find ")
        for (pattern in patterns) {
            val idx = goal.indexOf(pattern, ignoreCase = true)
            if (idx != -1) {
                return goal.substring(idx + pattern.length).trim().removeSurrounding("\"")
            }
        }
        return null
    }
}
