package com.example.agent

import kotlinx.coroutines.delay

/** Bounded verification: dispatching an intent alone is not evidence of a successful launch. */
object ForegroundPackageVerifier {
    const val DEFAULT_ATTEMPTS = 4
    const val DEFAULT_DELAY_MILLIS = 250L

    fun matches(targetPackage: String?, observedPackage: String?): Boolean =
        !targetPackage.isNullOrBlank() && !observedPackage.isNullOrBlank() &&
            targetPackage.equals(observedPackage, ignoreCase = true)

    /**
     * True when [packageName] looks like the device home/launcher surface.
     *
     * Single source of truth for "are we home?", used by the postcondition
     * verifier, the hybrid fast path and the heuristic planner. It covers AOSP,
     * Pixel and the common OEM launchers whose package name does not contain
     * "launcher" (e.g. `com.miui.home`), which previously made a genuine
     * "return home" step look unverified and retry forever.
     */
    fun isHomePackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase() ?: return false
        if (pkg.isBlank()) return false
        // Never treat SystemUI as home: it owns the notification shade and quick
        // settings, so a Home step performed while the shade is open would be
        // declared complete even though the launcher was never reached.
        return pkg.contains("launcher") ||
            pkg.endsWith(".home") ||
            pkg.contains("miui.home") ||
            pkg.contains("touchwiz") ||
            pkg.contains("trebuchet") ||
            pkg.contains("lawnchair")
    }

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
