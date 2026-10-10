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
 * Shared, testable smoke-probe policy (plan 8.1). Runs up to [attempts] bounded
 * generations via [probe], growing the token budget by [tokenStep] each time.
 *
 * An EMPTY-but-no-exception answer counts as SUCCESS: a healthy Gemma-4 E2B build can
 * legitimately return an empty string for a tiny prompt (thinking-token-only output or
 * no `Content.Text`), and that must never be reported as "the model failed to load" or
 * cause a working runtime to be closed. Only generation that THROWS on every attempt is
 * a real failure.
 */
internal suspend fun runTolerantSmokeProbe(
    attempts: Int = 2,
    baseTokens: Int = 8,
    tokenStep: Int = 8,
    probe: suspend (maxOutputTokens: Int) -> String
): Boolean {
    var lastError: Throwable? = null
    for (attempt in 0 until attempts) {
        try {
            val result = probe(baseTokens + attempt * tokenStep)
            // Non-blank OR empty-without-exception: the engine is serving requests.
            Log.i(
                TAG_SMOKE,
                "Smoke probe attempt ${attempt + 1} returned '${result.take(32)}' (blank=${result.isBlank()}); engine usable"
            )
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            Log.w(TAG_SMOKE, "Smoke probe attempt ${attempt + 1} threw: ${e.message}")
        }
    }
    Log.w(TAG_SMOKE, "Smoke probe failed after $attempts attempts: ${lastError?.message}")
    return false
}

private const val TAG_SMOKE = "SmokeProbe"

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
            cacheDir = cacheDir,
            // Bound the KV/context size explicitly instead of leaving it to the model
            // default: this limits peak RAM so the engine coexists with the
            // accessibility service on an 8 GB device.
            maxNumTokens = MAX_CONTEXT_TOKENS
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

            val conversationConfig = buildConversationConfig(systemInstruction, maxOutputTokens)

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

    /**
     * Multimodal generation (plan 7.4): attaches the screenshot as an image content
     * part alongside the text prompt so the vision-capable E2B build can locate a
     * target the a11y tree missed. Falls back to the text path on any image-content
     * failure so a text-only model build cannot break a step.
     */
    override suspend fun generateWithImage(
        prompt: String,
        systemInstruction: String?,
        maxOutputTokens: Int,
        imageBytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        val eng = engine
        if (eng == null || !eng.isInitialized()) {
            throw LlmInferenceException("LiteRT-LM engine is not initialized")
        }
        val config = buildConversationConfig(systemInstruction, maxOutputTokens)
        try {
            eng.createConversation(config).use { conversation ->
                val contents = Contents.of(
                    Content.Text(prompt),
                    Content.ImageBytes(imageBytes)
                )
                extractText(conversation.sendMessage(contents))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Multimodal generation failed (${e.message}); retrying text-only")
            generate(prompt, systemInstruction, maxOutputTokens)
        }
    }

    override suspend fun smokeTest(): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) {
            Log.w(TAG, "Smoke test failed: engine not ready")
            return@withContext false
        }
        runTolerantSmokeProbe { maxTokens ->
            generate(
                prompt = "Reply with the single word: ready",
                systemInstruction = "You are a minimal health check.",
                maxOutputTokens = maxTokens
            )
        }
    }

    /**
     * Builds the real LiteRT-LM conversation config. Extracted so tests can assert
     * that the requested [maxOutputTokens] reaches [ConversationConfig.maxOutputToken]
     * without loading a 2.59 GB model.
     */
    internal fun buildConversationConfig(systemInstruction: String?, maxOutputTokens: Int): ConversationConfig =
        ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(it) },
            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2, seed = 0),
            // Apply the caller's bound to the real generation config so full-plan and
            // one-step decisions both stay bounded on-device.
            maxOutputToken = maxOutputTokens.coerceAtLeast(1)
        )

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
        // Bounded context window, raised from 2048 to 3072 to fit the richer
        // observations (page content, tool digests) while protecting peak RAM on the
        // confirmed 8 GB device. Kept conservative — not 4096.
        const val MAX_CONTEXT_TOKENS = 3072
        // Smoke probe (plan 8.1): bounded attempts, starting small and nudging the token
        // budget up. An empty answer is tolerated as success; only a thrown exception
        // (after all attempts) marks the engine unavailable.
        private const val SMOKE_TEST_ATTEMPTS = 2
        private const val SMOKE_TEST_MAX_TOKENS = 8
        private const val SMOKE_TEST_TOKEN_STEP = 8
    }
}
