package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.example.agent.AppDirectory
import com.example.agent.TaskOrchestrator
import com.example.cloudflare.CloudflareGateway
import com.example.data.local.AgentDatabase
import com.example.model.AgentEngine
import com.example.model.ApiInferenceEngine
import com.example.model.ApiLlmRuntime
import com.example.model.LlmProviderConfig
import com.example.planning.AgentPlanner
import com.example.settings.CapabilitySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OmniAgentApplication : Application(), ComponentCallbacks2 {

    lateinit var database: AgentDatabase
        private set
    /** User-supplied provider/key/endpoint/model for the remote LLM API. */
    lateinit var llmProviderConfig: LlmProviderConfig
        private set
    lateinit var inferenceEngine: ApiInferenceEngine
        private set
    lateinit var agentEngine: AgentEngine
        private set
    lateinit var planner: AgentPlanner
        private set
    lateinit var appDirectory: AppDirectory
        private set
    lateinit var orchestrator: TaskOrchestrator
        private set
    lateinit var cloudflareGateway: CloudflareGateway
        private set
    lateinit var settings: CapabilitySettings
        private set
    /**
     * 7.7: ADB-level actions, refused unless the user opted in AND Shizuku is running.
     * Reached reflectively; a missing Shizuku runtime degrades to honest refusal.
     */
    val privilegedActions: com.example.privileged.PrivilegedActionBridge by lazy {
        com.example.privileged.GatedPrivilegedBridge(
            optIn = { settings.shizukuEnabled },
            shizukuRunning = { com.example.privileged.ShizukuRuntime.isBinderRunning() },
            exec = com.example.privileged.ShizukuRuntime.commandRunner()
        )
    }
    /** On-device speech output (plan 7.5/7.17); gated live by the Settings toggle. */
    lateinit var speechOutput: com.example.voice.SpeechOutput
        private set
    /** 9.8/9.9: posts the daily briefing and shares the task-result channel. */
    lateinit var completionNotifier: com.example.notifications.CompletionNotifier
        private set
    /** 9.1: the opt-in, battery-aware proactive awareness loop (lazily observed by Settings). */
    var proactiveRunner: com.example.agent.ProactiveEngine.Runner? = null
        private set

    /**
     * 8.2: the engine's live lifecycle state, exposed so SetupScreen can show the real
     * connection/failure reason and a Retry.
     */
    val engineState: kotlinx.coroutines.flow.StateFlow<com.example.model.EngineState>
        get() = inferenceEngine.state

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        lateinit var instance: OmniAgentApplication
            private set

        private const val TAG = "OmniAgentApp"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AgentDatabase.getInstance(this)
        settings = CapabilitySettings(this)
        // The reasoning now runs through a remote LLM API: the user picks any provider
        // and supplies their own key. No on-device model is downloaded or loaded.
        llmProviderConfig = LlmProviderConfig(this)

        // Exactly one client instance: the engine owns the single runtime.
        // 8.9/8.10: the single-flight and close-before-replace guarantees are kept, so a
        // retry racing the startup path can never leave two clients alive.
        inferenceEngine = ApiInferenceEngine(
            runtimeFactory = { ApiLlmRuntime(llmProviderConfig) },
            scope = appScope
        )
        planner = AgentPlanner(
            inferenceEngine,
            // 9.9: only advertise read_notifications to the planner while triage is on.
            notificationTriageEnabled = { settings.notificationTriageEnabled }
        )
        agentEngine = AgentEngine(this, inferenceEngine, planner)

        appDirectory = AppDirectory(this)
        cloudflareGateway = CloudflareGateway()

        // 7.2/7.3: real on-device OCR + OCR-backed vision grounding, wired into the
        // automation layer so a tree-miss surface (canvas/WebView) can still be read and
        // tapped. 7.5/7.17: TTS output gated live by the Settings toggle.
        val ocrReader = com.example.perception.MlKitOcrReader()
        val visionGrounder = com.example.perception.OcrVisionGrounder(ocrReader)
        speechOutput = com.example.voice.AndroidTextToSpeechOutput(this) { settings.voiceOutputEnabled }
        completionNotifier = com.example.notifications.CompletionNotifier(this)
        planner.fallbackExplicit = settings.basicFallbackExplicit

        // The gateway is exposed to the loop as an explicit web tool (web_search /
        // read_url); the configured LLM API remains the primary planner.
        orchestrator = TaskOrchestrator(
            context = this,
            agentEngine = agentEngine,
            appDirectory = appDirectory,
            deviceAutomation = com.example.agent.AndroidDeviceAutomation(
                appDirectory,
                ocrReader = ocrReader,
                visionGrounder = visionGrounder,
                // 9.9: live opt-in gate for reading/summarising notifications.
                triageEnabled = { settings.notificationTriageEnabled },
                // Media control primitive: changes a system audio stream directly.
                audioController = com.example.agent.AndroidAudioController(this),
                mediaController = com.example.agent.AndroidMediaController(this),
                // Ringer-mode primitive: switches the whole device's ring/vibrate/silent
                // state directly, service-free like the audio/media controllers.
                ringerModeController = com.example.agent.AndroidRingerModeController(this),
                // Device-status read primitive: battery/screen/ringer/connectivity,
                // read fresh from system services on each call.
                deviceStatusReader = com.example.agent.AndroidDeviceStatusReader(this),
                // Alarm primitive: dispatches the platform AlarmClock intent, service-free
                // like the audio/media/ringer controllers (no UI tap sets an alarm reliably).
                alarmController = com.example.agent.AndroidAlarmController(this),
                // Countdown-timer primitive: dispatches AlarmClock ACTION_SET_TIMER,
                // service-free like the alarm (distinct from it - a timer counts down).
                timerController = com.example.agent.AndroidTimerController(this),
                // Calendar-event primitive: dispatches CalendarContract ACTION_INSERT,
                // service-free like the alarm/timer (no UI tap adds an event reliably).
                calendarController = com.example.agent.AndroidCalendarController(this),
                // Contact primitive: dispatches the ContactsContract insert intent,
                // service-free like the calendar/alarm/timer (no UI tap adds a contact
                // reliably).
                contactController = com.example.agent.AndroidContactController(this),
                // Dialer primitive: dispatches the platform ACTION_DIAL intent with a
                // tel: URI, service-free like the contact/calendar/alarm primitives (no
                // UI tap opens a dialer reliably). It opens the dialer for the user to
                // confirm; it never places the call itself.
                dialerController = com.example.agent.AndroidDialerController(this),
                // SMS primitive: dispatches the platform ACTION_SENDTO intent with an
                // smsto: URI + sms_body extra, service-free like the dialer/contact
                // primitives (no UI tap opens a compose screen reliably). It opens the
                // messaging app pre-filled for the user to press send; it never sends
                // the message itself.
                smsController = com.example.agent.AndroidSmsController(this),
                // Share primitive: dispatches the platform ACTION_SEND intent with a
                // text/plain payload, service-free like the SMS/dialer/contact primitives
                // (no UI tap hands text to an arbitrary target app reliably). It opens the
                // share sheet (optionally addressed to a named app) for the user to choose
                // a destination and confirm; it never posts anywhere by itself.
                shareController = com.example.agent.AndroidShareController(this),
                // Email primitive: dispatches the platform ACTION_SENDTO intent with a
                // mailto: URI (+ EXTRA_SUBJECT/EXTRA_TEXT), service-free like the
                // SMS/dialer/share primitives (no UI tap opens a compose screen reliably).
                // It opens the mail app pre-filled for the user to press send; it never
                // sends the message itself.
                emailController = com.example.agent.AndroidEmailController(this),
                // Navigation primitive: dispatches the platform ACTION_VIEW intent with a
                // geo:0,0?q= URI, service-free like the SMS/dialer/share/email primitives
                // (no UI tap opens a map at a chosen place reliably). It opens the map at
                // the place for the user to start directions; it never navigates by itself.
                navigationController = com.example.agent.AndroidNavigationController(this),
                // App-info primitive: dispatches the platform ACTION_APPLICATION_DETAILS_SETTINGS
                // intent addressed to the resolved package, service-free like the other
                // intent-backed primitives. It opens the app's Settings page; the user
                // decides what to change there, so nothing consequential is performed.
                appInfoController = com.example.agent.AndroidAppInfoController(this)
            ),
            webGateway = cloudflareGateway,
            screenAwareness = com.example.perception.AccessibilityScreenAwareness(),
            visionGrounder = visionGrounder,
            ocrReader = ocrReader,
            retriever = com.example.memory.rag.TokenOverlapRetriever(database.knowledgeDao()),
            completionNotifier = completionNotifier,
            // A clean, fully-verified run is saved as a re-runnable local skill.
            autoSaveSkills = true,
            nanoSummarizer = com.example.model.ApiNanoSummarizer(
                generate = { prompt, system, max -> inferenceEngine.generate(prompt, system, max) },
                ready = { inferenceEngine.isReady() }
            ),
            speechOutput = speechOutput,
            // 9.9: keep the advertised catalogue in step with the executor gate.
            notificationTriageEnabled = { settings.notificationTriageEnabled }
        )

        // 7.8: re-arm (or cancel) any persisted automations according to the current
        // master switch, so a previously scheduled job cannot outlive the user's choice.
        // 9.8: also (re)arm the opt-in daily briefing.
        appScope.launch { syncAutomations() }
        appScope.launch { runCatching { com.example.notifications.BriefingScheduler.sync(this@OmniAgentApplication) } }
        startProactiveAwareness()

        createNotificationChannels()
    }

    /**
     * 9.1: a bounded, opt-in, battery-aware proactive loop. It only reads the
     * foreground package and the recent notifications already on-device, only
     * *offers* help (never acts), and pauses entirely when the toggle is off or the
     * battery guard says so.
     */
    private fun startProactiveAwareness() {
        val runner = com.example.agent.ProactiveEngine.Runner(
            shouldRun = { settings.proactiveAwarenessEnabled },
            battery = { com.example.perception.BatteryGuard.check(this) },
            sampleContext = {
                val foreground = com.example.accessibility.AgentAccessibilityService.instance
                    ?.screenEvents?.value?.activePackageName
                com.example.agent.ProactiveEngine.Context(
                    foregroundPackage = foreground,
                    notifications = com.example.notifications.NotificationBus.recent(5),
                    minuteOfDay = java.util.Calendar.getInstance().let {
                        it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
                    }
                )
            },
            onSuggestion = { s ->
                completionNotifier.notifySuggestion(s.text)
                if (settings.voiceOutputEnabled) runCatching { speechOutput.speak(s.text) }
            }
        )
        proactiveRunner = runner
        runner.start(appScope)
    }

    /**
     * Loads the engine off the UI thread once an LLM API is configured. Returns
     * immediately; observe [ApiInferenceEngine.state] for the real outcome.
     *
     * 8.9: single-flight. Concurrent callers (the MainActivity LaunchedEffect, the
     * Setup/dashboard Retry buttons, or a config-change re-transition) collapse onto one
     * load. There is no load-time test prompt: readiness is configuration, and a real
     * provider defect surfaces on the first actual generation.
     */
    fun initializeEngineIfConfigured() {
        if (!llmProviderConfig.isConfigured()) {
            Log.i(TAG, "Skipping engine init: no LLM API configured")
            inferenceEngine.reportUnavailable("No LLM API configured - add a provider and key in Setup")
            return
        }
        appScope.launch {
            try {
                if (inferenceEngine.ensureReady()) {
                    Log.i(TAG, "Engine initialized and ready")
                } else {
                    Log.w(TAG, "Engine unavailable: see EngineState for the reason")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing engine: ${e.message}", e)
                inferenceEngine.reportUnavailable("Engine initialization error: ${e.message ?: "unknown"}")
            }
        }
    }

    /** Applies a new provider/key/endpoint/model: reloads the engine so it takes effect. */
    fun reloadEngineForConfigChange() {
        inferenceEngine.shutdown()
        initializeEngineIfConfigured()
    }

    fun shutdownEngine() = inferenceEngine.shutdown()

    /**
     * Re-arms or cancels all scheduled automations to match the persisted list and the
     * master switch (plan 7.8). Safe to call whenever the toggle or the automation list
     * changes.
     */
    suspend fun syncAutomations() {
        try {
            if (!settings.automationsEnabled) {
                com.example.automation.AutomationScheduler.cancelAll(this)
                return
            }
            val automations = database.automationDao().getEnabledAutomations()
            automations.forEach { com.example.automation.AutomationScheduler.schedule(this, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Automation sync failed: ${e.message}")
        }
    }

    /** Applies the voice-output toggle to the live speech engine (plan 7.15). */
    fun applyVoiceOutputSetting(enabled: Boolean) {
        settings.voiceOutputEnabled = enabled
        if (!enabled) runCatching { speechOutput.shutdown() }
    }

    /** Applies the basic-fallback toggle to the live planner (plan 7.15). */
    fun applyBasicFallbackSetting(enabled: Boolean) {
        settings.basicFallbackExplicit = enabled
        planner.fallbackExplicit = enabled
    }

    /**
     * The reasoning now runs through a remote API, so there is no multi-GB on-device
     * engine to release under memory pressure. The engine state is left untouched on any
     * trim; a configured API stays ready across trims and backgrounding.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val agentChannel = NotificationChannel(
                "agent_channel",
                "Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent status and task notifications"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(agentChannel)
        }
    }
}
