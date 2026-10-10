package com.example.privileged

import android.util.Log

/**
 * ADB-level (privileged) actions without root, gated behind Shizuku (plan 4.5 / 7.7).
 *
 * Advanced/opt-in: every call is refused unless Shizuku is reported running AND the
 * user opted in, so nothing privileged is ever reachable by default. The Shizuku
 * runtime is reached reflectively (no compile-time dependency), so the loop stays
 * unit-testable without it and a missing/unexpected Shizuku version degrades to an
 * honest refusal instead of a crash.
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
 * Best-effort reflective bridge to the Shizuku runtime (plan 7.7). This build does not
 * compile against Shizuku, so it is located by reflection at runtime:
 * - [isBinderRunning] calls `Shizuku.pingBinder()` when the class is on the classpath.
 * - [commandRunner] returns a lambda that runs a shell command through
 *   `Shizuku.newProcess(...)`, or null when Shizuku is absent/unavailable.
 *
 * Any reflection failure returns the honest "unavailable/refused" result — the bridge
 * never fabricates success.
 */
object ShizukuRuntime {

    private const val TAG = "ShizukuRuntime"
    private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"

    fun isBinderRunning(): Boolean = try {
        val clazz = Class.forName(SHIZUKU_CLASS)
        val ping = clazz.getMethod("pingBinder")
        (ping.invoke(null) as? Boolean) ?: false
    } catch (t: Throwable) {
        false
    }

    /** A `(command) -> exitCodeWasZero` runner backed by Shizuku, or null when absent. */
    fun commandRunner(): ((String) -> Boolean)? {
        return try {
            val clazz = Class.forName(SHIZUKU_CLASS)
            val newProcess = clazz.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val runner: (String) -> Boolean = { command ->
                try {
                    val parts = arrayOf("sh", "-c", command)
                    val process = newProcess.invoke(null, parts, null, null) as? Process
                    process != null && process.waitFor() == 0
                } catch (t: Throwable) {
                    Log.w(TAG, "Shizuku command failed: ${t.message}")
                    false
                }
            }
            runner
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * Default bridge. Reports unavailable unless both the opt-in flag is set and the
 * injected "Shizuku running" probe says so; every privileged action is refused
 * (returns false) when unavailable. A command runner (e.g. [ShizukuRuntime.commandRunner])
 * can be injected to make the actions real.
 */
class GatedPrivilegedBridge(
    private val optIn: () -> Boolean,
    private val shizukuRunning: () -> Boolean,
    private val exec: ((String) -> Boolean)? = null
) : PrivilegedActionBridge {

    override fun isAvailable(): Boolean = ShizukuGate.permit(optIn(), shizukuRunning())

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
