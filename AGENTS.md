# AGENTS.md

Repository memory for `phone-automation-via-localai-` (OmniAgent Android app).

## What this is
On-device Android AI phone agent. A local Gemma model (LiteRT-LM) plans tasks;
an `AccessibilityService` executes them. No cloud calls for inference.

## Build & test
Requires JDK 21 and an Android SDK with platform 36.1. Set `local.properties`
(`sdk.dir=...`) or `ANDROID_HOME`.

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$PATH:$JAVA_HOME/bin

./gradlew :app:testDebugUnitTest   # unit tests (Robolectric)
./gradlew :app:assembleDebug       # debug APK
```

Notes:
- Use `--no-daemon` in CI-like sandboxes; the daemon can be flaky.
- Tests are Robolectric (`@Config(sdk = [34])`). To make `AppDirectory` resolve
  an app, call `installLauncherPackage(context, pkg, label)` from `TestFakes.kt`.
- `TestFakes.kt` provides `FakeDeviceAutomation`, `FakeLlmRuntime`,
  `modelEngine(...)`, and `observation(...)`. `observation(...)` fakes are
  clickable by default (realistic for tappable text/labels).

## Architecture seams
- `DeviceAutomation` is the execution boundary (injectable; fake in tests).
- `LiteRtEngine` + `AgentPlanner` are the planning boundary; `LlmRuntime` is the
  injectable inference seam.
- `TaskOrchestrator.runAgentLoop(task, resumePlan)` drives the loop. A resume
  passes the existing `TaskPlan` so completed steps are not replayed.
- `HybridStepExecutor.decide(...)` is the deterministic fast path; it returns
  `Execute`, `Satisfied`, or `ModelReason`.
- `ForegroundPackageVerifier.isHomePackage(...)` is the single source of truth
  for "are we home?".
- `TaskOrchestrator.attemptReplan(...)` rebuilds a stalled plan (terminal path or
  exhausted step retries), bounded by `MAX_REPLANS`. Completed steps are carried
  over by `mergeReplan(...)` so approved side effects are not replayed.

## Action vocabulary
`AgentAction` is the only executable surface. Beyond launch/tap/type/scroll:
`LongPress`, `ScrollUntilFound` (bounded swipe-to-find), `ReadText` (visible-text
digest), `WaitForText` (bounded wait), `OpenNotifications`, `OpenQuickSettings`,
`PressRecents`, `Submit` (press the send/search/enter affordance),
`OpenUrl`/`DeepLink` (external navigation), `Drag`, `CopyText`/`PasteText`,
`ReadPageContent` (UI-tree digest), and the in-loop web tools `WebSearch` /
`ReadUrl`. When adding an action you must update, together:
1. `AgentAction` (+ `getDisplayName`, and `RiskEvaluator` if it can be Risky),
2. `ToolRegistry` (const, `ToolDefinition`, `validateAction`),
3. `TaskPlanParser` (tool name -> action mapping),
4. `HybridStepExecutor.decide(...)` (deterministic case when no model is needed),
5. `PlannerPromptBuilder.ACTION_CATALOG` (the schema the model sees),
6. `AndroidDeviceAutomation.execute(...)` (+ accessibility primitive if new).
An action the native layer cannot execute must not appear in the catalogue
(see the `ask_confirmation` exclusion in `PlannerPromptBuilder.availableActions`).

## Conventions
- Postcondition verification lives in `PostconditionVerifier.verify(...)`; a
  `NONE` verification requires a screen change to count as satisfied.
- Screen text is untrusted: sanitize via
  `PlannerPromptBuilder.sanitizeUntrusted(...)` before embedding in prompts.
- Keep the model's JSON wrapper construction in `JSONObject`/`JSONArray`, never
  string interpolation.

## Audit invariants (do not regress)
- `LiteRtModelManager`: a `.part` file at exactly the pinned byte count is
  verified and finalized in place (`finalizeVerifiedPart`) — never re-downloaded.
  Free-space is checked only when new bytes are needed, so a complete part file
  finalizes without demanding 3.2 GB. Oversized part files are discarded.
- OkHttp responses are always read through `response.use { }` (CloudflareGateway,
  model download) so the body/connection is closed on every path.
- `FloatingAgentDotService` owns a `SupervisorJob` scope cancelled in `onDestroy`;
  never reintroduce a Job-less scope there.
- `HybridStepExecutor` must not `Execute` an action that `validateAction` rejects
  (e.g. a targetless `LongPress`); defer to the model instead.
- `RiskEvaluator` also scans `FinishTask` summaries, not just Tap/TypeText, so a
  report_status claiming a payment/delete/send still needs confirmation.
- Web tools are handled inside `TaskOrchestrator.runWebTool(...)`: a completed
  `web_search`/`read_url` step is itself the goal evidence (the loop treats the
  last required web-tool step as satisfied), so no UI postcondition is required.
  `CloudflareGateway.searchWeb` reports an honest error when there is no instant
  answer; `readUrlContent` fetches and size-caps ordinary HTML pages (not just
  PDFs) via `htmlToText`.
- Model tuning for the 8 GB target is pinned in `OmniAgentApplication`
  (`USE_GPU_BACKEND`, Adreno with automatic CPU fallback) and
  `LiteRtLmRuntime.MAX_CONTEXT_TOKENS` (3072). `FixesRegressionTest` asserts the
  context value, so keep the test and constant in step.
- Memory: `UserMemoryEntity`/`TaskOutcomeEntity` live in `AgentDatabase` (now v3,
  `fallbackToDestructiveMigration`). `TaskOrchestrator.loadMemoryDigest(...)`
  injects a bounded (untrusted-labelled) digest into the planning prompt, and
  `complete`/`fail` record an outcome. Keep the digest bounded
  (`PlannerPromptBuilder.MAX_MEMORY_CHARS`).

## Jarvis Phase 4 access levels (do not regress)
- Everything advanced is OFF by default and must stay that way:
  - Wake word (`voice/OptInWakeWordDetector`) never listens unless BOTH the
    user opt-in AND mic permission are present (`isEnabled()`/`isListening()`).
  - Privileged ADB actions (`privileged/GatedPrivilegedBridge`) refuse every call
    unless Shizuku is running AND opted in (`ShizukuGate.permit`); absent a real
    executor they return false rather than pretending success.
  - Scheduled automations (`automation/AutomationGate`) require BOTH the master
    switch and the individual automation; intervals clamp to 15 minutes.
  - `TaskOrchestrator.autoSaveSkills` defaults to false; only a fully-COMPLETED,
    model-produced plan is auto-saved as a `SavedSkillEntity`.
- Perception fallbacks must be honest no-ops when unwired:
  `UnavailableVisionGrounder`/`UnavailableOcrReader` return null/"" and
  `ScreenshotDiffer.changed(null, ...)` is false, so a missing screenshot never
  fabricates a screen change. The vision-diff fallback in `verifyAction` only
  upgrades a `SCREEN_CHANGED` verdict when a real screenshot diff confirms it.
- Notifications: `notifications/AgentNotificationListenerService` feeds the
  bounded `NotificationBus` (cap 20, most-recent-first); `read_notifications` is
  an approved tool and reads only the bounded digest. `CompletionNotifier` posts
  a result notification only when POST_NOTIFICATIONS is granted.
- RAG (`memory/rag`): dependency-free token-overlap retrieval over the user's own
  `knowledge_notes`; `retrievedContext` is bounded by
  `PlannerPromptBuilder.MAX_RETRIEVED_CHARS` before it reaches the prompt.
- Mentioning an action in the prompt requires it be executable and approved:
  `read_notifications` follows the same rule as every other tool — catalogue,
  `ToolRegistry` definition/validation, `DeviceAutomation.execute`, and
  `TaskPlanParser.mapAction` must all know it.

## Jarvis Phase 5 autonomy & self-healing (do not regress)
- The execution loop replaces "retry the same action N times then fail" with a
  bounded Detect -> Diagnose -> Backtrack -> Re-plan -> Explore -> Learn cycle.
  The *taxonomy and loop* are engine-level; the *recoveries* are model-chosen
  against the live screen. Never reintroduce a blind, unclassified retry.
- `agent/FailureClassifier` maps observed signals to a `FailureType` and a
  suggested `RecoveryStrategy`; it is deterministic (no model) and unit-tested.
  A timeout/loop/validation signal must always win over a generic no-change.
- `agent/ProgressBudget` is the single degradation order — re-plan, then explore,
  then escalate, then stop. `MAX_REPLANS` stays 1 and `MAX_EXPLORES` stays 2 so a
  hard task cannot wander; the budget is recreated at the top of `runAgentLoop`.
- `report_status`/finish is still never trusted on its own: every required step
  must be COMPLETED AND a fresh goal-level postcondition must hold. Phase 5 only
  changes *how a failed step recovers*, never the completion gate.
- Reflection (`planning/Reflector`, via `AgentPlanner.reflect`) runs ONLY on a
  failure/stall (reflection-on-demand), never on a healthy step. Exploration
  (`AgentPlanner.exploreNextAction`) returns one non-terminal action and is only
  used for a TARGET_NOT_FOUND / NO_SCREEN_CHANGE diagnosis.
- Honest escalation (5.8): when recovery is exhausted the terminal state is
  `OrchestratorState.Failed(escalated = true, blockedOn, suggestion)`, surfaced by
  the dashboard/monitor/overlay. It is still a `Failed`, never a `Completed`, so
  a stuck run can never fake success.
- Episodic memory (5.9): `EpisodeEntity` (DB v4) records goal/outcome/failure/
  lesson; `loadLessons` injects a bounded digest (`MAX_LESSONS_CHARS`) into the
  planning prompt. Keep the destructive migration and bump the DB version when
  entities change.
- Optional, OFF-by-default seams: `model/NanoSummarizer` (4.7) and
  `planning/CloudPlannerProvider` (4.8, consulted only after a local failure).
  Disabled/unconfigured providers must return null and never touch the network;
  a cloud plan is re-validated through the same `TaskPlanParser` as local output.

## Phase 7 (completion & hardening) invariants
- Every optional capability is owned by `settings/CapabilitySettings` and
  defaults OFF. `ui/SettingsScreen.kt` is the single surface; when adding a
  capability toggle, add it there too. Cloud on/off lives in that one place
  (`CloudPlannerProvider.isEnabled()` reads it); endpoint/key stay in the
  provider's own prefs.
- Privileged actions go through `privileged/GatedPrivilegedBridge` exposed as
  `OmniAgentApplication.privilegedActions`; `isAvailable()` is true only when the
  user opted in AND Shizuku is running. Never execute privileged commands
  otherwise.
- Automations: `automation/AutomationScheduler` runs via WorkManager and must
  honour `AutomationGate` (master switch AND per-item) and the 15-minute minimum.
  `OmniAgentApplication.syncAutomations()` re-arms/cancels after a toggle change.
- Vision: ML Kit OCR (`MlKitOcrReader`) + `OcrVisionGrounder`. On a tree-miss the
  agent sends a bounded JPEG to the multimodal model; grounding refuses (null)
  when a label is absent rather than blind-tapping.
- Web search (`CloudflareGateway`): the HTML-results fallback is gated to the
  built-in DuckDuckGo provider so injected/custom endpoints (and MockWebServer
  tests) keep their own result shape. Keep `parseResultLinks`/`resolveResultUrl`
  pure and offline-testable.
- Public API surface is often asserted in `app/src/test/**`: before changing a
  signature used by tests, grep the tests. `GatedPrivilegedBridge`'s `optIn` is a
  `() -> Boolean` supplier (live toggle), not a captured Boolean.
