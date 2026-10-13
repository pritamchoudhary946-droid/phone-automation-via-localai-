# AGENTS.md

Repository memory for `phone-automation-via-localai-` (OmniAgent Android app).

## What this is
Android AI phone agent. A remote LLM API plans tasks; an `AccessibilityService`
executes them. There is no on-device model: the user supplies a provider, key and
(optionally) endpoint/model in Setup, and every plan/decision is a remote API call.

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

### Pre-merge verification (mandatory)
Before opening or merging a PR, run the same gate CI runs:

```bash
bash .github/scripts/verify.sh        # unit tests + debug APK; fails closed
SKIP_APK=1 bash .github/scripts/verify.sh   # unit tests only (faster)
```

`verify.sh` exits non-zero when the build fails AND when no JDK/SDK is present.
"Do not merge on a red build" means: never merge when this could not be run or
did not pass. A sandbox with no toolchain is not a pass — install the toolchain
(`openjdk-21-jdk-headless` + Android SDK platform 36.1 / build-tools 36.1.0) or
stop and report. A one-character typo merged unverified broke `main` once
already (`PlannerPromptBuilder` missing comma, PR #44).

## Delivery rules (do not regress)
- Work on a branch; open a PR; let the **Verify (unit tests + APK)** check go
  green before merge. Branch protection on `main` requires it. Do NOT self-merge
  a red PR — PR #44 was merged 35 seconds after its check failed, which broke
  `main`. Admin-only setup: `.github/scripts/protect-main.sh`.
- `.agents_tmp/RUN_STATE.json` is the loop's run log and is rewritten every
  session, so it is excluded from merge conflicts via a `merge=ours` driver in
  `.gitattributes`. Enable the driver once per clone:
  `git config merge.ours.driver true`.

Notes:
- Use `--no-daemon` in CI-like sandboxes; the daemon can be flaky.
- Tests are Robolectric (`@Config(sdk = [34])`). To make `AppDirectory` resolve
  an app, call `installLauncherPackage(context, pkg, label)` from `TestFakes.kt`.
- `TestFakes.kt` provides `FakeDeviceAutomation`, `FakeLlmRuntime`,
  `buildTestEngine(...)`, and `observation(...)`. `buildTestEngine` wires a real
  `AgentEngine`/`ApiInferenceEngine` to an injected `LlmRuntime` factory, so no
  network or on-device model is ever needed. `observation(...)` fakes are
  clickable by default (realistic for tappable text/labels).

## Architecture seams
- `DeviceAutomation` is the execution boundary (injectable; fake in tests).
- `AgentEngine` + `AgentPlanner` are the planning boundary; `LlmRuntime` is the
  injectable inference seam. `ApiLlmRuntime` is the real remote implementation
  (OpenAI / Anthropic / Gemini wire shapes); `ApiInferenceEngine` is the engine
  that loads it and reports `EngineState`. `LlmProviderConfig` persists the
  user-supplied provider/key/endpoint/model and is the single truth for whether
  the app can plan (`isConfigured()`).
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
`ClearText` (empty the focused/named field via `ACTION_SET_TEXT ""` before
retyping), `ReadPageContent` (UI-tree digest), and the in-loop web tools
`WebSearch` / `ReadUrl`. When adding an action you must update, together:
1. `AgentAction` (+ `getDisplayName`, and `RiskEvaluator` if it can be Risky),
2. `ToolRegistry` (const, `ToolDefinition`, `validateAction`),
3. `TaskPlanParser` (tool name -> action mapping),
4. `HybridStepExecutor.decide(...)` (deterministic case when no model is needed),
5. `PlannerPromptBuilder.ACTION_CATALOG` (the schema the model sees),
6. `AndroidDeviceAutomation.execute(...)` (+ accessibility primitive if new),
7. `TaskPlanSerializer` (`toParams` for every param the action carries, plus
   `toVerificationTarget`/`toolNameFor`) — omitting `toParams` silently drops the
   action's parameters on skill replay (e.g. a `ScrollUntilFound` direction), so a
   learned skill would replay with the wrong defaults.
An action the native layer cannot execute must not appear in the catalogue
(see the `ask_confirmation` exclusion in `PlannerPromptBuilder.availableActions`).
Corollary (regression found 2026-10-11): every advertised tool needs a real branch
in `AndroidDeviceAutomation.execute`. `adjust_volume` was catalogued/registered/
validated/parsed/fast-pathed but had no execute branch, so a planned volume change
hit the final `else` and could never run. `AndroidAudioController` now backs it
(`AudioStream` enum -> real `AudioManager` stream constants), resolved *before* the
a11y-service gate because audio is `AudioManager`-backed, not tree-backed (same rule
as `ReadNotifications`). The final `else` is now `UNSUPPORTED_ACTION` (not `UNKNOWN`)
and names the action, so a missing executor is diagnosable. `AdjustVolume` carries
`stream`/`steps`/`mute` through parser, serializer `toParams` (skill replay) and the
prompt catalogue; the controller read-backs the level and reports `AUDIO_UNCHANGED`
(never a fake success) when the device ignores the request. `play_media`
(`AgentAction.PlayMedia` + `MediaController`/`MediaTransport`) follows the same rule:
a media transport key (`KEYCODE_MEDIA_*`) is dispatched through `AudioManager`
service-free, resolved before the a11y gate; `AndroidMediaController` read-backs
`isMusicActive()` so play/pause/toggle/stop report success only when the playback
state actually moved (`MEDIA_UNCHANGED`/`MEDIA_NOT_PLAYING` otherwise), while
next/previous are an honest delivered-key (track change is not state-verifiable).
`set_ringer_mode` (`AgentAction.SetRingerMode` + `RingerModeController`/`RingerMode`
enum -> real `AudioManager.RINGER_MODE_*`) follows the same rule: the ringer mode is a
system audio setting no tap or stream-volume change sets reliably, so it is resolved
before the a11y gate; `AndroidRingerModeController` read-backs `AudioManager.ringerMode`
and reports `RINGER_UNCHANGED` (or `RINGER_PERMISSION` when the platform denies it for
lack of notification-policy access) rather than a fake success. It is distinct from
muting a stream. `set_alarm` (`AgentAction.SetAlarm` + `AlarmController` ->
`AlarmClock.ACTION_SET_ALARM`) follows the same rule: no accessibility-tree read or
UI tap sets a clock alarm reliably on an arbitrary screen, so it is resolved before
the a11y gate and dispatches the platform intent to the clock app. Because the alarm
is created by a separate process the app cannot read back, `AndroidAlarmController`
confirms only that the intent was *delivered* (and reports `ALARM_UNAVAILABLE` /
`ALARM_PERMISSION` honestly otherwise); it never claims the alarm now exists. The
prompt never advertises a capability the executor cannot run. `set_timer`
(`AgentAction.SetTimer` + `TimerController` -> `AlarmClock.ACTION_SET_TIMER`) follows
the same rule and is distinct from `set_alarm`: a countdown timer counts down from now
(an alarm fires at a clock time), so it carries `seconds` (bounded by
`ToolRegistry.MAX_TIMER_SECONDS`, 24 h) plus an optional `label`, is resolved before
the a11y gate, and — because the clock app is a separate process — reports
`TIMER_DISPATCHED` only after the intent was delivered (`TIMER_UNAVAILABLE` /
`TIMER_PERMISSION` / `INVALID_TIMER_DURATION` otherwise). `add_calendar_event`
(`AgentAction.AddCalendarEvent` + `CalendarController` -> the documented
`Intent.ACTION_INSERT` with `CalendarContract.Events.CONTENT_URI`) follows the same
rule: no accessibility-tree read or UI tap adds an event reliably on an arbitrary
screen, so it is resolved before the a11y gate and opens the calendar's own editor
pre-filled. It carries `title` plus `dayOffset`/`startHour`/`startMinute`/
`durationMinutes`/`location`, resolving a relative day ("tomorrow") to a numeric
offset the parser and prompt both explain; `AndroidCalendarController.beginMillis(...)`
is the pure relative-day -> absolute-instant resolver. Because the event is created by
the calendar app (a separate process) the app cannot read it back, so it reports only
that the intent was *delivered* (`CALENDAR_DISPATCHED`) and never claims the event now
exists (`CALENDAR_UNAVAILABLE` / `CALENDAR_PERMISSION` / `INVALID_EVENT_*` otherwise).
`add_contact` (`AgentAction.AddContact` + `ContactController` -> the documented
`ContactsContract.Intents.Insert.ACTION` with the NAME/PHONE/EMAIL extras) follows the
same rule: no accessibility-tree read or UI tap adds a contact reliably on an arbitrary
screen, so it is resolved before the a11y gate and opens the contacts app's own editor
pre-filled. It carries `name` plus optional `phone`/`email`, and requires at least one
of the two so an empty contact is never created. Because the contact is created by the
contacts app (a separate process) the app cannot read it back, so it reports only that
the intent was *delivered* (`CONTACT_DISPATCHED`) and never claims the contact now
exists (`CONTACT_UNAVAILABLE` / `CONTACT_PERMISSION` / `INVALID_CONTACT_*` otherwise).
`dial_number` (`AgentAction.DialNumber` + `DialerController` -> the documented
`Intent.ACTION_DIAL` with a `tel:` URI) follows the same rule: no accessibility-tree
read or UI tap opens a dialer pre-filled with a number reliably on an arbitrary screen,
so it is resolved before the a11y gate and opens the system dialer for the user to
confirm. It carries a `number`; `AndroidDialerController.normalizeDialNumber(...)` is a
pure, unit-tested gate that accepts only a dialable `tel:` value (digits plus `+ * # , ;`,
at least one digit) and rejects a blank or garbage target before any intent is built.
It deliberately uses `ACTION_DIAL`, not `ACTION_CALL`: placing the call would need the
`CALL_PHONE` runtime permission and would start a call without the user's confirmation,
which crosses the section 3.5 boundary. Because the dialer is a separate process the app
cannot read back that the number was entered, so it reports only that the intent was
*delivered* (`DIAL_DISPATCHED`) and never claims a call was placed
(`DIAL_UNAVAILABLE` / `DIAL_PERMISSION` / `INVALID_DIAL_NUMBER` otherwise). The prompt
must never let the model claim a call was made.

`send_sms` (`AgentAction.SendSms` + `SmsController` -> the documented
`Intent.ACTION_SENDTO` with an `smsto:` URI and the `"sms_body"` extra) follows the same
rule: no accessibility-tree read or UI tap opens a messaging compose screen pre-filled
with a number and body reliably on an arbitrary screen, so it is resolved before the a11y
gate and opens the messaging app for the user to review and press send. It carries a
`number` and a `message`; `AndroidSmsController.normalizeSmsNumber(...)` is a pure,
unit-tested gate that accepts only a dialable `smsto:` value (digits plus `+ * # , ;`, at
least one digit) and rejects a blank or garbage target before any intent is built. It
deliberately opens the compose screen and never sends the message: sending is a
consequential, hard-to-undo action that must stay under the user's explicit control, so
the primitive does not request `SEND_SMS`. Because the messaging app is a separate process
the app cannot read back that the message was sent, so it reports only that the intent was
*delivered* (`SMS_DISPATCHED`) and never claims the message was sent
(`SMS_UNAVAILABLE` / `SMS_PERMISSION` / `INVALID_SMS_NUMBER` / `INVALID_SMS_MESSAGE`
otherwise). The prompt must never let the model claim a message was sent.

`share_text` (`AgentAction.ShareText` + `ShareController` -> the documented
`Intent.ACTION_SEND` with a `text/plain` payload) follows the same rule: no
accessibility-tree read or UI tap hands a piece of text to an arbitrary target app
reliably on an arbitrary screen (and driving a specific app's UI would be app-specific),
so it is resolved before the a11y gate and dispatches the platform share sheet. It
carries `text` plus an optional `targetPackage` ("share this with WhatsApp"); when a
package is named the intent is addressed to it and the platform chooser is kept as a
fallback (via `EXTRA_INITIAL_INTENTS`), so a missing/renamed package degrades to "let the
user choose" rather than failing the whole share. It never posts anywhere by itself: the
user picks the final destination and confirms, so a consequential share stays under the
user's control (section 3.5). Because the receiving app is a separate process the app
cannot read back that the text was posted, so it reports only that the intent was
*delivered* (`SHARE_DISPATCHED`) and never claims the text was shared
(`SHARE_UNAVAILABLE` / `SHARE_PERMISSION` / `SHARE_ERROR` / `INVALID_SHARE_TEXT`
otherwise). The prompt must never let the model claim the text was shared.

`send_email` (`AgentAction.SendEmail` + `EmailController` -> the documented
`Intent.ACTION_SENDTO` with a `mailto:` URI and the `EXTRA_EMAIL`/`EXTRA_SUBJECT`/
`EXTRA_TEXT` extras) follows the same rule: no accessibility-tree read or UI tap opens a
mail compose screen pre-filled with a recipient and body reliably on an arbitrary screen
(and driving a specific mail app's UI would be app-specific), so it is resolved before
the a11y gate and opens the mail app for the user to review and press send. It carries a
`to` plus optional `subject`/`body`; `AndroidEmailController.normalizeEmailAddress(...)`
is a pure, unit-tested gate that accepts only a plausible single address (a non-empty
local part, exactly one `@`, and a dotted domain with no empty labels) and rejects a
blank or garbage target before any intent is built. It deliberately opens the compose
screen and never sends the message: sending is a consequential, hard-to-undo action that
must stay under the user's explicit control, so the primitive does not request any send
permission. Because the mail app is a separate process the app cannot read back that the
message was sent, so it reports only that the intent was *delivered* (`EMAIL_DISPATCHED`)
and never claims the message was sent (`EMAIL_UNAVAILABLE` / `EMAIL_PERMISSION` /
`EMAIL_ERROR` / `INVALID_EMAIL_ADDRESS` otherwise). The prompt must never let the model
claim an email was sent.

`navigate_to` (`AgentAction.NavigateTo` + `NavigationController` -> the documented
`Intent.ACTION_VIEW` with a `geo:0,0?q=<place>` URI) follows the same rule: no
accessibility-tree read or UI tap opens a maps app at a chosen place/address reliably on
an arbitrary screen (and driving a specific maps app's UI would be app-specific), so it
is resolved before the a11y gate and opens the map showing the place. It carries a
single `target` (a place name or street address);
`AndroidNavigationController.normalizeNavigationTarget(...)` is a pure, unit-tested gate
that trims a usable place and rejects a blank, over-`MAX_NAV_TARGET_CHARS`-long, or
control-character-bearing value before any URI is built. The `geo:0,0?q=` form shows the
place on the map and the user starts turn-by-turn directions from there; it never begins
navigating by itself, so the consequential route stays under the user's control (section
3.5). Because the maps app is a separate process the app cannot read back that the place
was shown, so it reports only that the intent was *delivered* (`NAV_DISPATCHED`) and
never claims a route was started (`NAV_UNAVAILABLE` / `NAV_PERMISSION` / `NAV_ERROR` /
`INVALID_NAV_TARGET` otherwise). The prompt must never let the model claim navigation
began.

`open_app_info` (`AgentAction.OpenAppInfo` + `AppInfoController` -> the documented
`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, package:<pkg> URI)`) follows the
same rule: no accessibility-tree read or UI tap opens an app's own Settings/App-info page
reliably on an arbitrary screen (and driving that page would be app/device-specific), so
it is resolved before the a11y gate and lets the system Settings app show the page. It
carries a single `packageName` (a package, or an app alias like "chrome" that
`AndroidDeviceAutomation` resolves through `AppDirectory.resolvePackage` when the value
has no dot); `AndroidAppInfoController.normalizePackageName(...)` is a pure, unit-tested
gate that trims a usable package and rejects a blank, over-`MAX_PACKAGE_CHARS`-long, or
control-character-bearing value before any URI is built. It only *opens* the page (the
user decides what to do there - force-stop, clear data, change permissions); it never
changes a setting itself, so a consequential change stays under the user's control
(section 3.5). Because the Settings app is a separate process the app cannot read back
that the page was shown, so it reports only that the intent was *delivered*
(`APP_INFO_OPENED`) and never claims a setting was changed (`APP_INFO_UNAVAILABLE` /
`APP_INFO_PERMISSION` / `APP_INFO_ERROR` / `INVALID_APP_INFO_PACKAGE` otherwise). The
prompt must never let the model claim a setting was changed.

`open_settings` (`AgentAction.OpenSettings` + `SettingsController` -> the documented
`Settings.ACTION_*` panel intents) follows the same rule: no accessibility-tree read or
UI tap opens a named system Settings panel (wifi/bluetooth/display/...) reliably on an
arbitrary screen, so it is resolved before the a11y gate and lets the system Settings app
show the panel. It carries a single `panel`; `SettingsPanel` is a *closed* enum mapping
each named panel to its real `Settings.ACTION_*` constant, and
`SettingsPanel.fromKey(...)` is a pure, unit-tested resolver that accepts canonical keys
and natural aliases ("wi-fi", "screen" -> display) and returns null for an unknown value,
so the executor refuses an unrecognised panel with `INVALID_SETTINGS_PANEL` before any
intent is built (the tool boundary rejects it too). It is distinct from `open_app_info`
(one app's page) and from `open_quick_settings` (the shade). It only *opens* the panel;
the user decides what to change there, so a consequential change stays under the user's
control (section 3.5). Because the Settings app is a separate process the app cannot read
back that the panel was shown, so it reports only that the intent was *delivered*
(`SETTINGS_OPENED`) and never claims a setting was changed (`SETTINGS_UNAVAILABLE` /
`SETTINGS_PERMISSION` / `INVALID_SETTINGS_PANEL` otherwise). The prompt must never let the
model claim a setting was changed.

`open_calendar` (`AgentAction.OpenCalendar(dayOffset)` + `CalendarViewer` ->
`Intent.ACTION_VIEW` with the documented `CalendarContract.CONTENT_URI` `time/`
URI of the chosen day's midnight) follows the same rule: no accessibility-tree read or UI
tap opens the calendar app at a chosen day reliably on an arbitrary screen (and the
calendar app's own UI is app/device-specific), so it is resolved before the a11y gate and
shows the user their day. It carries a `dayOffset` (0 = today, 1 = tomorrow, negative =
a past day; bounded to +/-`MAX_EVENT_DAY_OFFSET` by `ToolRegistry` validation) resolved
from a relative day like "tomorrow" by `parseEventDayOffset`, so "show my calendar for
tomorrow" opens tomorrow rather than always today; the offset is carried through the
parser, the `TaskPlanSerializer` `toParams` (skill replay) and the prompt catalogue. It
is distinct from `add_calendar_event` (which opens the calendar
*editor* pre-filled to create an event) - `open_calendar` only *views* the calendar.
Because the calendar app is a separate process the app cannot read back that the day was
shown, so it reports only that the intent was *delivered* (`CALENDAR_OPENED`) and never
claims an event was created or read (`CALENDAR_UNAVAILABLE` / `CALENDAR_PERMISSION`
otherwise). `AndroidCalendarViewer.openCalendar(nowMillis, dayOffset)` resolves the
chosen day's midnight via the pure `AndroidCalendarViewer.dayStart(...)`; an
unwired viewer returns `CALENDAR_UNAVAILABLE`, never a fabricated success. It is
injectable via the `AndroidDeviceAutomation` constructor and wired in
`OmniAgentApplication`.

`create_note` (`AgentAction.Note(title?, content?)` + `NoteController` -> the documented
`Intent.ACTION_CREATE_NOTE` with the `EXTRA_TITLE`/`EXTRA_TEXT` extras) follows the same
rule: no accessibility-tree read or UI tap opens the notes app's own editor pre-filled
with a note reliably on an arbitrary screen (and driving a specific notes app's UI would
be app-specific), so it is resolved before the a11y gate and opens the notes app for the
user to review and save. It carries an optional `title` and `content`, and requires at
least one of the two so an empty note is never created (`ToolRegistry` validation rejects
both-null; bounds are `MAX_NOTE_TITLE_CHARS` 200 / `MAX_NOTE_CONTENT_CHARS` 2000). It
deliberately opens the editor and never saves the note itself: saving is the user's action
(section 3.5). Because the notes app is a separate process the app cannot read back that
the note was saved, so it reports only that the intent was *delivered* (`NOTE_DISPATCHED`)
and never claims the note was created (`NOTE_UNAVAILABLE` / `NOTE_PERMISSION` /
`INVALID_NOTE` / `NOTE_ERROR` otherwise). The prompt must never let the model claim a
note was saved.

`summarize_text` (`AgentAction.SummarizeText(text, maxChars)` + the shared
`NanoSummarizer` -> the configured cloud LLM engine, the same one behind the
planning memory/lessons digest) follows the same rule: a summarisation is computed
by a remote model, not reflected by any screen, so it is resolved before the a11y
gate and condenses a block of text (often the value an earlier
`read_text`/`read_page_content` step captured) to a short digest. It carries `text`
(bounded by `ToolRegistry.MAX_SUMMARIZE_INPUT_CHARS` 8000) and `maxChars`
(bounded `MIN_SUMMARY_CHARS` 40 / `MAX_SUMMARY_CHARS` 1200; the parser's
`parseSummaryChars(...)` accepts `maxChars`/`max_chars`/`length`/`chars` and
defaults to `DEFAULT_SUMMARY_CHARS` 400). The digest is carried as
`ActionResult.readText`, so it flows into a later step as a `{{name}}` variable
(cross-app data passing) and is the step's own evidence - `verifyAction` treats a
NONE-verified `SummarizeText` as satisfied by its non-empty digest (empty ->
failure) and `selfEvidencingToolSatisfied(...)` counts a completed one as goal
evidence, exactly like the reads. It is honest about failure: blank input is
`INVALID_SUMMARY_INPUT` (validation rejects it too), an unconfigured engine
(`isAvailable()` false / `UnavailableNanoSummarizer`) is `SUMMARY_UNAVAILABLE`, and
a blank generation is `SUMMARY_FAILED` - it never fabricates a summary. It is
injectable via the `AndroidDeviceAutomation` constructor and wired in
`OmniAgentApplication`. `NanoSummarizer.promptFor(...)`/`SYSTEM_INSTRUCTION` keep the
loop's digest and `ApiNanoSummarizer.summarize(...)` byte-identical in what they ask
the model. The prompt must never let the model claim a summary it did not receive.

`set_torch` (`AgentAction.SetTorch` + `TorchController` -> `CameraManager.setTorchMode`
on the back camera with a flash unit) follows the same rule: the torch is a
camera-hardware setting no accessibility-tree read or UI tap toggles reliably on an
arbitrary screen (it may live only in quick settings, off-screen), so it is resolved
before the a11y gate. It carries a boolean `on`; `TaskPlanParser.parseTorchOn(...)`
accepts a JSON boolean or a string alias ("on"/"off"/"enable"/"disable"/"yes"/"no"/
"1"/"0") and defaults to ON for the common "turn on the flashlight" intent, always a
valid request. Unlike the intent-dispatch primitives it *does* read the state back:
`AndroidTorchController` registers a `CameraManager.TorchCallback`, waits a bounded
`CONFIRM_BUDGET_MS` for the requested state, and reports `TORCH_ON`/`TORCH_OFF` only on
a confirmed change, `TORCH_UNCHANGED` when the hardware ignored the request, and
`TORCH_UNAVAILABLE` (no camera service / no flash) / `TORCH_PERMISSION` / `TORCH_ERROR`
otherwise — never a fake success. It is injectable via the `AndroidDeviceAutomation`
constructor and wired in `OmniAgentApplication`.

`speak` (`AgentAction.Speak(text)` + `SpeakController` -> the platform `TextToSpeech`
engine) follows the same rule: speech output is a platform TTS service, not an
accessibility-tree control, so no screen read or UI tap can produce speech on an
arbitrary screen. It is therefore resolved *before* the a11y-service gate, carries a
single `text` (bounded by `ToolRegistry.MAX_SPEAK_CHARS`, 1000 chars — a blank or
oversized value is `Invalid` at validation), and is *opt-in*: like `read_notifications`
it is gated by a live `() -> Boolean` (`voiceOutputEnabled`), and the executor refuses
with `SPEAK_DISABLED` when it is off (never a silent no-op). `AndroidSpeakController`
creates the engine lazily, waits a bounded budget for async initialisation, and reports
`SPOKEN` only once the engine accepted the utterance (waits briefly for
`UtteranceProgressListener.onDone`); a `speak` the engine rejects is `TTS_ERROR`, and an
engine that never became ready is `TTS_UNAVAILABLE` — never a fake success. It is
injectable via the `AndroidDeviceAutomation` constructor and wired in
`OmniAgentApplication`. The opt-in flag also gates the *prompt*:
`PlannerPromptBuilder.availableActions(..., voiceOutputEnabled)` withholds `speak` from
the advertised tool list and `actionCatalog(...)` withholds its schema line while speech
output is off, read live per call by `AgentPlanner`.

## Conventions
- Postcondition verification lives in `PostconditionVerifier.verify(...)`; a
  `NONE` verification never proves completion; every terminal goal needs concrete evidence.
- Screen text is untrusted: sanitize via
  `PlannerPromptBuilder.sanitizeUntrusted(...)` before embedding in prompts.
- Keep the model's JSON wrapper construction in `JSONObject`/`JSONArray`, never
  string interpolation.
- The planning prompt carries the device clock: `PlanningRequest.currentDateTime`
  (rendered by the pure `PlannerPromptBuilder.formatDateTime(...)`) is emitted as
  `Current date/time: ...` so the model can resolve a relative time ("in 20
  minutes", "tomorrow at 7am") into the absolute hour/minute `set_alarm` requires.
  `AgentPlanner.plan` fills it when the caller left it blank (a supplied value
  wins, so tests stay deterministic). Keep it blank-degrading — a missing clock
  omits the line, it must never fabricate a time.

## Audit invariants (do not regress)
- `LlmProviderConfig` is the single truth for whether the app can plan
  (`isConfigured()`): a non-blank key, plus a base URL for the catch-all
  `OPENAI_COMPATIBLE` provider. Never gate planning on anything else.
- `ApiLlmRuntime` sends only over HTTPS to the configured base URL, reads every
  response body through `response.use { }`, and throws `LlmInferenceException`
  on a non-2xx status — it must never fabricate output.
- OkHttp responses are always read through `response.use { }` (CloudflareGateway,
  API client) so the body/connection is closed on every path.
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
- Model tuning is pinned in `LlmProviderConfig`: `effectiveBaseUrl()` and
  `effectiveModel()` normalise the user's endpoint/model and fall back to the
  provider defaults. `FixesRegressionTest` asserts the normalisation, so keep the
  test and the config in step.
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
- 9.9 opt-in also gates the *prompt*: `PlannerPromptBuilder.availableActions(registry,
  notificationTriageEnabled)` withholds `read_notifications` from the advertised tool
  list, and `actionCatalog(enabled)` withholds its schema line from the planning
  prompt, while the capability is off. The flag must be a live `() -> Boolean` read per
  call (AgentPlanner reads it in `availableToolNames()`), never captured once, so a
  Settings toggle flip is honoured on the next prompt. Both new params default to the
  safe/OFF value; keep it that way so a caller that forgets the flag never advertises an
  action the executor refuses.
- `ReadNotifications` is notification-bus backed and must NOT sit behind the
  accessibility-service gate: `AndroidDeviceAutomation.execute` resolves it (consent
  check, then `readNotificationDigest()`) *before* `serviceProvider()`, so an
  opted-in read works without a bound a11y service and is never misreported as
  `NO_SERVICE`. Keep the digest helper service-free.
- A `read_notifications` step must be self-evidencing, exactly like
  `read_text`/`read_page_content`: the digest `readNotificationDigest()` reads is
  carried as `ActionResult.readText`, and `TaskOrchestrator.verifyAction` treats a
  NONE-verified `ReadNotifications` as satisfied by that non-empty digest (empty ->
  failure). Regression found 2026-10-11: without the carried digest the step could
  never satisfy its NONE postcondition, so it retried to exhaustion and failed — the
  goal gate's `selfEvidencingToolSatisfied(ReadNotifications)` allowance was
  unreachable and every triage read failed. Do not drop the `readText` carry or the
  self-satisfaction branch.

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
- Optional, OFF-by-default seam: `model/NanoSummarizer` (4.7) delegates to the
  configured engine. An unconfigured engine must return null and never touch the
  network; a plan is always re-validated through the same `TaskPlanParser`.

## Phase 7 (completion & hardening) invariants
- Every optional capability is owned by `settings/CapabilitySettings` and
  defaults OFF. `ui/SettingsScreen.kt` is the single surface; when adding a
  capability toggle, add it there too. The LLM API provider/key/endpoint/model
  live in `model/LlmProviderConfig`, configured from the Setup screen.
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
- Engine load (8.1): `ApiInferenceEngine.initialize` builds ONE `ApiLlmRuntime`
  from the configured provider and reports `EngineState.Failed` when it throws
  (e.g. no API key). A runtime that never reports ready is closed and is not kept
  as the active engine. There is no GPU/CPU two-attempt scheme and no context-token
  literal: the request budget is bounded in `AgentPlanner`.
- Honest failure (8.2): never a silent "not loaded". `ApiInferenceEngine` records
  the real init/smoke-test reason via `EngineState.Failed(message)` and
  `reportUnavailable(reason)`; both `AgentDashboardScreen` and `SetupScreen` show
  that message plus a Retry (`initializeEngineIfConfigured()` re-attempts). The
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
    it is the only place the load/failure reason shows next to the API config.
  * BS2 Shizuku: `OmniAgentApplication.privilegedActions` is referenced by the
    Settings self-test (real status); the bridge refuses unless opted in AND
    Shizuku runs. Do NOT advertise it as available by default.
  * BS3 Wake word: `OptInWakeWordDetector` fronts the hands-free flow in
    `AgentDashboardScreen` (armed only with opt-in + mic permission). Keep it
    gated; never capture audio silently.
  * BS4 LLM API: configurable end to end via `SetupScreen` ->
    `LlmProviderConfig.save(provider, key, endpoint, model)`. `isConfigured()`
    requires a non-blank key (and a base URL for `OPENAI_COMPATIBLE`). There is one
    shared config on the application (`llmProviderConfig`). The old
    `CloudPlannerProvider` / `cloudFallbackEnabled` escape hatch is gone — the API
    *is* the planner now, so there is nothing to fall back from.
  * BS8 start gating: `canStartTask(modelReady, basicFallbackExplicit)` is the
    single truth; the dashboard shows "Model required • tasks are blocked" and
    routes to Setup instead of pretending a "Basic mode" runs.
  * BS9 `AgentEngine.reasonAndPlan` is a legacy diagnostic-only path, NOT in the
    live loop; its fixed budget/foreground are documented as such.
  * BS10 no reachable `Unavailable*` default: the app wires real OCR/vision/
    summarizer; the stubs are inert constructor defaults for tests only.
- No load-time test prompt (8.8): the engine is declared ready once a provider + key
  are configured — readiness is configuration, NOT a live probe. There is deliberately
  no smoke-test generation at load. Gating readiness on a tiny probe is what produced
  false "engine not available" failures right after a valid key was added (a live
  provider is slow on its first call and may answer a one-word prompt with an empty
  string). A real defect (bad key, unreachable endpoint) surfaces on the first actual
  `generate`, which degrades honestly via `EngineState.Failed`; do not reintroduce a
  load-time probe gate in `ensureReady`/`initialize`.
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
- 9.4 cross-app data passing (task variables): `PlanVariableStore` is a per-run,
  never-persisted, tenant-scoped store; a step declares `"captures":["name"]` on a
  `read_text`/`read_page_content` step and a later step uses `{{name}}`
  (`substitute` runs just before execution). A capture step's success is the
  non-empty text it captured — `verifyAction` treats a NONE-verified read as
  satisfied by its own `readText` (empty -> failure, never a fake success), and the
  goal gate's `selfEvidencingToolSatisfied(...)` counts a completed
  ReadText/ReadPageContent/ReadNotifications as goal evidence. A capture step
  removes its variable names before storing (no stale reuse on re-run) and a failed
  capture removes them too. `PlanVariableStore.looksSensitive(...)` refuses a value
  that looks like a secret (13-19 digit card, labelled otp/pin/password/cvv/secret)
  so it never reaches a later step or the prompt. Do not regress the read's
  self-satisfaction or the secret guard.
- Service-backed actions are self-evidencing too. `adjust_volume`, `play_media`,
  `set_ringer_mode`, `set_alarm`, `set_timer`, `add_calendar_event`, `add_contact`,
  `dial_number`, `send_sms`, `share_text`, `send_email`, `navigate_to`,
  `open_app_info`, `open_settings`, `set_torch`,
  `speak`, `open_calendar`, `create_note`, `summarize_text` and
  `read_notifications` act on a system
  service (`AudioManager`, the notification bus, the clock app via `AlarmClock`, the
  calendar app via `CalendarContract`, the contacts app via `ContactsContract`, the
  dialer via `Intent.ACTION_DIAL`, the messaging app via `Intent.ACTION_SENDTO`, the
  platform share sheet via `Intent.ACTION_SEND`, the mail app via `Intent.ACTION_SENDTO`
  `mailto:`, the maps app via `Intent.ACTION_VIEW` `geo:`, the Settings app via
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (app info) or the named
  `Settings.ACTION_*` panels (open_settings), the text-to-speech engine via
  `TextToSpeech`, the calendar app via the `CalendarContract` time URI (open_calendar),
  the notes app via `Intent.ACTION_CREATE_NOTE` (create_note))
  that no
  accessibility screen reflects, so their default NONE verification can never be
  satisfied from a fresh observation. `verifyAction` therefore accepts a *successful*
  result for these as the step's evidence, and `selfEvidencingToolSatisfied(...)`
  counts a completed one as goal evidence (same rule as the reads). This is safe only
  because the executor reports success honestly: the audio/media/ringer controllers
  read the stream level / `isMusicActive()` / `ringerMode` back and return
  `AUDIO_UNCHANGED` / `MEDIA_UNCHANGED` / `RINGER_UNCHANGED` when nothing moved,
  `set_alarm` reports `ALARM_DISPATCHED` only after the intent was delivered to a clock
  app (`ALARM_UNAVAILABLE` otherwise), `set_timer` reports `TIMER_DISPATCHED` on the
  same honest delivery basis (`TIMER_UNAVAILABLE` otherwise), `add_calendar_event`
  reports `CALENDAR_DISPATCHED` only after the insert intent was delivered to a calendar
  app (`CALENDAR_UNAVAILABLE` otherwise), `add_contact` reports `CONTACT_DISPATCHED`
  only after the insert intent was delivered to a contacts app (`CONTACT_UNAVAILABLE`
  otherwise), and `dial_number` reports `DIAL_DISPATCHED` only after the `ACTION_DIAL`
  intent was delivered to a dialer (`DIAL_UNAVAILABLE` otherwise), and `send_sms` reports
  `SMS_DISPATCHED` only after the `ACTION_SENDTO` intent was delivered to a messaging app
  (`SMS_UNAVAILABLE` otherwise), and `share_text` reports `SHARE_DISPATCHED` only after
  the `ACTION_SEND` intent was delivered to the share sheet (`SHARE_UNAVAILABLE`
  otherwise), and `send_email` reports `EMAIL_DISPATCHED` only after the `ACTION_SENDTO`
  `mailto:` intent was delivered to a mail app (`EMAIL_UNAVAILABLE` otherwise), and
  `navigate_to` reports `NAV_DISPATCHED` only after the `ACTION_VIEW` `geo:` intent was
  delivered to a maps app (`NAV_UNAVAILABLE` otherwise), and `open_app_info` reports
  `APP_INFO_OPENED` only after the `ACTION_APPLICATION_DETAILS_SETTINGS` `package:`
  intent was delivered to a settings app (`APP_INFO_UNAVAILABLE` otherwise), and
  `open_settings` reports `SETTINGS_OPENED` only after the `Settings.ACTION_*` panel
  intent was delivered to the Settings app (`SETTINGS_UNAVAILABLE` otherwise), and
  `open_calendar` reports `CALENDAR_OPENED` only after the `CalendarContract` time-URI
  intent was delivered to a calendar app (`CALENDAR_UNAVAILABLE` otherwise), and
  `create_note` reports `NOTE_DISPATCHED` only after the `Intent.ACTION_CREATE_NOTE`
  intent was delivered to a notes app (`NOTE_UNAVAILABLE` otherwise), and
  `set_torch` reports `TORCH_ON`/`TORCH_OFF` only after `CameraManager.TorchCallback`
  confirmed the state moved (`TORCH_UNCHANGED` / `TORCH_UNAVAILABLE` otherwise), and
  `speak` reports `SPOKEN` only after the platform `TextToSpeech` engine accepted the
  utterance (`TTS_UNAVAILABLE` / `SPEAK_DISABLED` otherwise), and `summarize_text`
  reports `SUMMARY_OK` only with a non-blank digest from the configured summarizer
  (`SUMMARY_UNAVAILABLE` when the engine is not ready, `SUMMARY_FAILED` on a blank
  generation, `INVALID_SUMMARY_INPUT` on blank input). Never widen
  this to an
  action whose success is not independently confirmed.
- `read_device_status` (`AgentAction.ReadDeviceStatus` + `DeviceStatusReader`) is
  the device-state read primitive: battery level/charging, screen on/off, ringer
  mode and connectivity live on system services (`BatteryManager`, `PowerManager`/
  `KeyguardManager`, `AudioManager`, `ConnectivityManager`), not the accessibility
  tree, so no screen read or UI tap can answer "how much battery do I have" /
  "is wifi on" / "am I on silent". Like `read_notifications` it is resolved
  *before* the a11y-service gate, carries its digest as `ActionResult.readText`,
  and self-evidences a NONE-verified step (empty digest -> failure, never a fake
  success). `AndroidDeviceStatusReader.format(...)` is pure and unit-tested, so
  the planner-visible digest text is asserted without a device; an unwired reader
  returns `STATUS_UNAVAILABLE`, never a fabricated status, and each reading is
  fresh per call (no cache). The digest is the whole goal evidence for a
  device-status question - do not require a screen change for it.
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
- A detected runtime permission dialog is UNRESOLVABLE and must never be
  auto-granted. `ObstacleDetector.detect` marks `Kind.PERMISSION` with
  `dismissLabel = null, unresolvable = true` (a request still needs a deny AND a
  grant affordance, which is what tells it apart from a Settings list); the
  orchestrator's 9.12 handler therefore stops the run honestly and asks the user
  to grant access. Do NOT restore a grant-selecting `dismissLabel` (section 3.5:
  the agent must not tap a grant control or bypass a permission prompt on the
  user's behalf). Regression: `OrchestratorLoopTest.permissionDialogIsNotAutoGranted`,
  `Phase9UpgradeTest.detectsAndSafelyDismissesCommonBlockers`.

## Build gotcha (found in the Phase 9 lint gate)
- Android Lint (`lintAnalyzeDebug`) can CRASH with
  `Bad parent: class org.jetbrains.kotlin.psi.KtDeclarationModifierList` (an
  `ExperimentalDetector`/`LightClassUtil` bug) on a declaration annotated
  `@Volatile`. Prefer a plain `var`/`@Synchronized` here; if it recurs, the
  suggested escape hatch is `lint { disable("UnsafeOptInUsageError",
  "UnsafeOptInUsageWarning") }` - but first just drop the offending modifier.
- Always run the full gate locally before pushing:
  `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

## Loader invariants - do not regress
- ONE load path: `ApiInferenceEngine` takes a single `runtimeFactory` that builds an
  `ApiLlmRuntime`. Do not reintroduce a multi-attempt load scheme.
- `initialize()` is single-flight: the Ready check is repeated INSIDE
  `mutex.withLock`, and any previous runtime is closed before it is replaced. Two
  concurrent initializers must never leave two runtimes alive.
- No probe, no flag: `ensureReady()` is just `initialize()`. There is NO load-time
  smoke probe and no "probe ran" flag. Readiness is configuration; a bad key or
  unreachable endpoint surfaces on the first real `generate()`. Do not reintroduce a
  probe gate here — it is the source of the false "engine not available" bug.
- There is no on-device runtime to release on memory pressure, so
  `OmniAgentApplication` no longer tears the engine down on a trim. Keep it that way;
  the API client holds no multi-GB state.

## Phase 10.7 target resolution invariant (do not regress)
- `perception/TargetResolver.resolve(...)` must reject any invisible node before
  scoring: a zero-area node (`bounds.width() > 0 && bounds.height() > 0`) can still
  expose a strong label but cannot be tapped, so it must return `NONE`, never
  `UNIQUE`. This mirrors the a11y dump/tap guards in `AgentAccessibilityService`
  (lines 199/376/424). Disabled nodes are already excluded; keep both filters.
  Regression test: `Phase10AdversarialTest.zeroAreaTargetIsNotResolved`.

