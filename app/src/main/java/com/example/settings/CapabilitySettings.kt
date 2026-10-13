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

    // --- Phase 9 opt-in capabilities. Every one defaults OFF and must be enabled
    // explicitly by the user; nothing always-on runs without it. ---

    /** 9.1 Proactive awareness: bounded context polling that *offers* help. */
    var proactiveAwarenessEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_AWARENESS, false)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVE_AWARENESS, v).apply()

    /** 9.2 Location-based automation triggers (arrive/leave). Off by default. */
    var locationTriggersEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_TRIGGERS, false)
        set(v) = prefs.edit().putBoolean(KEY_LOCATION_TRIGGERS, v).apply()

    /** 9.8 Daily briefing/digest. Off by default; fires once at the set time. */
    var briefingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BRIEFING, false)
        set(v) = prefs.edit().putBoolean(KEY_BRIEFING, v).apply()

    /** 9.8 Minute-of-day (0..1439) at which the briefing fires. Default 08:00. */
    var briefingMinuteOfDay: Int
        get() = prefs.getInt(KEY_BRIEFING_MINUTE, 8 * 60)
        set(v) = prefs.edit().putInt(KEY_BRIEFING_MINUTE, v.coerceIn(0, 1439)).apply()

    /** 9.9 Smart notification triage (summarise + offer actions). Off by default. */
    var notificationTriageEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_TRIAGE, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFICATION_TRIAGE, v).apply()

    /**
     * Clipboard reading (`read_clipboard`). Off by default: the clipboard can hold
     * another app's secret (a password, a one-time code), so reading it is opt-in,
     * exactly like notification triage. While off the executor refuses the action and
     * the planner does not advertise it.
     */
    var clipboardReadEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_READ, false)
        set(v) = prefs.edit().putBoolean(KEY_CLIPBOARD_READ, v).apply()

    companion object {
        const val PREFS = "capability_prefs"
        const val KEY_VOICE_OUTPUT = "voice_output_enabled"
        const val KEY_HANDS_FREE = "hands_free_enabled"
        const val KEY_SHIZUKU = "shizuku_enabled"
        const val KEY_AUTOMATIONS = "automations_enabled"
        const val KEY_BASIC_FALLBACK = "basic_fallback_explicit"
        const val KEY_PROACTIVE_AWARENESS = "proactive_awareness_enabled"
        const val KEY_LOCATION_TRIGGERS = "location_triggers_enabled"
        const val KEY_BRIEFING = "briefing_enabled"
        const val KEY_BRIEFING_MINUTE = "briefing_minute_of_day"
        const val KEY_NOTIFICATION_TRIAGE = "notification_triage_enabled"
        const val KEY_CLIPBOARD_READ = "clipboard_read_enabled"
    }
}
