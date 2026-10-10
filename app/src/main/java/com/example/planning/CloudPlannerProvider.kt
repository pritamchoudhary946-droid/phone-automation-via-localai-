package com.example.planning

/**
 * Opt-in cloud planner fallback (plan 4.8). OFF by default and never consulted
 * silently: the orchestrator only asks it after a repeated *local* failure AND only
 * when the user has explicitly enabled it. When disabled, unreachable, or configured
 * without credentials, it returns null so the app keeps its honest local behaviour.
 */
interface CloudPlannerProvider {

    /** Whether the user has explicitly opted in. Must default to false everywhere. */
    fun isEnabled(): Boolean

    /**
     * Returns a raw model response (the same JSON shape the local planner parses) for
     * [prompt], or null when disabled/unconfigured/unreachable. Never throws.
     */
    suspend fun complete(prompt: String, systemInstruction: String?): String?
}

/**
 * Default provider: disabled and unconfigured. This is what ships unless the user
 * turns the fallback on and supplies an endpoint; it never contacts the network.
 */
object DisabledCloudPlannerProvider : CloudPlannerProvider {
    override fun isEnabled(): Boolean = false
    override suspend fun complete(prompt: String, systemInstruction: String?): String? = null
}
