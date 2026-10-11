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
  `NONE` verification never proves completion; every terminal goal needs concrete evidence.
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
- 9.9 opt-in is enforced, not just displayed: `AndroidDeviceAutomation` takes a
  `triageEnabled: () -> Boolean` (live supplier, default `{ false }`) and refuses
  `AgentAction.ReadNotifications` with `TRIAGE_DISABLED` when it is off;
  `DailyBriefingWorker` requires `briefingEnabled` AND
  `notificationTriageEnabled`; `OmniAgentApplication` wires
  `triageEnabled = { settings.notificationTriageEnabled }`. Never read the user's
  notifications from a default-off capability.
- `ReadNotifications` is notification-bus backed and must NOT sit behind the
  accessibility-service gate: `AndroidDeviceAutomation.execute` resolves it (consent
  check, then `readNotificationDigest()`) *before* `serviceProvider()`, so an
  opted-in read works without a bound a11y service and is never misreported as
  `NO_SERVICE`. Keep the digest helper service-free.

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

## Phase 8 (Jarvis rebrand, working load, premium UI) invariants
- The app is now branded "Jarvis" in every *user-visible* string (`strings.xml`
  `app_name`, dashboard/setup/settings copy, notifications, overlay toasts, the
  agent prompt identity). Internal class names (`OmniAgentApplication`) and the
  package stay `com.example` on purpose — do NOT rename them.
- Model load (8.1): `GemmaInferenceEngine.initialize` makes the primary attempt
  with the GPU flag from `OmniAgentApplication.USE_GPU_BACKEND`, then ONE bounded
  retry on a fresh CPU-only runtime (`cpuRuntimeFactory`) before reporting
  `EngineState.Failed`. A runtime that never reports ready is closed and is not
  kept as the active engine. `LiteRtEngine.MAX_CONTEXT_TOKENS` now delegates to
  `LiteRtLmRuntime.MAX_CONTEXT_TOKENS` — keep the single source of truth; do not
  re-introduce a literal.
- Honest failure (8.2): never a silent "not loaded". `GemmaInferenceEngine`
  records the real init/ smoke-test reason via `EngineState.Failed(message)` and
  `reportUnavailable(reason)`; both `AgentDashboardScreen` and `SetupScreen` show
  that message plus a Retry (`initializeEngineIfModelReady()` re-attempts). The
  old "Basic mode" claim on the header is gone.
- Theme (8.4): `MyApplicationTheme` defaults to the dark Jarvis HUD (cyan/violet,
  near-black surfaces). If you add a screen, use the `ui/theme` tokens and the
  `CrimsonDanger` (not a nonexistent color) for errors.
- Brand mark (8.5): the launcher foreground and the in-app logo are the same
  vector `arc reactor` (`drawable/ic_launcher_foreground.xml`,
  `drawable/ic_jarvis_logo.xml`); keep them in sync if either changes.
- Live progress (8.6): the dashboard shows a `now_doing_card` derived from
  `OrchestratorState` (Planning/Executing/Replanning/Paused) so the user always
  sees the current action and its reasoning.
- Bug sweep (8.7, BS1-BS10): the previously "written but not working" seams are
  now real or honestly gated:
  * BS1 `SetupScreen` actually calls `EngineLoadStatusCard(app)`; keep it wired —
    it is the only place the load/failure reason shows next to download progress.
  * BS2 Shizuku: `OmniAgentApplication.privilegedActions` is referenced by the
    Settings self-test (real status); the bridge refuses unless opted in AND
    Shizuku runs. Do NOT advertise it as available by default.
  * BS3 Wake word: `OptInWakeWordDetector` fronts the hands-free flow in
    `AgentDashboardScreen` (armed only with opt-in + mic permission). Keep it
    gated; never capture audio silently.
  * BS4 Cloud fallback: configurable end to end via `SettingsScreen` ->
    `HttpCloudPlannerProvider.saveConfiguration(endpoint, key, model)`. Still
    OFF by default; `isEnabled()` requires the toggle AND non-blank endpoint/key.
    There is one shared provider instance on the application (`cloudPlannerProvider`).
  * BS5 `MAX_CONTEXT_TOKENS` stays a single source of truth (LiteRtLmRuntime).
  * BS8 start gating: `canStartTask(modelReady, basicFallbackExplicit)` is the
    single truth; the dashboard shows "Model required • tasks are blocked" and
    routes to Setup instead of pretending a "Basic mode" runs.
  * BS9 `LiteRtEngine.reasonAndPlan` is a legacy diagnostic-only path, NOT in the
    live loop; its fixed budget/foreground are documented as such.
  * BS10 no reachable `Unavailable*` default: the app wires real OCR/vision/
    summarizer/cloud; the stubs are inert constructor defaults for tests only.
- Smoke probe (8.8): a blank smoke answer is NOT a load failure. A healthy Gemma-4
  E2B build can return an empty string for a tiny probe. `LiteRtLmRuntime.smokeTest`
  routes through the shared, unit-tested `runTolerantSmokeProbe(...)` — up to 2
  bounded attempts with a growing token budget; non-blank OR empty-without-exception
  succeeds; only a THROWN generation on every attempt fails. Never close a working
  runtime on an inconclusive probe. Keep the `LlmRuntime.smokeTest` contract ("empty
  is usable") in step with any new runtime.
- Learn-from-demonstration (4.17): the dashboard's "Watch me / Save skill" card is
  the entry point. `TaskOrchestrator.startWatching()` records each executed step;
  `saveWatchedSkill(name, goal)` persists it via `skillDao`. Do not remove the UI
  wiring without another reachable entry, or the capture becomes dead again.
- `AgentDashboardScreen` is large; keep new wiring behind a `testTag` so it stays
  reachable to the UI tests.

## Phase 9 (proactive / hands-free / self-healing) - seams
- Every Phase 9 capability is OPT-IN and OFF by default: 9.1 proactive awareness,
  9.2 location triggers, 9.7 hands-free, 9.8 briefing, 9.9 triage. Never enable a
  capability implicitly. `CapabilitySettings` is the single home for the flags.
- 9.1 `ProactiveEngine.Runner` is started once from `OmniAgentApplication`; it only
  *offers* (notification/TTS) and is gated by the toggle AND `BatteryGuard` (9.14).
- 9.7 `VoiceLoop`/`VoiceLoopController` drive wake -> listen -> confirm -> execute ->
  spoken result with a hard stop phrase; wired in `AgentDashboardScreen` behind
  opt-in + mic permission. `OptInWakeWordDetector` must stay the only arm path.
- 9.8 `DailyBriefing`/`BriefingScheduler` are synced both on app startup and when
  the Settings toggle flips; keep those two call sites in step.
- 9.11 `ElementRanker` ranks the on-screen tree for the step-decision prompt; always
  keep the `ifBlank { observation.summary }` fallback so ranking never regresses
  behavior.
- Notification ids are deliberately distinct (`CompletionNotifier`): 4210 task
  result, 4211 briefing, 4212 suggestion. Do not collapse them.

## Build gotcha (found in the Phase 9 lint gate)
- Android Lint (`lintAnalyzeDebug`) can CRASH with
  `Bad parent: class org.jetbrains.kotlin.psi.KtDeclarationModifierList` (an
  `ExperimentalDetector`/`LightClassUtil` bug) on a declaration annotated
  `@Volatile`. Prefer a plain `var`/`@Synchronized` here; if it recurs, the
  suggested escape hatch is `lint { disable("UnsafeOptInUsageError",
  "UnsafeOptInUsageWarning") }` - but first just drop the offending modifier.
- Always run the full gate locally before pushing:
  `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

## Loader invariants (8.9/8.10) - do not regress the phone hang
- ONE load path: `GemmaInferenceEngine` takes a single CPU-first `runtimeFactory`.
  Do not reintroduce a GPU-primary + CPU two-attempt scheme (`cpuRuntimeFactory`):
  a failed GPU delegate doubles the ~2.6 GB init work and thrashes memory.
- `initialize()` is single-flight: the Ready check is repeated INSIDE
  `mutex.withLock`, and any previous runtime is closed before it is replaced. Two
  concurrent initializers must never leave two runtimes alive.
- `USE_GPU_BACKEND` is `false` (CPU-first). GPU is strictly opt-in.
- Smoke probe runs once: call `ensureReady()` (load + one probe + remember), not
  `initialize()` + a manual probe on every re-open. `reportUnavailable()`/`shutdown()`
  clear the probe flag so a genuine reload re-verifies.
- Memory trims must not thrash the engine: `MemoryTrimPolicy.shouldReleaseEngine`
  releases only at `TRIM_MEMORY_COMPLETE`; `onLowMemory()` is advisory and keeps the
  engine. A release bumps `trimGeneration`; the next explicit use reloads.

