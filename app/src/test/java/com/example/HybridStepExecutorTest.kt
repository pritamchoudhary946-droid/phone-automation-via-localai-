package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.UiNode
import com.example.agent.AgentAction
import com.example.agent.AppDirectory
import com.example.agent.FastPathDecision
import com.example.agent.HybridStepExecutor
import com.example.agent.UiObservation
import com.example.planning.PlanStep
import com.example.planning.VerificationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hybrid fast path must recognise, from the accessibility tree alone, which
 * steps need no model call, and must defer anything ambiguous to the model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridStepExecutorTest {

    private lateinit var executor: HybridStepExecutor

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        installLauncherPackage(context, "com.google.android.youtube", "YouTube")
        executor = HybridStepExecutor(AppDirectory(context))
    }

    @Test
    fun launchIsDeterministicWhenTargetNotForeground() {
        val steps = listOf(step(1, AgentAction.OpenApp("youtube", null), VerificationMethod.FOREGROUND_PACKAGE))
        val decision = executor.decide(steps, 0, obs("com.android.launcher", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
        assertEquals("youtube", ((decision as FastPathDecision.Execute).action as AgentAction.OpenApp).appQuery)
    }

    @Test
    fun launchIsAlreadySatisfiedWhenTargetForeground() {
        val steps = listOf(step(1, AgentAction.OpenApp("youtube", null), VerificationMethod.FOREGROUND_PACKAGE))
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Satisfied)
    }

    @Test
    fun openSearchIsDeterministicWhenSearchAffordanceVisible() {
        val steps = listOf(step(1, AgentAction.OpenSearch("lo-fi beats"), VerificationMethod.SCREEN_CHANGED))
        val searchIcon = node("Search", null, "com.google.android.youtube:id/search_button", "com.google.android.youtube", clickable = true)
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", listOf(searchIcon)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
        assertTrue((decision as FastPathDecision.Execute).action is AgentAction.OpenSearch)
    }

    @Test
    fun openSearchIsSatisfiedWhenEditableFieldAlreadyPresent() {
        val steps = listOf(step(1, AgentAction.OpenSearch("lo-fi"), VerificationMethod.SCREEN_CHANGED))
        val field = node(null, null, "com.google.android.youtube:id/search_edit", "com.google.android.youtube", editable = true)
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", listOf(field)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Satisfied)
    }

    @Test
    fun textEntryIsDeterministicWhenEditableFieldPresent() {
        val steps = listOf(step(1, AgentAction.TypeText("lo-fi beats", null), VerificationMethod.RESULTS_PRESENT))
        val field = node(null, null, "com.google.android.youtube:id/search_edit", "com.google.android.youtube", editable = true)
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", listOf(field)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
        assertEquals("lo-fi beats", ((decision as FastPathDecision.Execute).action as AgentAction.TypeText).text)
    }

    @Test
    fun textEntryAlwaysExecutesEvenIfFieldLooksPreFilled() {
        // Text entry must actually run (the field may need an explicit submit), so it
        // is never short-circuited as "already satisfied".
        val steps = listOf(step(1, AgentAction.TypeText("lo-fi beats", null), VerificationMethod.RESULTS_PRESENT))
        val field = node("lo-fi beats", null, "com.google.android.youtube:id/search_edit", "com.google.android.youtube", editable = true)
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", listOf(field)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
    }

    @Test
    fun textEntryWithoutEditableFieldDefersToModel() {
        val steps = listOf(step(1, AgentAction.TypeText("lo-fi beats", null), VerificationMethod.RESULTS_PRESENT))
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.ModelReason)
    }

    @Test
    fun openSearchExecutesWithNativeFallbackWhenNoAffordanceVisible() {
        // A foreground app but no search affordance in the tree: the native layer still
        // has the bounded ACTION_SEARCH intent fallback, so this stays on the fast path.
        val steps = listOf(step(1, AgentAction.OpenSearch("lo-fi beats"), VerificationMethod.SCREEN_CHANGED))
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
    }

    @Test
    fun openSearchDefersToModelWhenScreenUnknown() {
        // No usable package: the model should figure out what is going on.
        val steps = listOf(step(1, AgentAction.OpenSearch("lo-fi beats"), VerificationMethod.SCREEN_CHANGED))
        val decision = executor.decide(steps, 0, obs(null, emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.ModelReason)
    }

    @Test
    fun openItemUsesObservedMatchingResult() {
        val steps = listOf(
            step(1, AgentAction.TypeText("lo-fi beats", null), VerificationMethod.RESULTS_PRESENT),
            step(2, AgentAction.OpenItem("lo-fi beats"), VerificationMethod.TEXT_PRESENT)
        )
        val result = node("Lo-fi beats radio", null, "com.google.android.youtube:id/result", "com.google.android.youtube", clickable = true)
        val decision = executor.decide(steps, 1, obs("com.google.android.youtube", listOf(result)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
        assertTrue((decision as FastPathDecision.Execute).action is AgentAction.OpenItem)
    }

    @Test
    fun openItemWaitsOnceThenDefersToModel() {
        val steps = listOf(
            step(1, AgentAction.TypeText("lo-fi beats", null), VerificationMethod.RESULTS_PRESENT),
            step(2, AgentAction.OpenItem("lo-fi beats"), VerificationMethod.TEXT_PRESENT)
        )
        val empty = obs("com.google.android.youtube", emptyList())
        val first = executor.decide(steps, 1, empty, nudgeUsed = false)
        assertTrue(first is FastPathDecision.Execute)
        assertTrue((first as FastPathDecision.Execute).action is AgentAction.Wait)
        val second = executor.decide(steps, 1, empty, nudgeUsed = true)
        assertTrue(second is FastPathDecision.ModelReason)
    }

    @Test
    fun homeIsDeterministicUnlessAlreadyHome() {
        val steps = listOf(step(1, AgentAction.Home, VerificationMethod.HOME_FOREGROUND))
        assertTrue(executor.decide(steps, 0, obs("com.google.android.youtube", emptyList()), false) is FastPathDecision.Execute)
        assertTrue(executor.decide(steps, 0, obs("com.android.launcher", emptyList()), false) is FastPathDecision.Satisfied)
    }

    @Test
    fun unrelatedPackagedNodeDoesNotCountAsEditable() {
        // An editable node belonging to a different package must not be used.
        val steps = listOf(step(1, AgentAction.TypeText("x", null), VerificationMethod.TEXT_PRESENT))
        val foreign = node(null, null, "other:id/edit", "com.other.app", editable = true)
        val decision = executor.decide(steps, 0, obs("com.google.android.youtube", listOf(foreign)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.ModelReason)
    }

    @Test
    fun tapIsDeterministicWhenTargetIsObserved() {
        val steps = listOf(step(1, AgentAction.Tap("Wi-Fi", null, null), VerificationMethod.SCREEN_CHANGED))
        val toggle = node("Wi-Fi", null, "com.android.settings:id/wifi", "com.android.settings", clickable = true)
        val decision = executor.decide(steps, 0, obs("com.android.settings", listOf(toggle)), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.Execute)
        assertTrue((decision as FastPathDecision.Execute).action is AgentAction.Tap)
    }

    @Test
    fun tapDefersToModelWhenTargetNotObserved() {
        val steps = listOf(step(1, AgentAction.Tap("Wi-Fi", null, null), VerificationMethod.SCREEN_CHANGED))
        val decision = executor.decide(steps, 0, obs("com.android.settings", emptyList()), nudgeUsed = false)
        assertTrue(decision is FastPathDecision.ModelReason)
    }

    // ---- helpers ----

    private var fp = 0
    private fun obs(pkg: String?, nodes: List<UiNode>): UiObservation =
        UiObservation(pkg, nodes, "Active Package: ${pkg ?: "unknown"}", fingerprint = "fp_${pkg}_${fp++}")

    private fun step(id: Int, action: AgentAction, verify: VerificationMethod): PlanStep = PlanStep(
        id = id,
        sequence = id,
        description = action.getDisplayName(),
        action = action,
        rawActionName = action.javaClass.simpleName,
        expectedPostcondition = "post",
        verification = verify
    )

    private fun node(
        text: String?,
        desc: String?,
        id: String,
        pkg: String?,
        clickable: Boolean = false,
        editable: Boolean = false
    ): UiNode = UiNode(
        id = id,
        text = text,
        contentDescription = desc,
        className = if (editable) "android.widget.EditText" else "android.widget.TextView",
        packageName = pkg,
        bounds = Rect(0, 0, 200, 80),
        isClickable = clickable,
        isScrollable = false,
        isEditable = editable,
        isCheckable = false,
        isChecked = false,
        isFocused = editable
    )
}
