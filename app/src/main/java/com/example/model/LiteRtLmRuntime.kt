package com.example.model

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Genuine on-device inference adapter around the official LiteRT-LM Kotlin API.
 *
 * Uses the CPU backend first for maximum device compatibility. GPU is opt-in and
 * falls back to CPU when acceleration initialization fails.
 *
 * All heavy work happens on [Dispatchers.IO]; the engine is created once and
 * reused for the lifetime of the adapter.
 */
class LiteRtLmRuntime(
    private val modelPath: String,
    private val cacheDir: String? = null,
    private val useGpu: Boolean = false,
    private val cpuThreads: Int = DEFAULT_CPU_THREADS
) : LlmRuntime {

    private val initialized = AtomicBoolean(false)
    private var engine: Engine? = null
    private var activeBackend: String = "CPU"

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (initialized.get() && engine?.isInitialized() == true) return@withContext

        try {
            engine = buildEngine(useGpu = useGpu)
            activeBackend = if (useGpu) "GPU" else "CPU"
            initialized.set(true)
            Log.i(TAG, "LiteRT-LM engine initialized (backend=$activeBackend, threads=$cpuThreads)")
        } catch (gpuError: Throwable) {
            if (useGpu) {
                Log.w(TAG, "GPU backend failed, falling back to CPU: ${gpuError.message}")
                try {
                    engine = buildEngine(useGpu = false)
                    activeBackend = "CPU"
                    initialized.set(true)
                    Log.i(TAG, "LiteRT-LM engine initialized on CPU fallback after GPU failure")
                } catch (cpuError: Throwable) {
                    initialized.set(false)
                    engine = null
                    throw LlmInferenceException("LiteRT-LM engine initialization failed on CPU fallback: ${cpuError.message}", cpuError)
                }
            } else {
                initialized.set(false)
                engine = null
                throw LlmInferenceException("LiteRT-LM engine initialization failed: ${gpuError.message}", gpuError)
            }
        }
    }

    private fun buildEngine(useGpu: Boolean): Engine {
        val backend: Backend = if (useGpu) {
            Backend.GPU()
        } else {
            Backend.CPU(threadCount = cpuThreads, numOfThreads = cpuThreads)
        }
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir
        )
        val newEngine = Engine(config)
        newEngine.initialize()
        return newEngine
    }

    override fun isReady(): Boolean = initialized.get() && engine?.isInitialized() == true

    override fun backendLabel(): String = activeBackend

    override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String =
        withContext(Dispatchers.IO) {
            val eng = engine
            if (eng == null || !eng.isInitialized()) {
                throw LlmInferenceException("LiteRT-LM engine is not initialized")
            }

            val conversationConfig = ConversationConfig(
                systemInstruction = systemInstruction?.let { Contents.of(it) },
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2, seed = 0)
            )

            try {
                eng.createConversation(conversationConfig).use { conversation ->
                    val message: Message = conversation.sendMessage(prompt)
                    extractText(message)
                }
            } catch (e: LlmInferenceException) {
                throw e
            } catch (e: Throwable) {
                throw LlmInferenceException("LiteRT-LM generation failed: ${e.message}", e)
            }
        }

    override suspend fun smokeTest(): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext false
        try {
            val result = generate(
                prompt = "Reply with the single word: ready",
                systemInstruction = "You are a minimal health check.",
                maxOutputTokens = 8
            )
            result.isNotBlank()
        } catch (e: Throwable) {
            Log.w(TAG, "Smoke test inference failed: ${e.message}")
            false
        }
    }

    private fun extractText(message: Message): String {
        return message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
            .trim()
    }

    override fun close() {
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing LiteRT-LM engine: ${e.message}")
        } finally {
            engine = null
            initialized.set(false)
        }
    }

    companion object {
        private const val TAG = "LiteRtLmRuntime"
        // Conservative thread budget on an 8 GB device that also runs an accessibility service.
        const val DEFAULT_CPU_THREADS = 4
    }
}
