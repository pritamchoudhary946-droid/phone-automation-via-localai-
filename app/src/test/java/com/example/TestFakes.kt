package com.example

import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.DeviceAutomation
import com.example.agent.UiObservation
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.model.AgentEngine
import com.example.model.ApiInferenceEngine
import com.example.model.HeuristicPlanner
import com.example.model.LlmInferenceException
import com.example.model.LlmRuntime
import com.example.planning.AgentPlanner
import com.example.planning.TaskPlanParser
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo

/** Installs a fake launcher package in Robolectric so AppDirectory can resolve it. */
fun installLauncherPackage(context: Context, packageName: String, label: String) {
    val appInfo = ApplicationInfo().apply {
        this.packageName = packageName
        nonLocalizedLabel = label
    }
    val packageInfo = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = appInfo
    }
    val packageManager = context.packageManager
    shadowOf(packageManager).installPackage(packageInfo)

    val activityInfo = ActivityInfo().apply {
        this.packageName = packageName
        name = "$packageName.MainActivity"
        applicationInfo = appInfo
        exported = true
    }
    val resolveInfo = ResolveInfo().apply { this.activityInfo = activityInfo }
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    shadowOf(packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
}

/**
 * In-memory [LlmRuntime] used to exercise the genuine inference seam without a
 * 2.59 GB model. It records prompts and returns whatever was configured, so tests
 * can prove the planner consumes the runtime output instead of canned text.
 */
class FakeLlmRuntime(
    var readyAfterInitialize: Boolean = true,
    var output: String = "",
    var throwOnGenerate: Boolean = false,
    var smokeOk: Boolean = true,
    var backend: String = "CPU"
) : LlmRuntime {

    val prompts = mutableListOf<String>()
    val requestedMaxOutputTokens = mutableListOf<Int>()
    var initializeCount = 0
    var smokeTestCount = 0
    var closed = false

    override suspend fun initialize() {
        initializeCount++
        if (!readyAfterInitialize) throw LlmInferenceException("fake init failure")
    }

    override fun isReady(): Boolean = initializeCount > 0 && readyAfterInitialize && !closed

    override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String {
        prompts.add(prompt)
        requestedMaxOutputTokens.add(maxOutputTokens)
        if (throwOnGenerate) throw LlmInferenceException("fake generation failure")
        return output
    }

    override suspend fun smokeTest(): Boolean = smokeOk.also { smokeTestCount++ }

    override fun backendLabel(): String = backend

    override fun close() {
        closed = true
    }
}

/** Builds a real AgentEngine wired to an injectable fake runtime (no network). */
fun buildTestEngine(
    context: android.content.Context,
    runtimeFactory: () -> LlmRuntime = { FakeLlmRuntime() },
    maxOutputTokens: Int = 768
): AgentEngine {
    val inference = ApiInferenceEngine(runtimeFactory = runtimeFactory)
    val planner = AgentPlanner(
        inferenceEngine = inference,
        parser = TaskPlanParser(),
        heuristicPlanner = HeuristicPlanner(),
        maxOutputTokens = maxOutputTokens
    )
    return AgentEngine(context, inference, planner)
}

fun assertReady(engine: AgentEngine) = assertTrue("engine should be ready", engine.isReady())

/** Deterministic device automation double for the orchestrator loop tests. */
class FakeDeviceAutomation(
    private val ready: Boolean = true,
    private val reason: String? = null
) : DeviceAutomation {
    val executed = mutableListOf<AgentAction>()
    var observationQueue = ArrayDeque<UiObservation>()
    var defaultObservation = observation("com.android.launcher", "Home screen", 1)
    var executeResult: (AgentAction) -> ActionResult = { ActionResult(true, "FAKE", "ok") }

    override fun isReady(): Boolean = ready

    override fun unavailabilityReason(): String? = if (ready) null else (reason ?: "not ready")

    override suspend fun observe(): UiObservation =
        if (observationQueue.isNotEmpty()) observationQueue.removeFirst() else defaultObservation

    override suspend fun execute(action: AgentAction): ActionResult {
        executed.add(action)
        return executeResult(action)
    }
}

/**
 * In-memory [LlmRuntime] that returns a different canned output on each successive
 * `generate` call. Used to observe replanning: the first planning call gets one plan,
 * the next gets another.
 */
class SequencedLlmRuntime(private val outputs: List<String>) : LlmRuntime by FakeLlmRuntime() {
    private var index = 0

    override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String {
        val out = outputs.getOrElse(index) { outputs.lastOrNull() ?: "" }
        index++
        return out
    }
}

fun observation(
    pkg: String?,
    summary: String,
    fingerprintSeed: Int,
    editable: Boolean = false,
    clickable: Boolean = true
): UiObservation {
    val nodes = listOf(
        UiNode(
            id = "node_$fingerprintSeed",
            text = summary.take(40),
            contentDescription = null,
            className = if (editable) "android.widget.EditText" else "android.widget.TextView",
            packageName = pkg,
            bounds = android.graphics.Rect(0, 0, 100, 100),
            isClickable = clickable,
            isScrollable = false,
            isEditable = editable,
            isCheckable = false,
            isChecked = false,
            isFocused = editable
        )
    )
    return UiObservation.fromSnapshot(UiTreeSnapshot(pkg, nodes, timestamp = fingerprintSeed.toLong()))
}

/**
 * An observation whose visible labels are set explicitly, for exercising the
 * obstacle detector (plan 9.12) and other label-driven seams in tests.
 */
fun observationText(
    pkg: String?,
    summary: String,
    fingerprintSeed: Int,
    labels: List<String>
): UiObservation {
    val nodes = labels.mapIndexed { i, label ->
        UiNode(
            id = "node_${fingerprintSeed}_$i",
            text = label,
            contentDescription = null,
            className = "android.widget.TextView",
            packageName = pkg,
            bounds = android.graphics.Rect(0, i * 20, 100, i * 20 + 20),
            isClickable = true,
            isScrollable = false,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isFocused = false
        )
    }
    return UiObservation.fromSnapshot(UiTreeSnapshot(pkg, nodes, timestamp = fingerprintSeed.toLong()))
        .copy(summary = summary)
}
