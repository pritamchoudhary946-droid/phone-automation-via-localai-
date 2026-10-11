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
import com.example.model.GemmaInferenceEngine
import com.example.model.LiteRtEngine
import com.example.model.LiteRtLmRuntime
import com.example.model.LiteRtModelManager
import com.example.planning.AgentPlanner
import com.example.planning.HttpCloudPlannerProvider
import com.example.settings.CapabilitySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OmniAgentApplication : Application(), ComponentCallbacks2 {

    lateinit var database: AgentDatabase
        private set
    lateinit var modelManager: LiteRtModelManager
        private set
    lateinit var inferenceEngine: GemmaInferenceEngine
        private set
    lateinit var liteRtEngine: LiteRtEngine
        private set
    lateinit var planner: AgentPlanner
        private set
    lateinit var appDirectory: AppDirectory
        private set
    lateinit var orchestrator: TaskOrchestrator
        private set
    lateinit var cloudflareGateway: CloudflareGateway
        private set
    /**
     * 8.7/BS4: the real cloud planner provider, exposed so SettingsScreen can persist
     * an endpoint/key. Without this the opt-in cloud fallback was unreachable end to end.
     */
    lateinit var cloudPlannerProvider: HttpCloudPlannerProvider
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
     * load/failure reason and a Retry - not just download progress.
     */
    val engineState: kotlinx.coroutines.flow.StateFlow<com.example.model.EngineState>
        get() = inferenceEngine.state

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 8.9: bumped whenever a memory trim releases the engine, so an in-flight init can
     * notice the engine was dropped and let the next explicit call re-arm it — without
     * the app re-loading on its own and spinning load/unload cycles. Advisory only, so
     * a plain field is fine (and deliberately not @Volatile, which crashed lint's
     * Kotlin PSI analysis here).
     */
    private var trimGeneration = 0

    companion object {
        lateinit var instance: OmniAgentApplication
            private set

        // 8.10: GPU is OFF by default. The Adreno 810 + LiteRT GPU delegate is poorly
        // supported for this VLM and a failed GPU attempt doubles the ~2.6 GB init work
        // (and the memory churn) on an 8 GB device, which is what made the phone hang.
        // CPU-first is the supported path; GPU stays strictly opt-in.
        private const val USE_GPU_BACKEND = false
        private const val TAG = "OmniAgentApp"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AgentDatabase.getInstance(this)
        settings = CapabilitySettings(this)
        modelManager = LiteRtModelManager(this)

        // 7.1: verify the pinned artifact is reachable/consistent at startup (bounded,
        // non-blocking) so a stale hash that would block planning is logged loudly.
        appScope.launch { runCatching { modelManager.logRemoteArtifactCheck() } }

        // Exactly one heavy model instance: the engine owns the single runtime.
        // 8.9/8.10: a single CPU-first factory (no GPU-primary + CPU two-attempt
        // scheme) so a failed backend cannot double the load work and thrash memory.
        inferenceEngine = GemmaInferenceEngine(
            runtimeFactory = { modelPath, cacheDir ->
                LiteRtLmRuntime(modelPath = modelPath, cacheDir = cacheDir, useGpu = USE_GPU_BACKEND)
            },
            scope = appScope
        )
        planner = AgentPlanner(
            inferenceEngine,
            modelManager,
            // 9.9: only advertise read_notifications to the planner while triage is on.
            notificationTriageEnabled = { settings.notificationTriageEnabled }
        )
        liteRtEngine = LiteRtEngine(this, modelManager, inferenceEngine, planner)

        appDirectory = AppDirectory(this)
        cloudflareGateway = CloudflareGateway()
        // 8.7/BS4: build the real provider once and reuse it, so the Settings surface and
        // the orchestrator share the same prefs (the fallback is now configurable).
        cloudPlannerProvider = HttpCloudPlannerProvider(this)

        // 7.2/7.3: real on-device OCR + OCR-backed vision grounding, wired into the
        // automation layer so a tree-miss surface (canvas/WebView) can still be read and
        // tapped. 7.5/7.17: TTS output gated live by the Settings toggle.
        val ocrReader = com.example.perception.MlKitOcrReader()
        val visionGrounder = com.example.perception.OcrVisionGrounder(ocrReader)
        speechOutput = com.example.voice.AndroidTextToSpeechOutput(this) { settings.voiceOutputEnabled }
        completionNotifier = com.example.notifications.CompletionNotifier(this)
        planner.fallbackExplicit = settings.basicFallbackExplicit

        // The gateway is exposed to the loop as an explicit web tool (web_search /
        // read_url); the on-device model remains the primary planner.
        orchestrator = TaskOrchestrator(
            context = this,
            liteRtEngine = liteRtEngine,
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
                // Device-status read primitive: battery/screen/ringer/connectivity,
                // read fresh from system services on each call.
                deviceStatusReader = com.example.agent.AndroidDeviceStatusReader(this)
            ),
            webGateway = cloudflareGateway,
            screenAwareness = com.example.perception.AccessibilityScreenAwareness(),
            visionGrounder = visionGrounder,
            ocrReader = ocrReader,
            retriever = com.example.memory.rag.TokenOverlapRetriever(database.knowledgeDao()),
            completionNotifier = completionNotifier,
            // A clean, fully-verified run is saved as a re-runnable local skill.
            autoSaveSkills = true,
            cloudPlannerProvider = cloudPlannerProvider,
            nanoSummarizer = com.example.model.EngineNanoSummarizer(
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
     * Loads and smoke-tests the engine off the UI thread once the model is verified.
     * Returns immediately; observe [GemmaInferenceEngine.state] for the real outcome.
     *
     * 8.9: single-flight. Concurrent callers (the MainActivity LaunchedEffect, the
     * Setup/dashboard Retry buttons, or a model-ready re-transition) collapse onto one
     * load, and the smoke probe runs only on the first successful load. A background
     * trim can still drop a loaded engine; the [initGeneration] check re-loads it on
     * the next explicit call but never spins by itself.
     */
    fun initializeEngineIfModelReady() {
        val modelFile = modelManager.getModelFile()
        if (!modelFile.exists()) {
            Log.i(TAG, "Skipping engine init: model not downloaded")
            return
        }
        val generation = trimGeneration
        appScope.launch {
            try {
                if (inferenceEngine.ensureReady(modelFile)) {
                    Log.i(TAG, "Engine initialized and smoke test passed successfully")
                } else {
                    Log.w(TAG, "Engine unavailable: see EngineState for the reason")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing engine: ${e.message}", e)
                inferenceEngine.reportUnavailable("Engine initialization error: ${e.message ?: "unknown"}")
            }
            if (generation != trimGeneration) {
                Log.i(TAG, "Engine re-armed after a memory trim; it will reload on the next use")
            }
        }
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
     * 8.9: do NOT tear the ~2.6 GB engine down on an ordinary trim. Loading is a
     * multi-second, multi-GB operation, so a trim → shutdown → reload cycle is a giant
     * CPU+IO spike that stutters the whole device (the reported hang). Only release
     * under genuine critical pressure, and let the next explicit use reload.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (com.example.model.MemoryTrimPolicy.shouldReleaseEngine(level)) {
            Log.w(TAG, "onTrimMemory($level): critical pressure, releasing the engine")
            releaseEngineForMemoryPressure()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Advisory only: keep the engine so we never thrash a multi-GB reload.
            Log.i(TAG, "onTrimMemory($level): keeping the engine loaded to avoid a reload spike")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    /**
     * 8.9: [onLowMemory] is advisory (it is also delivered for cached/background
     * processes). Shutting the engine down unconditionally here was the load/unload
     * thrash that hung the phone; release only when the engine is genuinely unusable.
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory(): advisory only, keeping the engine loaded")
    }

    /** Releases the engine under genuine pressure and arms the next explicit reload. */
    private fun releaseEngineForMemoryPressure() {
        trimGeneration++
        inferenceEngine.shutdown()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadChannel = NotificationChannel(
                "model_download_channel",
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of local Gemma 4 E2B LiteRT-LM model download"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(downloadChannel)
        }
    }
}
