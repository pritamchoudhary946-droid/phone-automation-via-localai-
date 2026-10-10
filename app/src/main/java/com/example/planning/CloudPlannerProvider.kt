package com.example.planning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

/**
 * Real opt-in HTTPS provider (plan 7.6). It speaks the widely-compatible
 * OpenAI-style chat-completions request body and reads the first choice's content,
 * so a user can point it at that shape (or a proxy) with their own key.
 *
 * Safety properties:
 * - [enabled] defaults to false; when false [complete] returns null without any
 *   network call, so nothing egresses until the user opts in AND supplies a key.
 * - The endpoint and key are user-supplied (stored locally in Settings), never
 *   hardcoded here.
 * - Output is still parsed by the strict [TaskPlanParser] at the call site, so a
 *   cloud plan can never bypass the local validation gate.
 */
class HttpCloudPlannerProvider(
    private val context: android.content.Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : CloudPlannerProvider {

    private val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    // The single Settings surface (7.15) owns the on/off switch; endpoint/key live here.
    private val settings = com.example.settings.CapabilitySettings(context)

    override fun isEnabled(): Boolean =
        settings.cloudFallbackEnabled &&
            !prefs.getString(KEY_ENDPOINT, null).isNullOrBlank() &&
            !prefs.getString(KEY_API_KEY, null).isNullOrBlank()

    override suspend fun complete(prompt: String, systemInstruction: String?): String? =
        withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext null
            val endpoint = prefs.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: return@withContext null
            val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return@withContext null
            try {
                val messages = JSONArray().apply {
                    if (!systemInstruction.isNullOrBlank()) {
                        put(JSONObject().put("role", "system").put("content", systemInstruction))
                    }
                    put(JSONObject().put("role", "user").put("content", prompt))
                }
                val body = JSONObject().apply {
                    put("model", prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL)
                    put("messages", messages)
                    put("temperature", 0.2)
                }.toString().toRequestBody(JSON)

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val text = resp.body?.string() ?: return@withContext null
                    extractContent(text)
                }
            } catch (e: Exception) {
                null
            }
        }

    /** Reads `choices[0].message.content` (OpenAI shape), or null when absent. */
    internal fun extractContent(raw: String): String? = try {
        JSONObject(raw).optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val PREFS = "cloud_planner_prefs"
        const val KEY_ENABLED = "cloud_enabled"
        const val KEY_ENDPOINT = "cloud_endpoint"
        const val KEY_API_KEY = "cloud_api_key"
        const val KEY_MODEL = "cloud_model"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private val JSON = "application/json".toMediaType()
    }
}
