package com.example.model

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [LlmRuntime] backed by a remote chat-completions HTTP API instead of an on-device
 * model. It speaks the OpenAI, Anthropic and Google Gemini request shapes, so the user
 * can plug in any provider with their own key (see [LlmProvider]).
 *
 * Safety properties:
 * - Nothing egresses until [initialize] verifies a key is configured; [isReady] stays
 *   false otherwise and callers keep their honest "not configured" behaviour.
 * - A request never fabricates a response: a non-2xx status or a malformed body throws
 *   [LlmInferenceException] so the planner degrades honestly rather than acting on junk.
 * - `maxOutputTokens` from the caller is honoured per provider so a full plan and a
 *   one-step decision both stay bounded.
 */
class ApiLlmRuntime(
    private val config: LlmProviderConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
) : LlmRuntime {

    private val ready = AtomicBoolean(false)

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            ready.set(false)
            throw LlmInferenceException("No LLM API configured: set a provider and API key in Setup")
        }
        // No network call here: configuration presence is the readiness signal. A real
        // call is made by smokeTest() so a bad key surfaces as an honest failure.
        ready.set(true)
        Log.i(TAG, "API LLM runtime ready (provider=${config.provider.displayName}, model=${config.effectiveModel()})")
    }

    override fun isReady(): Boolean = ready.get() && config.isConfigured()

    override fun backendLabel(): String = config.provider.displayName

    override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured()) {
                throw LlmInferenceException("No LLM API configured")
            }
            when (config.provider.apiStyle) {
                LlmApiStyle.OPENAI -> generateOpenAi(prompt, systemInstruction, maxOutputTokens)
                LlmApiStyle.ANTHROPIC -> generateAnthropic(prompt, systemInstruction, maxOutputTokens)
                LlmApiStyle.GEMINI -> generateGemini(prompt, systemInstruction, maxOutputTokens)
            }
        }

    /**
     * Multimodal generation: attaches [imageBytes] as an inline image part when the
     * selected provider supports it (OpenAI, Anthropic, Gemini). A provider whose vision
     * shape we do not build falls back to the text path rather than failing the step.
     */
    override suspend fun generateWithImage(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        if (!config.isConfigured()) {
            throw LlmInferenceException("No LLM API configured")
        }
        try {
            when (config.provider.apiStyle) {
                LlmApiStyle.OPENAI -> generateOpenAi(prompt, systemInstruction, maxOutputTokens, imageBytes)
                LlmApiStyle.ANTHROPIC -> generateAnthropic(prompt, systemInstruction, maxOutputTokens, imageBytes)
                LlmApiStyle.GEMINI -> generateGemini(prompt, systemInstruction, maxOutputTokens, imageBytes)
            }
        } catch (e: LlmInferenceException) {
            Log.w(TAG, "Vision request failed (${e.message}); retrying text-only")
            generate(prompt, systemInstruction, maxOutputTokens)
        }
    }

    /**
     * The load-time smoke probe.
     *
     * A live provider is often slow on its first call (cold start) and may answer a
     * one-word probe with an empty string. Treating either as a hard failure is the
     * "engine not available right after I added my key" bug, so the probe runs through
     * the shared, unit-tested [runTolerantSmokeProbe]: up to two bounded attempts with a
     * growing token budget, where an empty-but-no-exception answer counts as usable and
     * only a generation that THROWS on every attempt fails.
     *
     * It never re-checks [isReady] here — [ApiInferenceEngine] guards readiness before
     * calling, and short-circuiting a live probe on the [ready] flag would defeat it.
     */
    override suspend fun smokeTest(): Boolean = withContext(Dispatchers.IO) {
        runTolerantSmokeProbe { maxOutputTokens ->
            try {
                generate(
                    prompt = "Reply with the single word: ready",
                    systemInstruction = "You are a minimal health check.",
                    maxOutputTokens = maxOutputTokens
                )
            } catch (e: EmptyModelResponseException) {
                // The provider answered 2xx but gave no text for the tiny probe: the key
                // and endpoint are demonstrably working, so report an empty-but-usable
                // result rather than letting the exception fail the load.
                Log.i(TAG, "Smoke probe got an empty provider answer; treating as usable")
                ""
            }
        }
    }

    override fun close() {
        ready.set(false)
    }

    // ---- Request builders, one per wire format. ----

    private fun generateOpenAi(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray? = null
    ): String {
        val messages = JSONArray().apply {
            if (!systemInstruction.isNullOrBlank()) {
                put(JSONObject().put("role", "system").put("content", systemInstruction))
            }
            val userContent: Any = if (imageBytes != null && imageBytes.isNotEmpty()) {
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", prompt))
                    put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,${base64(imageBytes)}")
                        )
                    )
                }
            } else {
                prompt
            }
            put(JSONObject().put("role", "user").put("content", userContent))
        }
        val body = JSONObject().apply {
            put("model", config.effectiveModel())
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", maxOutputTokens.coerceAtLeast(1))
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return execute(request) { raw ->
            JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun generateAnthropic(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray? = null
    ): String {
        val content: Any = if (imageBytes != null && imageBytes.isNotEmpty()) {
            JSONArray().apply {
                put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", base64(imageBytes))
                    )
                )
                put(JSONObject().put("type", "text").put("text", prompt))
            }
        } else {
            prompt
        }
        val body = JSONObject().apply {
            put("model", config.effectiveModel())
            put("max_tokens", maxOutputTokens.coerceAtLeast(1))
            if (!systemInstruction.isNullOrBlank()) put("system", systemInstruction)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", content))
            })
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return execute(request) { raw ->
            JSONObject(raw).optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun generateGemini(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray? = null
    ): String {
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", base64(imageBytes))
                    )
                )
            }
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().put("role", "user").put("parts", parts))
            })
            if (!systemInstruction.isNullOrBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", systemInstruction))
                }))
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", maxOutputTokens.coerceAtLeast(1))
            })
        }.toString().toRequestBody(JSON)

        val url = "${config.effectiveBaseUrl()}/models/${config.effectiveModel()}:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return execute(request) { raw ->
            JSONObject(raw).optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
        }
    }

    /** Runs [request] and maps the JSON body through [extract], honestly failing on any error. */
    private inline fun execute(request: Request, extract: (String) -> String?): String {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LlmInferenceException("Provider returned HTTP ${resp.code}: ${text.take(200)}")
            }
            return extract(text)
                ?: throw EmptyModelResponseException("Provider response had no usable content: ${text.take(200)}")
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        private const val TAG = "ApiLlmRuntime"
        private val JSON = "application/json".toMediaType()
    }
}
