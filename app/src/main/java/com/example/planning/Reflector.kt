package com.example.planning

import android.util.Log
import com.example.model.GemmaInferenceEngine

/**
 * Hierarchical reflection (plan 5.4). Reviews the last action against the current
 * subgoal and the live screen and returns one short diagnostic sentence. It is called
 * ONLY on a failure or a detected stall (reflection-on-demand) so an 8 GB device does
 * not pay for an extra model pass on every healthy step. When the model is unavailable
 * it returns the deterministic failure reason rather than a fabricated diagnosis.
 */
class Reflector(
    private val inferenceEngine: GemmaInferenceEngine,
    private val maxOutputTokens: Int = 160
) {

    suspend fun reflect(request: ReflectionRequest): String {
        if (!inferenceEngine.isReady()) {
            return request.failureReason.ifBlank { "No model available for reflection" }
        }
        val raw = try {
            inferenceEngine.generate(
                PlannerPromptBuilder.buildReflectionPrompt(request),
                PlannerPromptBuilder.SYSTEM_INSTRUCTION,
                maxOutputTokens
            )
        } catch (e: Exception) {
            Log.w(TAG, "Reflection generate threw: ${e.message}")
            null
        }
        return raw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
            ?.take(300)
            ?: request.failureReason.ifBlank { "Reflection unavailable" }
    }

    private companion object {
        const val TAG = "Reflector"
    }
}
