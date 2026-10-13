# 1. OBJECTIVE

Upgrade the app (currently branded "OmniAgent", to be renamed "Jarvis") from a
"plan once, run a short fixed action list, stop" automator into something much
closer to a Jarvis-style assistant: continuous screen awareness, open-ended
multi-step task execution across many apps, memory of the user and past tasks, a
premium Jarvis-like UI, and a model that actually loads.

Scope for this work: Phases 0, 1, 2, 3, 4 (all items 4.1 - 4.17), Phase 5
(Autonomy & Self-Healing, items 5.1 - 5.10), Phase 6 (Reasoning & Reliability
Rescue, items 6.1 - 6.24), Phase 7 (Completion & Hardening, items 7.1 - 7.17) AND
Phase 8 (Jarvis rebrand, working model load, premium UI - items 8.1 - 8.10) AND
Phase 9 (fully-automated Jarvis capability upgrades - items 9.1 - 9.14) AND
Phase 10 (reliable multi-step execution, items 10.1 - 10.11), followed by a
FINAL BUG SWEEP (section 4A) that finds and fixes every remaining bug.
Phase 6 is landed; Phase 7 finishes the seams; Phase 8 fixes the live device
blockers, fixes every confirmed "written but not working" defect (section 2.3),
and rebrands the app to "Jarvis". The code audit (section 2.1) shows the tree has
advanced - OCR, voice, automations and the Settings surface are now really wired -
while a few seams (vision grounding beyond OCR, Shizuku, cloud fallback, wake-word)
and several "defined but never called" defects (section 2.3) still remain. Real
reported failures ("open WhatsApp, message Prateek" -> app opens then scrolls
forever; "search for X" -> text not verified in the field; the model "downloads but
won't load") trace to concrete defects, listed in section 4 under "Observed
defects". Phase 6 fixed those; Phase 7 closes the rest of the gap, and Phase 8
fixes the live "won't load" bug, the dead wiring, and rebrands to Jarvis.

Confirmed decisions (locked):
- Model-not-loaded behavior: BLOCK. The app requires a healthy local model
  before a task starts and shows "Local AI not loaded - set up the model in
  Setup" instead of silently running keywords. The keyword planner is only an
  explicitly-labelled, user-visible degraded mode; it is never presented as AI.
- Web Skills: REMOVE the standalone Web Skills tab/screen. `CloudflareGateway`
  stays only as the in-loop `web_search`/`read_url` tool, so web access serves
  task completion and automation, never a separate unused surface.

Target device (single, fixed): Motorola Edge 70 Fusion - Snapdragon 7s
Gen 3/4, **8 GB RAM (confirmed)**, 256 GB storage, Android 16, 6.78" 1272x2772
AMOLED, Adreno 810 GPU. 8 GB is the memory ceiling to design against: the model
must coexist with the accessibility service and the Android runtime, so the
context window and thread budget stay bounded (see the model strategy below).

Full scope at a glance (everything in this plan):
- Phase 0 - Core reliability: input-field heuristic, IME text entry, `Submit`
  (press Enter), `OpenUrl`, stable-screen `observeStable()`.
- Phase 1 - Action vocabulary: copy/paste, long-press, drag, open notifications,
  open quick settings, deep links, read page content, in-loop `web_search` +
  `read_url`.
- Phase 2 - Perception: event-driven `screenEvents`, bounded settle-wait,
  screenshot/vision grounding, OCR, vision-diff verification, GPU on, context
  2048 -> 3072.
- Phase 3 - Planning & memory: first-class re-planning, progress budgeting,
  persistent user/preference/outcome memory store.
- Phase 4 - Jarvis layer (4.1-4.17): vision grounding, OCR, vision-diff,
  NotificationListener, Shizuku (opt-in), multimodal E2B planner, Gemini Nano
  (best-effort), opt-in cloud fallback, LoRA (deferred), wake word, on-device
  TTS, hands-free loop, skill/routine library, scheduled/trigger automations,
  local RAG, self-critique, learn-from-demonstration.
- Phase 5 - Autonomy & self-healing (5.1-5.10): verification gate, failure
  taxonomy/detector, checkpoint-backtrack, hierarchical reflection, adaptive
  re-planning, proactive exploration, loop guard, honest escalation, episodic
  memory/lessons, progress budgeting + graceful degradation.
- Phase 6 (TOP PRIORITY) - Reasoning & reliability rescue (6.1-6.24): force the
  model to be the planner, kill the scroll/read fallback loop, understand
  Hindi/Roman-Hindi, decompose messaging tasks, fix search/submit verification,
  ask-before-scroll on a missing target, plus ReAct step reasoning, real screen
  grounding (rich element JSON), bounded history, typed error recovery, robust
  JSON + coerced params, per-action verification, stale-node safety, generic
  workflow templates, one shared engine, stronger vision fallback, event-driven
  re-observation, a visible model-use trace, a model-fit guard, and removal of
  the dead Web Skills surface.
- Phase 7 (TOP PRIORITY) - Completion & hardening (7.1-7.17): verify/fix the
  pinned model artifact; make the remaining seams REAL - vision grounding, OCR,
  multimodal E2B image input, voice input/hands-free, opt-in cloud fallback,
  Shizuku, WorkManager automations, model-fit guard; finish
  self-critique/learn-from-demonstration; improve web-search quality; add one
  opt-in settings surface, on-device instrumentation tests + an in-app self-test,
  Hindi/Roman-Hindi end-to-end, and reliability hardening from real device runs.
  All Phase 7 items are built together in one pass.
- Phase 8 (TOP PRIORITY, user-requested) - Jarvis rebrand + working load + premium
  UI (8.1 - 8.7): make the model actually load after a verified download and show
  the real failure reason (never a silent "not loaded"); fix every confirmed
  "written-but-not-working" defect (section 2.3, BS1 - BS10); rebrand all
  user-visible text to "Jarvis"; redesign the UI into a cool, premium, Jarvis-like
  interface; a new launcher/in-app icon; and a polish pass so task progress is
  always visible. Built together with Phase 7 in the same pass. Also fixes the
  phone-hang regression (8.9) introduced by the model-load work and reverts the
  earlier GPU/CPU backend changes (8.10).
- Phase 9 (NEW, user-requested) - Fully-automated "Jarvis" capability upgrades
  (9.1 - 9.14): proactive awareness, context/event automation triggers, multi-turn
  conversational memory, cross-app data passing, personal memory commands + UI,
  skill library UI, full hands-free voice loop, daily briefing, notification
  triage, a conversational HUD overlay, smarter element understanding, proactive
  obstacle self-healing, Hindi-first language, and a battery/thermal guard. NO new
  model (reuse the single E2B engine); every always-on/privacy feature is opt-in
  and OFF by default. Built on top of Phase 7/8.
- Phase 10 (acceptance spec) - Reliable multi-step execution (10.1 - 10.11):
  separate dispatch from success, goal-specific final verification, the full YouTube
  search-to-play benchmark path (launch/search/submit/results/select/playback),
  trustworthy observations, event-driven settling + a perception ladder, OCR
  grounding that justifies a tap, safe target resolution, failure-typed self-healing,
  robust NL planning + BASIC fallback integrity, an adversarial regression suite,
  and permissions/security/user-control kept intact. Reliability-first, no new
  model/backend.
- Delivery rules (section 4B): work on a branch off latest `main` (review PR #25),
  preserve all working behaviour, produce a gap matrix, honest real-device validation
  + QA checklist, run the mandatory Gradle validation commands, and open a PR (not
  auto-merged).
- FINAL BUG SWEEP (section 4A, end of the pass): find and FIX every remaining bug -
  build/tests green, no reachable dead seams, no dead wiring, lint issues resolved,
  no crash on edge cases, and all honest-behaviour rules intact.

Honest framing: a phone cannot be literal Jarvis. But the gap between today and
that feel is a concrete, ordered set of capability upgrades, not one magic
feature. This plan turns the request into phases, each tied to real files.

Current limits that block "complex, Jarvis-level" automation (observed in code):
- Perception is a single textual a11y dump taken on a fixed `delay(300)`; no
  image/screenshot understanding and no continuous monitoring while idle.
- The action vocabulary is tiny (`AgentAction`) and cannot express typing-and-
  submitting, browsing to a URL, copying/pasting, long-press/drag, opening
  notifications, multi-app hand-offs, or reading a page's content.
- Planning is one-shot: `TaskPlanParser.MAX_STEPS = 12`, `defaultMaxSteps = 25`;
  the loop never re-plans a whole new approach except on a single stuck signal.
- No long-term memory: `TaskEntity/TaskStepEntity/CheckpointEntity` store one
  task run; there is no user profile, preferences, or "what I learned" store.
- `CloudflareGateway` web skills exist but are isolated — the agent loop cannot
  call them as a tool.
- No self-correction loop that retries a *different* strategy after a failure;
  failures just retry the same action within a bounded count.

Operationally this plan (1) makes the core reliable, then (2) widens what the
agent can *do*, (3) upgrades how it *sees*, and (4) gives it *memory*.

Model and connectivity strategy (8 GB device; "increase model size / use Google
search or cloud if needed"; GPU enabled):
- Keep the model at E2B size (confirmed). On 8 GB it must coexist with the
  accessibility service, so a materially larger model (e.g. E4B) risks the OOM
  that already triggers `onTrimMemory` shutdowns. In Phase 4, item 4.6 moves to a
  vision-capable E2B build (same size class) so one model both plans and sees; the
  pinned `LiteRtModelManager` URL + bytes + SHA-256 are updated together. Do not
  go above E2B unless on-device profiling shows comfortable headroom.
- Enable the Adreno 810 GPU backend (`OmniAgentApplication.USE_GPU_BACKEND =
  true`). `LiteRtLmRuntime` already falls back to CPU automatically if GPU init
  fails, so this is safe.
- Raise the context window conservatively, 2048 -> 3072 (not 4096), to fit richer
  observations while protecting peak RAM. Keep `DEFAULT_CPU_THREADS = 4`.
- Use the internet only for explicit web tasks (search / fetch a URL), routed
  through the existing `CloudflareGateway` and exposed as an in-loop tool. This
  is the "use Google search / cloud if needed" path — a *tool the agent can
  choose*, not a replacement for the on-device planner.
- Include an OPT-IN cloud fallback (item 4.8) for genuinely hard steps, reached
  only when the user has turned it on in settings; it is OFF by default and is
  never used silently. When off or unreachable, the app keeps its current honest
  `BASIC_FALLBACK` labelling.

# 2. CONTEXT SUMMARY

The app is an on-device Android automation agent: a local Gemma model plans, an
`AccessibilityService` executes. No cloud inference.

## 2.1 CODE AUDIT BASELINE (compulsory - every seam below MUST be fixed)

This is the state of the code as it actually stands right now, read from the
source tree (not assumed). Everything in the "Seams only" list is COMPULSORY to
fix - each maps to a Phase 7 item and none may be left as a stub.

A) Real and working today (do NOT rebuild these; only extend):
- Accessibility automation + action vocabulary + `HybridStepExecutor` fast path.
- Gemma E2B engine with GPU on + CPU fallback (single shared engine).
- Planner + strict `TaskPlanParser` + `ToolRegistry` validation.
- Recovery loop: `Reflector`, `ProgressBudget`, `StuckLoopDetector`,
  `FailureClassifier`.
- Notification listener (`AgentNotificationListenerService`).
- On-device TTS (`AndroidTextToSpeechOutput`).
- RAG retriever (`TokenOverlapRetriever` / `LocalRag`).
- `CloudflareGateway` (DuckDuckGo instant answer + `read_url` + PDF head check).
- Phase 6 fixes landed: 6.1, 6.2, 6.3/6.4, 6.6/6.10, 6.12, 6.16, 6.18, 6.19, 6.22.

B) The tree has ADVANCED since the first audit - the build agent kept landing
Phase 7 items. VERIFIED present in the tree right now:
- 7.15: `ui/SettingsScreen.kt` + `settings/CapabilitySettings.kt` + a
  `Capabilities` tab in `MainActivity`. Landed.
- 7.8: `automation/AutomationScheduler.kt` + `AutomationWorker` using real
  WorkManager (`androidx.work` wired in `app/build.gradle.kts`). Landed.
- 7.5: real `SpeechRecognizer` voice input + hands-free re-arm in
  `ui/AgentDashboardScreen.kt`. Landed.
- 7.4: multimodal `generateWithImage(...)` in `LiteRtLmRuntime`. Landed.
- 7.9: `isFitForDevice()` device-fit report in `LiteRtModelManager`. Landed.
- 7.1: `probeRemoteArtifact()` + `logRemoteArtifactCheck()` in
  `LiteRtModelManager`. Landed (still verify the hash at build time - 7.1).
- 8.2 partial: the dashboard DOES read `EngineState` - but only to show a generic
  label, not the real failure reason (see C2).
- 7.3 PARTIAL: `mlkit-text-recognition` IS wired in `app/build.gradle.kts`, but a
  search for `com.google.mlkit` / `TextRecognition` finds NO code using it yet - so
  the dependency is present but the OCR reader is NOT implemented. Finish it.

STILL SEAMS until proven otherwise (re-verify at build time - the tree is moving
fast): 4.1 vision grounding (`UnavailableVisionGrounder`), 4.5 Shizuku
(`exec = null`), 4.8 cloud planner fallback (hard-disabled), 4.7 Gemini Nano,
4.16/4.17 self-critique / learn-from-demonstration, and the OCR reader above.

C) LIVE BLOCKERS reported by the user (these are the top priority - fix first):
- C1 MODEL "WON'T LOAD" - VERIFIED PRECISE ROOT CAUSE (device error text:
  "The model loaded but did not answer a test prompt (load or memory failure)").
  This string is emitted by `OmniAgentApplication.initializeEngineIfModelReady()`
  ONLY when `inferenceEngine.smokeTest(modelFile)` returns false. So the model
  actually INITIALIZES ("1 se 2 sec me load hone ke baad") - it is the SMOKE TEST
  that fails, and the app then wrongly reports "Engine failed to load".
  The real mechanism, read from the code:
  * `LiteRtLmRuntime.smokeTest()` calls `generate("Reply with the single word: ready",
    systemInstruction="You are a minimal health check.", maxOutputTokens = 8)` and
    returns `result.isNotBlank()`. On a Gemma-4 E2B build the tiny 8-token prompt
    can legitimately return an EMPTY string (thinking-token-only output, or the
    `extractText` filter finds no `Content.Text`), which is reported as a failure
    even though the engine is perfectly usable.
  * There is NO in-place retry: one blank smoke answer calls
    `reportUnavailable(...)`, which CLOSES the runtime and sets
    `EngineState.Failed`. So a single hiccup throws away a successfully loaded model.
  * Because `GemmaInferenceEngine.initialize()` only returns success when `isReady()`,
    and the smoke test is what flips that, the app treats a healthy-but-quiet model
    as "failed to load". This is the exact user symptom.
  Fix (8.1): make the smoke test non-destructive and tolerant - accept a non-blank
  OR an empty-but-no-exception answer as "engine works", retry the probe a bounded
  number of times (e.g. with a slightly larger token budget / a longer prompt), and
  NEVER close a working runtime on a single inconclusive probe. Only a thrown
  exception (a real init/generation failure) should mark the engine failed.
  Also surface the real reason on screen (8.2). This corrects the earlier
  "check the artifact hash / version" hypothesis - the model DOES load.
  * Secondary (still worth keeping): keep the CPU-first backend and the bounded
    CPU retry, since a GPU delegate failure can still produce a real init error.
  * `MAX_CONTEXT_TOKENS`: now RECONCILED in the tree - `LiteRtEngine.MAX_CONTEXT_TOKENS`
    delegates to `LiteRtLmRuntime.MAX_CONTEXT_TOKENS` (3072). Confirm it stays that way.
- C2 THE MODEL-LOAD ERROR IS NEVER SHOWN, and the label is misleading. The
  dashboard does read `EngineState` but only renders a GENERIC label
  ("Local AI unavailable • Basic mode" / "Model not loaded • Basic mode") - the
  real exception text from `GemmaInferenceEngine` is never surfaced. `SetupScreen`
  shows only `downloadState` (download/verify), not `EngineState` at all. And with
  6.1 the app BLOCKS when the model is missing, so the "Basic mode" wording is
  itself wrong. Must show the real reason + a Retry, and fix the label.
- C3 REBRAND: everything user-visible still says "OmniAgent" (`strings.xml`
  `app_name`, accessibility service label/description, notifications, dashboard,
  setup copy, launcher). The user wants it named "Jarvis".
- C4 UI: the user wants a much better, "Jarvis-like", cool/premium interface and
  a new small icon, not the current generic look.
- C5 (NEW, reported after the model-load work) THE WHOLE PHONE HANGS / STUTTERS.
  "Pur phone bahut hand ho raha hai". VERIFIED resource causes in the code - the
  model-load changes made the phone thrash. Each is a real defect, not a guess:
  * DOUBLE-LOAD LEAK in `GemmaInferenceEngine.initialize()` (`model/GemmaInferenceEngine.kt`).
    The early "already Ready" guard is checked BEFORE `mutex.withLock`, and there is
    NO re-check inside the lock. Two concurrent callers (e.g. the `MainActivity`
    `LaunchedEffect(isModelReady)` + a Retry tap, or the dashboard/Setup observers)
    both pass the guard, then BOTH load a fresh `LiteRtLmRuntime`; the second does
    `runtime = created`, OVERWRITING the first WITHOUT closing it. Each runtime
    holds the whole ~2.6 GB model + KV cache, so two live engines = OOM / heavy
    swap = the phone hangs. Fix: re-check `_state.value is Ready` inside the lock and
    close any previous runtime before replacing it (single-flight init).
  * LOAD/UNLOAD THRASH. `OmniAgentApplication.onLowMemory()` calls
    `inferenceEngine.shutdown()` UNCONDITIONALLY, and `onTrimMemory(level >=
    TRIM_MEMORY_RUNNING_LOW)` also shuts the engine down. Loading is a multi-second,
    multi-GB operation, so a trim followed by a re-open/re-init is a giant CPU+IO
    spike that stutters the whole device. `MainActivity`'s
    `LaunchedEffect(isModelReady)` re-triggers `initializeEngineIfModelReady()` on
    every model-ready transition, so this load/unload cycle can repeat. Fix: do not
    shut the engine down on every trim; only release under genuine critical pressure
    and debounce/back-off re-init so it cannot spin.
  * GPU BACKEND LEFT ON. `OmniAgentApplication.USE_GPU_BACKEND = true`. The Adreno
    810 + LiteRT GPU delegate is known to be poorly supported for this VLM and, on
    failure, the engine ALSO tries the CPU path - i.e. a failed GPU attempt doubles
    the init work and memory churn. `MAX_CONTEXT_TOKENS = 3072` and
    `DEFAULT_CPU_THREADS = 4` further raise the footprint. Fix: default back to
    CPU-first (GPU strictly opt-in), and make the CPU retry reuse the same load
    rather than re-initializing a second engine.
  * SMOKE TEST COST. `smokeTest()` runs a real generation on every initialize, and
    the new tolerant probe can generate up to 2 attempts - extra CPU on an
    already-hot device. Fix: run the probe once, cheaply, and only after the first
    successful load (not on every re-open).
  PRIMARY FIX (8.9): make engine init single-flight (re-check inside the lock, close
  the old runtime before replacing), stop unconditional shutdown on trim/low-memory
  (debounce + only on critical), default to CPU-first, and run the smoke probe once
  - so the phone does not hang. Reference: `model/GemmaInferenceEngine.kt`,
  `OmniAgentApplication.kt`, `MainActivity.kt`, `model/LiteRtLmRuntime.kt`.

D) High-risk item to verify while building (not a gate, part of the same pass):
- `LiteRtModelManager` pins `gemma-4-E2B-it.litertlm` with a hardcoded byte length
  and SHA-256. The repo/filename are confirmed real; the exact hash still must be
  verified, because a wrong hash deletes the file and (with 6.1) the app blocks.
  -> handled in 7.1; the load failure itself is handled in Phase 8 (8.1).

## 2.3 BUG SWEEP - WRITTEN BUT NOT WORKING (verified in the current tree)

Each item below was confirmed by reading the code as it stands now. These are the
"code likha hai par kaam nahi kar raha" defects. All MUST be fixed in the same pass
as Phase 7 and Phase 8 (tracked as 8.7 sub-items).

- BS1 (worst) `ui/SetupScreen.kt` defines `EngineLoadStatusCard(app)` (lines ~303-364)
  that reads `app.engineState` and shows the REAL load failure text + a Retry - but
  it is NEVER CALLED anywhere. So the one screen the user goes to after a download
  shows download progress only and NO engine/load status. `SetupScreen` does not even
  receive the application/engine, so the helper is currently uncallable. This is the
  direct cause of "download hua par load nahi ho raha, kuch dikhta hi nahi".
- BS2 `OmniAgentApplication.privilegedActions` (Shizuku bridge, 4.5/7.7) is built but
  referenced by NOTHING else - no action path calls it. Shizuku is dead wiring.
- BS3 `voice/WakeWord.kt` (`OptInWakeWordDetector`, 4.10) is referenced by NOTHING -
  no listener service, no wiring - so wake-word is dead code (the manual voice button
  in the dashboard is the only live voice input).
- BS4 Cloud fallback (7.6) can never be turned on end to end: `HttpCloudPlannerProvider`
  reads `KEY_ENDPOINT` / `KEY_API_KEY` from `cloud_planner_prefs`, but NO UI anywhere
  writes them, and `SettingsScreen` only toggles `cloudFallbackEnabled`. The path is
  unreachable in practice.
- BS5 `MAX_CONTEXT_TOKENS`: NOW RECONCILED - `model/LiteRtEngine.kt` delegates to
  `model/LiteRtLmRuntime.MAX_CONTEXT_TOKENS` (3072) as the single source of truth.
  Keep this delegation; do not reintroduce a second literal.
- BS6 Rebrand is incomplete: `strings.xml` `app_name` is now "Jarvis", but
  user-visible "OmniAgent" text still remains in `ui/AgentDashboardScreen.kt` (the
  header title + icon contentDescription), `notifications/CompletionNotifier.kt`
  (notification title), `overlay/FloatingAgentDotService.kt` (overlay label), and
  other spots (e.g. the assistant name in prompts). These must read "Jarvis".
- BS7 Launcher icon (8.5) is NOT done: `res/mipmap-*/ic_launcher.png` are still the
  default/generic icons; there is no Jarvis brand mark. A new adaptive icon is still
  required.
- BS8 Misleading label: the dashboard shows "... • Basic mode" for
  `EngineState.Failed` / `NotInitialized`, but with 6.1 the app BLOCKS in those states
  instead of running a basic mode. The wording is wrong and must be corrected.
- BS9 `model/LiteRtEngine.kt` `reasonAndPlan(...)` hardcodes `remainingStepBudget = 10`
  and `foregroundPackage = null` on the legacy diagnostic path; confirm it is not on
  the live loop (it is a test/back-compat path) and otherwise remove the stale claim.
- BS10 Dead defaults: verify `DisabledCloudPlannerProvider` and
  `UnavailableVisionGrounder` / `UnavailableOcrReader` / `UnavailableNanoSummarizer`
  are no longer used by the live wiring (the app wires real OCR, OCR-grounder, and the
  E2B summarizer). Remove any leftover references so no "Unavailable" seam remains
  reachable at runtime.

Note: the seam map, the "working today" list, and the full Phase 7 detail also
appear in section 4 (Observed defects) and section 5. They are repeated here so
the audit is visible up front and cannot be missed.

## 2.2 KEY SEAMS

- Execution boundary: `DeviceAutomation` / `AndroidDeviceAutomation`
  (`agent/DeviceAutomation.kt`). `observe()` dumps the a11y tree; `execute()`
  dispatches one approved `AgentAction`.
- Action vocabulary: sealed `AgentAction` (`agent/AgentAction.kt`), validated by
  `ToolRegistry` (`agent/ToolRegistry.kt`), mapped from model JSON by
  `TaskPlanParser` (`planning/TaskPlanParser.kt`, `MAX_STEPS = 12`).
- Deterministic fast path: `HybridStepExecutor.decide(...)`
  (`agent/HybridStepExecutor.kt`) returns Execute / Satisfied / ModelReason.
- Loop: `TaskOrchestrator.runAgentLoop(...)` (`agent/TaskOrchestrator.kt`) —
  plan once via `LiteRtEngine`/`AgentPlanner`, then per step: observe -> decide
  -> validate -> risk-check -> execute -> `delay(300)` -> observe -> verify
  (`PostconditionVerifier`). `StuckLoopDetector` gives one recovery signal.
- Perception: `AgentAccessibilityService.dumpHierarchy()` -> `UiNode` /
  `UiTreeSnapshot` (`accessibility/UiNode.kt`); `AccessibilityTreeJsonParser`
  emits token-efficient JSON; `takeScreenshotFallback()` exists but is unused by
  planning.
- Model: `GemmaInferenceEngine` owns one `LlmRuntime` (`model/LiteRtLmRuntime`,
  `MAX_CONTEXT_TOKENS = 2048`); `PlannerPromptBuilder` builds bounded prompts and
  sanitizes untrusted screen text.
- Memory: Room DB (`data/local/TaskEntities.kt`) stores tasks/steps/checkpoints
  only. No user profile or cross-task knowledge.
- Voice/UI: `AgentDashboardScreen` (`ui/AgentDashboardScreen.kt`) one-shot
  `SpeechRecognizer`; `FloatingAgentDotService` overlay orb; `MainActivity`
  TabHost (`Agent`, `Live Monitor`, `Setup`, `History`, `Web Skills`).
- Web skills: `CloudflareGateway` (`cloudflare/CloudflareGateway.kt`) web search
  + PDF, isolated from the loop.
- Service: `AgentExecutionService` foreground notification; manifest already
  declares `INTERNET`, `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`, and an `https`
  `<queries>` entry.

Constraints/conventions: all model output must pass `ToolRegistry` +
`TaskPlanParser`; screen text is untrusted and sanitized via
`PlannerPromptBuilder.sanitizeUntrusted`; JSON wrappers via `JSONObject`/`JSONArray`
(never interpolation); never blind fixed-coordinate tapping; tests are
Robolectric (`@Config(sdk = [34])`) with fakes in `TestFakes.kt`; build with JDK
21 + Android platform 36.1 (`./gradlew :app:testDebugUnitTest`,
`:app:assembleDebug`).

Device/model specifics: `LiteRtModelManager` pins one model
(`gemma-4-E2B-it.litertlm`, 2.59 GB) by URL + byte length + SHA-256;
`LiteRtLmRuntime` fixes `MAX_CONTEXT_TOKENS = 2048` and `DEFAULT_CPU_THREADS = 4`;
`OmniAgentApplication` currently ships `USE_GPU_BACKEND = false` — Phase 2 flips
it to `true` for the Adreno 810 (with automatic CPU fallback).

# 3. APPROACH OVERVIEW

Seven capability layers (Phase 0-6), built together as one coordinated change set
on the existing seams so nothing breaks the validate -> risk -> execute -> verify
pipeline. The full Phase 6 (reasoning & reliability rescue) is the top priority
and is built first in the unified order below.

- Phase 0 (Core reliability): fix input discovery, text entry, submit/Enter,
  open-URL, and stable observation. Without this, nothing else feels reliable.
  (This is the earlier plan; kept as the foundation.)
- Phase 1 (Action vocabulary): add the actions real tasks need — copy/paste,
  long-press/drag, open notifications/quick settings, read page content, launch
  deep links, and expose `CloudflareGateway` as an in-loop `web_search`/`read_url`
  tool.
- Phase 2 (Perception): continuous, event-driven screen state using the already
  present (but unused) `AgentAccessibilityService.screenEvents` StateFlow; a
  bounded "settle" wait; and optional screenshot/vision-assisted grounding when
  the a11y tree is inadequate (paired with a larger context window).
- Phase 3 (Planning & memory): re-planning as a first-class loop (not just one
  stuck signal), a higher step budget with progress budgeting, and a persistent
  user/preference/task-outcome memory store.
- Phase 4 (Jarvis layer): the full set 4.1 - 4.17 — multimodal vision grounding
  and OCR, vision-diff verification, notification listening, Shizuku, a
  multimodal E2B planner, best-effort Gemini Nano, opt-in cloud fallback, voice
  in (wake word) and out (TTS) with a hands-free loop, saved skills/routines,
  scheduled/trigger automations, local RAG, self-critique, and
  learn-from-demonstration.
- Phase 5 (Autonomy & self-healing): run start-to-end like Jarvis — verify every
  step, detect its own failures, classify them against a failure taxonomy,
  backtrack to a known-good checkpoint, re-plan a *different* approach, explore
  when blocked, guard against looping, escalate honestly only when truly stuck,
  and write "lessons learned" back into memory so the next run avoids the same
  trap.

# 4. IMPLEMENTATION STEPS

UNIFIED ALL-AT-ONCE BUILD ORDER (does every item together in one coordinated
pass). This is the master order the code agent follows so Phases 0-6 are built as
one coherent change set, not as separate drops. Work is grouped into 8
workstreams; within a workstream items are done together, and workstreams are
ordered so each is buildable and testable on top of the last. Nothing here is
deferred except item 4.9 (LoRA fine-tune), which is documented-only by design.

Legend: [P0..P6, item] = the phase number and its item id.

The 24 Phase-6 items (grouped by their workstream in the unified order):
1. 6.1 force the model to plan (block if not loaded)
2. 6.2 kill the generic scroll/read fallback loop
3. 6.3 Hindi/Roman-Hindi command understanding
4. 6.4 messaging/contact task decomposition
5. 6.5 fix search verification
6. 6.6 reliable focus -> type -> submit
7. 6.7 ask the model/web before scrolling on a missing target
8. 6.8 one shared engine (no double load)
9. 6.9 vision/OCR as the default answer to a blind screen
10. 6.10 remove the standalone Web Skills tab
11. 6.11 event-driven re-observation (read the screen every moment)
12. 6.12 visible per-step model-use trace (GEMMA vs BASIC)
13. 6.13 ReAct step loop (think -> act -> observe)
14. 6.14 structured-JSON screen grounding for the model
15. 6.15 rich per-element context (desc/role/bounds/parent label)
16. 6.16 bounded history inside the prompt
17. 6.17 typed error recovery table
18. 6.18 robust JSON output + repair
19. 6.19 typed params with value coercion/aliases
20. 6.20 per-action pre/post verification
21. 6.21 stale/ghost-node protection
22. 6.22 generic workflow templates (message/call/navigate/search/play/alarm)
23. 6.23 model-fit guard with honest labelling
24. 6.24 wait-for-quiescence discipline

Workstream 1 - Core automation spine (foundation). Do together:
- [P0] input-field heuristic, IME text entry, Submit/Enter, OpenUrl, observeStable.
- [P1] expanded action vocabulary (copy/paste, long-press, drag, notifications,
  quick settings, deep links, read-page, web_search/read_url as in-loop tools).
- [P2] event-driven screenEvents + bounded settle wait.
Reference: `accessibility/*`, `agent/AgentAction.kt`, `agent/ToolRegistry.kt`,
`agent/HybridStepExecutor.kt`, `agent/DeviceAutomation.kt`,
`planning/TaskPlanParser.kt`, `planning/PostconditionVerifier.kt`,
`perception/ScreenAwareness.kt`.

Workstream 2 - Reasoning core: make the AI actually drive (Phase 6, part A).
Do together: [6.1] force the model to plan, [6.2] kill the generic scroll/read
loop, [6.13] ReAct step loop, [6.14] structured-JSON screen grounding,
[6.15] rich element context, [6.24] wait-for-quiescence discipline.
Reference: `planning/AgentPlanner.kt`, `planning/PlannerPromptBuilder.kt`,
`model/HeuristicPlanner.kt`, `accessibility/AccessibilityTreeJsonParser.kt`,
`accessibility/UiNode.kt`, `agent/TaskOrchestrator.kt`,
`perception/ScreenAwareness.kt`.

Workstream 3 - Language + task patterns (Phase 6, part B + Phase 4 skills).
Do together: [6.3] Hindi/Roman-Hindi understanding, [6.4] messaging/contact
decomposition, [6.22] generic workflow templates (message/call/navigate/search/
play/alarm), [P1] web_search/read_url wiring, [4.13] save a plan as a named skill.
Reference: `model/HeuristicPlanner.kt`, `planning/PlannerPromptBuilder.kt`,
`planning/TaskPlanParser.kt`, `data/local/*`, `agent/TaskOrchestrator.kt`.

Workstream 4 - Verification + reliable input (Phase 6, part C + Phase 5 core).
Do together: [6.5] fix search verification, [6.6] focus->type->submit verified,
[6.20] per-action pre/post verification, [6.21] stale/ghost-node protection,
[5.1] step verification gate, [5.2] failure taxonomy/detector, [5.7] loop guard,
[6.17] typed error recovery table.
Reference: `planning/PostconditionVerifier.kt`, `agent/HybridStepExecutor.kt`,
`agent/FailureClassifier.kt`, `agent/StuckLoopDetector.kt`,
`accessibility/*`, `agent/TaskOrchestrator.kt`.

Workstream 5 - Resilience + parsing (Phase 6, part D + Phase 5 recovery).
Do together: [6.18] robust JSON + repair, [6.19] param coercion/aliases,
[5.3] checkpoint/backtrack, [5.4] hierarchical reflection, [5.5] adaptive
re-planning, [5.6] proactive exploration, [5.8] honest escalation,
[5.9] episodic lessons, [5.10] progress budgeting, [6.7] ask-before-scroll on a
missing target.
Reference: `planning/TaskPlanParser.kt`, `planning/AgentPlanner.kt`,
`planning/Reflector.kt`, `agent/ProgressBudget.kt`, `agent/TaskOrchestrator.kt`,
`data/local/*`.

Workstream 6 - Perception depth (Phase 2 + Phase 4 vision + Phase 6 leftover).
Do together: [4.1] vision grounding fallback, [4.2] OCR fallback, [4.3]
vision-diff verification, [4.6] multimodal E2B planner, [6.9] vision/OCR as the
default answer to a blind screen, [6.11] fully event-driven re-observation,
[P2] GPU on + context 3072.
Reference: `perception/VisionPerception.kt`, `perception/ScreenAwareness.kt`,
`model/LiteRtModelManager.kt`, `model/LiteRtLmRuntime.kt`,
`model/GemmaInferenceEngine.kt`, `OmniAgentApplication.kt`,
`accessibility/AgentAccessibilityService.kt`.

Workstream 7 - Memory, device, and Jarvis capabilities (Phase 3 + Phase 4).
Do together: [P3] user/preference/outcome memory store, [4.4]
NotificationListenerService, [4.5] Shizuku (opt-in), [4.7] Gemini Nano
(best-effort), [4.8] opt-in cloud fallback, [4.10] wake word, [4.11] on-device
TTS, [4.12] hands-free loop, [4.14] scheduled/trigger automations, [4.15] local
RAG, [4.16] self-critique, [4.17] learn-from-demonstration, [6.23] model-fit
guard. Reference: `data/local/*`, `notifications/*`, `privileged/*`,
`voice/*`, `automation/*`, `memory/rag/*`, `planning/CloudPlannerProvider.kt`,
`model/NanoSummarizer.kt`, `ui/*`, `agent/TaskOrchestrator.kt`.

Workstream 8 - One engine, observable, and cleanup (Phase 6 leftovers).
Do together: [6.8] single shared LiteRtEngine/GemmaInferenceEngine,
[6.10] remove the Web Skills tab (keep web tools in-loop), [6.12] visible
model-use trace (GEMMA vs BASIC per step). Reference: `OmniAgentApplication.kt`,
`MainActivity.kt`, `ui/WebSkillsScreen.kt`, `ui/AgentLiveMonitorScreen.kt`,
`ui/TaskHistoryScreen.kt`, `agent/TaskOrchestrator.kt`.

Workstream 9 - Completion & hardening: make the remaining seams real (Phase 7).
Do ALL items together in one pass: [7.1] verify/fix the pinned model artifact,
[7.2]+[7.3]+[7.4] vision stack (grounding + OCR + multimodal image input),
[7.5] real voice input/hands-free, [7.6]+[7.11] cloud fallback + web-search
quality, [7.7]+[7.8] Shizuku + WorkManager automations, [7.9] model-fit guard,
[7.10]+[7.12] finish self-critique/learn-from-demo + Gemini-Nano, [7.13]
reliability hardening, [7.15] one opt-in settings surface, [7.16] on-device
instrumentation tests + in-app self-test, and [7.17] Hindi/Roman-Hindi end-to-end.
Every Phase 7 item is built in this single workstream - no sub-ordering to wait on.
Reference: `model/LiteRtModelManager.kt`, `perception/VisionPerception.kt`,
`model/LiteRtLmRuntime.kt`, `voice/*`, `planning/CloudPlannerProvider.kt`,
`cloudflare/CloudflareGateway.kt`, `privileged/PrivilegedActions.kt`,
`automation/AutomationGate.kt`, `ui/SetupScreen.kt`, `app/build.gradle.kts`.

Integration gates (run after each workstream, not at the end):
- `./gradlew :app:testDebugUnitTest` stays green (Robolectric, sdk 34).
- `./gradlew :app:assembleDebug` produces an APK.
- The phase-specific checks in section 5 for the items just completed pass.
This keeps "all together" honest: the whole set is one change, but it is built in
8 verifiable chunks so a failure is caught early instead of at the very end.

Deferred by design: item 4.9 (LoRA/fine-tune) is documented, not implemented, in
this pass. Everything else (P0-P6, 4.1-4.17, 5.1-5.10, 6.1-6.24) is in scope and
built together in the order above.

The per-phase detail and defect analysis below remain the reference for exactly
what each item changes.

Phase 0 — Core reliability (foundation; from the earlier plan).
- Step 0.1 Reusable input-field heuristic: score likely text-input nodes even
  when `isEditable` is false (class name, viewId/description hints, focus).
  Reference: `accessibility/UiNode.kt`, `agent/DeviceAutomation.kt`.
- Step 0.2 Harden `performTextInput` with an IME/key-event fallback when
  `ACTION_SET_TEXT` is rejected. Reference:
  `accessibility/AgentAccessibilityService.kt`.
- Step 0.3 New `AgentAction.Submit` ("press Enter"/activate Send control) wired
  through `ToolRegistry`, `TaskPlanParser`, prompt, `HybridStepExecutor`.
- Step 0.4 New `AgentAction.OpenUrl` + `AppDirectory.openUrl` (ACTION_VIEW https).
- Step 0.5 Bounded `observeStable()` (fingerprint quiescence) used before
  deciding/verifying. Reference: `agent/DeviceAutomation.kt`,
  `agent/TaskOrchestrator.kt`.

Phase 1 — Action vocabulary for complex tasks.
- Goal: let the model *express* multi-app, real tasks instead of only
  launch/search/type/tap.
- Method: add an `ActionCatalog` expansion — new `AgentAction` variants and
  matching `ToolRegistry`/`TaskPlanParser`/prompt entries for: `CopyText` /
  `PasteText`, `LongPress`, `Drag` (swipe-between-points), `OpenNotifications`,
  `OpenQuickSettings`, `DeepLink` (app-specific URI), `ReadPageContent` (extract
  visible text of a scrollable container into the observation), and a
  `WebSearch`/`ReadUrl` tool backed by `CloudflareGateway`. Add deterministic
  fast-path handling where unambiguous and safety/risk entries for consequential
  ones (send/pay/delete already covered by `RiskEvaluator`).
- Reference: `agent/AgentAction.kt`, `agent/ToolRegistry.kt`,
  `planning/TaskPlanParser.kt`, `planning/PlannerPromptBuilder.kt`,
  `agent/HybridStepExecutor.kt`, `agent/DeviceAutomation.kt`,
  `cloudflare/CloudflareGateway.kt`, `accessibility/AgentAccessibilityService.kt`.

Phase 2 — Continuous & richer perception.
- Goal: perceive the screen continuously and settle on a stable, complete view.
- Method: (a) drive a shared screen-state observer off the existing
  `screenEvents` StateFlow + `onAccessibilityEvent`, so the loop (and future
  monitors) can react to window/content changes instead of only polling; (b)
  generalize the Phase-0 settle wait; (c) add screenshot capture + a bounded
  vision/diff check used only to disambiguate when the a11y tree lacks the
  target; (d) surface scroll/extract of long content as `ReadPageContent`
  summaries; (e) tune the local model for the confirmed 8 GB device — enable the
  Adreno 810 GPU backend (`OmniAgentApplication.USE_GPU_BACKEND = true`; CPU
  fallback is already built in) and raise `LiteRtLmRuntime.MAX_CONTEXT_TOKENS`
  conservatively 2048 -> 3072 to fit richer observations while protecting peak
  RAM. Keep the existing 2.59 GB Gemma 4 E2B model and `DEFAULT_CPU_THREADS = 4`;
  do not enlarge the model file on 8 GB (it must coexist with the accessibility
  service, and `onTrimMemory` already shuts the engine down under pressure).
- Reference: `accessibility/AgentAccessibilityService.kt`
  (`onAccessibilityEvent`, `takeScreenshotFallback`), `agent/DeviceAutomation.kt`,
  `agent/TaskOrchestrator.kt`, `accessibility/UiNode.kt`, `model/LiteRtLmRuntime.kt`,
  `model/LiteRtModelManager.kt`, `OmniAgentApplication.kt`.

Phase 3 — Planning loop & memory.
- Goal: re-plan like an agent when a step fails or the task is long, and
  remember the user and past outcomes.
- Method: (a) promote re-planning to a first-class branch: on repeated failure or
  a "goal not yet provable" signal, ask the model for a *new* plan/sub-plan with
  the failure context rather than retrying the same action; raise
  `MAX_STEPS`/step budget with a progress-budget guard so long tasks stay
  bounded; (b) add a Room-backed memory (`UserMemoryEntity`, `TaskOutcomeEntity`)
  written at task end with DAO access, and inject a bounded memory summary into
  `PlannerPromptBuilder` so plans reflect learned preferences (apps the user
  uses, phrasing, past successful strategies).
- Reference: `agent/TaskOrchestrator.kt`, `planning/AgentPlanner.kt`,
  `planning/TaskPlanParser.kt`, `planning/PlannerPromptBuilder.kt`,
  `data/local/TaskEntities.kt`, `data/local/AgentDao.kt`,
  `data/local/AgentDatabase.kt`.

Phase 4 — Jarvis layer (all of 4.1 - 4.17). Confirmed: E2B model, Shizuku
included, opt-in cloud fallback included. Each item lists goal -> method ->
reference. Every capability routes through the existing validate -> risk ->
execute -> verify pipeline, and all proactive/scheduled behavior is opt-in with
an always-visible off switch.

Eyes (multimodal perception)
- 4.1 Vision grounding fallback. Goal: locate elements the a11y tree misses
  (canvas/WebView/games). Method: reuse `takeScreenshotFallback()`; send the image
  + instruction to the multimodal E2B planner to get a target point, then tap via
  the normal `Tap`/gesture path. Use only when tree-based grounding fails.
  Reference: `accessibility/AgentAccessibilityService.kt`,
  `agent/DeviceAutomation.kt`, `model/*`.
- 4.2 OCR fallback. Goal: read text baked into images. Method: ML Kit on-device
  text recognition over the screenshot; feed extracted text into the observation.
  Reference: new `perception/` module; `agent/DeviceAutomation.kt`.
- 4.3 Vision-diff verification. Goal: confirm an action when the a11y fingerprint
  is unchanged. Method: compare before/after screenshots (bounded, coarse diff) and
  treat a real change as evidence. Reference: `planning/PostconditionVerifier.kt`,
  `agent/TaskOrchestrator.kt`.

Hands (device capabilities)
- 4.4 NotificationListenerService. Goal: read/act/summarize notifications.
  Method: add a `NotificationListenerService` capturing `onNotificationPosted`,
  expose a bounded notification list to the planner, and support reply/action via
  the notification's `RemoteInput`/actions. Requires user "notification access";
  keep content on-device. Reference: new service + `AndroidManifest.xml`,
  `agent/ToolRegistry.kt`, `planning/PlannerPromptBuilder.kt`.
- 4.5 Shizuku (opt-in/advanced). Goal: ADB-level actions without root. Method: add
  the Shizuku dependency, a settings toggle, a runtime availability check, and a
  small privileged-action bridge (settings/package ops) that is only reachable
  when Shizuku is running; document the per-reboot re-auth. Reference: new
  `privileged/` module, `ui/SetupScreen.kt`, `agent/DeviceAutomation.kt`.

Brain (reasoning & model)
- 4.6 Multimodal E2B planner. Goal: one model that plans AND sees. Method: point
  `LiteRtModelManager` at the multimodal E2B build (update pinned URL + bytes +
  SHA-256) and extend `LlmRuntime`/`LiteRtLmRuntime` to accept an image input for
  vision steps. Keep text-only calls unchanged. Reference:
  `model/LiteRtModelManager.kt`, `model/LiteRtLmRuntime.kt`,
  `model/GemmaInferenceEngine.kt`, `planning/AgentPlanner.kt`.
- 4.7 Gemini Nano (best-effort). Goal: use AICore/ML Kit GenAI for summarization
  if the device exposes it. Method: feature-detect availability; use only for
  non-critical summarization; graceful fallback to E2B when absent. Reference: new
  `model/NanoSummarizer.kt`; `planning/AgentPlanner.kt`.
- 4.8 Cloud fallback (opt-in). Goal: handle hard steps when the user enables it.
  Method: a `CloudPlannerProvider` behind a settings flag (OFF by default) that is
  consulted only on repeated local failure; never used silently; reuses
  `CloudflareGateway`/an HTTPS endpoint with secrets injected, not hardcoded.
  Reference: `cloudflare/CloudflareGateway.kt`, `ui/SetupScreen.kt`,
  `planning/AgentPlanner.kt`.
- 4.9 Optional LoRA/fine-tune. Goal: more reliable JSON tool-calling. Method:
  documented, deferred; only if 4.6 output quality proves insufficient. Reference:
  docs only (not implemented in the first pass).

Voice (offline, on-device)
- 4.10 Wake word + continuous listening. Goal: hands-free trigger. Method: Porcupine
  (or Vosk keyword spotting) in the overlay/service; only after an explicit opt-in
  and mic permission. Reference: `overlay/FloatingAgentDotService.kt`,
  `ui/AgentDashboardScreen.kt`, `AndroidManifest.xml`.
- 4.11 On-device TTS. Goal: speak results. Method: sherpa-onnx Piper (or Android
  TTS) behind a `SpeechOutput` interface; no cloud. Reference: new
  `voice/SpeechOutput.kt`; `ui/AgentLiveMonitorScreen.kt`.
- 4.12 Hands-free conversation loop. Goal: listen -> plan -> act -> speak.
  Method: wire 4.10 + `TaskOrchestrator` + 4.11 into a single conversational
  session gate. Reference: `agent/TaskOrchestrator.kt`, `overlay/*`.

Memory, routines & proactivity
- 4.13 Skill/routine library. Goal: save and re-run successful multi-step tasks.
  Method: persist a completed `TaskPlan` as a named skill (Room), list them in the
  UI, and re-run by name/voice through the existing resume path. Reference:
  `data/local/*`, `agent/TaskOrchestrator.kt`, `ui/*`.
- 4.14 Scheduled & trigger automations. Goal: run a saved skill on a schedule or a
  condition. Method: WorkManager jobs referencing a saved skill, gated by a
  settings flag and an off switch; conditions via system broadcasts (e.g. Wi-Fi).
  Reference: new `automation/Scheduler.kt`; `service/AgentExecutionService.kt`.
- 4.15 Local RAG. Goal: answer questions over the user's own notes/past tasks.
  Method: on-device embeddings + a small vector store over stored notes/tasks;
  inject bounded retrieved context into the prompt. Reference: new `memory/rag/`;
  `planning/PlannerPromptBuilder.kt`, `data/local/*`.

Robustness
- 4.16 Self-critique pass. Goal: second opinion before consequential steps. Method:
  ask the planner to review the planned action against the current screen; only on
  risky/financial/destructive steps. Reference: `agent/TaskOrchestrator.kt`,
  `agent/AgentAction.kt` (`RiskEvaluator`).
- 4.17 Learn-from-demonstration. Goal: record a user doing a task once, then
  replay it. Method: capture the user's action sequence (via the accessibility
  event stream) into a skill record; replay through the normal executor with
  verification. Reference: `accessibility/AgentAccessibilityService.kt`,
  `data/local/*`, `agent/TaskOrchestrator.kt`.

Phase 4 sequencing: land 4.4 + 4.13 + 4.10 + 4.11 first (biggest "feels alive"
jump), then 4.6 + 4.1 + 4.3 (vision), then 4.14 + 4.15 + 4.16 + 4.17, then 4.5 +
4.7 + 4.8 (advanced/optional), 4.9 deferred.

Phase 5 — Autonomy & Self-Healing (items 5.1 - 5.10). Goal: the agent operates
start-to-end without babysitting; when it hits a problem it solves it itself and
finds another way, instead of hardcoded retries or giving up. This mirrors the
2025-2026 research consensus (Reflexion, MobileUse's hierarchical reflection,
BacktrackAgent's verifier/judger/reflector + backtracking, AgentRewind's
recoverable execution): failure is expected on long tasks, so the system — not
the prompt alone — must provide detection, diagnosis, rollback, and a fresh
approach. Not hardcoded: the *taxonomy and loop* are engine-level, while the
*recoveries* are chosen by the model against the live screen.

Core principle to add to the loop: replace the current
"retry-the-same-action-N-times then fail" with a
Detect -> Diagnose -> Backtrack -> Re-plan -> Explore -> Learn cycle, all bounded
so it can never hang or loop forever.

- 5.1 Step-level verification gate. Goal: never advance on an unproven step.
  Method: keep the existing postcondition check but make it the single gate that
  decides "progressed / no progress" and feeds the recovery cycle (already
  partially present via `PostconditionVerifier`). Reference:
  `planning/PostconditionVerifier.kt`, `agent/TaskOrchestrator.kt`.
- 5.2 Failure taxonomy & detector. Goal: name *why* a step failed. Method: a
  `FailureClassifier` that maps observed signals (no screen change, target not
  found, wrong package, timeout, permission dialog, network error, validation
  rejection, loop detection) to typed failures; unit-testable without a model.
  Reference: new `agent/FailureClassifier.kt`; `agent/StuckLoopDetector.kt`.
- 5.3 Checkpoint / backtrack (recoverable execution). Goal: undo a bad path.
  Method: snapshot the meaningful screen state + plan position before each step
  (extend the existing `CheckpointEntity`) and, on a typed failure, return to the
  last known-good state (e.g. press Back/Home to a safe surface) before retrying.
  Reference: `data/local/TaskEntities.kt`, `agent/TaskOrchestrator.kt`,
  `agent/DeviceAutomation.kt`.
- 5.4 Hierarchical reflection (act-level, step-level, task-level). Goal: catch
  errors at multiple time scales, on demand. Method: a `Reflector` that reviews
  (a) the last action, (b) the current subgoal, and (c) overall task progress, and
  runs only when a failure or stall is detected (reflection-on-demand, to save
  compute on 8 GB). Reference: new `planning/Reflector.kt`;
  `planning/AgentPlanner.kt`, `agent/TaskOrchestrator.kt`.
- 5.5 Adaptive re-planning with backtracking. Goal: try a genuinely different
  route. Method: on typed failure, ask the planner for a NEW sub-plan with the
  failure context and the reflection summary, and mark the abandoned steps SKIPPED
  rather than re-running them. Reference: `planning/AgentPlanner.kt`,
  `planning/TaskPlanParser.kt`, `agent/TaskOrchestrator.kt` (extend the existing
  `REPLAN_NEW_APPROACH` path from one signal to the full cycle).
- 5.6 Proactive exploration when blocked. Goal: discover a way forward on an
  unfamiliar/cold-start screen. Method: when the target is not visible, the agent
  self-plans safe exploratory probes (scroll, open a likely entry point, expand a
  menu) to reveal it, then resumes the plan — bounded by a probe budget so it
  cannot wander. Reference: `agent/HybridStepExecutor.kt`, `agent/TaskOrchestrator.kt`.
- 5.7 Loop & dead-end guard. Goal: never spin. Method: generalize
  `StuckLoopDetector` to count repeated (state, action) pairs and consecutive
  no-progress cycles across the whole cycle, forcing a different strategy or an
  honest stop. Reference: `agent/StuckLoopDetector.kt`, `agent/TaskOrchestrator.kt`.
- 5.8 Honest escalation. Goal: ask the user only when truly stuck. Method: after
  the bounded cycle is exhausted, surface a clear "I'm blocked on X because Y;
  want me to try Z?" prompt (voice-friendly) instead of a bare failure; never fake
  success. Reference: `agent/TaskOrchestrator.kt`, `ui/AgentDashboardScreen.kt`,
  `agent/OrchestratorState.kt`.
- 5.9 Episodic memory / lessons learned (closes the learn loop). Goal: do not
  repeat the same mistake. Method: at task end (and after each recovery), write a
  compact episode record — goal, what failed, what finally worked — into the
  Phase-3 memory store, and inject the most relevant lessons into future planning
  prompts. Reference: `data/local/*`, `planning/PlannerPromptBuilder.kt`.
- 5.10 Progress budgeting & graceful degradation. Goal: keep long tasks bounded
  and honest. Method: a progress meter (steps used vs. remaining, recoveries
  used) that triggers re-plan, then exploration, then escalation, then a clean
  stop — in that order — so a hard task degrades gracefully instead of hanging.
  Reference: `agent/TaskOrchestrator.kt`.

Worked example (illustrative, not hardcoded): "Open ChatGPT, say hi, send it."
If the composer is not found -> 5.2 classifies "target not found" -> 5.6 explores
(tap the message area / expand) -> 5.1 sees the field now editable -> plan
resumes -> type -> submit. If the send control never appears -> 5.5 re-plans to
"press the keyboard Enter" as an alternative route -> 5.1 verifies the message
left the composer. If everything fails -> 5.8 asks the user, and 5.9 remembers
what worked for next time. No branch is a hardcoded ChatGPT special-case; the
engine supplies the loop and the model supplies the moves.

Phase 5 sequencing: 5.1 + 5.2 + 5.7 first (make failure legible and non-looping),
then 5.3 + 5.5 (checkpoint + re-plan), then 5.4 + 5.6 (reflection + exploration),
then 5.9 + 5.10 + 5.8 (learn + budget + honest escalation). Phase 5 depends on
Phase 3 (memory) and benefits from Phase 4 vision when the tree is blind.

Note: Phase 0 lands first and is implemented together with the concrete
submit/open-URL fixes you reported. Phases 1, 2, 3, 4, and 5 then follow in order.
The internet is used only for explicit web tasks (CloudflareGateway
`web_search`/`read_url`) and, if the user opts in, the item 4.8 cloud fallback;
the on-device E2B model remains the primary planner.

Observed defects (root-caused in the CURRENT code; these explain the reported
behavior and drive Phase 6). File:line references are to the code as it stands
now.

DEFECT A - The model is often not used at all, and nothing tells the user.
- `AgentPlanner.plan()` returns `PlanSource.BASIC_FALLBACK` the moment
  `inferenceEngine.isReady()` is false (`planning/AgentPlanner.kt` ~L37-42); it
  also falls back after only two bad parses (~L73-75).
- `TaskOrchestrator.runAgentLoop` swaps in `liteRtEngine.fallbackPlan(...)` on
  `Unavailable`/`Rejected`/timeout (`agent/TaskOrchestrator.kt` ~L281-299).
- Consequence: the "AI" you expect to hear from is silently replaced by the
  keyword `HeuristicPlanner`. Nothing in the run forces the local model to be
  used, and there is no "model unavailable - this is basic mode" signal strong
  enough to notice.

DEFECT B - The fallback planner is the direct cause of "opens WhatsApp then
scrolls forever".
- `HeuristicPlanner.planNextStep` ends with: if the goal OR the screen contains
  the word "scroll" -> `AgentAction.Scroll("down")`, else `ReadScreen`
  (`model/HeuristicPlanner.kt` ~L267-275).
- So a command like "Prateek ko WhatsApp pe message karo" is never decomposed
  (no "open"/"search"/"home" keyword matches), falling to `CUSTOM -> ReadScreen`,
  and then the generic scroll/read loop runs with nothing to do -> it scrolls.
- The fallback also only understands English keyword clauses (open/search/find/
  home/back/toggle) and keyword app aliases (`isTargetAppForeground`), so Hindi/
  Roman-Hindi ("message karo", "kholo", "bhejo") matches nothing.

DEFECT C - "Verification failed: request text was not verified in the current
field" on normal search.
- The fallback's search step uses
  `verification = RESULTS_PRESENT` with `expectedPostcondition = query`
  (`model/HeuristicPlanner.kt` ~L109-120), and the plan-level
  `verifyGoalPostcondition` then fails because the exact query text / results
  are not matched on screen - even though the text was typed. The verify method
  and the expectation disagree.

DEFECT D - `Submit` / `enter_text` semantics can misfire after typing.
- In `HybridStepExecutor`, `TypeText` executes only if a likely text input is
  visible (`agent/HybridStepExecutor.kt` ~L71-77); otherwise it defers. `Submit`
  always executes ("press Enter / activate Send"). If focus was not actually in
  the field, Enter goes nowhere, so the typed text is "not verified".

DEFECT E - The "fast path" can bypass the model on findings it cannot justify,
and there is no explicit model-use guarantee.
- `HybridStepExecutor.decide(...)` returns deterministic actions (including
  `Scroll`) and `TaskOrchestrator` only calls the model when it returns
  `ModelReason` (`agent/TaskOrchestrator.kt` ~L400-427). This is intentional for
  speed, but combined with Defect B it produces confident scrolling instead of
  reasoning.

DEFECT F - "Web Skills" is a separate, unused tab.
- `WebSkillsScreen` is reachable via its own tab (`MainActivity` ~L30,L109-113)
  and `CloudflareGateway` is a standalone screen, not wired into the task loop
  as a tool the planner can call (the loop accepts a `webGateway` but the UI
  surface is disconnected from task execution).

DEFECT G - Continuous per-event screen reading exists in code but is not what
drives decisions.
- `ScreenAwareness`/`AgentAccessibilityService.screenEvents` exist, but
  `TaskOrchestrator` still calls `deviceAutomation.observe()` in a step loop
  (~L348); it does not yet react to each event, so "read the screen every
  moment" is not realized.

Phase 6 - Reasoning & Reliability Rescue (items 6.1 - 6.24; 6.1 - 6.12 approved
for implementation now). Goal: make the AI actually drive the task, fix the
fallback bugs, make Hindi/Roman-Hindi understood, guarantee "read the screen, and
if the target is missing ask the model (or the web) before ever scrolling", and
remove the dead Web Skills surface. Items 6.13 - 6.24 deepen the same rescue so
the small on-device model is actually reliable, not just wired in.

- 6.1 Force the model to be the planner. Goal: never silently run keywords.
  Method: require a healthy local model before a task starts; surface a clear
  blocking state ("Local AI not loaded - set up the model in Setup") instead of
  quietly using BASIC_FALLBACK; keep the fallback only as an explicit,
  user-visible degraded mode. Reference: `planning/AgentPlanner.kt`,
  `agent/TaskOrchestrator.kt`, `ui/AgentDashboardScreen.kt`.
- 6.2 Kill the generic scroll/read loop. Goal: stop the "scrolling forever"
  behavior. Method: remove the "goal or screen contains scroll -> Scroll" and
  the bare `ReadScreen` final fallbacks from `planNextStep`; if the planner
  cannot map the command to a real subgoal, return an explicit "cannot plan"
  decision that triggers re-plan/ask, not a scroll.
  Reference: `model/HeuristicPlanner.kt`.
- 6.3 Multilingual command understanding. Goal: understand Hindi/Roman-Hindi
  intent. Method: route ALL language understanding through the model (6.1) and
  give the fallback a minimal Hindi/Roman-Hindi verb map (kholo/bhejo/message/
  karo/dhundho/band) so degraded mode is not English-only; the model path needs
  no keyword map. Reference: `model/HeuristicPlanner.kt`,
  `planning/PlannerPromptBuilder.kt` (system instruction: accept Hindi/Roman
  Hindi commands).
- 6.4 Messaging/contact task decomposition. Goal: "message X on WhatsApp"
  becomes launch -> open search -> type contact -> open chat -> type message ->
  submit. Method: make the planner produce these real subgoals (model-first),
  and add the same shape to the fallback as a *pattern*, not a hardcode.
  Reference: `planning/PlannerPromptBuilder.kt`, `model/HeuristicPlanner.kt`,
  `planning/TaskPlanParser.kt`.
- 6.5 Fix search verification. Goal: a typed query verifies correctly. Method:
  change the fallback search step to `SCREEN_CHANGED` (or a query-input check),
  and make `RESULTS_PRESENT` accept "the input field now contains the query" as
  evidence; keep the existing list/scrollable checks. Reference:
  `planning/PostconditionVerifier.kt`, `model/HeuristicPlanner.kt`.
- 6.6 Reliable text entry + submit. Goal: typed text lands and submits. Method:
  focus/verify the target field first (re-observe after focus), then submit;
  after submit, verify the field accepted the text (TEXT_PRESENT in the field
  node) before marking complete; add a bounded retry that re-focuses instead of
  re-typing blindly. Reference: `agent/HybridStepExecutor.kt`,
  `accessibility/AgentAccessibilityService.kt`, `agent/TaskOrchestrator.kt`.
- 6.7 Missing-target rule: ask before scrolling. Goal: the exact behavior you
  asked for ("if you can't find it, ask the model / google, don't just scroll").
  Method: when a target/label is not visible, first ask the model for a grounded
  alternative (and, if web search is enabled, optionally search the web) to
  locate it; only if that fails, allow ONE bounded exploration probe (5.6). Never
  fall through to unbounded scrolling. Reference: `agent/TaskOrchestrator.kt`,
  `planning/AgentPlanner.kt`, `perception/*`.
- 6.8 Workspace/shared model context. Goal: one loaded engine, no re-init cost
  or double memory use. Method: hold a single `LiteRtEngine`/`GemmaInferenceEngine`
  in `OmniAgentApplication` and share it (the UI and orchestrator must reference
  the same instance); assert only one engine is initialized at a time.
  Reference: `OmniAgentApplication.kt`, `MainActivity.kt`, `agent/TaskOrchestrator.kt`.
- 6.9 Strengthen the vision/OCR fallback. Goal: when the a11y tree is blind
  (WebView/canvas), the agent can still see. Method: make item 4.1/4.2/4.3 the
  default first response to a missing target on such surfaces, not optional.
  Reference: `perception/VisionPerception.kt`, `perception/ScreenAwareness.kt`,
  `model/LiteRtLmRuntime.kt` (multimodal input, item 4.6).
- 6.10 Remove the Web Skills surface. Goal: no dead, non-functional tab.
  Method: remove `WebSkillsScreen` and its tab; keep `CloudflareGateway` ONLY as
  the in-loop `web_search`/`read_url` tool (defect F), so web access serves task
  completion rather than a separate unused screen. Reference: `MainActivity.kt`,
  `ui/WebSkillsScreen.kt`, `cloudflare/CloudflareGateway.kt`,
  `agent/AgentAction.kt` (`WebSearch`/`ReadUrl`).
- 6.11 Real-time, event-driven reasoning. Goal: honor "read the screen every
  moment". Method: drive the loop from `ScreenAwareness.events` (a bounded
  debounce/settle, not a fixed `delay(300)`) so each meaningful UI change
  triggers re-observation/re-decision. Reference: `perception/ScreenAwareness.kt`,
  `accessibility/AgentAccessibilityService.kt`, `agent/TaskOrchestrator.kt`.
- 6.12 Bounded "what am I doing / why" trace. Goal: you can SEE the model
  thinking and confirm it is used. Method: surface a per-step trace that names
  the source (GEMMA vs BASIC), the chosen action, and the reason, in the Live
  Monitor and History. Reference: `agent/TaskOrchestrator.kt` (`_modelCalls`,
  `_fastPathSteps`, `_usesModelPlanning`), `ui/AgentLiveMonitorScreen.kt`,
  `ui/TaskHistoryScreen.kt`.

Deepening items (6.13 - 6.24) - so the on-device model is actually reliable:

- 6.13 ReAct step loop (think -> act -> observe). Goal: reason one step at a
  time from the live screen instead of trusting a stale upfront plan. Method:
  make `decideNextAction` the primary driver of every step (the model sees the
  freshest observation and the just-happened result), and treat the upfront plan
  as advisory; only fall back to fast-path/fixed actions when provably
  deterministic. Reference: `planning/AgentPlanner.kt`,
  `agent/TaskOrchestrator.kt`, `planning/PlannerPromptBuilder.kt`.
- 6.14 Screen grounding for the model: structured JSON, not native a11y. Goal:
  the model can actually "read" the screen. Method: build a compact, sanitized
  element list (text, class, clickable, editable, nearest icon/symbol hint,
  bounds, parent label) - including icon-only buttons described by their
  contentDescription/emoji/aria - and pass THAT (bounded) instead of a raw dump;
  add the whole foreground package + activity. Reference:
  `accessibility/AccessibilityTreeJsonParser.kt`, `accessibility/UiNode.kt`,
  `planning/PlannerPromptBuilder.kt`.
- 6.15 Enrich every element with context. Goal: no more "can't find where to
  tap" for unlabeled icons. Method: per node add `desc` (contentDescription/text/
  hint), `clickable`, `editable`, `scrollable`, `checked`, `selected`, `enabled`,
  `bounds`, and a `role` guess; include list-item container titles so
  "message Prateek" can target the row whose container says "Prateek". Reference:
  `accessibility/UiNode.kt`, `accessibility/AccessibilityTreeJsonParser.kt`.
- 6.16 Bounded history inside the prompt. Goal: stop repeating a failed move.
  Method: inject the last N (state, action, result) triples into the step prompt
  with "do not repeat a failed action on an unchanged screen", capped in size.
  Reference: `planning/PlannerPromptBuilder.kt`, `agent/TaskOrchestrator.kt`.
- 6.17 Typed error recovery table. Goal: act correctly per failure instead of
  generic retry. Method: map each `FailureType` to a concrete recovery (dialogs ->
  dismiss/allow; empty field -> refocus+retype; slow load -> wait_for_text;
  wrong package -> relaunch; auth wall -> escalate login), chosen before
  re-asking the model. Reference: `agent/FailureClassifier.kt`,
  `agent/TaskOrchestrator.kt`.
- 6.18 Robust JSON output + repair. Goal: the small model's malformed JSON stops
  killing tasks. Method: keep the current balanced-brace extractor but tolerate
  trailing commas, single quotes, code fences, and multiple JSON objects; add a
  targeted "here is what was wrong, return only fixed JSON" repair pass and, as a
  last resort, regex-salvage a single action. Reference: `planning/TaskPlanParser.kt`,
  `planning/AgentPlanner.kt`.
- 6.19 Typed params with value coercion. Goal: string params stop failing schema
  checks. Method: coerce "3"/"true"/numbers, accept aliases (app=appQuery,
  text=query, element=targetText), and fill sensible defaults so a near-miss
  still validates instead of being rejected wholesale. Reference:
  `planning/TaskPlanParser.kt`, `agent/ToolRegistry.kt`.
- 6.20 Deterministic pre/post verification per action. Goal: every action has a
  cheap, app-generic check that it worked. Method: define, per action type, what
  "worked" means (e.g. open_search -> an editable field appeared; submit ->
  field cleared or message left the composer) and make it the step verifier.
  Reference: `planning/PostconditionVerifier.kt`, `agent/HybridStepExecutor.kt`,
  `agent/TaskOrchestrator.kt`.
- 6.21 Post-action clone/ghost-node protection. Goal: stop taps on stale bounds.
  Method: always re-observe before acting, prefer `targetText`/role matching over
  cached coordinates, and drop nodes whose bounds fall outside the current window
  or that are marked invisible/obscured. Reference: `accessibility/UiNode.kt`,
  `accessibility/AgentAccessibilityService.kt`, `agent/DeviceAutomation.kt`.
- 6.22 Workflow templates (not hardcoded apps). Goal: common intents work
  reliably even when the model is weak. Method: add a small library of generic,
  model-preferred recipes (message someone; call someone; navigate to a place;
  search-in-app then open a result; play a song; set an alarm) expressed as
  subgoal patterns the model can adapt, and let the model fill the slots (contact,
  query, place). Reference: `planning/*`, `model/HeuristicPlanner.kt`.
- 6.23 Model-fit guard with honest fallback labelling. Goal: the E2B model is
  small; be honest when it struggles. Method: track per-step model agreement /
  repair counts; if quality is repeatedly poor on this device, surface an honest
  "on-device model is limited here" notice and (if enabled) use the opt-in cloud
  fallback or a larger local model; never silently pretend success. Reference:
  `model/LiteRtModelManager.kt`, `planning/AgentPlanner.kt`, `ui/SetupScreen.kt`.
- 6.24 Waiting/motion discipline. Goal: stop racing a loading UI or scrolling
  mid-animation. Method: replace fixed `delay(300)` with "wait until the UI is
  quiet" (fingerprint/file-size stability) plus action-specific waits (keyboard
  shown, spinner gone, results present, message sent), each bounded. Reference:
  `perception/ScreenAwareness.kt`, `agent/DeviceAutomation.kt`,
  `agent/TaskOrchestrator.kt`.

Phase 6 sequencing: 6.1 + 6.2 + 6.5 + 6.6 first (stop keywords, stop scrolling,
fix search/submit), then 6.3 + 6.4 + 6.7 (understand Hindi, decompose messaging,
ask-before-scroll), then 6.13 + 6.14 + 6.15 + 6.24 (ReAct loop + real screen
grounding + rich elements + settle discipline), then 6.16 + 6.17 + 6.20 + 6.21
(history, typed recovery, per-action verification, stale-node safety), then
6.18 + 6.19 (robust JSON + coerced params), then 6.22 (workflow templates), then
6.8 + 6.12 (one engine + visible trace), then 6.9 + 6.11 (vision + event-driven
loop), then 6.10 + 6.23 (remove Web Skills + model-fit guard). Phase 6 supersedes
and subsumes the Phase 0 foundations wherever they overlap; do Phase 6 before
polishing Phases 4-5.

Current gaps audit (post-Phase-6, from reading the code as it stands now). Phase 6
is landed and real. But several Phase 4/5 items are SEAMS ONLY (an interface plus
an "Unavailable"/disabled default) - the loop compiles and is honest, but the
capability is not actually present. These are the "kami" still remaining.

REAL and working today:
- Accessibility automation, action vocabulary, `HybridStepExecutor` fast path.
- LiteRT-LM Gemma E2B engine with GPU on + CPU fallback (single shared engine in
  `OmniAgentApplication`).
- Planner + strict `TaskPlanParser` + `ToolRegistry` validation + `RiskEvaluator`.
- Orchestrator recovery loop: `Reflector`, `ProgressBudget`, `StuckLoopDetector`,
  `FailureClassifier`, episodic lessons, checkpoints.
- `AgentNotificationListenerService` (real; reads posted notifications on grant).
- `AndroidTextToSpeechOutput` (real Android TTS).
- `TokenOverlapRetriever` / `LocalRag` (real Room-backed retrieval).
- `CloudflareGateway` (real: DuckDuckGo instant-answer search + `read_url` HTML
  text extraction + PDF head check).
- Phase 6 fixes: 6.1, 6.2, 6.3/6.4, 6.10, 6.12, 6.16, 6.18, 6.19, 6.22.

SEAM ONLY (interface exists, real backend missing) - this is the remaining work:
- 4.1 Vision grounding: only `UnavailableVisionGrounder` (returns null).
- 4.2 OCR: only `UnavailableOcrReader` (returns "").
- 4.6 Multimodal E2B: `LiteRtLmRuntime` sends `Content.Text` only; no image input.
- 4.5 Shizuku: `GatedPrivilegedBridge` has `exec = null` (always refuses); the
  Shizuku SDK is not a dependency.
- 4.7 Gemini Nano: `UnavailableNanoSummarizer` (no AICore); `firebase.ai` is in
  deps but unused for Nano.
- 4.8 Cloud fallback: only `DisabledCloudPlannerProvider` (isEnabled=false).
- 4.10/4.12 Voice in / hands-free: `OptInWakeWordDetector` arms a flag but there
  is NO SpeechRecognizer/ASR; voice commands are not actually captured.
- 4.14 Scheduled automations: `AutomationGate` is pure logic; no WorkManager
  worker is registered and nothing schedules.
- 4.16/4.17 Self-critique / learn-from-demonstration: not wired end-to-end.
- 6.23 Model-fit/battery guard: not implemented (deferred).

Suspected HIGH-RISK issue to verify first:
- `LiteRtModelManager` pins `gemma-4-E2B-it.litertlm` from
  `huggingface.co/litert-community/gemma-4-E2B-it-litert-lm` with a hardcoded size
  and SHA-256. The repo and the `gemma-4-E2B-it.litertlm` filename are CONFIRMED
  real (checked against Hugging Face; the E4B sibling uses the same naming), so
  the URL is very likely correct. What still must be verified is the exact byte
  length and SHA-256, because a wrong pinned hash fails `finalizeVerifiedPart`
  and deletes the file - and with 6.1 in place the app then BLOCKS planning.
  Verify the hash before anything else.

Phase 7 - Completion & Hardening (items 7.1 - 7.17). Goal: turn every remaining
seam into a real capability, fix the highest-risk reliability issues, and close the
"not fully functional" gap. All items are done together in one pass.

- 7.1 VERIFY + FIX the pinned model artifact (highest priority). Goal: the model
  actually downloads and verifies. Method: confirm the real HuggingFace repo/filename
  and its true byte length + SHA-256; correct `OFFICIAL_MODEL_URL`,
  `MODEL_FILENAME`, `EXPECTED_MODEL_BYTES`, `EXPECTED_MODEL_SHA256`; add a startup
  self-check that logs a clear error if the artifact is unreachable. Reference:
  `model/LiteRtModelManager.kt`, `ui/SetupScreen.kt`.
- 7.2 Real vision grounding (4.1). Goal: locate a target the a11y tree cannot
  represent. Method: implement `VisionGrounder` backed by the multimodal E2B (or a
  lightweight on-device detector) and call it only when the tree misses the target.
  Reference: `perception/VisionPerception.kt`, `agent/TaskOrchestrator.kt`.
- 7.3 Real OCR (4.2). Goal: read text baked into images/canvas. Method: add an
  offline OCR backend (e.g. ML Kit text recognition bundled) behind `OcrReader`,
  bounding the work to the current screen. Reference: `perception/VisionPerception.kt`,
  `app/build.gradle.kts`.
- 7.4 Multimodal E2B input (4.6). Goal: the vision-capable model actually sees a
  screenshot. Method: extend `LlmRuntime.generate` (or add an image overload) to
  send `Content.Image` alongside text; feed the screenshot on a tree-miss.
  Reference: `model/LiteRtLmRuntime.kt`, `model/GemmaInferenceEngine.kt`,
  `planning/AgentPlanner.kt`.
- 7.5 Real voice input / hands-free loop (4.10, 4.12). Goal: usable voice
  commands. Method: implement a `SpeechRecognizer`-based listener behind the
  existing opt-in + mic permission, wire recognized text into `startTask`, and add
  a confirm-before-run step. Reference: `voice/WakeWord.kt`, `voice/SpeechOutput.kt`,
  `MainActivity.kt`, `agent/TaskOrchestrator.kt`.
- 7.6 Real opt-in cloud fallback (4.8). Goal: a working cloud planner when the
  local model fails, still off by default. Method: implement an HTTPS
  `CloudPlannerProvider` with a user-supplied endpoint/key stored locally; keep the
  strict parser gate; never egress without explicit opt-in. Reference:
  `planning/CloudPlannerProvider.kt`, `ui/SetupScreen.kt`, `agent/TaskOrchestrator.kt`.
- 7.7 Shizuku real execution OR drop the promise (4.5). Goal: no inert feature.
  Method: either add the Shizuku dependency and a real bridge executor, or remove
  the claim and keep only the honest gate. Decide explicitly. Reference:
  `privileged/PrivilegedActions.kt`, `app/build.gradle.kts`, `AndroidManifest.xml`.
- 7.8 Scheduled automations via WorkManager (4.14). Goal: automations actually
  run. Method: register a WorkManager worker honoring `AutomationGate`, persist
  automations, and add a UI to enable them (default OFF). Reference:
  `automation/AutomationGate.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `ui/*`.
- 7.9 Model-fit / battery guard (6.23). Goal: honest device-fit guidance. Method:
  add `isFitForDevice()` (RAM/storage/backend) and surface a recommendation in
  Setup; note the pinned E2B context (3072) is near the 8 GB ceiling. Reference:
  `model/LiteRtModelManager.kt`, `model/LiteRtLmRuntime.kt`, `ui/SetupScreen.kt`.
- 7.10 Self-critique + learn-from-demonstration (4.16, 4.17). Goal: finish these.
  Method: verify `PlanSelfReview` runs before execution; add a bounded "watch me"
  capture that turns an observed successful run into a saved skill. Reference:
  `planning/PlanSelfReview.kt`, `data/local/*`, `agent/TaskOrchestrator.kt`.
- 7.11 Web-search quality. Goal: `web_search` returns usable results, not only a
  DuckDuckGo instant answer. Method: add a real results endpoint (opt-in key)
  alongside the current one; keep `read_url` for page depth. Reference:
  `cloudflare/CloudflareGateway.kt`, `agent/TaskOrchestrator.kt`.
- 7.12 Gemini Nano: wire AICore or drop (4.7). Goal: no dead path. Method: either
  add the AICore/Nano integration, or remove `NanoSummarizer` from the wiring and
  document the E2B-only path. Reference: `model/NanoSummarizer.kt`,
  `OmniAgentApplication.kt`.
- 7.13 Reliability hardening from real device runs. Goal: fix the concrete bugs
  you hit. Method: run the target flows on the Motorola Edge 70 Fusion, capture the
  exact error text/step, and fix each root cause (this list is populated by real
  runs, not guesses). Reference: whichever files the failures point to.
- 7.14 Deferred by design: item 4.9 (LoRA fine-tune) stays documented-only.
- 7.15 One opt-in capability settings screen. Goal: the new capabilities have a
  single, honest home. Method: add a Settings surface to toggle voice, cloud
  fallback (+endpoint/key), Shizuku, automations, GPU/CPU backend, and to read the
  model-fit recommendation; every toggle defaults OFF and shows its state.
  Reference: `ui/SetupScreen.kt` (or a new `ui/SettingsScreen.kt`),
  `MainActivity.kt`, `OmniAgentApplication.kt`.
- 7.16 On-device instrumentation tests + scripted self-test. Goal: verify the
  real flows on the target phone, not only Robolectric. Method: add an
  `androidTest` suite plus an in-app "Run self-test" that exercises launch /
  search / type / submit / vision-fallback and reports pass/fail per capability.
  Reference: `app/src/androidTest/**`, `ui/*`, `agent/TaskOrchestrator.kt`.
- 7.17 Hindi / Roman-Hindi done end-to-end. Goal: the app speaks the user's
  language everywhere. Method: extend the multilingual handling from commands to
  the UI strings, TTS locale, and status/error messages (bounded, no full i18n
  framework needed). Reference: `ui/*`, `voice/SpeechOutput.kt`,
  `agent/TaskOrchestrator.kt`.

Phase 7 scope note: do ALL of 7.1 - 7.17 together in one pass, not one-after-another.
There is no "first this, then that" ordering to wait on - every item below is in
scope for the same build. The only ordering that exists is the natural build
dependency inside the code (you cannot call a real backend before you write it),
but the whole phase is one deliverable: no item is deferred except 7.14 (item
4.9, LoRA, which is documented-only by design). 7.1 (verify the pinned model
artifact) is listed first only because a wrong hash would block everything else
at runtime - it is not a gate you must finish before starting the rest; build it
alongside the others in the same pass.

Phase 8 - Jarvis rebrand, working load, and premium UI (TOP PRIORITY, user-requested
8.1 - 8.7). Do all together with Phase 7 in the same build pass. This phase fixes
the exact complaints: the model downloads but will not load, no error is ever
shown, many features are written but not actually wired, the app is still called
"OmniAgent", and the UI is generic.

- 8.1 MAKE THE MODEL ACTUALLY LOAD (the headline bug). Goal: after a verified
  download, `EngineState` reaches `Ready` and planning uses the real model.
  VERIFIED cause: the model already loads; the SMOKE TEST fails on a tiny 8-token
  prompt that legitimately returns an empty string, and one blank answer calls
  `reportUnavailable(...)` which closes the working runtime. Method:
  (a) make `LiteRtLmRuntime.smokeTest()` non-destructive and tolerant - succeed on a
  non-blank answer, and on an empty-but-no-exception answer treat the engine as
  usable; retry the probe a bounded number of times (slightly larger token budget);
  only a THROWN exception marks failure; (b) stop `GemmaInferenceEngine` /
  `OmniAgentApplication.initializeEngineIfModelReady()` from closing a working
  runtime on a single inconclusive smoke probe (combine (a) and (b): a healthy
  engine is never thrown away); (c) KEEP the CPU-first backend + bounded CPU retry
  for genuine init failures (GPU delegate) - already present, do not regress;
  (d) confirm `MAX_CONTEXT_TOKENS` stays a single source of truth (already
  reconciled: `LiteRtEngine` delegates to `LiteRtLmRuntime`, 3072).
  Reference: `model/LiteRtLmRuntime.kt`, `model/GemmaInferenceEngine.kt`,
  `OmniAgentApplication.kt`, `model/LiteRtEngine.kt`.
- 8.2 SHOW THE MODEL-LOAD ERROR (never a silent "not loaded"). Goal: the user
  always sees why. Method: surface the real `EngineState.Failed(message)` text in
  the dashboard and in `SetupScreen` (which currently shows download state only),
  with a prominent reason and a Retry button; stop showing the generic
  "Basic mode" label when 6.1 actually BLOCKS, and correct that wording. Reference:
  `ui/AgentDashboardScreen.kt`, `ui/SetupScreen.kt`,
  `model/GemmaInferenceEngine.kt`, `MainActivity.kt`.
- 8.3 REBRAND to "Jarvis" everywhere user-visible. Goal: no "OmniAgent" remains on
  screen. Method: change `strings.xml` `app_name`, the accessibility service
  label/description, notification titles, dashboard/setup/settings copy, and the
  launcher label. Keep internal class names (`OmniAgentApplication`, package
  `com.example`) to avoid a massive rename unless the user asks for it - but ALL
  user-facing text becomes "Jarvis". Reference: `res/values/strings.xml`,
  `res/values*/`, `ui/*`, `notifications/CompletionNotifier.kt`,
  `service/AgentExecutionService.kt`, `AndroidManifest.xml`.
- 8.4 PREMIUM "JARVIS" UI REDESIGN. Goal: a cool, modern, high-quality interface
  worthy of the Jarvis name. Method: rebuild the app shell and primary screens with
  a cohesive dark, glassy/sci-fi theme - a central animated status orb, subtle
  glow/gradient accents, smooth transitions, an icon/capability status strip, and
  a clear voice/text command bar as the focal element. Keep the existing tabs
  working (`Agent`, `Live Monitor`, `Setup`, `Capabilities`, `History`) but make
  them feel premium and consistent. Reference: `ui/theme/*`, `ui/AgentDashboardScreen.kt`,
  `ui/AgentLiveMonitorScreen.kt`, `ui/SetupScreen.kt`, `ui/SettingsScreen.kt`,
  `MainActivity.kt`.
- 8.5 NEW SMALL ICON / BRAND MARK. Goal: a distinct Jarvis launcher icon and in-app
  mark, not the default. Method: add adaptive-icon resources (foreground/background/
  monochrome) with a clean minimalist mark, update `mipmap-anydpi-v26`, and use the
  same mark as the in-app logo and the floating orb. Reference:
  `res/mipmap-anydpi-v26/*`, `res/drawable/*`, `AndroidManifest.xml`,
  `overlay/AgentOrbView.kt`.
- 8.6 UI polish pass for the flows that felt broken. Goal: when Jarvis opens an app
  or types, the user SEES what is happening. Method: surface the live "what I'm
  doing now" line (open app / find field / type / press Enter / verify), a
  model-use badge (Jarvis AI vs basic mode), and a clear success/failure summary on
  each task; wire these to the orchestrator's existing observables. Reference:
  `ui/AgentDashboardScreen.kt`, `ui/AgentLiveMonitorScreen.kt`,
  `agent/TaskOrchestrator.kt`.
- 8.7 FIX EVERY "WRITTEN BUT NOT WORKING" DEFECT from section 2.3 (BS1 - BS10).
  These are confirmed defects, not new features - make each one actually work:
  * BS1: CALL `EngineLoadStatusCard` in `SetupScreen` (pass the application/engine
    in) so the load status + real failure + Retry is actually visible on the Setup
    screen. This is the headline "download hua, load nahi hua, kuch dikha nahi" fix.
  * BS2: wire `privilegedActions` into a real action path (or, if it stays opt-in
    only, stop advertising it as working) so Shizuku is not dead wiring.
  * BS3: wire `WakeWordDetector` into the hands-free flow, or remove it from the
    capability list so it is not advertised-but-dead.
  * BS4: add endpoint/key entry to `SettingsScreen` so cloud fallback is actually
    reachable end to end (still opt-in, still off by default).
  * BS5: make `MAX_CONTEXT_TOKENS` a single source of truth used by both
    `LiteRtEngine` and `LiteRtLmRuntime`.
  * BS6: replace every user-visible "OmniAgent" with "Jarvis" (dashboard header,
    notifications, overlay, prompts).
  * BS7: replace the launcher icon with the new Jarvis adaptive icon.
  * BS8: correct the misleading "Basic mode" dashboard label to state the truth
    (blocked / model required).
  * BS9: reconcile or remove the stale legacy `reasonAndPlan` claims.
  * BS10: remove/neutralize any leftover reachable "Unavailable*" seam so no dead
    default ships in the live graph.
  Reference: `ui/SetupScreen.kt`, `ui/AgentDashboardScreen.kt`,
  `ui/SettingsScreen.kt`, `OmniAgentApplication.kt`, `voice/WakeWord.kt`,
  `privileged/PrivilegedActions.kt`, `planning/CloudPlannerProvider.kt`,
  `model/LiteRtEngine.kt`, `model/LiteRtLmRuntime.kt`,
  `notifications/CompletionNotifier.kt`, `overlay/FloatingAgentDotService.kt`,
  `res/mipmap-anydpi-v26/*`.
- 8.9 STOP THE PHONE HANGING (C5, NEW, user-reported after the model-load work).
  Goal: the phone is as smooth as before - the assistant never thrashes memory or
  CPU. Method: (a) make engine init SINGLE-FLIGHT - re-check the Ready state INSIDE
  `mutex.withLock` and close any existing runtime before assigning a new one, so
  two concurrent initializers can never leave two ~2.6 GB runtimes alive;
  (b) stop the load/unload thrash - `onLowMemory()`/`onTrimMemory()` must NOT
  unconditionally `shutdown()` the engine; only release under genuine critical
  pressure, and debounce/back-off any re-init so it cannot spin;
  (c) default to CPU-first (`USE_GPU_BACKEND = false`; GPU strictly opt-in) so a
  failed GPU delegate does not double the init work; (d) run the smoke probe once,
  cheaply, only after the first successful load - not on every re-open;
  (e) confirm the bounded context (3072) and thread budget (4) stay conservative.
  Reference: `model/GemmaInferenceEngine.kt`, `OmniAgentApplication.kt`,
  `MainActivity.kt`, `model/LiteRtLmRuntime.kt`.
- 8.10 UNDO THE EARLIER GPU/CPU BACKEND CHANGES (user-requested revert). Goal: put
  the model-backend behaviour back to how it was BEFORE the GPU/CPU changes were
  made, because those changes are suspected of causing the hang. Method: revert the
  loader-side changes rather than layering more on top -
  * restore the original CPU-first load path and the original single runtime factory
    (drop the added GPU-primary `runtimeFactory` + separate `cpuRuntimeFactory`
    two-attempt scheme in `OmniAgentApplication`, unless a single CPU path already
    covers it);
  * set `USE_GPU_BACKEND = false` (GPU strictly opt-in, or removed for now);
  * revert the context/thread/branch changes tied to that work back to the prior
    values (`LiteRtLmRuntime` constructor defaults, `MAX_CONTEXT_TOKENS`,
    `DEFAULT_CPU_THREADS`) so the engine footprint matches the pre-change build;
  * keep only the honest error-surfacing (8.2) that the user asked for - do NOT
    keep the GPU retry/two-engine behaviour that thrashes memory.
  Method note: prefer a clean revert (git diff against the pre-change commit) over a
  partial hand-edit, so no half-reverted loader path remains. Reference:
  `model/GemmaInferenceEngine.kt`, `OmniAgentApplication.kt`,
  `model/LiteRtLmRuntime.kt`, `model/LiteRtEngine.kt`, `git` history.

Phase 8 scope note: do ALL of 8.1 - 8.10 together. 8.1, 8.2, 8.7, 8.9 and 8.10 are
the functional fixes (8.9 is the phone-hang fix and 8.10 reverts the GPU/CPU
loader changes); 8.3 - 8.6 are the rebrand and UI. They ship in the same pass.

Phase 9 - Fully-automated "Jarvis" capability upgrades (items 9.1 - 9.14, NEW
features the user asked for). Goal: move from "responds to a typed command" to a
genuinely proactive, conversational, hands-free, cross-app assistant - the closest
honest thing to Jarvis on one 8 GB phone. Two hard constraints kept throughout:
(a) NO second/larger model - everything reuses the single E2B engine already
loaded; (b) every always-on or privacy-sensitive feature is OPT-IN and OFF by
default. Build these on top of the Phase 7/8 work (they assume the seams are now
real). Grounded in the files we verified exist.

- 9.1 Proactive awareness mode (opt-in). Goal: Jarvis notices context and *offers*
  help without being asked. Method: a bounded foreground awareness loop that reads
  the current foreground app + recent notifications + time, and surfaces a
  suggestion ("You usually message Prateek now - want to?"). Reuse the real
  `AgentNotificationListenerService` and `ScreenAwareness`; no continuous screen
  capture (poll-bounded, battery-aware). Reference: `perception/ScreenAwareness.kt`,
  `notifications/NotificationBus.kt`, `service/AgentExecutionService.kt`,
  new `agent/ProactiveEngine.kt`.
- 9.2 Context/event triggers for automations (extends 7.8 / 4.14). Goal: automate a
  routine on an event, not just a clock time. Method: add triggers - time/interval,
  battery level, charging, network/Wi-Fi change, app-foreground, notification-from-
  app, and (opt-in) location arrive/leave. Reuse `AutomationGate` + `AutomationScheduler`.
  Reference: `automation/AutomationGate.kt`, `automation/AutomationScheduler.kt`,
  `data/local/AgentDao.kt`.
- 9.3 Multi-turn conversational memory. Goal: follow-ups resolve ("and send it",
  "do the same for Ravi"). Method: keep a short conversational context (last
  command, last target, last app) and feed it into planning next to the existing
  memory summary. Reference: `agent/TaskOrchestrator.kt`, `planning/AgentPlanner.kt`,
  `planning/PlannerModels.kt`.
- 9.4 Cross-app data passing (task variables/slots). Goal: carry a value across
  steps/apps ("copy that price, open Notes, paste it"). Method: add a small typed
  variable store on the plan (last-read text, clipboard, search result) usable by a
  later step; expose via `AgentAction` + plan serialization. Reference:
  `agent/AgentAction.kt`, `planning/TaskPlan.kt`,
  `planning/TaskPlanSerializer.kt`.
- 9.5 Explicit personal memory commands + Memory UI. Goal: the user can teach Jarvis
  facts and see them. Method: implement "remember that ...", "forget ...", "what do
  you know about ...", store in the existing Room-backed store, always inject the
  user profile/preferences into planning, and add a Memory list screen. Reference:
  `memory/rag/LocalRag.kt`, `data/local/*`, `ui/*`.
- 9.6 Skill library UI (view / edit / replay / delete). Goal: learned routines are
  first-class and reusable. Method: surface the skills produced by 7.10
  (`autoSaveSkills` / episodes) in a screen, let the user rename/trigger/delete, and
  allow a skill to be voice-triggered. Reference: `planning/EpisodeDeriver.kt`,
  `data/local/*`, `ui/*`, `agent/TaskOrchestrator.kt`.
- 9.7 Full hands-free voice loop. Goal: "Jarvis" wake -> listen -> confirm ->
  execute -> spoken result, interruptible. Method: on-device wake-word + ASR behind
  the existing opt-in + mic permission, a short spoken confirmation for risky
  actions, and a spoken completion via the existing TTS; a hard stop phrase. No
  cloud audio, ever. Reference: `voice/WakeWord.kt`, `voice/SpeechOutput.kt`,
  `ui/AgentDashboardScreen.kt`, `overlay/FloatingAgentDotService.kt`.
- 9.8 Daily briefing / digest. Goal: a scheduled spoken briefing. Method: a
  WorkManager job at a user-set time composes a bounded summary (your notifications
  + optional web) and speaks it; shown as a notification too. Reference:
  `automation/AutomationScheduler.kt`, `voice/SpeechOutput.kt`,
  `cloudflare/CloudflareGateway.kt`, `notifications/*`.
- 9.9 Smart notification triage. Goal: Jarvis summarises and offers actions on
  notifications. Method: group/summarise posted notifications on-device and offer
  one-tap "reply / open / dismiss" (opt-in), never auto-sending without confirm.
  Reference: `notifications/NotificationBus.kt`, `agent/TaskOrchestrator.kt`.
- 9.10 Conversational HUD overlay. Goal: the floating dot becomes a real voice/text
  HUD. Method: upgrade `AgentOrbView`/`FloatingAgentDotService` into a compact HUD
  with live transcript, current-step status, and a stop button, sharing the premium
  theme from 8.4. Reference: `overlay/AgentOrbView.kt`,
  `overlay/FloatingAgentDotService.kt`, `ui/theme/*`.
- 9.11 Smarter element understanding (tree-miss upgrade). Goal: fewer wrong targets.
  Method: rank candidate elements (text, id, editable, clickable, position/bounds)
  into a compact ranked list for the model, and cache the settled tree; builds on
  6.15 + the real vision/OCR from 7.2/7.3. Reference: `accessibility/UiNode.kt`,
  `perception/*`, `agent/HybridStepExecutor.kt`.
- 9.12 Proactive obstacle self-healing. Goal: recover from dialogs/ads/permission
  walls mid-task without asking. Method: detect common blockers on the live tree
  (rate-limit 6.17) and dismiss/handle them with a bounded, logged recovery, then
  resume - never invent success. Reference: `agent/FailureClassifier.kt`,
  `agent/TaskOrchestrator.kt`, `planning/PostconditionVerifier.kt`.
- 9.13 Language expansion (Hindi-first) end-to-end. Goal: understand and reply in
  Hindi/Roman-Hindi for commands, status, TTS, and voice. Method: extend 7.17 to the
  new voice loop and memory commands; keep it bounded (no full i18n framework).
  Reference: `voice/LanguageDetector.kt`, `voice/SpeechOutput.kt`, `ui/*`,
  `planning/PlannerPromptBuilder.kt`.
- 9.14 Battery / thermal / cost guard for always-on mode. Goal: proactive features
  never drain the phone. Method: gate awareness/triggers on Doze, battery level, and
  charging state; degrade gracefully and tell the user when a feature is paused.
  Reference: `automation/AutomationGate.kt`, `perception/ScreenAwareness.kt`,
  `OmniAgentApplication.kt`.

Phase 9 defaults (locked): all of 9.1, 9.2 (location), 9.7, 9.8, 9.9 are OPT-IN and
OFF by default; nothing auto-sends a message or auto-spends money; NO new model is
introduced; the single E2B engine is reused for every AI step.

Phase 10 - RELIABLE MULTI-STEP EXECUTION (items 10.1 - 10.11, acceptance spec).
Goal: one natural-language instruction (English / Hindi / Roman-Hindi) completes the
WHOLE task across screens and apps, verified by fresh evidence - not just a plan.
The benchmark task is the YouTube workflow: "Open YouTube, search for lo-fi music,
select the first relevant video, verify it is playing, then return home." This phase
is reliability-first; it does NOT add features, a bigger model, or a new backend.
A literal, infallible fictional Jarvis is NOT the acceptance criterion: deliver the
best practical agent within Android's APIs, the device's memory/perf limits, the
available model, and user-consent rules. The agent must, at minimum:
(1) understand the intended final outcome; (2) break the command into the smallest
appropriate subgoal sequence; (3) observe the actual current device state;
(4) select and execute a valid action; (5) verify the resulting state with fresh
evidence; (6) recover intelligently when an action fails; (7) continue from the
current state WITHOUT repeating completed side effects; (8) report success only when
the requested outcome has sufficient evidence; (9) explain the actual blocker when
reliable completion is impossible. It must NOT stop after opening an app, get stuck
on a search field, scroll forever, or show an error unrelated to the real failure.

- 10.1 SEPARATE DISPATCH FROM SUCCESS (loop integrity). Goal: a dispatched action
  is never treated as task success. Method: audit the live path end to end
  (`TaskOrchestrator` -> `DeviceAutomation` -> `HybridStepExecutor` -> observation ->
  `PostconditionVerifier`); require a fresh post-action observation + an
  action-specific postcondition before advancing task state; `VerificationMethod.NONE`
  must NOT prove completion; keep `SCREEN_CHANGED` only as a meaningful intermediate
  signal, never a universal substitute. A model `report_status`, an animation, a
  keyboard opening, or an unrelated popup must never mark the task complete.
  Reference: `agent/TaskOrchestrator.kt`, `agent/DeviceAutomation.kt`,
  `agent/HybridStepExecutor.kt`, `planning/PostconditionVerifier.kt`.
- 10.2 GOAL-SPECIFIC FINAL VERIFICATION. Goal: the final gate evaluates the user's
  REQUESTED OUTCOME, not the last planned action. Method: add per-goal verifiers -
  OpenApp -> correct foreground package; search -> query submitted + a result
  surface (or explicit no-results); play video -> player/playback evidence, not a
  tapped row; Wi-Fi -> the actual toggle state; home -> launcher foreground; read
  page -> meaningful content obtained; send message -> approval + verified send
  state where reliable. Extend the existing verification types only as required;
  completed subgoals stay verified across replans; a replan never silently replays a
  risky/irreversible action. When unverifiable, report failure/uncertainty, not
  success. Reference: `planning/PostconditionVerifier.kt`,
  `agent/ForegroundPackageVerifier.kt`, `agent/TaskOrchestrator.kt`.
- 10.3 YOUTUBE SEARCH-TO-PLAY PATH (primary benchmark, no keyword patch). Goal: the
  full flow actually completes. Method: (A) app launch - resolve the intended
  installed app with overlapping names/aliases, verify the foreground package,
  bounded cold-start waiting; (B) search interface - an arbitrary editable field is
  NOT a search field; improve `hasSearchInputField()` /
  `AgentAccessibilityService.openSearchInterface()` using view id, search text/
  content-desc, widget class/hint, focus/enabled, package and context; a comment/
  message/login/password field never qualifies; do not type into the first editable
  element when the intended field is unresolved; (C) submit - verify the query
  entered the intended field and that submission happened (IME search / Enter /
  button), never assume typing auto-submitted; (D) results - build on `RESULTS_PRESENT`
  (never a bare screen change) and distinguish available / explicit no-results /
  loading / network failure / UI-open-unsubmitted / query-entered-unsubmitted /
  ambiguous; (E) select - use actual visible candidates + context, semantic (not
  crude whole-string) matching, bounded scrolling with re-observe, honest stop when
  nothing is relevant; (F) playback - verify a real player/playback state (controls,
  selected title, semantics, bounded wait + fresh observation) across layouts/full-
  screen/transient controls; if playback cannot be established, report UNVERIFIED.
  Reference: `agent/TaskOrchestrator.kt`, `accessibility/AgentAccessibilityService.kt`,
  `agent/DeviceAutomation.kt`, `planning/PostconditionVerifier.kt`,
  `agent/ForegroundPackageVerifier.kt`.
- 10.4 TRUSTWORTHY OBSERVATIONS. Goal: the tree is trustworthy evidence. Method:
  audit `dumpHierarchy()` traversal/filtering; record active package/window, visible
  text + content-desc, view id + widget class, clickable/editable/scrollable/focused/
  enabled/selected, bounds + hierarchy + timestamp, and a truncation/partial flag;
  never treat invisible/off-screen/zero-area/disabled controls as valid tap targets
  (keep disabled state for reasoning, never auto-activate); handle clickable child vs
  ancestor, rank ambiguous candidates or report ambiguity; inspect multi-window/dialog
  surfaces; never reuse stale nodes/coordinates after a transition. Reference:
  `accessibility/UiNode.kt`, `accessibility/AgentAccessibilityService.kt`,
  `agent/DeviceAutomation.kt`.
- 10.5 EVENT-DRIVEN SETTLING + PERCEPTION LADDER. Goal: act on settled, sufficient
  evidence. Method: reuse the a11y event stream + bounded settle (wait for a relevant
  event or bounded condition, re-observe, compare semantic state, brief extra wait on
  real loading evidence, then a bounded action-specific timeout - no infinite polling,
  no second uncontrolled loop); choose screenshot/vision by meaningful coverage
  signals (target-match failure, useful labels, truncation, missing interactive
  elements, expected-vs-observed mismatch) NOT node count; fallback order =
  tree semantics -> fresh semantic search -> OCR with real bounding boxes ->
  multimodal model (when the runtime supports images) -> alternative validated app
  action/deep link -> bounded recovery or honest blocker. Reference:
  `perception/ScreenAwareness.kt`, `agent/DeviceAutomation.kt`,
  `perception/VisionPerception.kt`, `agent/TaskOrchestrator.kt`.
- 10.6 OCR GROUNDING JUSTIFIES A TAP. Goal: OCR coordinates are real, mapped, and
  confidence-gated. Method: keep `OcrTextBox`/`readTextBoxes()`; ground from actual
  recognised bounding boxes (never text-line-index, screen-centre, or arbitrary
  coordinates); map screenshot coords <-> Android screen coords accounting for
  rotation/display size/insets; on low confidence, overlapping targets, or missing
  box return "target unresolved" and DO NOT tap; if a screenshot is sent but the
  runtime falls back to text-only, record that truthfully and never present text-only
  as visual inference. Audit that the active model artifact/runtime can accept images.
  Reference: `perception/VisionPerception.kt`, `model/LiteRtLmRuntime.kt`,
  `model/GemmaInferenceEngine.kt`.
- 10.7 RELIABLE TARGET RESOLUTION + TEXT INPUT. Goal: no unsafe target fallbacks.
  Method: review `findTargetNodeInfo()`/`findTargetEditableNode()`; remove the
  "fall back to the first editable element" behaviour, disabled/invisible matching,
  first-fuzzy-match-when-ambiguous, and "API dispatch == correct element reacted";
  introduce a small testable resolution result (unique / ambiguous / none with
  reason + confidence); update `AgentAction`/`ToolRegistry`/`TaskPlanParser`/
  validation/risk/executor/tests together only when a contract truly changes.
  Reference: `agent/DeviceAutomation.kt`, `agent/ToolRegistry.kt`,
  `planning/TaskPlanParser.kt`, `agent/AgentAction.kt`.
- 10.8 FAILURE DIAGNOSIS + SELF-HEALING (audit first, no duplicates). Goal: recover
  by the REAL failure type. Method: audit actual call paths of `FailureClassifier`,
  `StuckLoopDetector`, `ProgressBudget`, `ObstacleDetector`, `attemptReplan(...)`,
  reflection/exploration, checkpoints, screen events; classify before recovering
  (a11y unavailable / target missing / incomplete tree / ambiguous / wrong app /
  wrong field / text not persisted / not submitted / loading / explicit no-results /
  network / permission-or-login / model unavailable-or-invalid / timeout / repeated
  loop / postcondition failed); pick recovery by type (a missing target is NOT
  automatically "scroll"; a failed tap is NOT automatically "Back"; loading is NOT
  always "replan"); bounded strategies only (wait, re-observe+re-resolve, retry only
  on transient evidence, alternate interaction, alternate verified route, safe
  backtrack, replan with current screen + completed subgoals, ask the user); detect
  short AND long recurring loops; recovery budgets terminate. One source of truth for
  task progress; no duplicate actions from async state/resume/UI events/overlapping
  jobs; cancellation + pause/resume preserve intended state. Reference:
  `agent/FailureClassifier.kt`, `agent/StuckLoopDetector.kt`, `agent/ProgressBudget.kt`,
  `agent/ObstacleDetector.kt`, `agent/TaskOrchestrator.kt`.
- 10.9 ROBUST NL PLANNING + FALLBACK INTEGRITY. Goal: better decisions, not bigger
  models. Method: reuse the local planner + schema + parser + validation +
  step-decision pipeline; improve only where real failures show a need; support
  multiple ordered subgoals, dependent actions, conditional next steps, semantic
  result selection, evidence-waiting, cross-screen extraction, completion-vs-partial
  distinction, and follow-ups referencing the prior task/result; structured bounded
  outputs, all params validated, no arbitrary code/shell/unrestricted calls; keep the
  local model for ambiguity and deterministic actions for clear steps. Preserve +
  regression-test the explicit BASIC fallback gate: a malformed model response must
  NOT silently downgrade to heuristics unless BASIC was explicitly enabled; make the
  active planning source visible in logs and UI. Do NOT increase model size or
  context indiscriminately - the goal is better decision quality and grounding, not
  more tokens. Reference: `planning/AgentPlanner.kt`,
  `planning/TaskPlanParser.kt`, `planning/PlannerPromptBuilder.kt`,
  `ui/AgentDashboardScreen.kt`.
- 10.10 ADVERSARIAL REGRESSION SUITE (the task-3.1/4.G scenarios). Goal: failures
  become tests. Method: add deterministic tests (real decision/verification logic,
  not hardcoded successes) for: search icon with no field yet; comment input present;
  multiple editable fields; unresolvable field; keyboard opens but no submit; query
  entered but no results; changed screen with no results evidence; explicit no-results;
  result titles differing from the query; network-delayed results; popup/ad/permission
  prompt; tap dispatches but opens the wrong screen; result opens but playback not
  established; already on the intended screen; user changes the screen mid-run. Plus
  the scripted end-to-end: launch YouTube -> search -> submit -> results state ->
  select -> player state -> playback -> home. The fake device must NOT return success
  regardless of action. Reference: `app/src/test/java/com/example/**` (reuse
  `TestFakes.kt`), `app/src/androidTest/**`.
- 10.11 PERMISSIONS / SECURITY / USER CONTROL (keep intact). Goal: autonomy never
  bypasses the user. Method: keep all execution inside the validated action/tool
  architecture; require explicit confirmation for messages/calls/publish/delete/
  purchase/payment and other sensitive/irreversible actions; never bypass auth/
  biometrics/OTP/CAPTCHA/consent - ask the user; Shizuku stays off unless explicitly
  enabled AND the service is present; cloud planning stays OFF by default and reports
  network failures honestly without shipping private screen data; validate untrusted
  app/web text before it enters prompts (data, never instructions); no hidden
  surveillance/background access/data collection. Reference:
  `agent/AgentAction.kt` (`RiskEvaluator`), `settings/CapabilitySettings.kt`,
  `privileged/PrivilegedActions.kt`, `planning/CloudPlannerProvider.kt`,
  `planning/PlannerPromptBuilder.kt`.

Phase 10 note: prefer a small number of dependable, verifiable capabilities over many
impressive-sounding ones. Do NOT replace the app, rewrite `TaskOrchestrator`, add a
bigger/second model, change the GPU/CPU backend, or duplicate engines/orchestrators.
Every item is regression-tested against the existing suite.
- Follow one real task through the COMPLETE live path and audit at least:
  `TaskOrchestrator.kt`, `DeviceAutomation.kt`, `HybridStepExecutor.kt`,
  `accessibility/AgentAccessibilityService.kt`, `accessibility/UiNode.kt`,
  `ForegroundPackageVerifier.kt`, `planning/PostconditionVerifier.kt`,
  `planning/TaskPlanParser.kt`, `agent/ToolRegistry.kt`, `planning/AgentPlanner.kt`,
  `model/GemmaInferenceEngine.kt`, `model/LiteRtEngine.kt`, `model/LiteRtLmRuntime.kt`,
  `perception/VisionPerception.kt`, plus the screen-observation/event/recovery/
  model-loading/logging/test classes. Check which code is ACTUALLY called, what
  counts as success at each stage, and how failures propagate.
- Tests: reuse the current fakes + orchestration test infrastructure (`TestFakes.kt`);
  keep tests deterministic; validate NEGATIVE cases and real outcomes, not merely
  that a helper returned a value; do not inflate test count or weaken assertions to
  make CI green. Where Android instrumentation tests can safely exercise real service
  wiring, include them (do not claim fake observations prove the real YouTube
  workflow works on a physical phone).

## 4B. CHANGE MANAGEMENT, VALIDATION & DELIVERY (per the acceptance spec)

Apply these rules to the WHOLE pass (Phases 7-10), not just Phase 10:

- Branch + review discipline: work on a dedicated branch off the latest `main`
  (e.g. `openhands/jarvis-autonomy-reliability`); do NOT commit to `main`, merge your
  own PR, rewrite history, or discard user changes; open a PR for review.
- Inspect-before-change: read the actual latest `main`, current commit, working tree,
  `AGENTS.md`, `.agents_tmp/PLAN.md`, Gradle config, manifest, tests, and the live
  wiring of every relevant feature; review the latest merged changes INCLUDING
  PR #25; treat this plan as a hypothesis, not truth. Produce a concise GAP MATRIX
  (working / partially implemented / disconnected / broken / infeasible) in the PR
  description, then implement - do not finish with an audit-only response.
- Preserve working functionality: keep Jarvis branding/UI, the model download + init
  flow (error reporting + retry), the on-device Gemma E2B/LiteRT architecture, the
  a11y service + native actions + planner/parser/tool-validation pipeline, history/
  persistence/skills/memory/voice/settings/notification/automation capabilities, the
  permission gates/risk checks/confirmations/opt-in defaults, and all Phase 0-9
  behaviour. Prefer targeted improvements; no unnecessary deps/abstractions/redesigns.
- Capability audit (spec section 9): classify each Phase 0-9 capability as
  working / under-tested / partial / broken / infeasible, then fix genuine gaps.
  Audit each capability from the UI/trigger through the real implementation to the
  final observable result - do NOT recreate existing features:
  * 9.1 Voice/hands-free: speech recognition, mic-permission handling, language
    detection, Hindi/Roman-Hindi, TTS, hands-free re-arming, pause/stop, lifecycle,
    error recovery, and - critically - the mic must NOT stay active once the feature
    is disabled, permission revoked, or the agent stops.
  * 9.2 Conversational memory: persistence of preferences/recent context/outcomes/
    cross-turn references; memory must NOT override an explicit current instruction;
    handle stale/conflicting memories; provide inspect/correct/delete of saved info.
  * 9.3 Skills/routines: view/save/edit/delete/replay; replays must re-resolve
    semantic targets on the CURRENT screen (never stale coordinates); risky actions
    still require confirmation even for a previously-approved skill.
  * 9.4 Cross-app workflows: validated variables, bounded state, no reuse of an old
    value after extraction fails, source/freshness tracking, no leak of sensitive
    content.
  * 9.5 Scheduled/event automation: WorkManager scheduling, trigger matching,
    persistence, duplicate prevention, cancellation, permission state, battery
    constraints; OFF by default + explicit opt-in; no risky background action.
  * 9.6 Notifications/proactive awareness: listener + permission state; distinguish
    confirmed data from inference; no sensitive notification content in logs; aware-
    ness bounded/explainable/disableable; no uncontrolled proactive-action loop.
  * 9.7 HUD/settings/diagnostics: the UI must show active model/planning source,
    current task+subgoal, last action + verification result, recovery attempts +
    reason, current blocker, cancellation/pause state, and model-load/permission
    failures; it must distinguish "action dispatched" / "step verified" / "goal
    completed" / "goal unverified" and never hide failure behind a generic label.
  Features needing special device permissions must explain the requirement instead
  of silently pretending to work.
- Reliability/perf (spec section 11): profile the real lifecycle (no guesses); keep
  init, image inference, observation and long tasks bounded + cancellable; prevent
  repeated model init, duplicate concurrent tasks, leaks, unrecycled Android objects
  and UI-thread blocking. Test recovery when the model/app is interrupted by: low
  available RAM; model-load or inference error; app backgrounding; a screen
  transition mid-execution; the accessibility service disabled/disconnected; network
  disconnection; pause / cancellation / resume; and device resource constraints.
  Keep the device-fit checks and the E2B architecture; only implement optimisations
  supported by tests/measurements; battery/thermal behaviour must degrade gracefully
  with no hidden network activity and no endless expensive retries.
- Real-device validation + honest reporting: build/improve an in-app self-test and a
  concise device QA checklist (Android version/device info, model readiness + actual
  inference mode, a11y enabled, required permissions/opt-ins, the test command +
  observed start state, screen/action sequence, expected vs observed postconditions,
  exact failure stage + logs, and whether the run was simulated / emulator / physical).
  NEVER report a physical-device test as passed unless it actually ran on the device;
  if there is no phone access, say physical validation is pending and give a
  repeatable manual procedure. Prefer reproducible failures over vague intelligence
  claims.
- Mandatory validation commands (from the repo root, per `AGENTS.md`): run
  `./gradlew :app:testDebugUnitTest --no-daemon`,
  `./gradlew :app:assembleDebug --no-daemon`,
  `./gradlew :app:lintDebug --no-daemon` (if supported), and
  `./gradlew :app:compileDebugAndroidTestKotlin --no-daemon` (where supported).
  Diagnose + fix real failures and re-run; never delete tests or suppress errors to
  force green. Inspect the final diff for unintended UI changes, duplicate impls,
  dead/disconnected code, unsafe fallbacks, accidental permission changes, secret/
  key exposure, unnecessary deps, regressions in download/loading, incorrect success
  states, and non-cancellable background work. Do not confuse a green APK build with
  real-device automation.
- Definition of done: audit done + real gaps addressed; high-priority search/target/
  verification bugs covered by regression tests; no false success from failed/
  ambiguous interactions; bounded state-aware recovery; existing features + safety
  gates intact; new tests + builds pass (or every remaining failure documented); every
  changed capability has a real test or an honest limitation note; no unverified
  device claims; a PR exists (not auto-merged). Final report must include: root causes
  found in the latest code, fixes/behaviour changes, files changed + why, exact test/
  build commands with pass/fail, what remains unimplemented/unverified on a physical
  phone, how to build/install + reproduce the E2E tests, and the PR link.

## 4A. FINAL BUG SWEEP - FIND AND FIX EVERY BUG (compulsory, end of the pass)

The user asked, explicitly, to find and fix ALL bugs/errors at the end. After
Phases 7, 8, 9 and 10 are implemented, run a full audit and fix every finding (do
not just report them):

- Build + tests: make `./gradlew :app:assembleDebug` and
  `./gradlew :app:testDebugUnitTest` pass with zero failures; fix compile errors,
  `androidTest` failures, and any Robolectric breakages caused by the new code.
- Static sweep: search the whole tree for leftover `Unavailable*` seams, `= null` /
  `return null` default backends that are still reachable in the live graph, unused
  classes (e.g. anything referenced only by itself), and dead branches; wire or
  delete each one so no advertised capability is inert.
- Dead-wiring sweep: confirm every public entry point is reachable - re-check
  BS1 - BS10 from section 2.3 AND every Phase 9 feature is actually called from the
  app graph (not just defined).
- Lint / warnings: resolve Android lint errors and Kotlin warnings that indicate
  real defects (unused, deprecation that breaks behaviour, resource mismatches,
  unresolved manifest references).
- Runtime-error sweep: no crash on: fresh install, denied permission, no network,
  model missing, model failing to load, empty/clipboard-empty, and rotate/kill.
- Regression: every honest-behaviour rule still holds - no fake success, no silent
  cloud egress, risky actions still confirm, and the model-not-loaded path still
  blocks (6.1) with the real reason shown (8.2). Also re-verify the phone-hang fix
  (8.9) holds under repeated open/close and memory pressure.

### 4A run log

#### 2026-10-11 - confirmed defect: permission-dialog misclassification (operator precedence)

- **Finding (confirmed defect).** `FailureClassifier.classify` tested
  `lowerScreen.contains("permission") || lowerScreen.contains("allow") && (contains("dialog") || contains("grant"))`.
  Kotlin's `&&` binds tighter than `||`, so this parsed as
  `contains("permission") || (contains("allow") && (contains("dialog") || contains("grant")))`.
  Any screen whose summary merely contained the bare word `permission` - ordinary
  Settings copy, Play Store permission descriptions, help articles - was misclassified
  as `PERMISSION_DIALOG`, whose recovery is `PRESS_BACK_TO_UNBLOCK`. In `recoverFromFailure`
  that fires a real Back navigation, so the recovery cycle could be triggered by
  text on the screen and abort the current task surface.
- **Fix.** Parenthesised the grant-side test so both a permission/allow signal AND a
  dialog/consent cue (`dialog`, `grant`, `while using`, `deny`) are required; a bare
  `permission`/`allow` token no longer hijacks recovery. A genuine system dialog is
  still detected.
- **Validation (unit/build only).** New regression test
  `Phase5AutonomyTest.classifierRequiresBothPermissionAndDialogSignals`; full
  `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug --no-daemon` run this
  session (JDK 21 Temurin, android-36.1). No emulator/real-device run available in
  the sandbox.
- **Not verified.** Real-device behaviour of the permission-dialog recovery path
  remains unverified (same limitation as the rest of the loop's device-side recovery).

#### 2026-10-11 - confirmed defect: generic Skip/Close misread as a full-screen ad

- **Finding (confirmed defect).** `ObstacleDetector.detect` used a bare
  `skip`/`close`/`x` label as the ad cue, and its dismiss matcher included a bare
  `x`. `isSafeDismiss("skip")` is true, so `TaskOrchestrator` (L472-489) actually
  **tapped** the control as if it dismissed an ad. Any ordinary screen with a
  "Skip" affordance - onboarding, a tutorial, "Skip for now" on a login sheet -
  was classified `Kind.AD` and auto-tapped mid-task. Separately, `firstPresent`
  returned the *candidate token* rather than the real on-screen label, so the
  orchestrator could tap text that was not on screen; the `x` candidate also
  substring-matched unrelated labels such as "Next".
- **Fix.** AD now requires a real ad cue (`advertisement`, `sponsored`, `skip ad`,
  `close ad`, `dismiss ad`); the loose dismiss matcher no longer includes a bare
  `x`. `firstPresent` returns the actual matching on-screen label.
- **Validation (unit/build only).** New regression test
  `Phase9UpgradeTest.genericSkipControlIsNotMisreadAsAnAd` (a bare "Skip" /
  "Skip for now" is NOT an ad; a real "Skip Ad" cue is still detected and the
  dismiss label is the real label). Full gate
  `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` -> BUILD
  SUCCESSFUL, 261 tests 0 failures 0 errors, `app-debug.apk` produced. No
  emulator/real-device run available in the sandbox.
- **Not verified.** Real-device ad/dialog handling remains unverified.

#### 2026-10-11 - confirmed defect: Settings > Permissions screen misread as a permission dialog

- **Finding (confirmed defect).** `ObstacleDetector.detect`'s permission branch required
  `containsAny("allow", "while using the app") && containsAny("permission", "access", "deny", "don't allow")`.
  An ordinary Android Settings > Apps > [app] > **Permissions** screen exposes a title
  containing `permission` and per-permission rows such as `Allow access to camera` /
  `Allow`. Both sub-conditions were satisfied, so `detect` returned `Kind.PERMISSION` with
  `dismissLabel` = the real `Allow` row. In `TaskOrchestrator` (L468-503) the blocker is
  then **tapped** (`isSafeDismiss("Allow")` is true), derailing the current task surface
  and potentially granting access the user never requested. Same false-positive class as
  the already-fixed bare-`Skip` ad cue in this file.
- **Root cause.** The rule keyed on loose keywords (`allow`/`permission`/`access`) that
  appear on non-dialog screens. A genuine runtime request always offers a *deny* affordance
  alongside the grant choice; an informational permissions list does not.
- **Fix.** `detect` now requires BOTH a deny choice (`deny`, `don't allow`, `do not allow`)
  AND a grant choice (`allow`, `while using`, `only this time`, `grant`). The Settings
  Permissions list (no deny control) no longer matches; a real dialog still does.
- **Validation (unit/build only).** New regression test
  `Phase9UpgradeTest.settingsPermissionsScreenIsNotMisreadAsAPermissionDialog` (Settings
  Permissions list + "Allow notifications" are not blockers; a real dialog is). Full gate
  `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` -> BUILD SUCCESSFUL,
  262 tests 0 failures 0 errors, `app-debug.apk` (115 MB) produced. No emulator/real-device
  run available in the sandbox.
- **Not verified.** Real-device behaviour of the permission-dialog blocker path remains
  unverified.

#### 2026-10-11 - confirmed defect: a detected permission dialog was auto-granted

- **Finding (confirmed defect, safety).** The previous fix correctly narrowed
  `ObstacleDetector.detect`'s permission branch to a deny-AND-grant affordance pair, but
  it still returned `dismissLabel = firstPresent(..., "while using the app", "allow", "ok")`.
  In `TaskOrchestrator` (L468-503) that label is a *grant* control, and
  `isSafeDismiss("While using the app")` is true (`DISMISS_WORDS` contains "allow" and
  "continue"; no contradiction with `DENY_WORDS`), so the orchestrator **tapped the grant
  button itself** for any real runtime permission request. The master prompt (3.5) forbids
  bypassing permission prompts; the task's own comment ("The caller still decides whether
  to tap the grant control") contradicts the code.
- **Root cause.** `ObstacleDetector`'s permission handling was lumped in with ads/consent
  banners, which are safe to auto-dismiss. A permission prompt is not: granting it is the
  user's decision, not a blocker to clear.
- **Fix.** `Kind.PERMISSION` is now returned with `dismissLabel = null` and
  `unresolvable = true`. The orchestrator's existing unresolvable branch stops the run
  with an honest message ("Blocked by PERMISSION ... I won't guess at dismissing it") and
  never dispatches a tap. Detection still requires the deny+grant pair, so a Settings
  Permissions list remains untouched.
- **Validation (unit only).** Updated `Phase9UpgradeTest.detectsAndSafelyDismissesCommonBlockers`
  (permission now asserts `unresolvable` and `dismissLabel == null`); new
  `OrchestratorLoopTest.permissionDialogIsNotAutoGranted` drives the live loop against a
  permission dialog and asserts `Failed` with no grant tap. See the run log entry below for
  the exact gate result (toolchain-dependent).
- **Not verified.** Real-device behaviour remains unverified.

# 5. TESTING AND VALIDATION

Success is defined per phase; each phase must keep the existing behaviour
honest (no step may be marked COMPLETED unless a fresh observation proves it).
Phases implemented: 0, 1, 2, 3, 4 (all items 4.1 - 4.17; 4.9 documented and
deferred), 5 (items 5.1 - 5.10), 6 (items 6.1 - 6.24), and 7 (items 7.1 - 7.17).
Phase 8 (8.1 - 8.10) is the rebrand + working-load + bug-sweep + UI phase and is
done next, together with any remaining Phase 7 seams.

Phase 0 (must pass first):
- A simulated "Open ChatGPT, say hi, then press Enter" task reaches
  `OrchestratorState.Completed`, with executed actions containing the launch, a
  text entry that targeted the composer, and a `Submit`.
- A WebView-style node not flagged `isEditable` but matching the input heuristic
  is treated as a valid type target; `Submit` with no visible Send control falls
  back to the key-event/IME path; `OpenUrl` drives a browser foreground change;
  `observeStable()` returns once the fingerprint stabilises.
- No regression in `FixesRegressionTest`, `SearchRecoveryTest`,
  `HybridStepExecutorTest`, `OrchestratorLoopTest`, `GemmaIntegrationTest`.

Phase 1 (action vocabulary):
- Every new action parses via `TaskPlanParser`, validates via `ToolRegistry`, is
  present in the prompt catalog, and has a deterministic or model decision test.
- `web_search`/`read_url` return a value usable by the planner without a network
  call in tests (fake `CloudflareGateway`), and the tool never bypasses risk
  checks. Copy/paste, long-press, drag, notifications and quick-settings each
  have an execution test through `FakeDeviceAutomation`.

Phase 2 (perception):
- `screenEvents` emits on window/content changes; a settle-wait test proves the
  loop waits for quiescence and remains bounded (never hangs).
- Vision/diff fallback is exercised only when the tree lacks the target.
- A config/constants test pins the raised `MAX_CONTEXT_TOKENS` (3072) and the
  enabled GPU backend flag, and confirms the pinned model URL/size/SHA-256 stay
  mutually consistent (model file unchanged).

Phase 3 (planning & memory):
- A failing step triggers a *new* sub-plan (assert a second planning call with
  failure context) rather than repeating the same action.
- Memory round-trips: a task-outcome/preference written at task end is injected
  into the next planning prompt (assert prompt content), with bounded size.

Phase 4 (Jarvis layer):
- 4.1/4.2/4.3: vision grounding is only attempted when the tree fails; OCR text
  enters the observation; vision-diff counts as verification only on a real
  change. All faked (no real camera/model) in unit tests.
- 4.4: notification listener surfaces a bounded list and reply goes through the
  RemoteInput path (fake service).
- 4.5: privileged actions are unreachable unless Shizuku is reported running.
- 4.6: the multimodal E2B path accepts an image input; text-only calls are
  unchanged; the pinned model URL/bytes/SHA-256 stay mutually consistent.
- 4.7/4.8: Gemini Nano and cloud fallback are never consulted when disabled, and
  never silently; when enabled, a repeated local failure is the only trigger.
- 4.10/4.11/4.12: voice in/out sit behind interfaces (fake recognizer/TTS) and are
  inert until the user opts in.
- 4.13/4.14: a saved skill re-runs; scheduled jobs are gated by the off switch.
- 4.15: RAG injects bounded retrieved context into the prompt.
- 4.16/4.17: self-critique runs only on risky steps; learn-from-demonstration
  records then replays through the normal verified executor.
- Proactive/scheduled/cloud/voice features are all OFF by default and require an
  explicit, visible opt-in.

Phase 5 (autonomy & self-healing):
- 5.2/5.7: `FailureClassifier` maps each scripted failure signal to the right type;
  the loop guard forces a different strategy (or a clean stop) and never spins —
  assert the run terminates within the bound.
- 5.3/5.5: after a typed failure the agent returns to the last good checkpoint and
  a *new* sub-plan is requested (assert a second planning call with failure
  context); abandoned steps become SKIPPED, not re-run.
- 5.4: reflection runs only on failure/stall, not on every step (assert no extra
  model call on a clean run).
- 5.6: exploration probes are bounded and stop once the target appears.
- 5.9: a recovery writes a lesson that appears in the next planning prompt.
- 5.10/5.8: a hard task degrades in order (re-plan -> explore -> escalate ->
  stop) and never fakes success; escalation surfaces a clear blocked message.
- End-to-end: a scripted "blocked then recovered" task reaches
  `OrchestratorState.Completed` via a re-plan/exploration path with no hardcoded
  app special-case, and an unrecoverable task ends in honest escalation rather
  than a loop or a false success.

Phase 6 (reasoning & reliability rescue):
- 6.1: with the model not ready, starting a task produces a clear "local AI not
  loaded" state and does NOT silently run keywords; with it ready, the run
  reports `usesModelPlanning = true`.
- 6.2: a command the planner cannot decompose (e.g. "Prateek ko WhatsApp pe
  message karo") never emits an unbounded `Scroll`; assert no `Scroll` action is
  produced as a generic fallback.
- 6.3/6.4: a Roman-Hindi messaging command yields real subgoals (launch -> open
  search -> type contact -> open chat -> type message -> submit), asserted on the
  parsed plan.
- 6.5/6.6: typing a query into a field and submitting verifies successfully
  (no spurious "request text was not verified"); a focused-then-typed path lands
  the text in the target node.
- 6.7: a missing target first triggers a model/web lookup, then at most ONE
  bounded probe - never unbounded scrolling.
- 6.8: only one `LiteRtEngine`/`GemmaInferenceEngine` is initialized for the app.
- 6.10: the Web Skills tab no longer exists; `web_search`/`read_url` still work
  as in-loop actions.
- 6.11: the loop re-observes on meaningful UI events (bounded), not only on a
  fixed delay.
- 6.12: the run records, per step, the source (GEMMA vs BASIC), the action, and
  the reason, visible in the UI.
- 6.13: with the model ready, each step is decided from the CURRENT observation
  (assert the step-decision prompt contains the fresh screen and last result).
- 6.14/6.15: the observation handed to the model is bounded structured JSON
  listing text/class/clickable/editable/bounds/desc, and icon-only buttons carry
  a usable description; the raw native dump is not used.
- 6.16: a failed (state, action) pair is not offered again unless the screen
  changed; injected history stays within its size cap.
- 6.17: each `FailureType` maps to the intended recovery (dialogs, empty field,
  slow load, wrong package, auth wall) in unit tests.
- 6.18/6.19: trailing commas, single quotes, fences, and string params
  ("3","true", aliases) parse successfully; a near-miss action validates instead
  of being rejected.
- 6.20/6.21: each action type has a deterministic pre/post check; taps are
  reacquired from a fresh tree and stale/out-of-window nodes are ignored.
- 6.22: a generic messaging recipe (with slots) plans a full contact-message flow
  without any app-hardcoded branch.
- 6.23: repeated low model quality surfaces an honest notice and never a fake
  success.
- 6.24: with a slow-loading screen, the loop waits for quiescence (bounded) and
  does not act mid-animation.

Phase 7 (completion & hardening):
- 7.1: downloading the pinned model passes size + SHA-256 verification (or fails
  with a clear, actionable error); a wrong hash does not silently loop.
- 7.2/7.3/7.4: with a tree-miss on a canvas/WebView screen, grounding + OCR + a
  screenshot fed to the multimodal model locate the target; the no-op grounders
  are only used when no backend is configured.
- 7.5: with voice opt-in + mic permission, a spoken command is recognized and
  routed into `startTask`; with the opt-in off, no audio is captured.
- 7.6: with the cloud fallback disabled (default) no network egress occurs; when
  enabled + configured it returns a validated plan through the strict parser.
- 7.9: `isFitForDevice()` reports honestly for the 8 GB device and the Setup UI
  shows the recommendation.
- 7.8: an enabled automation actually fires via WorkManager; the default (off)
  schedules nothing.
- 7.10: a successful observed run can be saved and replayed as a skill.
- 7.15: every new capability is reachable and OFF by default from one Settings
  surface, and its state is visible.
- 7.16: the in-app self-test reports pass/fail per capability on the device, and
  the `androidTest` suite runs green.
- 7.17: a Hindi/Roman-Hindi command produces a Hindi-appropriate status/TTS
  response end-to-end.
- Regression: all existing tests stay green; no seam regresses to a fake success.

Phase 8 (Jarvis rebrand, working load, premium UI):
- 8.1: after a verified download on the Motorola Edge 70 Fusion, `EngineState`
  reaches `Ready` and a command is planned by the real model (Live Monitor shows
  "Jarvis AI", not basic mode). Specifically: an EMPTY smoke-test answer must NOT
  flip the engine to `Failed` (regression test); only a thrown exception does.
- 8.2: when the model genuinely cannot load, the screen shows the real reason
  (exception text) with a Retry button - never a bare "not loaded".
- 8.3: every user-visible string reads "Jarvis"; no "OmniAgent" text remains on
  screen, in notifications, or on the launcher.
- 8.4/8.5: the app opens to the new premium UI with the new Jarvis icon/brand
  mark; no default/generic icon remains.
- 8.6: while a task runs, the UI shows the current step ("Opening app", "Typing",
  "Pressing Enter", "Verifying") and a clear final success/failure summary.
- 8.7 (bug sweep): for every BS item in section 2.3 there is a test or a device
  check proving it is now live - e.g. `EngineLoadStatusCard` is reachable in
  `SetupScreen` (BS1), Shizuku/wake-word are either wired or no longer advertised
  (BS2/BS3), cloud fallback can be configured end to end (BS4), one
  `MAX_CONTEXT_TOKENS` (BS5), no user-visible "OmniAgent" remains (BS6), the new
  icon ships (BS7), no misleading "Basic mode" label (BS8), and no reachable
  "Unavailable*" default remains (BS10). No dead code left advertising a capability.
- 8.9 (phone hang): two concurrent `initialize()` calls result in exactly ONE live
  runtime (assert the second does not create a second engine and that any prior
  runtime is closed); a trim/low-memory event does NOT repeatedly shut down and
  re-load the engine (assert no init spin); the GPU backend is opt-in and the
  default is CPU-first; and the smoke probe does not re-run on every re-open.
  Device check: the phone stays smooth while the model is loaded and the app is
  reopened.
- 8.10 (revert): the loader is back to the pre-GPU/CPU-change shape - a single
  CPU-first runtime factory, no GPU-primary + CPU two-attempt scheme, `USE_GPU_BACKEND`
  false/removed, and the prior context/thread defaults restored; the honest
  load-error surfacing (8.2) still works. Assert the app builds and loads on CPU.

STATUS (Phase 8.7, done): BS1 `EngineLoadStatusCard(app)` is called in
`SetupScreen`; BS2 `privilegedActions` is surfaced in the Settings self-test and
refuses by default; BS3 `OptInWakeWordDetector` fronts the hands-free flow,
gated by opt-in + mic permission; BS4 `SettingsScreen` writes endpoint/key/model
via `HttpCloudPlannerProvider.saveConfiguration`, with one shared provider on the
application; BS5 single `MAX_CONTEXT_TOKENS`; BS6 no visible "OmniAgent"; BS7
`arc reactor` adaptive icon plus matching legacy PNG mipmaps for API 24/25; BS8
`canStartTask(...)` gates the Run button and the banner states "Model required •
tasks are blocked"; BS9 `reasonAndPlan` documented as diagnostic-only; BS10 the
`Unavailable*`/`Disabled*` seams are inert and a test asserts it. Regression
coverage: `Phase8BugSweepTest` (BS4/BS8/BS10/BS2). Suite: 190 tests, 0 failures.

STATUS (Phase 8.8, done): the VERIFIED C1 root cause is fixed. The model already
loaded; the smoke test failed on a tiny 8-token probe that can legitimately return
an empty string, and a single blank answer closed a working runtime. Now:
- `LiteRtLmRuntime.smokeTest()` delegates to the shared, testable
  `runTolerantSmokeProbe(...)`: up to 2 bounded attempts with a growing token
  budget; a non-blank answer OR an empty-but-no-exception answer counts as usable;
  only a generation that THROWS on every attempt is a real failure. The engine is
  never closed on an inconclusive probe.
- `OmniAgentApplication.initializeEngineIfModelReady()` additionally keeps the
  engine if it still reports ready after an inconclusive probe.
- `LlmRuntime.smokeTest` KDoc now documents the "empty is usable" contract.
Regression coverage added to `Phase8ModelLoadTest` (empty answer = success, retry
on throw, transient-throw-then-empty = success). 4.17 learn-from-demonstration
was implemented in the orchestrator but had no reachable UI; it is now wired to a
"Watch me / Save skill" card in the dashboard, with an end-to-end regression test
(`OrchestratorLoopTest.watchingARunSavesAReusableSkill`). Same "written but not
reachable" defect class as the BS sweep. Suite: 194 tests, 0 failures.

STATUS (Phase 8.9 + 8.10, done): the reported phone hang (C5) is fixed and the
GPU/CPU loader changes are reverted to the pre-change shape.
- 8.9(a) single-flight init: `GemmaInferenceEngine.initialize()` re-checks the Ready
  state INSIDE `mutex.withLock` and closes any previous runtime before assigning a
  new one, so two concurrent initializers (MainActivity `LaunchedEffect` + a Retry
  tap) can never leave two ~2.6 GB runtimes alive (the leak that OOM'd the device).
- 8.9(b) no load/unload thrash: `MemoryTrimPolicy.shouldReleaseEngine(level)` gates
  release to `TRIM_MEMORY_COMPLETE` only; `onLowMemory()` no longer shuts the engine
  down at all (it is advisory). A release bumps `trimGeneration` and the next
  explicit use reloads - the app never re-loads on its own and cannot spin.
- 8.9(c) CPU-first: `USE_GPU_BACKEND = false`; GPU is strictly opt-in, so a failed
  GPU delegate cannot double the init work.
- 8.9(d) probe once: `GemmaInferenceEngine.ensureReady()` loads once, runs the smoke
  probe once, and remembers it; re-open/re-transition is a cheap no-op.
- 8.10 revert: the GPU-primary + CPU two-attempt `cpuRuntimeFactory` scheme and
  `MAX_INIT_ATTEMPTS` are removed; a single CPU-first `runtimeFactory` is the one
  load path. The honest error surfacing (8.2) is kept (`reportUnavailable`).
Regression coverage added to `Phase8HangFixTest` (concurrent init -> one runtime,
repeated init no-op, shutdown closes + reloads, probe once, re-probe after trim,
trim policy) and `Phase8ModelLoadTest` updated to the CPU-first shape.
Suite: 229 tests, 0 failures; lint clean; assembleDebug green.

Phase 9 (fully-automated Jarvis capability upgrades):
- 9.1: proactive suggestions fire ONLY when the awareness mode is opted in; with it
  off, no awareness loop runs and no screen data is read (assert no capture).
- 9.2: each new trigger (time/interval, battery, charging, network, app-foreground,
  notification, location) fires its automation only when its gate passes and the
  master switch is on; with the master off nothing is scheduled.
- 9.3: a follow-up command ("and send it", "same for Ravi") resolves against the
  previous command/target and reaches completion; a follow-up with no context fails
  honestly (asks), not by guessing.
- 9.4: a value read in one app (price/text/result) is usable by a later step in
  another app (assert the copied variable reaches the paste/type step).
- 9.5: "remember that ..." persists and appears in the next planning prompt;
  "forget ..." removes it; "what do you know about X" answers from the store only.
- 9.6: a saved skill is listed, can be renamed/replayed/deleted, and replay goes
  through the normal verified executor (no bypass of verification).
- 9.7: opt-in + mic permission -> wake word -> recognised text -> confirm -> run ->
  spoken result; the stop phrase halts it; with opt-in off no audio is captured.
- 9.8: the scheduled briefing fires at the set time, composes a bounded summary,
  and speaks it; off by default.
- 9.9: notification triage summarises on-device and a reply never auto-sends
  without confirmation.
- 9.10: the overlay HUD shows live transcript + current step + a stop control and
  shares the premium theme.
- 9.11: on a tree-miss screen, ranked candidate elements (or the vision/OCR path)
  locate the intended target; a genuinely absent target is refused, not blind-tapped.
- 9.12: a scripted dialog/ad/permission wall is detected and dismissed with a
  bounded recovery, then the task resumes and is verified - never a fake success.
- 9.13: Hindi/Roman-Hindi works for commands, status, TTS and the new voice loop.
- 9.14: with the battery low / Doze / not charging, always-on features pause and
  say so; they resume when conditions allow.
- All Phase 9 always-on/privacy features are OFF by default and require an
  explicit, visible opt-in (assert the default state for each).

Phase 10 (reliable multi-step execution, acceptance spec):
- 10.1: a dispatched-but-unverified action does NOT advance the task; a `NONE`
  verification never marks completion; a bare screen change alone never satisfies a
  step; a model `report_status` cannot complete the task.
- 10.2: each goal type has its own verifier - OpenApp (foreground package), search
  (submitted + result surface / explicit no-results), play (player/playback evidence,
  not a tapped row), Wi-Fi (real toggle state), home (launcher foreground), read
  (content obtained), send (approval + verified send state). Unverifiable -> honest
  failure, not success.
- 10.3: the full YouTube path (launch -> search -> submit -> results -> select ->
  playback -> home) completes on scripted observations; playback-unverifiable is
  reported UNVERIFIED, not success.
- 10.4: invisible/off-screen/zero-area/disabled nodes are never valid tap targets;
  disabled state is preserved for reasoning but not auto-activated; ambiguous
  candidates are reported, not arbitrarily chosen; stale nodes are not reused.
- 10.5: settling waits on events/bounded conditions (no infinite poll, no second
  loop); vision is selected by coverage signals, not node count; the fallback ladder
  is exercised in order.
- 10.6: OCR taps use real recognised bounding boxes (no line-index/centre/arbitrary
  coordinates); low-confidence/overlap/missing-box returns "unresolved" and does NOT
  tap; a text-only fallback when an image was sent is recorded truthfully.
- 10.7: no fallback to the first editable element; ambiguous/disabled/invisible
  targets are refused; resolution returns unique/ambiguous/none with a reason.
- 10.8: recovery is chosen by failure type (missing target != scroll; failed tap !=
  Back; loading != replan); short and long loops are detected; budgets terminate; one
  source of truth prevents duplicate actions; cancel/pause/resume keep state.
- 10.9: multi-subgoal, dependent, conditional, cross-screen-extraction and follow-up
  commands plan correctly; malformed output does NOT downgrade to heuristics unless
  BASIC was explicitly enabled; the active planning source is visible.
- 10.10: the full adversarial scenario list (search icon/no field, comment input,
  multiple fields, unresolvable field, no-submit, no-results, changed-screen-no-
  evidence, titles differing from the query, delayed results, popup/ad/permission,
  wrong-screen tap, playback-not-established, already-on-screen, mid-run user change)
  is covered by tests of real decision/verification logic; the fake device never
  returns unconditional success.
- 10.11: risky/irreversible actions still confirm; auth/OTP/CAPTCHA/consent are never
  bypassed; Shizuku stays off unless enabled + present; cloud planning stays off by
  default and reports failures honestly; untrusted text never overrides policy.

Spec section 12 mandatory test scenarios (at minimum - deterministic, negative-case
assertions, not just "a helper returned a value"):
1. Correct app launch AND wrong-package rejection.
2. Search icon versus search input.
3. Search field versus comment/message field.
4. Text entered into the wrong field must NOT satisfy the intended step.
5. Text not persisted, or query not submitted.
6. Search results missing / loading / explicitly empty.
7. A changed screen without relevant evidence.
8. Ambiguous target and disabled target.
9. OCR target bounding-box grounding and absent-label rejection.
10. Result tap with no valid detail/player transition.
11. Playback not established.
12. Wrong app opened, or an unexpected popup.
13. Recovery chooses a different valid path after an actual failure.
14. No endless scrolling / repeated same-state actions.
15. Replanning preserves completed work and never replays a risky side effect.
16. Invalid JSON, invalid parameters, unavailable model, explicit fallback gating.
17. Pause/resume/cancellation and task-budget exhaustion.
18. Hindi and Roman-Hindi commands.
19. Skills, memory and cross-app data do not use stale values.
20. All risky actions retain their confirmation gates.

Final bug sweep (section 4A):
- `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug` are green; the whole
  app graph has no unreachable/dead capability; lint errors are resolved; the
  no-crash edge cases pass; and every honest-behaviour rule (no fake success, no
  silent egress, risky actions confirm, model-not-loaded blocks with the reason) is
  re-asserted.

Global:
- `./gradlew :app:testDebugUnitTest` passes (Robolectric, sdk 34, JDK 21,
  Android platform 36.1; `--no-daemon` in a sandbox).
- `./gradlew :app:assembleDebug` produces a debug APK.
- Manual device check on the Motorola Edge 70 Fusion: "Open ChatGPT, say hi,
  then press Enter" opens the app, focuses the composer, types "hi", and submits
  it; "open google.com in the browser" opens the URL; a multi-app task (e.g.
  open an app, search, read a page, return home) completes with each step proven
  by a fresh, settled observation.
- Manual device check (Phase 8): the app installs as "Jarvis" with the new icon,
  the model loads to Ready after download (or shows the real failure + Retry), the
  premium UI shows live step progress during a run, and the Setup screen actually
  displays the engine load status card (BS1) instead of nothing.
- Manual device check (Phase 9): with voice opt-in, saying "Jarvis" + a command
  runs hands-free and speaks the result; a follow-up ("and send it") resolves; a
  "remember that ..." fact is used in a later task; a scheduled briefing fires; and
  every always-on feature stays OFF until explicitly enabled and pauses on low
  battery.
- Manual device check (Phase 10, the benchmark): on the Motorola Edge 70 Fusion,
  "Open YouTube, search for lo-fi music, select the first relevant video, verify it
  is playing, then return home" completes with a verified playback state and a
  launcher foreground - and when playback cannot be established the app says
  UNVERIFIED, never a false success. If no phone is available, this remains PENDING
  and the repeatable manual QA checklist (section 4B) is the deliverable instead.

Real-device QA checklist + honest reporting (section 4B): the in-app self-test and
the manual checklist record Android/device info, model readiness + actual inference
mode, accessibility state, required permissions/opt-ins, the test command + start
state, the screen/action sequence, expected vs observed postconditions, the exact
failure stage + logs, and whether the run was simulated / emulator / physical. No
physical-device pass is claimed unless it actually ran on the device.


## Run 2026-10-12 (session 34) - set_torch capability
- Milestone: new AgentAction.SetTorch + tool set_torch (turn the device
  flashlight/torch on or off). Torch is a camera-hardware setting
  (CameraManager.setTorchMode), not an accessibility-tree control, so it is
  resolved before the a11y gate like the audio/ringer primitives.
- TorchController seam + AndroidTorchController: registers
  CameraManager.TorchCallback and reads the state back, so a request the
  hardware ignored is TORCH_UNCHANGED (failure), never a fake success.
- Wired through all action-vocabulary seams (AgentAction, ToolRegistry,
  TaskPlanParser, HybridStepExecutor, PlannerPromptBuilder, PlanVariables,
  AndroidDeviceAutomation, TaskPlanSerializer, TaskOrchestrator self-evidencing).
- Fix during validation: comma typo in TaskOrchestrator.selfEvidencingToolSatisfied
  when list caused a Kotlin syntax error (caught by the gate, not merged).
- Validation: SKIP_APK=1 verify.sh -> BUILD SUCCESSFUL, 412 tests 0 failures
  (402 baseline + 10 new set_torch tests).


## Run 2026-10-12 (session 37) - open_calendar capability
- Milestone: new AgentAction.OpenCalendar + tool open_calendar ("open my calendar" /
  "show the calendar" / "what's on today"). The calendar app is a separate process no
  accessibility tap opens at a chosen day reliably on an arbitrary screen, so it is
  resolved before the a11y gate like the app_info/settings/navigation primitives.
- New CalendarViewer seam + AndroidCalendarViewer: dispatches the documented
  CalendarContract time URI (`content://com.android.calendar/time/<millis>`) with a
  CATEGORY_APP_CALENDAR ACTION_MAIN launch fallback; reports CALENDAR_OPENED only after
  the intent was delivered (CALENDAR_UNAVAILABLE / CALENDAR_PERMISSION / CALENDAR_ERROR
  otherwise), never a fake success. Distinct from add_calendar_event (opens the editor
  to create an event).
- Wired through all seven action-vocabulary seams (AgentAction, ToolRegistry,
  TaskPlanParser, HybridStepExecutor, PlannerPromptBuilder, DeviceAutomation,
  TaskPlanSerializer) plus PlanVariables.substitute and both TaskOrchestrator
  self-evidencing lists; AndroidManifest <queries> extended for calendar visibility.
- Validation: ./gradlew :app:testDebugUnitTest --no-daemon -> BUILD SUCCESSFUL, 440
  tests 0 failures 0 errors 0 skipped (431 baseline + 9 new open_calendar tests);
  ./gradlew :app:assembleDebug --no-daemon -> BUILD SUCCESSFUL, app-debug.apk produced.
- Next: file/document picking (ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT),
  split-screen/multi-window handling.


---

## SESSION 39 (2026-10-13) - milestone: summarize_text capability

Status: DONE. New `AgentAction.SummarizeText(text, maxChars)` + tool `summarize_text`
(condense a captured block of text through the configured cloud summarizer). Closes the
section 2.1 gap "read then summarise" (read_text/read_page_content -> summarize_text).
- Service-free: resolved before the a11y gate (like speak/create_note).
- Self-evidencing: the digest is carried as `ActionResult.readText`; `verifyAction`
  accepts a NONE-verified summarise from its non-empty digest, `selfEvidencingToolSatisfied`
  counts it as goal evidence. A failed summarise still fails the run (not masked).
- Honest: SUMMARY_UNAVAILABLE / SUMMARY_FAILED / INVALID_SUMMARY_INPUT; never fabricates.
- All seven action seams + OmniAgentApplication wired; `NanoSummarizer.promptFor` keeps the
  loop digest and the memory digest identical.
- Validation: `:app:testDebugUnitTest --tests com.example.CapabilityUpgradeTest` ->
  BUILD SUCCESSFUL, 208 tests, 0 failures.
- Next: file/document picking (ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT); a
  capture->summarise->act end-to-end benchmark test.
