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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        lateinit var instance: OmniAgentApplication
            private set

        // CPU first for broad compatibility: GPU acceleration is not verified on the
        // target device, so it is opt-in rather than the default.
        private const val USE_GPU_BACKEND = false
        private const val TAG = "OmniAgentApp"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AgentDatabase.getInstance(this)
        modelManager = LiteRtModelManager(this)

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
        orchestrator = TaskOrchestrator(this, liteRtEngine, appDirectory)
        cloudflareGateway = CloudflareGateway()

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
