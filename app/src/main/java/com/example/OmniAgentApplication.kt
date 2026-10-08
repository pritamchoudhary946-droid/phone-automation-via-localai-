package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.agent.AppDirectory
import com.example.agent.TaskOrchestrator
import com.example.cloudflare.CloudflareGateway
import com.example.data.local.AgentDatabase
import com.example.model.LiteRtEngine
import com.example.model.LiteRtModelManager

class OmniAgentApplication : Application() {

    lateinit var database: AgentDatabase
        private set
    lateinit var modelManager: LiteRtModelManager
        private set
    lateinit var liteRtEngine: LiteRtEngine
        private set
    lateinit var appDirectory: AppDirectory
        private set
    lateinit var orchestrator: TaskOrchestrator
        private set
    lateinit var cloudflareGateway: CloudflareGateway
        private set

    companion object {
        lateinit var instance: OmniAgentApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AgentDatabase.getInstance(this)
        modelManager = LiteRtModelManager(this)
        liteRtEngine = LiteRtEngine(this, modelManager)
        appDirectory = AppDirectory(this)
        orchestrator = TaskOrchestrator(this, liteRtEngine, appDirectory)
        cloudflareGateway = CloudflareGateway()

        createNotificationChannels()
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
