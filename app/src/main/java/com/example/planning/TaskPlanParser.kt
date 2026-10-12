package com.example.planning

import com.example.agent.AgentAction
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict parser that turns the local model's JSON plan into a validated [TaskPlan].
 *
 * Rejects anything unsafe or malformed instead of executing it:
 * - Unknown action names (only the approved tool catalog is accepted).
 * - Invalid parameters (delegated to [ToolRegistry.validateAction]).
 * - Empty plans and out-of-order/duplicate step identifiers.
 * - Impossible dependencies (referencing unknown or forward-only step ids).
 * The model can never inject arbitrary code or arbitrary action names.
 */
class TaskPlanParser(private val toolRegistry: ToolRegistry = ToolRegistry()) {

    fun parse(rawModelOutput: String, goal: String): PlanParseResult {
        val root = parseRootObject(rawModelOutput)
            ?: return PlanParseResult.Invalid("No JSON object found in model output", rawModelOutput)

        val stepsArray: JSONArray = root.optJSONArray("steps")
            ?: return PlanParseResult.Invalid("Plan is missing a 'steps' array", rawModelOutput)

        if (stepsArray.length() == 0) {
            return PlanParseResult.Invalid("Plan contains no steps", rawModelOutput)
        }
        if (stepsArray.length() > MAX_STEPS) {
            return PlanParseResult.Invalid("Plan exceeds the maximum of $MAX_STEPS steps", rawModelOutput)
        }

        val parsedSteps = mutableListOf<PlanStep>()
        val seenIds = mutableSetOf<Int>()

        for (i in 0 until stepsArray.length()) {
            // 6.18: org.json is lenient and turns a trailing comma into a `null` array
            // element. Skip that artifact rather than failing the whole plan; a real
            // non-object element (e.g. a bare string) is still rejected below.
            val stepObj = stepsArray.optJSONObject(i) ?: run {
                if (stepsArray.isNull(i) || stepsArray.opt(i) == null) continue
                return PlanParseResult.Invalid("Step $i is not a JSON object", rawModelOutput)
            }

            val id = stepObj.optInt("id", i + 1)
            if (!seenIds.add(id)) {
                return PlanParseResult.Invalid("Duplicate step id $id", rawModelOutput)
            }

            val rawAction = stepObj.optString("action", "").trim().lowercase()
            if (rawAction.isBlank()) {
                return PlanParseResult.Invalid("Step $id is missing an action", rawModelOutput)
            }
            if (toolRegistry.getTool(rawAction) == null) {
                return PlanParseResult.Invalid("Unknown action '$rawAction' is not in the approved catalog", rawModelOutput)
            }

            val params = stepObj.optJSONObject("params") ?: JSONObject()
            val action = try {
                mapAction(rawAction, params)
            } catch (e: Exception) {
                return PlanParseResult.Invalid("Step $id has invalid parameters: ${e.message}", rawModelOutput)
            }

            when (val validation = toolRegistry.validateAction(action)) {
                is ToolValidationResult.Invalid ->
                    return PlanParseResult.Invalid("Step $id rejected: ${validation.reason}", rawModelOutput)
                is ToolValidationResult.Valid -> Unit
            }

            val verification = parseVerification(stepObj.optString("verify", "NONE"))
            val dependsOn = parseDependencies(stepObj.optJSONArray("dependsOn"))
            val captures = parseCaptures(stepObj.optJSONArray("captures"))

            // A capture only means something on a step whose action can read back text;
            // on any other action the captured value would always be empty, so reject
            // the plan rather than silently drop the variable.
            if (captures.isNotEmpty() && !producesReadableText(action)) {
                return PlanParseResult.Invalid(
                    "Step $id declares captures $captures but action '$rawAction' produces no readable text",
                    rawModelOutput
                )
            }

            parsedSteps.add(
                PlanStep(
                    id = id,
                    sequence = stepObj.optInt("seq", i + 1),
                    description = stepObj.optString("desc", "Step $id").ifBlank { "Step $id" },
                    action = action,
                    rawActionName = rawAction,
                    expectedPostcondition = stepObj.optString("expect", ""),
                    verification = verification,
                    dependsOn = dependsOn,
                    maxRetries = stepObj.optInt("maxRetries", 1).coerceIn(0, 3),
                    captures = captures
                )
            )
        }

        // Impossible references: a dependency must exist and must point to an earlier step.
        for (step in parsedSteps) {
            for (dep in step.dependsOn) {
                val target = parsedSteps.find { it.id == dep }
                    ?: return PlanParseResult.Invalid("Step ${step.id} depends on unknown step $dep", rawModelOutput)
                if (target.sequence >= step.sequence) {
                    return PlanParseResult.Invalid("Step ${step.id} depends on a non-earlier step $dep", rawModelOutput)
                }
            }
        }

        val ordered = parsedSteps.sortedBy { it.sequence }
        return PlanParseResult.Success(TaskPlan(goal = goal, steps = ordered, fromModel = true))
    }

    /**
     * 6.18: tolerant root extraction. The small on-device model often wraps its JSON
     * in prose or code fences, and emits trailing commas or single quotes. Rather than
     * failing the whole task on such a near-miss, strip fences/prose, extract the first
     * balanced object, and retry after a light normalisation. Returns null only when no
     * usable object can be recovered at all.
     */
    private fun parseRootObject(rawModelOutput: String): JSONObject? {
        val text = rawModelOutput
            .replace("```json", " ")
            .replace("```", " ")
            .trim()
        val candidate = extractJsonObject(text) ?: return null
        return try {
            JSONObject(candidate)
        } catch (e: Exception) {
            try {
                JSONObject(normalizeJson(candidate))
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Repairs the two most common small-model JSON slips: trailing commas and single quotes. */
    private fun normalizeJson(input: String): String {
        var out = input.trim()
        // Trailing commas before } or ].
        out = out.replace(Regex(",\\s*([}\\]])"), "$1")
        // Single-quoted strings/keys -> double-quoted (only when unescaped in place).
        out = out.replace(Regex("'([^'\"\\\\]*)'"), "\"$1\"")
        return out
    }

    /** Extracts the first balanced top-level JSON object from surrounding prose/code fences. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * Maps a persisted (raw) action name + params into an approved [AgentAction].
     * Used when replaying a saved skill: returns null for an unknown tool or invalid
     * parameters so a stored plan can never introduce a new executable surface.
     */
    fun mapPersistedAction(rawAction: String, params: JSONObject): AgentAction? {
        val name = rawAction.trim().lowercase()
        if (name.isBlank() || toolRegistry.getTool(name) == null) return null
        return try {
            mapAction(name, params)
        } catch (e: Exception) {
            null
        }
    }

    private fun mapAction(rawAction: String, params: JSONObject): AgentAction {
        return when (rawAction) {
            ToolRegistry.TOOL_LAUNCH_APP -> {
                val query = str(params, "appQuery", "app", "appName", "application")
                val pkg = str(params, "packageName", "package", "pkg").ifBlank { null }
                AgentAction.OpenApp(query, pkg)
            }
            ToolRegistry.TOOL_READ_SCREEN -> AgentAction.ReadScreen
            ToolRegistry.TOOL_TAP_ELEMENT -> {
                val text = str(params, "targetText", "element", "text", "target", "label").ifBlank { null }
                val x = if (params.has("x")) params.optInt("x") else null
                val y = if (params.has("y")) params.optInt("y") else null
                AgentAction.Tap(text, x, y)
            }
            ToolRegistry.TOOL_ENTER_TEXT -> {
                val text = str(params, "text", "query", "value", "message")
                val nodeId = str(params, "targetNodeId", "nodeId", "id").ifBlank { null }
                AgentAction.TypeText(text, nodeId)
            }
            ToolRegistry.TOOL_CLEAR_TEXT ->
                AgentAction.ClearText(str(params, "targetText", "text", "element", "label").ifBlank { null })
            ToolRegistry.TOOL_SUBMIT ->
                AgentAction.Submit(str(params, "targetText", "target", "element").ifBlank { null })
            ToolRegistry.TOOL_OPEN_URL -> AgentAction.OpenUrl(str(params, "url", "uri", "link"))
            ToolRegistry.TOOL_COPY_TEXT ->
                AgentAction.CopyText(str(params, "targetText", "text", "element").ifBlank { null })
            ToolRegistry.TOOL_PASTE_TEXT ->
                AgentAction.PasteText(str(params, "targetText", "text", "element").ifBlank { null })
            ToolRegistry.TOOL_DRAG -> AgentAction.Drag(
                fromText = str(params, "fromText", "from", "start"),
                toText = str(params, "toText", "to", "end")
            )
            ToolRegistry.TOOL_DEEP_LINK -> AgentAction.DeepLink(str(params, "uri", "url", "link"))
            ToolRegistry.TOOL_READ_PAGE_CONTENT -> AgentAction.ReadPageContent
            ToolRegistry.TOOL_WEB_SEARCH -> AgentAction.WebSearch(str(params, "query", "text", "q"))
            ToolRegistry.TOOL_READ_URL -> AgentAction.ReadUrl(str(params, "url", "uri", "link"))
            ToolRegistry.TOOL_OPEN_SEARCH -> {
                val query = str(params, "query", "text", "q").ifBlank { null }
                AgentAction.OpenSearch(query)
            }
            ToolRegistry.TOOL_OPEN_ITEM -> AgentAction.OpenItem(str(params, "targetText", "text", "element", "label"))
            ToolRegistry.TOOL_SCROLL -> AgentAction.Scroll(str(params, "direction", "dir").ifBlank { "down" })
            ToolRegistry.TOOL_LONG_PRESS -> AgentAction.LongPress(
                targetText = str(params, "targetText", "text", "element", "label").ifBlank { null },
                durationMs = params.optLong("durationMs", 700L)
            )
            ToolRegistry.TOOL_SCROLL_UNTIL_FOUND -> AgentAction.ScrollUntilFound(
                targetText = str(params, "targetText", "text", "element", "label"),
                direction = str(params, "direction", "dir").ifBlank { "down" },
                maxSwipes = params.optInt("maxSwipes", 6)
            )
            ToolRegistry.TOOL_FIND_ELEMENT -> AgentAction.FindElement(
                targetText = str(params, "targetText", "text", "element", "label"),
                direction = str(params, "direction", "dir").ifBlank { "down" },
                maxSwipes = params.optInt("maxSwipes", 6)
            )
            ToolRegistry.TOOL_READ_TEXT -> AgentAction.ReadText
            ToolRegistry.TOOL_WAIT_FOR_TEXT -> AgentAction.WaitForText(
                targetText = str(params, "targetText", "text", "element", "label"),
                timeoutMs = params.optLong("timeoutMs", 5_000L)
            )
            ToolRegistry.TOOL_ADJUST_VOLUME -> AgentAction.AdjustVolume(
                stream = str(params, "stream", "channel", "type").ifBlank { "media" },
                steps = params.optInt("steps", params.optInt("delta", 1)),
                mute = params.optBoolean("mute", false)
            )
            ToolRegistry.TOOL_PLAY_MEDIA -> AgentAction.PlayMedia(
                transport = str(params, "transport", "command", "action").ifBlank { "toggle" }
            )
            ToolRegistry.TOOL_SET_RINGER_MODE -> AgentAction.SetRingerMode(
                mode = str(params, "mode", "state", "ringer").ifBlank { "silent" }
            )
            ToolRegistry.TOOL_SET_ALARM -> AgentAction.SetAlarm(
                hour = params.optInt("hour", 7),
                minute = params.optInt("minute", 0),
                label = str(params, "label", "message", "name").ifBlank { null },
                days = parseAlarmDays(params.optJSONArray("days"))
            )
            ToolRegistry.TOOL_SET_TIMER -> AgentAction.SetTimer(
                seconds = parseTimerSeconds(params),
                label = str(params, "label", "message", "name").ifBlank { null }
            )
            ToolRegistry.TOOL_READ_NOTIFICATIONS -> AgentAction.ReadNotifications
            ToolRegistry.TOOL_READ_DEVICE_STATUS -> AgentAction.ReadDeviceStatus
            ToolRegistry.TOOL_OPEN_NOTIFICATIONS -> AgentAction.OpenNotifications
            ToolRegistry.TOOL_OPEN_QUICK_SETTINGS -> AgentAction.OpenQuickSettings
            ToolRegistry.TOOL_OPEN_RECENTS -> AgentAction.PressRecents
            ToolRegistry.TOOL_NAVIGATE_BACK -> AgentAction.Back
            ToolRegistry.TOOL_NAVIGATE_HOME -> AgentAction.Home
            ToolRegistry.TOOL_WAIT -> AgentAction.Wait(params.optLong("durationMs", 800L))
            ToolRegistry.TOOL_SCREENSHOT -> AgentAction.Screenshot
            ToolRegistry.TOOL_REPORT_STATUS -> {
                val summary = str(params, "summary", "text", "message").ifBlank { "Goal achieved" }
                // Only an explicit success (or a bare report, whose completion is still
                // independently verified by the orchestrator) may finish the task. Any
                // other explicit status — "failure", "error", "partial" — must not be
                // silently upgraded to success.
                val status = params.optString("status", "").trim()
                if (status.isBlank() || status.equals("success", ignoreCase = true)) {
                    AgentAction.FinishTask(summary)
                } else {
                    AgentAction.FailTask(
                        params.optString("reason", "").ifBlank { "report_status reported '$status'" }
                    )
                }
            }
            else -> throw IllegalArgumentException("Unsupported action '$rawAction'")
        }
    }

    /**
     * 6.19: value coercion + aliases. The small model frequently emits a param under a
     * near-miss key, or as a number/boolean instead of a string. Accept the first
     * non-blank alias and coerce any scalar to its string form so a near-miss validates
     * instead of being rejected wholesale. Non-scalar values are ignored.
     */
    private fun str(params: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!params.has(key)) continue
            when (val value = params.opt(key)) {
                null -> continue
                is String -> if (value.isNotBlank()) return value
                is Number, is Boolean -> return value.toString()
                else -> continue
            }
        }
        return ""
    }

    private fun parseVerification(value: String): VerificationMethod {
        return when (value.trim().uppercase()) {
            "FOREGROUND_PACKAGE", "FOREGROUND", "PACKAGE" -> VerificationMethod.FOREGROUND_PACKAGE
            "SCREEN_CHANGED", "SCREEN" -> VerificationMethod.SCREEN_CHANGED
            "TEXT_PRESENT", "TEXT" -> VerificationMethod.TEXT_PRESENT
            "RESULTS_PRESENT", "RESULTS" -> VerificationMethod.RESULTS_PRESENT
            "HOME_FOREGROUND", "HOME" -> VerificationMethod.HOME_FOREGROUND
            "PLAYBACK", "PLAYING" -> VerificationMethod.PLAYBACK
            "CONTENT", "CONTENT_PRESENT" -> VerificationMethod.CONTENT
            else -> VerificationMethod.NONE
        }
    }

    private fun parseDependencies(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optInt(it, -1).takeIf { v -> v >= 0 } }
    }

    /**
     * Variable names a step populates from the text its read action captured
     * (plan 9.4). Names are normalised to the same form [PlanVariableStore] uses,
     * de-duplicated and bounded, so a malformed model output cannot mint an
     * unbounded number of variables.
     */
    private fun parseCaptures(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val names = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val raw = array.optString(i, "")
            val name = PlanVariableStore.normalize(raw)
            if (name.isNotEmpty()) names.add(name)
            if (names.size >= MAX_CAPTURES) break
        }
        return names.toList()
    }

    /** True for the approved actions that can return a bounded text digest ([ActionResult.readText]). */
    private fun producesReadableText(action: AgentAction): Boolean =
        action is AgentAction.ReadText || action is AgentAction.ReadPageContent

    /**
     * Weekday constants for a repeating alarm, bounded to the 1..7 `Calendar` range and
     * de-duplicated so a malformed model output cannot request an out-of-range day.
     */
    private fun parseAlarmDays(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val days = LinkedHashSet<Int>()
        for (i in 0 until array.length()) {
            val day = array.optInt(i, -1)
            if (day in 1..7) days.add(day)
        }
        return days.toList()
    }

    /**
     * Resolves a countdown length to seconds. The model may emit the duration as
     * `seconds`, `duration`/`durationSeconds`, or as `minutes` (a natural phrasing like
     * "set a 10-minute timer" is often expressed in minutes), so accept the first present
     * form. A missing/blank duration falls back to the tool default (5 minutes); the tool
     * boundary still rejects a non-positive or over-long value, so a bad value cannot
     * reach the executor as a fake success.
     */
    private fun parseTimerSeconds(params: JSONObject): Int {
        for (key in listOf("seconds", "durationSeconds", "duration")) {
            val value = params.optInt(key, Int.MIN_VALUE)
            if (value != Int.MIN_VALUE) return value
        }
        val minutes = params.optInt("minutes", Int.MIN_VALUE)
        if (minutes != Int.MIN_VALUE) return minutes * 60
        return 5 * 60
    }

    companion object {
        const val MAX_STEPS = 12
        const val MAX_CAPTURES = 4
    }
}
