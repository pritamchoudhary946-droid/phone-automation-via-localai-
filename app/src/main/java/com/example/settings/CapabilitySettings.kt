package com.example.settings

import android.content.Context

/**
 * Single, honest home for every opt-in capability flag (plan 7.15). Every toggle
 * defaults to OFF and its state is observable, so proactive/cloud/voice/privileged
 * behaviour can never run silently. Stored in plain SharedPreferences (no secrets
 * beyond the cloud key the user themselves enters).
 */
class CapabilitySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Voice output (TTS) — opt-in; speaks status lines in the user's language. */
    var voiceOutputEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_OUTPUT, false)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_OUTPUT, v).apply()

    /** Hands-free / wake listening — opt-in AND requires mic permission. */
    var handsFreeEnabled: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, false)
        set(v) = prefs.edit().putBoolean(KEY_HANDS_FREE, v).apply()

    /** Shizuku privileged actions — opt-in (advanced). */
    var shizukuEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU, false)
        set(v) = prefs.edit().putBoolean(KEY_SHIZUKU, v).apply()

    /** Scheduled automations master switch — opt-in, default OFF. */
    var automationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIONS, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTOMATIONS, v).apply()

    /** Explicitly enabled degraded (keyword) planning mode when the model is missing. */
    var basicFallbackExplicit: Boolean
        get() = prefs.getBoolean(KEY_BASIC_FALLBACK, false)
        set(v) = prefs.edit().putBoolean(KEY_BASIC_FALLBACK, v).apply()

    /** Cloud planner fallback enable switch (endpoint/key live in the cloud provider). */
    var cloudFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_FALLBACK, false)
        set(v) = prefs.edit().putBoolean(KEY_CLOUD_FALLBACK, v).apply()

    companion object {
        const val PREFS = "capability_prefs"
        const val KEY_VOICE_OUTPUT = "voice_output_enabled"
        const val KEY_HANDS_FREE = "hands_free_enabled"
        const val KEY_SHIZUKU = "shizuku_enabled"
        const val KEY_AUTOMATIONS = "automations_enabled"
        const val KEY_BASIC_FALLBACK = "basic_fallback_explicit"
        const val KEY_CLOUD_FALLBACK = "cloud_fallback_enabled"
    }
}
