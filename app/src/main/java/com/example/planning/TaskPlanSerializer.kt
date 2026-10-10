package com.example.planning

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a validated [TaskPlan] to a compact JSON string for durable storage
 * (saved skills). Only the already-validated, non-executable fields are written:
 * the mapped action name, description and verification method — never free-form text.
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
                }
            )
        }
        return JSONObject()
            .put("goal", plan.goal)
            .put("fromModel", plan.fromModel)
            .put("steps", steps)
            .toString()
    }
}
