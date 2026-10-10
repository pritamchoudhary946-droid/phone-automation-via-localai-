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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        lateinit var instance: OmniAgentApplication
            private set

        // GPU backend opt-in: the target device (Motorola Edge 70 Fusion, Adreno 810)
        // has a GPU backend available, and LiteRtLmRuntime already falls back to CPU
        // automatically if GPU initialization fails, so enabling this is safe.
        private const val USE_GPU_BACKEND = true
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
        inferenceEngine = GemmaInferenceEngine(
            runtimeFactory = { modelPath, cacheDir ->
                LiteRtLmRuntime(modelPath = modelPath, cacheDir = cacheDir, useGpu = USE_GPU_BACKEND)
            },
            scope = appScope
        )
        planner = AgentPlanner(inferenceEngine, modelManager)
        liteRtEngine = LiteRtEngine(this, modelManager, inferenceEngine, planner)

        appDirectory = AppDirectory(this)
        cloudflareGateway = CloudflareGateway()

        // 7.2/7.3: real on-device OCR + OCR-backed vision grounding, wired into the
        // automation layer so a tree-miss surface (canvas/WebView) can still be read and
        // tapped. 7.5/7.17: TTS output gated live by the Settings toggle.
        val ocrReader = com.example.perception.MlKitOcrReader()
        val visionGrounder = com.example.perception.OcrVisionGrounder(ocrReader)
        speechOutput = com.example.voice.AndroidTextToSpeechOutput(this) { settings.voiceOutputEnabled }
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
                visionGrounder = visionGrounder
            ),
            webGateway = cloudflareGateway,
            screenAwareness = com.example.perception.AccessibilityScreenAwareness(),
            visionGrounder = visionGrounder,
            ocrReader = ocrReader,
            retriever = com.example.memory.rag.TokenOverlapRetriever(database.knowledgeDao()),
            completionNotifier = com.example.notifications.CompletionNotifier(this),
            // A clean, fully-verified run is saved as a re-runnable local skill.
            autoSaveSkills = true,
            cloudPlannerProvider = HttpCloudPlannerProvider(this),
            nanoSummarizer = com.example.model.EngineNanoSummarizer(
                generate = { prompt, system, max -> inferenceEngine.generate(prompt, system, max) },
                ready = { inferenceEngine.isReady() }
            ),
            speechOutput = speechOutput
        )

        // 7.8: re-arm (or cancel) any persisted automations according to the current
        // master switch, so a previously scheduled job cannot outlive the user's choice.
        appScope.launch { syncAutomations() }

        createNotificationChannels()
    }

    /** Loads and smoke-tests the engine off the UI thread once the model is verified. */
    fun initializeEngineIfModelReady() {
        val modelFile = modelManager.getModelFile()
        if (!modelFile.exists()) {
            Log.i(TAG, "Skipping engine init: model not downloaded")
            return
        }
        appScope.launch {
            try {
                val ok = inferenceEngine.initialize(modelFile)
                if (ok) {
                    if (!inferenceEngine.smokeTest(modelFile)) {
                        Log.w(TAG, "Smoke test failed; marking engine unavailable")
                        inferenceEngine.shutdown()
                    } else {
                        Log.i(TAG, "Engine initialized and smoke test passed successfully")
                    }
                } else {
                    Log.w(TAG, "Engine initialization failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing engine: ${e.message}", e)
                inferenceEngine.shutdown()
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "onTrimMemory($level): releasing engine to protect the accessibility service")
            inferenceEngine.shutdown()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onLowMemory() {
        super.onLowMemory()
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
