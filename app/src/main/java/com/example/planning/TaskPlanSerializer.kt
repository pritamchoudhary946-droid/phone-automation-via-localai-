package com.example.planning

import com.example.agent.AgentAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a validated [TaskPlan] to a compact JSON string for durable storage
 * (saved skills). Only the already-validated, non-executable fields are written:
 * the mapped action name, description and verification method — never free-form text.
 *
 * [fromJson] is the inverse used to replay a persisted skill through the normal
 * verified loop. It reconstructs only approved [AgentAction] variants via the same
 * mapping as live planning, so a stored plan cannot introduce a new action surface.
 */
object TaskPlanSerializer {

    fun toJson(plan: TaskPlan): String {
        val steps = JSONArray()
        for (step in plan.steps) {
            steps.put(
                JSONObject().apply {
                    put("id", step.id)
                    put("seq", step.sequence)
                    put("desc", step.description)
                    put("action", step.rawActionName)
                    put("verify", step.verification.name)
                    put("status", step.status.name)
                    step.action.toParams()?.let { put("params", it) }
                    if (step.expectedPostcondition.isNotBlank()) put("expect", step.expectedPostcondition)
                    step.action.toVerificationTarget()?.let { put("target", it) }
                    if (step.captures.isNotEmpty()) put("captures", JSONArray(step.captures))
                }
            )
        }
        return JSONObject()
            .put("goal", plan.goal)
            .put("fromModel", plan.fromModel)
            .put("steps", steps)
            .toString()
    }

    /** Reconstructs the parser's params object for a single action, if it carries any. */
    private fun AgentAction.toParams(): JSONObject? = when (this) {
        is AgentAction.OpenApp -> JSONObject().apply {
            put("appQuery", appQuery)
            if (!packageName.isNullOrBlank()) put("packageName", packageName)
        }
        is AgentAction.TypeText -> JSONObject().apply {
            put("text", text)
            if (!targetNodeId.isNullOrBlank()) put("targetNodeId", targetNodeId)
        }
        is AgentAction.ClearText -> targetText?.takeIf { it.isNotBlank() }?.let { JSONObject().put("targetText", it) }
        is AgentAction.OpenUrl -> JSONObject().put("url", url)
        is AgentAction.DeepLink -> JSONObject().put("uri", uri)
        is AgentAction.WebSearch -> JSONObject().put("query", query)
        is AgentAction.ReadUrl -> JSONObject().put("url", url)
        is AgentAction.OpenSearch -> query?.let { JSONObject().put("query", it) }
        is AgentAction.Scroll -> JSONObject().put("direction", direction)
        is AgentAction.Wait -> JSONObject().put("durationMs", durationMs)
        is AgentAction.Tap -> JSONObject().apply {
            if (!targetText.isNullOrBlank()) put("targetText", targetText)
            x?.let { put("x", it) }
            y?.let { put("y", it) }
        }
        is AgentAction.Drag -> JSONObject().apply {
            put("fromText", fromText); put("toText", toText)
        }
        is AgentAction.ScrollUntilFound -> JSONObject().apply {
            put("targetText", targetText)
            put("direction", direction)
            put("maxSwipes", maxSwipes)
        }
        is AgentAction.FindElement -> JSONObject().apply {
            put("targetText", targetText)
            put("direction", direction)
            put("maxSwipes", maxSwipes)
        }
        is AgentAction.AdjustVolume -> JSONObject().apply {
            put("stream", stream)
            put("steps", steps)
            if (mute) put("mute", true)
        }
        is AgentAction.SetRingerMode -> JSONObject().put("mode", mode)
        is AgentAction.SetAlarm -> JSONObject().apply {
            put("hour", hour)
            put("minute", minute)
            if (!label.isNullOrBlank()) put("label", label)
            if (days.isNotEmpty()) put("days", org.json.JSONArray(days))
        }
        is AgentAction.SetTimer -> JSONObject().apply {
            put("seconds", seconds)
            if (!label.isNullOrBlank()) put("label", label)
        }
        is AgentAction.AddCalendarEvent -> JSONObject().apply {
            put("title", title)
            put("dayOffset", dayOffset)
            put("startHour", startHour)
            put("startMinute", startMinute)
            put("durationMinutes", durationMinutes)
            if (!location.isNullOrBlank()) put("location", location)
        }
        is AgentAction.PlayMedia -> JSONObject().put("transport", transport)
        else -> null
    }

    /** Reconstructs the target label for actions that carry one outside their params. */
    private fun AgentAction.toVerificationTarget(): String? = when (this) {
        is AgentAction.Submit -> targetText
        is AgentAction.ClearText -> targetText
        is AgentAction.CopyText -> targetText
        is AgentAction.PasteText -> targetText
        is AgentAction.LongPress -> targetText
        is AgentAction.ScrollUntilFound -> targetText
        is AgentAction.FindElement -> targetText
        is AgentAction.WaitForText -> targetText
        is AgentAction.OpenItem -> targetText
        else -> null
    }

    /**
     * Rebuilds a [TaskPlan] from previously persisted skill JSON. Returns null when the
     * JSON is missing, malformed, or is the legacy "label-only" format (a `null` opts
     * marker) that did not record the action parameters, so a skill is only ever
     * replayed when it can be reconstructed without guessing a target.
     */
    fun fromJson(json: String, goalOverride: String? = null, parser: TaskPlanParser = TaskPlanParser()): TaskPlan? {
        if (json.isBlank()) return null
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        // Legacy skills persisted only the action name/status with no params: neither
        // JSONObject.NULL nor a string opts-marker is replayable, so refuse rather than
        // dispatch a targetless action.
        if (json.contains("\"opts\":null")) return null

        val stepsArray = root.optJSONArray("steps") ?: return null
        if (stepsArray.length() == 0) return null

        val steps = mutableListOf<PlanStep>()
        for (i in 0 until stepsArray.length()) {
            val raw = stepsArray.optJSONObject(i) ?: continue
            val rawAction = raw.optString("action", "").trim().lowercase()
            if (rawAction.isBlank()) return null
            val params = raw.optJSONObject("params") ?: JSONObject()
            raw.optString("target", "").trim().takeIf { it.isNotBlank() && !params.has("targetText") }
                ?.let { params.put("targetText", it) }
            val action = parser.mapPersistedAction(rawAction, params) ?: return null
            steps.add(
                PlanStep(
                    id = raw.optInt("id", i + 1),
                    sequence = raw.optInt("seq", i + 1),
                    description = raw.optString("desc", "Step ${i + 1}").ifBlank { "Step ${i + 1}" },
                    action = action,
                    rawActionName = rawAction,
                    expectedPostcondition = raw.optString("expect", ""),
                    verification = parseVerification(raw.optString("verify", "NONE")),
                    captures = parseCaptures(raw.optJSONArray("captures"))
                )
            )
        }
        if (steps.isEmpty()) return null
        val goal = goalOverride?.takeIf { it.isNotBlank() } ?: root.optString("goal", "")
        return TaskPlan(goal = goal, steps = steps.sortedBy { it.sequence }, fromModel = true)
    }

    /**
     * The approved catalog name for an [AgentAction], so a demonstrated action can be
     * persisted under the same raw name the parser expects on replay (rather than the
     * Kotlin class name, which [fromJson] cannot map back).
     */
    fun toolNameFor(action: AgentAction): String? = when (action) {
        is AgentAction.OpenApp -> com.example.agent.ToolRegistry.TOOL_LAUNCH_APP
        is AgentAction.Tap -> com.example.agent.ToolRegistry.TOOL_TAP_ELEMENT
        is AgentAction.TypeText -> com.example.agent.ToolRegistry.TOOL_ENTER_TEXT
        is AgentAction.ClearText -> com.example.agent.ToolRegistry.TOOL_CLEAR_TEXT
        is AgentAction.Submit -> com.example.agent.ToolRegistry.TOOL_SUBMIT
        is AgentAction.OpenUrl -> com.example.agent.ToolRegistry.TOOL_OPEN_URL
        is AgentAction.CopyText -> com.example.agent.ToolRegistry.TOOL_COPY_TEXT
        is AgentAction.PasteText -> com.example.agent.ToolRegistry.TOOL_PASTE_TEXT
        is AgentAction.Drag -> com.example.agent.ToolRegistry.TOOL_DRAG
        is AgentAction.DeepLink -> com.example.agent.ToolRegistry.TOOL_DEEP_LINK
        is AgentAction.ReadPageContent -> com.example.agent.ToolRegistry.TOOL_READ_PAGE_CONTENT
        is AgentAction.WebSearch -> com.example.agent.ToolRegistry.TOOL_WEB_SEARCH
        is AgentAction.ReadUrl -> com.example.agent.ToolRegistry.TOOL_READ_URL
        is AgentAction.OpenSearch -> com.example.agent.ToolRegistry.TOOL_OPEN_SEARCH
        is AgentAction.OpenItem -> com.example.agent.ToolRegistry.TOOL_OPEN_ITEM
        is AgentAction.Scroll -> com.example.agent.ToolRegistry.TOOL_SCROLL
        is AgentAction.LongPress -> com.example.agent.ToolRegistry.TOOL_LONG_PRESS
        is AgentAction.ScrollUntilFound -> com.example.agent.ToolRegistry.TOOL_SCROLL_UNTIL_FOUND
        is AgentAction.FindElement -> com.example.agent.ToolRegistry.TOOL_FIND_ELEMENT
        is AgentAction.ReadText -> com.example.agent.ToolRegistry.TOOL_READ_TEXT
        is AgentAction.WaitForText -> com.example.agent.ToolRegistry.TOOL_WAIT_FOR_TEXT
        is AgentAction.AdjustVolume -> com.example.agent.ToolRegistry.TOOL_ADJUST_VOLUME
        is AgentAction.SetRingerMode -> com.example.agent.ToolRegistry.TOOL_SET_RINGER_MODE
        is AgentAction.SetAlarm -> com.example.agent.ToolRegistry.TOOL_SET_ALARM
        is AgentAction.SetTimer -> com.example.agent.ToolRegistry.TOOL_SET_TIMER
        is AgentAction.AddCalendarEvent -> com.example.agent.ToolRegistry.TOOL_ADD_CALENDAR_EVENT
        is AgentAction.PlayMedia -> com.example.agent.ToolRegistry.TOOL_PLAY_MEDIA
        is AgentAction.ReadNotifications -> com.example.agent.ToolRegistry.TOOL_READ_NOTIFICATIONS
        is AgentAction.ReadDeviceStatus -> com.example.agent.ToolRegistry.TOOL_READ_DEVICE_STATUS
        is AgentAction.OpenNotifications -> com.example.agent.ToolRegistry.TOOL_OPEN_NOTIFICATIONS
        is AgentAction.OpenQuickSettings -> com.example.agent.ToolRegistry.TOOL_OPEN_QUICK_SETTINGS
        is AgentAction.PressRecents -> com.example.agent.ToolRegistry.TOOL_OPEN_RECENTS
        is AgentAction.Back -> com.example.agent.ToolRegistry.TOOL_NAVIGATE_BACK
        is AgentAction.Home -> com.example.agent.ToolRegistry.TOOL_NAVIGATE_HOME
        is AgentAction.Wait -> com.example.agent.ToolRegistry.TOOL_WAIT
        is AgentAction.Screenshot -> com.example.agent.ToolRegistry.TOOL_SCREENSHOT
        is AgentAction.ReadScreen -> com.example.agent.ToolRegistry.TOOL_READ_SCREEN
        is AgentAction.FinishTask -> com.example.agent.ToolRegistry.TOOL_REPORT_STATUS
        else -> null
    }

    private fun parseCaptures(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val names = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val name = PlanVariableStore.normalize(array.optString(i, ""))
            if (name.isNotEmpty()) names.add(name)
        }
        return names.toList()
    }

    private fun parseVerification(value: String): VerificationMethod = when (value.trim().uppercase()) {
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
