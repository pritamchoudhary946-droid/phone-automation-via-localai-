# 1. OBJECTIVE

Upgrade the OmniAgent from a "plan once, run a short fixed action list, stop"
automator into something much closer to a Jarvis-style assistant: continuous
screen awareness, open-ended multi-step task execution across many apps, and
memory of the user and past tasks.

Scope for this work: Phases 0, 1, 2, 3, 4 (all items 4.1 - 4.17), Phase 5
(Autonomy & Self-Healing, items 5.1 - 5.10), Phase 6 (Reasoning & Reliability
Rescue, items 6.1 - 6.24), Phase 7 (Completion & Hardening, items 7.1 - 7.17) AND
Phase 8 (Jarvis rebrand, working model load, premium UI - items 8.1 - 8.6).
Phase 6 is landed; Phase 7 finishes the seams; Phase 8 fixes the live device
blockers and rebrands the app to "Jarvis". The code audit shows several Phase
4/5 features are interfaces with "Unavailable"/disabled defaults (vision, OCR,
voice input, Shizuku, cloud, automations), and the pinned model artifact needs
verifying before anything else can be trusted. Real reported failures ("open
WhatsApp, message Prateek" -> app
opens then scrolls forever; "search for X" -> text not verified in the field;
unclear whether the local AI is even used) trace to concrete defects, listed in
section 4 under "Observed defects". Phase 6 fixed those; Phase 7 closes the rest
of the gap, and Phase 8 fixes the live "won't load" bug and rebrands to Jarvis.

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
  UI (8.1 - 8.6): make the model actually load after a verified download and show
  the real failure reason (never a silent "not loaded"); rebrand all user-visible
  text to "Jarvis"; redesign the UI into a cool, premium, Jarvis-like interface; a
  new launcher/in-app icon; and a polish pass so task progress is always visible.
  Built together with Phase 7 in the same pass.

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
- C1 MODEL WON'T LOAD AFTER A SUCCESSFUL DOWNLOAD. The model file downloads and
  verifies, but the engine never becomes ready. This is the headline bug.
  Verified root-cause candidates in the code + the upstream runtime:
  * `initializeEngineIfModelReady()` in `OmniAgentApplication` only LOGS a failure
    (`Log.w`/`Log.e`) - it never surfaces the reason to the user, so the screen
    just says "not loaded" with no cause. `SetupScreen` renders ONLY
    `downloadState` (download/verify) and never shows `EngineState`
    (`NotInitialized`/`Loading`/`Ready`/`Failed`). The engine-failure reason is
    invisible. This exactly matches "downloaded, but load hi nahi ho raha, pata
    nahi kya error".
  * REAL upstream compatibility risk: `litertlm-android` (pinned 0.16.1) + a
    Gemma-4 E2B `.litertlm` artifact is known to fail `Engine.initialize()` on
    some library builds with errors like "Unsupported or unknown file format",
    a vision-encoder signature mismatch ("...must have exactly one signature but
    got 3"), or GPU delegate allocation failure. Community fixes were a specific
    library version and/or forcing the CPU backend. So "downloads, then won't
    load" is a version/backend problem, not a corrupt file.
  * `LiteRtEngine.kt` still claims `MAX_CONTEXT_TOKENS = 2048` while
    `LiteRtLmRuntime` requests 3072 - a mismatch to reconcile.
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

D) High-risk item to verify while building (not a gate, part of the same pass):
- `LiteRtModelManager` pins `gemma-4-E2B-it.litertlm` with a hardcoded byte length
  and SHA-256. The repo/filename are confirmed real; the exact hash still must be
  verified, because a wrong hash deletes the file and (with 6.1) the app blocks.
  -> handled in 7.1; the load failure itself is handled in Phase 8 (8.1).

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
8.1 - 8.6). Do all together with Phase 7 in the same build pass. This phase fixes
the exact complaints: the model downloads but will not load, no error is ever
shown, the app is still called "OmniAgent", and the UI is generic.

- 8.1 MAKE THE MODEL ACTUALLY LOAD (the headline bug). Goal: after a verified
  download, `EngineState` reaches `Ready` and planning uses the real model.
  Method: (a) surface the real failure - record the init exception text into
  `EngineState.Failed(message)` and show it; (b) reconcile the library/model
  combination that fails `Engine.initialize()`: try the CPU backend first (already
  the default) and, if init still fails, correct the `litertlm-android` version to
  the one that supports this Gemma-4 E2B artifact and/or re-verify which `.litertlm`
  is actually loadable on Android; (c) fix the `MAX_CONTEXT_TOKENS` mismatch
  (2048 in `LiteRtEngine` vs 3072 in `LiteRtLmRuntime`) so the config is coherent;
  (d) add a bounded retry that re-attempts load once on a transient failure.
  Reference: `model/GemmaInferenceEngine.kt`, `model/LiteRtLmRuntime.kt`,
  `model/LiteRtEngine.kt`, `OmniAgentApplication.kt`, `gradle/libs.versions.toml`.
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

Phase 8 scope note: do ALL of 8.1 - 8.6 together. 8.1 and 8.2 are the functional
fix; 8.3 - 8.6 are the rebrand and UI. They ship in the same pass.

# 5. TESTING AND VALIDATION

Success is defined per phase; each phase must keep the existing behaviour
honest (no step may be marked COMPLETED unless a fresh observation proves it).
Phases implemented: 0, 1, 2, 3, 4 (all items 4.1 - 4.17; 4.9 documented and
deferred), 5 (items 5.1 - 5.10), 6 (items 6.1 - 6.24), and 7 (items 7.1 - 7.17).
Phase 8 (8.1 - 8.6) is the rebrand + working-load + UI phase and is done next,
together with any remaining Phase 7 seams.

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
  "Jarvis AI", not basic mode).
- 8.2: when the model genuinely cannot load, the screen shows the real reason
  (exception text) with a Retry button - never a bare "not loaded".
- 8.3: every user-visible string reads "Jarvis"; no "OmniAgent" text remains on
  screen, in notifications, or on the launcher.
- 8.4/8.5: the app opens to the new premium UI with the new Jarvis icon/brand
  mark; no default/generic icon remains.
- 8.6: while a task runs, the UI shows the current step ("Opening app", "Typing",
  "Pressing Enter", "Verifying") and a clear final success/failure summary.

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
  the model loads to Ready after download (or shows the real failure + Retry), and
  the premium UI shows live step progress during a run.

