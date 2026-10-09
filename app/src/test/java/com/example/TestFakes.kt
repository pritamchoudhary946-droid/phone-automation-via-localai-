package com.example

import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.DeviceAutomation
import com.example.agent.UiObservation
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.model.GemmaInferenceEngine
import com.example.model.HeuristicPlanner
import com.example.model.LiteRtEngine
import com.example.model.LiteRtModelManager
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
import java.io.File

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
    var initializeCount = 0
    var closed = false

    override suspend fun initialize() {
        initializeCount++
        if (!readyAfterInitialize) throw LlmInferenceException("fake init failure")
    }

    override fun isReady(): Boolean = initializeCount > 0 && readyAfterInitialize && !closed

    override suspend fun generate(prompt: String, systemInstruction: String?, maxOutputTokens: Int): String {
        prompts.add(prompt)
        if (throwOnGenerate) throw LlmInferenceException("fake generation failure")
        return output
    }

    override suspend fun smokeTest(): Boolean = smokeOk

    override fun backendLabel(): String = backend

    override fun close() {
        closed = true
    }
}

/** Builds a real LiteRtEngine wired to an injectable fake runtime. */
fun buildTestEngine(
    context: android.content.Context,
    runtimeFactory: (String, String?) -> LlmRuntime = { _, _ -> FakeLlmRuntime() },
    modelManager: LiteRtModelManager = LiteRtModelManager(context),
    maxOutputTokens: Int = 768
): LiteRtEngine {
    // Ensure a model file exists so initialize() is exercised the same way it is on
    // a device that already downloaded the model.
    if (!modelManager.getModelFile().exists()) fakeModelFileAt(modelManager.getModelFile())
    val inference = GemmaInferenceEngine(runtimeFactory = runtimeFactory)
    val planner = AgentPlanner(
        inferenceEngine = inference,
        modelManager = modelManager,
        parser = TaskPlanParser(),
        heuristicPlanner = HeuristicPlanner(),
        maxOutputTokens = maxOutputTokens
    )
    return LiteRtEngine(context, modelManager, inference, planner)
}

/** Writes a dummy on-disk "model" file so initialization can be attempted in tests. */
fun fakeModelFile(dir: File, bytes: Int = 1024): File {
    val file = File(dir, "gemma-4-E2B-it.litertlm")
    file.writeBytes(ByteArray(bytes) { 1 })
    return file
}

/** Writes a dummy model at an explicit path (already the manager's target file). */
fun fakeModelFileAt(file: File, bytes: Int = 1024): File {
    file.parentFile?.mkdirs()
    file.writeBytes(ByteArray(bytes) { 1 })
    return file
}

fun assertReady(engine: LiteRtEngine) = assertTrue("engine should be ready", engine.isReady())

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

fun observation(pkg: String?, summary: String, fingerprintSeed: Int): UiObservation {
    val nodes = listOf(
        UiNode(
            id = "node_$fingerprintSeed",
            text = summary.take(40),
            contentDescription = null,
            className = "android.widget.TextView",
            packageName = pkg,
            bounds = android.graphics.Rect(0, 0, 100, 100),
            isClickable = false,
            isScrollable = false,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isFocused = false
        )
    )
    return UiObservation.fromSnapshot(UiTreeSnapshot(pkg, nodes, timestamp = fingerprintSeed.toLong()))
}
