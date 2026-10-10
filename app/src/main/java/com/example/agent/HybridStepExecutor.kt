package com.example.agent

import com.example.accessibility.UiNode
import com.example.planning.PlanStep

/** Outcome of evaluating one plan step against the deterministic fast path. */
sealed class FastPathDecision {
    /** A native, deterministic action can satisfy the step; no model call is needed. */
    data class Execute(val action: AgentAction, val reason: String) : FastPathDecision()

    /** The step's intended outcome already holds in the current observation. */
    data class Satisfied(val reason: String) : FastPathDecision()

    /** The step needs semantic reasoning; defer to the local model. */
    data class ModelReason(val reason: String) : FastPathDecision()
}

/**
 * Deterministic executor layer for the hybrid loop.
 *
 * For a step whose intent can be satisfied purely from the accessibility tree
 * (launch an installed app, open the search interface, type into an observed
 * field, press Home/Back, or open an observed result), the fast path returns the
 * concrete native action directly, so the on-device model never has to be
 * invoked for that step. That keeps the automation layer fast: a typical
 * "open app -> search -> open result -> home" run needs at most one model call
 * (for the initial plan) instead of one call per step.
 *
 * It never executes arbitrary actions: every returned action is one of the
 * approved [AgentAction] variants and is still validated by [ToolRegistry]
 * before dispatch. Anything ambiguous is deferred to the model, so the fast path
 * cannot silently do the wrong thing.
 */
class HybridStepExecutor(private val appDirectory: AppDirectory) {

    fun decide(
        steps: List<PlanStep>,
        index: Int,
        observation: UiObservation,
        nudgeUsed: Boolean
    ): FastPathDecision {
        val current = steps.getOrNull(index)
            ?: return FastPathDecision.ModelReason("no current step")
        val remaining = steps.drop(index)
        val typeStep = remaining.firstOrNull { it.action is AgentAction.TypeText }
        val query = (steps.firstOrNull { it.action is AgentAction.TypeText }?.action as? AgentAction.TypeText)?.text
            ?: (typeStep?.action as? AgentAction.TypeText)?.text

        return when (val action = current.action) {
            is AgentAction.OpenApp -> {
                if (targetPackageFor(action, observation) == observation.packageName) {
                    FastPathDecision.Satisfied("target package is already foreground")
                } else {
                    FastPathDecision.Execute(action, "deterministic app launch")
                }
            }

            is AgentAction.OpenSearch -> {
                if (observation.hasSearchInputField()) {
                    FastPathDecision.Satisfied("a search-specific input field is already on screen")
                } else if (observation.packageName != null) {
                    FastPathDecision.Execute(
                        AgentAction.OpenSearch(action.query ?: query),
                        "deterministic search-interface open from the observed tree"
                    )
                } else {
                    FastPathDecision.ModelReason("no foreground package is known to open search in")
                }
            }

            is AgentAction.TypeText -> {
                if (!editableFieldVisible(observation)) {
                    FastPathDecision.ModelReason("no editable field is exposed to type into")
                } else {
                    FastPathDecision.Execute(AgentAction.TypeText(action.text, null), "deterministic text entry")
                }
            }

            is AgentAction.Submit -> FastPathDecision.Execute(
                action,
                "deterministic submit of the current text entry"
            )

            is AgentAction.OpenUrl -> FastPathDecision.Execute(
                action,
                "deterministic open of an http(s) URL"
            )

            is AgentAction.DeepLink -> FastPathDecision.Execute(
                action,
                "deterministic open of an app deep link"
            )

            is AgentAction.Drag -> {
                if (observation.hasActivatableLabel(action.fromText, allowEditable = true) &&
                    observation.hasActivatableLabel(action.toText, allowEditable = true)
                ) {
                    FastPathDecision.Execute(action, "deterministic drag between observed controls")
                } else {
                    FastPathDecision.ModelReason("drag endpoints are not both visible")
                }
            }

            is AgentAction.CopyText -> FastPathDecision.Execute(
                action,
                "deterministic clipboard copy"
            )

            is AgentAction.PasteText -> {
                if (editableFieldVisible(observation)) {
                    FastPathDecision.Execute(action, "deterministic clipboard paste into the focused field")
                } else {
                    FastPathDecision.ModelReason("no editable field is exposed to paste into")
                }
            }

            is AgentAction.ReadPageContent -> FastPathDecision.Execute(
                action,
                "deterministic page-content extraction"
            )

            // Web tools require a network round-trip; the loop executes them directly
            // (they are explicit user-intent web tasks, not ambiguous UI decisions).
            is AgentAction.WebSearch -> FastPathDecision.Execute(action, "deterministic web search")
            is AgentAction.ReadUrl -> FastPathDecision.Execute(action, "deterministic read-url fetch")

            is AgentAction.Home -> {
                if (isHomeForeground(observation.packageName)) {
                    FastPathDecision.Satisfied("home launcher is already foreground")
                } else {
                    FastPathDecision.Execute(AgentAction.Home, "deterministic navigate home")
                }
            }

            AgentAction.Back -> FastPathDecision.Execute(AgentAction.Back, "deterministic navigate back")
            is AgentAction.Wait -> FastPathDecision.Execute(AgentAction.Wait(action.durationMs), "deterministic wait")

            is AgentAction.Tap -> {
                val target = action.targetText
                if (target.isNullOrBlank()) {
                    FastPathDecision.ModelReason("a coordinate-only tap needs model reasoning")
                } else if (observation.hasActivatableLabel(target, allowEditable = true)) {
                    FastPathDecision.Execute(AgentAction.Tap(target, null, null), "deterministic tap on an observed control")
                } else {
                    FastPathDecision.ModelReason("target '$target' is not visible to tap deterministically")
                }
            }

            is AgentAction.Scroll -> FastPathDecision.Execute(
                AgentAction.Scroll(action.direction),
                "deterministic scroll"
            )

            // These carry their own target or are pure system navigation: they need no
            // semantic reasoning, so the model is never consulted for them.
            is AgentAction.LongPress -> {
                if (action.targetText.isNullOrBlank()) {
                    // Validation rejects a targetless long press, so executing it here
                    // would only burn an attempt; let the model resolve the target.
                    FastPathDecision.ModelReason("long press has no target text to resolve")
                } else if (observation.hasActivatableLabel(action.targetText, allowEditable = true)) {
                    FastPathDecision.Execute(action, "deterministic long press on an observed control")
                } else {
                    FastPathDecision.ModelReason("long-press target '${action.targetText}' is not visible")
                }
            }

            is AgentAction.ScrollUntilFound -> FastPathDecision.Execute(
                action,
                "deterministic bounded scroll-to-find"
            )

            is AgentAction.WaitForText -> FastPathDecision.Execute(
                action,
                "deterministic wait for text"
            )

            AgentAction.ReadText,
            AgentAction.OpenNotifications,
            AgentAction.OpenQuickSettings,
            AgentAction.PressRecents -> FastPathDecision.Execute(
                action,
                "deterministic system/read action"
            )

            is AgentAction.OpenItem -> {
                val match = if (query != null) {
                    observation.nodes.firstOrNull {
                        !it.isEditable &&
                            it.packageName == observation.packageName &&
                            it.containsText(query) &&
                            observation.hasActivatableLabel(query, allowEditable = false)
                    }
                } else {
                    null
                }
                when {
                    match != null -> FastPathDecision.Execute(
                        AgentAction.OpenItem(match.bestLabel()),
                        "deterministic open of the observed matching result"
                    )
                    // Results often need a moment after the query is submitted; wait once
                    // (bounded by [nudgeUsed]) so the next observation can find the item.
                    !nudgeUsed -> FastPathDecision.Execute(
                        AgentAction.Wait(500L),
                        "waiting for results to render before opening a result"
                    )
                    else -> FastPathDecision.ModelReason("no matching result is visible to open deterministically")
                }
            }

            else -> FastPathDecision.ModelReason("action '${current.rawActionName}' needs model reasoning")
        }
    }

    fun targetPackageFor(action: AgentAction.OpenApp, observation: UiObservation): String? =
        if (action.packageName.equals(observation.packageName, ignoreCase = true)) {
            action.packageName
        } else {
            action.packageName ?: appDirectory.resolvePackage(action.appQuery)
        }

    private fun editableFieldVisible(observation: UiObservation): Boolean =
        observation.nodes.any { it.isLikelyTextInput() && inForegroundPackage(it, observation) }

    private fun inForegroundPackage(node: UiNode, observation: UiObservation): Boolean =
        node.packageName == null || node.packageName == observation.packageName

    private fun isHomeForeground(pkg: String?): Boolean =
        ForegroundPackageVerifier.isHomePackage(pkg)
}
