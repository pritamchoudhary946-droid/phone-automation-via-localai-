package com.example.model

import android.content.Context

/** The wire format a provider speaks. Determines how requests are built. */
enum class LlmApiStyle { OPENAI, ANTHROPIC, GEMINI }

/**
 * A supported remote LLM provider. Every entry is a plain, documented HTTP API, so the
 * app can be pointed at any of them (or at a self-hosted OpenAI-compatible gateway) with
 * the user's own key. [defaultBaseUrl] and [defaultModel] are only prefills — both are
 * editable in Setup, so an unlisted provider can be reached through [OPENAI_COMPATIBLE].
 */
enum class LlmProvider(
    val displayName: String,
    val apiStyle: LlmApiStyle,
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    OPENAI("OpenAI", LlmApiStyle.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("Anthropic (Claude)", LlmApiStyle.ANTHROPIC, "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
    GEMINI("Google Gemini", LlmApiStyle.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
    GROQ("Groq", LlmApiStyle.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    MISTRAL("Mistral AI", LlmApiStyle.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest"),
    DEEPSEEK("DeepSeek", LlmApiStyle.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"),
    OPENROUTER("OpenRouter", LlmApiStyle.OPENAI, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    TOGETHER("Together AI", LlmApiStyle.OPENAI, "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    XAI("xAI (Grok)", LlmApiStyle.OPENAI, "https://api.x.ai/v1", "grok-2-latest"),
    FIREWORKS("Fireworks AI", LlmApiStyle.OPENAI, "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct"),
    PERPLEXITY("Perplexity", LlmApiStyle.OPENAI, "https://api.perplexity.ai", "sonar"),
    OPENAI_COMPATIBLE("OpenAI-compatible (any base URL)", LlmApiStyle.OPENAI, "", "");

    companion object {
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.name == id } ?: OPENAI
    }
}

/**
 * Persisted, user-supplied LLM API configuration. Replaces the on-device model: the
 * planner now reasons through whichever remote provider the user picks, using their own
 * key. The key is stored only on this device (plain SharedPreferences, matching the
 * existing cloud-provider convention) and is sent only to the provider the user chose.
 */
class LlmProviderConfig(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The selected provider. Defaults to OpenAI but is only usable once a key is set. */
    var provider: LlmProvider
        get() = LlmProvider.fromId(prefs.getString(KEY_PROVIDER, null))
        set(v) = prefs.edit().putString(KEY_PROVIDER, v.name).apply()

    /** The user's API key. Blank until the user enters one; nothing egresses until then. */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_API_KEY, v.trim()).apply()

    /**
     * Optional base-URL override. Blank means "use the provider's default", so a user
     * only has to fill it for a self-hosted or OpenAI-compatible endpoint.
     */
    var baseUrlOverride: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trim()).apply()

    /** Optional model override. Blank means "use the provider's default model". */
    var modelOverride: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_MODEL, v.trim()).apply()

    /** The base URL requests are actually sent to. */
    fun effectiveBaseUrl(): String =
        baseUrlOverride.ifBlank { provider.defaultBaseUrl }.trimEnd('/')

    /** The model name sent in the request body. */
    fun effectiveModel(): String =
        modelOverride.ifBlank { provider.defaultModel }

    /**
     * True only when a key AND a resolvable base URL AND a model are all present. Until
     * then the engine honestly reports itself not ready and never contacts the network.
     */
    fun isConfigured(): Boolean =
        apiKey.isNotBlank() && effectiveBaseUrl().isNotBlank() && effectiveModel().isNotBlank()

    /** Persists a full configuration from the Setup surface. */
    fun save(provider: LlmProvider, apiKey: String, baseUrl: String, model: String) {
        prefs.edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    /** Removes the stored key and overrides, returning the app to an unconfigured state. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS = "llm_provider_prefs"
        const val KEY_PROVIDER = "llm_provider"
        const val KEY_API_KEY = "llm_api_key"
        const val KEY_BASE_URL = "llm_base_url"
        const val KEY_MODEL = "llm_model"
    }
}
