package com.example.privileged

/**
 * ADB-level (privileged) actions without root, gated behind Shizuku (plan 4.5).
 *
 * Advanced/opt-in: every call is refused unless Shizuku is reported running, so
 * nothing privileged is ever reachable by default. Kept as a seam (no hard Shizuku
 * dependency) so the loop can be tested without the Shizuku runtime.
 */
interface PrivilegedActionBridge {
    /** True only when a privileged channel is actually available right now. */
    fun isAvailable(): Boolean

    /** A privileged setting write (e.g. `settings put ...`). Returns true on success. */
    fun setSystemSetting(namespace: String, key: String, value: String): Boolean

    /** Forces a package into a stopped/disabled state. Returns true on success. */
    fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean
}

/**
 * Pure availability gate: privileged calls are permitted only when Shizuku reports
 * a running binder AND the user opted in. Unit-testable without Shizuku.
 */
object ShizukuGate {
    fun permit(optIn: Boolean, shizukuRunning: Boolean): Boolean = optIn && shizukuRunning
}

/**
 * Default bridge. Reports unavailable unless both the opt-in flag is set and the
 * injected "Shizuku running" probe says so; every privileged action is refused
 * (returns false) when unavailable.
 */
class GatedPrivilegedBridge(
    private val optIn: Boolean,
    private val shizukuRunning: () -> Boolean
) : PrivilegedActionBridge {

    private val exec: ((String) -> Boolean)? = null

    override fun isAvailable(): Boolean = ShizukuGate.permit(optIn, shizukuRunning())

    override fun setSystemSetting(namespace: String, key: String, value: String): Boolean {
        if (!isAvailable()) return false
        // The real implementation shells out via the Shizuku binder here. Absent a
        // configured executor, refuse rather than pretend success.
        return exec?.invoke("settings put $namespace $key $value") ?: false
    }

    override fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!isAvailable()) return false
        val verb = if (enabled) "enable" else "disable"
        return exec?.invoke("pm $verb $packageName") ?: false
    }
}
