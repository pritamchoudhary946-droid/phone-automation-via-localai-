package com.example.agent

import kotlinx.coroutines.delay

/** Bounded verification: dispatching an intent alone is not evidence of a successful launch. */
object ForegroundPackageVerifier {
    const val DEFAULT_ATTEMPTS = 4
    const val DEFAULT_DELAY_MILLIS = 250L

    fun matches(targetPackage: String?, observedPackage: String?): Boolean =
        !targetPackage.isNullOrBlank() && !observedPackage.isNullOrBlank() &&
            targetPackage.equals(observedPackage, ignoreCase = true)

    suspend fun awaitForegroundPackage(
        targetPackage: String,
        attempts: Int = DEFAULT_ATTEMPTS,
        delayMillis: Long = DEFAULT_DELAY_MILLIS,
        observePackage: suspend () -> String?
    ): String? {
        if (targetPackage.isBlank() || attempts <= 0) return null
        repeat(attempts) { attempt ->
            val observed = try { observePackage() } catch (_: Exception) { null }
            if (matches(targetPackage, observed)) return observed
            if (attempt < attempts - 1 && delayMillis > 0) delay(delayMillis)
        }
        return null
    }
}
