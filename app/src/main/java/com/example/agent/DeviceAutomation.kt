package com.example.agent

import com.example.accessibility.AgentAccessibilityService
import com.example.accessibility.UiNode
import com.example.accessibility.UiTreeSnapshot
import com.example.model.NanoSummarizer
import com.example.notifications.NotificationBus
import com.example.notifications.NotificationSource
import com.example.perception.TargetResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A fresh, bounded observation of the current screen. */
data class UiObservation(
    val packageName: String?,
    val nodes: List<UiNode>,
    val summary: String,
    val fingerprint: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromSnapshot(snapshot: UiTreeSnapshot, maxNodes: Int = 30): UiObservation = UiObservation(
            packageName = snapshot.packageName,
            nodes = snapshot.nodes,
            summary = snapshot.toPromptSummary(maxNodes),
            fingerprint = snapshot.getFingerprint()
        )
    }

    /**
     * True when [target] is (or sits inside) a tappable control on this screen.
     *
     * Android list rows usually expose a clickable container with a separate,
     * non-clickable text label, so a bare `isClickable` check on the labelled node
     * rejects real, tappable results. This treats a node as activatable when it is
     * clickable, or when its bounds contain a clickable node. Editable fields are
     * excluded for "open this item" decisions unless [allowEditable] is set, so a
     * search box is not mistaken for a result.
     */
    fun hasActivatableLabel(target: String, allowEditable: Boolean = false): Boolean {
        if (target.isBlank()) return false
        val labelled = nodes.filter { it.packageName == null || it.packageName == packageName }
            .filter { it.containsText(target) }
            .filter { allowEditable || !it.isEditable }
        return labelled.any { node ->
            if (node.isClickable) return@any true
            nodes.any { candidate ->
                candidate.isClickable &&
                    (candidate.packageName == null || candidate.packageName == packageName) &&
                    candidate.bounds.contains(node.bounds)
            }
        }
    }

    /**
     * True when a usable text-entry field is visible, using the platform's
     * `isEditable` flag or the [UiNode.isLikelyTextInput] heuristic (WebViews and
     * custom composers often omit the flag).
     */
    fun hasTextInputField(): Boolean =
        nodes.any { (it.packageName == null || it.packageName == packageName) && it.isLikelyTextInput() }

    /**
     * Search-specific input detection. A generic editable node is not enough:
     * message, comment, caption and login fields are valid text inputs but are
     * unsafe targets for an open-search step.
     */
    fun hasSearchInputField(): Boolean =
        nodes.any { node ->
            if (node.packageName != null && node.packageName != packageName) return@any false
            if (!node.isLikelyTextInput()) return@any false
            val haystack = listOfNotNull(
                node.text,
                node.contentDescription,
                node.id.substringAfterLast('/'),
                node.className
            ).joinToString(" ").lowercase()
            val searchSignal = listOf("search", "query", "searchview").any { haystack.contains(it) }
            val negativeSignal = listOf("message", "comment", "caption", "reply", "password").any { haystack.contains(it) }
            searchSignal || (node.isFocused && !negativeSignal && node.className.lowercase().contains("search"))
        }

    /**
     * True when the fresh screen exposes the system power menu (power off / restart /
     * emergency). A bare "power" glyph or a Settings page is not enough: the menu is
     * recognised by its action choices, optionally corroborated by the SystemUI
     * package. Used to verify an [com.example.agent.AgentAction.OpenPowerDialog] step
     * honestly from real screen evidence rather than a synthetic success.
     */
    fun hasPowerMenu(): Boolean {
        val haystack = nodes.joinToString(" ") {
            listOfNotNull(it.text, it.contentDescription).joinToString(" ")
        }.lowercase()
        val powerOff = listOf("power off", "switch off", "turn off power").any { haystack.contains(it) }
        val restart = listOf("restart", "reboot").any { haystack.contains(it) }
        val systemSurface = nodes.any { it.packageName?.contains("systemui") == true }
        return (powerOff && restart) || ((powerOff || restart) && systemSurface)
    }

    /**
     * Resolves [hint] to a single actionable target using [TargetResolver] (10.7).
     * Only a [TargetResolver.Outcome.UNIQUE] result is actionable; ambiguous or
     * unmatched hints return a non-actionable resolution the caller must report.
     */
    fun resolveTarget(
        hint: String,
        allowEditable: Boolean = false,
        requireActivatable: Boolean = false
    ): TargetResolver.Resolution =
        TargetResolver.resolve(
            nodes = nodes,
            hint = hint,
            foregroundPackage = packageName,
            allowEditable = allowEditable,
            requireActivatable = requireActivatable
        )
}

/** Outcome of dispatching a single native action. */
data class ActionResult(
    val success: Boolean,
    val method: String,
    val message: String,
    /**
     * The visible text a *read* action captured, when there was one (plan 9.4).
     * This is the value a later step can reference as a `{{name}}` variable; it is
     * null for every action that does not produce readable content. Captured text is
     * treated as untrusted, bounded by [MAX_CAPTURED_CHARS] and never persisted.
     */
    val readText: String? = null
) {
    companion object {
        const val MAX_CAPTURED_CHARS = 2_000
    }
}

/**
 * Execution layer boundary. The planning brain never touches Android APIs
 * directly; it can only request these approved capabilities. This seam is
 * injectable so the orchestrator is fully unit-testable.
 */
interface DeviceAutomation {
    fun isReady(): Boolean

    /** Non-blocking reason the automation layer is unavailable, if any. */
    fun unavailabilityReason(): String?

    suspend fun observe(): UiObservation

    suspend fun execute(action: AgentAction): ActionResult
}

/** Real implementation backed by the existing [AgentAccessibilityService]. */
class AndroidDeviceAutomation(
    private val appDirectory: AppDirectory,
    private val serviceProvider: () -> AgentAccessibilityService? = { AgentAccessibilityService.instance },
    private val notificationSource: NotificationSource = NotificationBus,
    private val ocrReader: com.example.perception.OcrReader = com.example.perception.UnavailableOcrReader,
    private val visionGrounder: com.example.perception.VisionGrounder = com.example.perception.UnavailableVisionGrounder,
    // 9.9: reading posted notifications is the smart-triage capability, so it is gated
    // by the opt-in flag. Default OFF means the flag is only read when a caller passes a
    // live supplier; the built-in path wires the real setting.
    private val triageEnabled: () -> Boolean = { false },
    // Device audio is controlled through the system AudioManager, not the accessibility
    // tree, so the volume primitive is resolved before the a11y-service gate (like
    // ReadNotifications). A caller that does not wire a controller gets an honest
    // "unavailable" result rather than a silent no-op.
    private val audioController: AudioController? = null,
    // Media transport is likewise service-free: it dispatches a standard media key
    // through the AudioManager to the active media session, so it is resolved before
    // the a11y-service gate (a UI tap cannot reach an arbitrary player's transport).
    // An unwired controller gets an honest "unavailable" result, never a silent no-op.
    private val mediaController: MediaController? = null,
    // The device ringer mode is a system audio setting (AudioManager), not an
    // accessibility-tree control, so like the stream/media primitives it is resolved
    // before the a11y-service gate. An unwired controller gets an honest "unavailable"
    // result, never a silent no-op.
    private val ringerModeController: RingerModeController? = null,
    // Device status (battery/screen/ringer/connectivity) is read from system services,
    // not the accessibility tree, so it is resolved before the a11y-service gate (same
    // rule as ReadNotifications/AdjustVolume/PlayMedia). An unwired reader returns an
    // honest "unavailable" result rather than a fabricated status.
    private val deviceStatusReader: DeviceStatusReader? = null,
    // The clock alarm is dispatched through the platform AlarmClock intent, not an
    // accessibility-tree control, so like the audio/ringer primitives it is resolved
    // before the a11y-service gate. An unwired controller gets an honest "unavailable"
    // result, never a silent no-op.
    private val alarmController: AlarmController? = null,
    // The countdown timer is likewise dispatched through the platform AlarmClock intent
    // (ACTION_SET_TIMER), service-free, so it is resolved before the a11y-service gate
    // exactly like the alarm. An unwired controller gets an honest "unavailable" result.
    private val timerController: TimerController? = null,
    // Adding a calendar event is dispatched through the platform CalendarContract
    // ACTION_INSERT intent (the calendar's own editor, pre-filled), not an
    // accessibility-tree control, so like the alarm/timer primitives it is resolved
    // before the a11y-service gate. An unwired controller gets an honest "unavailable"
    // result, never a silent no-op.
    private val calendarController: CalendarController? = null,
    // Adding a contact is dispatched through the platform ContactsContract insert
    // intent (the contacts app's own editor, pre-filled), not an accessibility-tree
    // control, so like the calendar/alarm/timer primitives it is resolved before the
    // a11y-service gate. An unwired controller gets an honest "unavailable" result,
    // never a silent no-op.
    private val contactController: ContactController? = null,
    // Opening the dialer is dispatched through the platform ACTION_DIAL intent (a
    // `tel:` URI), not an accessibility-tree control, so like the contact/calendar
    // primitives it is resolved before the a11y-service gate. An unwired controller
    // gets an honest "unavailable" result, never a silent no-op.
    private val dialerController: DialerController? = null,
    // Opening the messaging compose screen is dispatched through the platform
    // ACTION_SENDTO intent (`smsto:` URI + `"sms_body"`), not an accessibility-tree
    // control, so like the dialer/contact/calendar primitives it is resolved before
    // the a11y-service gate. An unwired controller gets an honest "unavailable"
    // result, never a silent no-op. It opens the compose screen pre-filled for the
    // user to press send; it never sends the message itself.
    private val smsController: SmsController? = null,
    // Sharing text is dispatched through the platform ACTION_SEND intent (a
    // `text/plain` payload), not an accessibility-tree control, so like the
    // SMS/dialer/contact/calendar primitives it is resolved before the a11y-service
    // gate. An unwired controller gets an honest "unavailable" result, never a silent
    // no-op. It opens the share sheet for the user to choose a destination and confirm;
    // it never posts anywhere by itself.
    private val shareController: ShareController? = null,
    // Composing an email is dispatched through the platform ACTION_SENDTO intent
    // (`mailto:` URI), not an accessibility-tree control, so like the SMS/dialer/
    // share primitives it is resolved before the a11y-service gate. An unwired
    // controller gets an honest "unavailable" result, never a silent no-op. It opens
    // the mail compose screen pre-filled for the user to press send; it never sends
    // the message itself.
    private val emailController: EmailController? = null,
    // Opening the map is dispatched through the platform ACTION_VIEW intent (a
    // `geo:0,0?q=` URI), not an accessibility-tree control, so like the SMS/dialer/
    // share/email primitives it is resolved before the a11y-service gate. An unwired
    // controller gets an honest "unavailable" result, never a silent no-op. It opens
    // the map at the place for the user to start directions; it never begins
    // navigating by itself.
    private val navigationController: NavigationController? = null,
    // Opening an app's Settings/App-info page is dispatched through the platform
    // ACTION_APPLICATION_DETAILS_SETTINGS intent, not an accessibility-tree control, so
    // like the SMS/dialer/share/email/navigation primitives it is resolved before the
    // a11y-service gate. An unwired controller gets an honest "unavailable" result,
    // never a silent no-op. It only opens the page; it never changes anything there.
    private val appInfoController: AppInfoController? = null,
    // The torch/flashlight is a camera-hardware setting (CameraManager.setTorchMode),
    // not an accessibility-tree control, so like the audio/ringer primitives it is
    // resolved before the a11y-service gate. An unwired controller gets an honest
    // "unavailable" result, never a silent no-op. The controller reads the state back,
    // so an ignored toggle is reported as failure, not a fake success.
    private val torchController: TorchController? = null,
    // On-device speech output is a platform TTS service, not an accessibility-tree
    // control, so it is resolved before the a11y-service gate. It is opt-in: the
    // controller refuses honestly when speech output is off, and an unwired controller
    // gets an honest "unavailable" result rather than a silent no-op.
    private val speakController: SpeakController? = null,
    // Opening a named system Settings panel is dispatched through the platform's
    // documented Settings.ACTION_* intent, not an accessibility-tree control, so like the
    // app-info/audio primitives it is resolved before the a11y-service gate. An unwired
    // controller gets an honest "unavailable" result, never a silent no-op. It only opens
    // the panel; it never changes anything there.
    private val settingsController: SettingsController? = null,
    // Opening the calendar is dispatched through the platform's documented calendar time
    // URI (with a CATEGORY_APP_CALENDAR launch fallback), not an accessibility-tree
    // control, so like the app-info/settings primitives it is resolved before the
    // a11y-service gate. An unwired viewer gets an honest "unavailable" result, never a
    // silent no-op. It only opens the calendar; it never adds or changes an event.
    private val calendarViewer: CalendarViewer? = null,
    // create_note dispatches the notes app's own compose intent, not an accessibility-tree
    // control, so like the contact/calendar inserts it is resolved before the a11y-service
    // gate. An unwired controller gets an honest "unavailable" result, never a silent no-op.
    private val noteController: NoteController? = null,
    // pick_file dispatches the platform's own document picker (Intent.ACTION_GET_CONTENT),
    // not an accessibility-tree control, so like create_note it is resolved before the
    // a11y-service gate. An unwired controller gets an honest "unavailable" result, never a
    // silent no-op. It only opens the picker for the user to choose; it reads/deletes nothing.
    private val filePickerController: FilePickerController? = null,
    // summarize_text condenses a block of text through the configured summarizer (the
    // remote LLM API), not the accessibility tree, so like Speak/ReadNotifications it is
    // resolved before the a11y-service gate. An unwired summarizer gets an honest
    // "unavailable" result, never a fabricated digest.
    private val nanoSummarizer: NanoSummarizer = com.example.model.UnavailableNanoSummarizer,
    // Do Not Disturb is a system notification-policy setting (NotificationManager), not an
    // accessibility-tree control, so like the ringer/torch primitives it is resolved before
    // the a11y-service gate. An unwired controller gets an honest "unavailable" result,
    // never a silent no-op. The controller reads the interruption filter back, so an
    // ignored change (or missing policy access) is reported as failure, not a fake success.
    private val dndController: DndController? = null,
    // The screen brightness is a system display setting (Settings.System), not an
    // accessibility-tree control, so like the ringer/torch/dnd primitives it is resolved
    // before the a11y-service gate. An unwired controller gets an honest "unavailable"
    // result, never a silent no-op. The controller reads the level back, so an ignored write
    // (or missing WRITE_SETTINGS access) is reported as failure, not a fake success.
    private val screenBrightnessController: ScreenBrightnessController? = null,
    // The audio output route is a system audio setting (AudioManager), not an
    // accessibility-tree control, so like the ringer/torch/dnd/brightness primitives it is
    // resolved before the a11y-service gate. An unwired controller gets an honest
    // "unavailable" result, never a silent no-op. The controller reads the active route
    // back, so a request the device ignored is reported as failure, not a fake success.
    private val audioDeviceController: AudioDeviceController? = null,
    // Whether the screen follows the device orientation is a system display setting
    // (Settings.System.ACCELEROMETER_ROTATION), not an accessibility-tree control, so like
    // the ringer/torch/dnd/brightness primitives it is resolved before the a11y-service
    // gate. An unwired controller gets an honest "unavailable" result, never a silent
    // no-op. The controller reads the state back, so an ignored write (or missing
    // WRITE_SETTINGS access) is reported as failure, not a fake success.
    private val autoRotateController: AutoRotateController? = null,
    // The clipboard is a system service (ClipboardManager), not an accessibility-tree
    // control, so like the ringer/torch/dnd/brightness primitives it is resolved before
    // the a11y-service gate. It is opt-in: the executor refuses honestly when clipboard
    // reading is off (the clipboard can hold another app's secret), and an unwired reader
    // gets an honest "unavailable" result rather than a silent no-op. Its non-empty text
    // is carried as readText so a NONE-verified read self-satisfies.
    private val clipboardReader: ClipboardReader? = null,
    private val clipboardReadEnabled: () -> Boolean = { false },
    // The clipboard write half (set_clipboard): the same ClipboardManager service, so it
    // is likewise resolved before the a11y-service gate. Opt-in too (a live
    // clipboardWriteEnabled gate), and an unwired writer gets an honest "unavailable"
    // result rather than a silent no-op. The write is read back, so success is reported
    // only when the clipboard actually holds the text.
    private val clipboardWriter: ClipboardWriter? = null,
    private val clipboardWriteEnabled: () -> Boolean = { false }
) : DeviceAutomation {

    companion object {
        /** Poll interval while waiting for the screen fingerprint to settle. */
        private const val SETTLE_POLL_MS = 150L

        /** Upper bound on how long to wait for the screen to settle before observing. */
        private const val SETTLE_BUDGET_MS = 900L
    }

    override fun isReady(): Boolean = serviceProvider() != null

    override fun unavailabilityReason(): String? =
        if (serviceProvider() == null) {
            "Accessibility Service is not enabled. Enable Jarvis in Android Settings > Accessibility."
        } else {
            null
        }

    override suspend fun observe(): UiObservation = withContext(Dispatchers.Default) {
        settleAndCompose()
    }

    /**
     * Reads the hierarchy repeatedly until its fingerprint stops changing or
     * [SETTLE_BUDGET_MS] elapses, then composes the final observation. This replaces a
     * single fixed-delay read: a slow surface is waited out (bounded), while an
     * already-stable screen returns after one poll.
     */
    private suspend fun settleAndCompose(): UiObservation {
        val service = serviceProvider()
            ?: return UiObservation(null, emptyList(), "Screen unavailable", "unavailable")
        var snapshot = service.dumpHierarchy()
        val deadline = System.currentTimeMillis() + SETTLE_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            delay(SETTLE_POLL_MS)
            val next = service.dumpHierarchy()
            if (next.getFingerprint() == snapshot.getFingerprint()) break
            snapshot = next
        }
        return composeObservation(snapshot)
    }

    /**
     * Composes the observation from the settled snapshot. Kept as a seam so extra
     * perception (page text, screenshot grounding) can be layered in here without
     * touching the settle loop.
     */
    private suspend fun composeObservation(snapshot: UiTreeSnapshot): UiObservation {
        val base = UiObservation.fromSnapshot(snapshot)
        // 7.2/7.3: when the settled tree is blind (no nodes at all), fall back to OCR
        // over a bounded screenshot so a canvas/WebView surface still yields text the
        // planner can reason about. Only attempted when there is nothing in the tree.
        if (base.nodes.isNotEmpty()) return base
        val service = serviceProvider() ?: return base
        val bitmap = try { service.takeScreenshotFallback() } catch (e: Exception) { null }
        if (bitmap == null) return base
        val text = try { ocrReader.readText(bitmap) } catch (e: Exception) { "" }
        if (text.isBlank()) return base
        return base.copy(summary = (base.summary + "\n[OCR]\n" + text).trim())
    }

    override suspend fun execute(action: AgentAction): ActionResult = withContext(Dispatchers.Default) {
        // Notification triage is independent of the accessibility tree: it reads the
        // bounded NotificationBus digest, so it is resolved before the service gate
        // (otherwise an opted-in read would be misreported as NO_SERVICE when the a11y
        // service happens to be down).
        if (action is AgentAction.ReadNotifications) {
            // 9.9: consent before capability. Refuse honestly when triage is off.
            if (!triageEnabled()) {
                return@withContext ActionResult(
                    false,
                    "TRIAGE_DISABLED",
                    "Notification triage is off. Enable it in Settings to summarise notifications."
                )
            }
            return@withContext readNotificationDigest()
        }
        // Device audio is likewise service-free: it changes a system stream through the
        // AudioManager, so it is resolved before the a11y-service gate. This is the media
        // control primitive the planner advertises; without it the model could plan a
        // volume change that the execution layer silently refused as UNKNOWN.
        if (action is AgentAction.AdjustVolume) {
            val stream = AudioStream.fromKey(action.stream)
                ?: return@withContext ActionResult(false, "INVALID_STREAM", "Unknown audio stream '${action.stream}'")
            val controller = audioController
                ?: return@withContext ActionResult(false, "AUDIO_UNAVAILABLE", "Device audio control is not available")
            val result = controller.adjust(stream, action.steps, action.mute)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Media transport control is the playback primitive behind "pause the music" /
        // "next track": a standard media key to the active session, service-free like the
        // audio stream above.
        if (action is AgentAction.PlayMedia) {
            val transport = MediaTransport.fromKey(action.transport)
                ?: return@withContext ActionResult(false, "INVALID_TRANSPORT", "Unknown media transport '${action.transport}'")
            val controller = mediaController
                ?: return@withContext ActionResult(false, "MEDIA_UNAVAILABLE", "Media control is not available")
            val result = controller.control(transport)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Ringer mode is likewise service-free: it changes a system audio setting through
        // the AudioManager, so it is resolved before the a11y-service gate. This is the
        // "put my phone on silent" primitive the planner advertises.
        if (action is AgentAction.SetRingerMode) {
            val mode = RingerMode.fromKey(action.mode)
                ?: return@withContext ActionResult(false, "INVALID_RINGER_MODE", "Unknown ringer mode '${action.mode}'")
            val controller = ringerModeController
                ?: return@withContext ActionResult(false, "RINGER_UNAVAILABLE", "Ringer mode control is not available")
            val result = controller.set(mode)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Device status is likewise service-free: it reads system services, so it is
        // resolved before the a11y-service gate. The digest is carried as readText so a
        // NONE-verified read_device_status step can self-satisfy (same rule as the
        // other read actions) instead of retrying to exhaustion.
        if (action is AgentAction.ReadDeviceStatus) {
            val reader = deviceStatusReader
                ?: return@withContext ActionResult(false, "STATUS_UNAVAILABLE", "Device status read is not available")
            val result = reader.read()
            val digest = result.message.take(ActionResult.MAX_CAPTURED_CHARS).ifBlank { null }
            return@withContext ActionResult(result.success, result.method, result.message, digest)
        }
        // The clipboard is likewise service-free: it reads the system ClipboardManager,
        // so it is resolved before the a11y-service gate. This is the read half of the
        // copy/paste duo "read the code I just copied"; opt-in (SECRETS), and the
        // non-empty text is carried as readText so a NONE-verified step self-satisfies.
        if (action is AgentAction.ReadClipboard) {
            if (!clipboardReadEnabled()) {
                return@withContext ActionResult(
                    false,
                    "CLIPBOARD_READ_DISABLED",
                    "Reading the clipboard is off. Enable it in Settings to read clipboard text."
                )
            }
            val reader = clipboardReader
                ?: return@withContext ActionResult(false, "CLIPBOARD_UNAVAILABLE", "Clipboard read is not available")
            val result = reader.read()
            val digest = result.message.take(ActionResult.MAX_CAPTURED_CHARS).ifBlank { null }
            return@withContext ActionResult(result.success, result.method, result.message, digest)
        }
        // The clipboard write is likewise service-free (the same ClipboardManager), so it
        // is resolved before the a11y-service gate. This is the write half of the
        // copy/paste duo "copy this to the clipboard"; opt-in (SECRETS), and the write is
        // read back by the writer, so a write that did not take fails the step.
        if (action is AgentAction.SetClipboard) {
            if (!clipboardWriteEnabled()) {
                return@withContext ActionResult(
                    false,
                    "CLIPBOARD_WRITE_DISABLED",
                    "Writing the clipboard is off. Enable it in Settings to copy text to the clipboard."
                )
            }
            val writer = clipboardWriter
                ?: return@withContext ActionResult(false, "CLIPBOARD_WRITE_UNAVAILABLE", "Clipboard write is not available")
            val result = writer.write(action.text)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // The clock alarm is likewise service-free: it is dispatched through the platform
        // AlarmClock intent, so it is resolved before the a11y-service gate. This is the
        // "wake me at 7am" primitive the planner advertises.
        if (action is AgentAction.SetAlarm) {
            if (action.hour !in 0..23 || action.minute !in 0..59) {
                return@withContext ActionResult(false, "INVALID_ALARM_TIME", "Invalid alarm time ${action.hour}:${action.minute}")
            }
            val controller = alarmController
                ?: return@withContext ActionResult(false, "ALARM_UNAVAILABLE", "Alarm scheduling is not available")
            val result = controller.setAlarm(action.hour, action.minute, action.label, action.days)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // The countdown timer is likewise service-free: it is dispatched through the
        // platform AlarmClock ACTION_SET_TIMER intent, so it is resolved before the
        // a11y-service gate. This is the "set a 10-minute timer" primitive the planner
        // advertises, and it is distinct from an alarm (which fires at a clock time).
        if (action is AgentAction.SetTimer) {
            if (action.seconds <= 0 || action.seconds > com.example.agent.ToolRegistry.MAX_TIMER_SECONDS) {
                return@withContext ActionResult(false, "INVALID_TIMER_DURATION", "Invalid timer duration ${action.seconds}s")
            }
            val controller = timerController
                ?: return@withContext ActionResult(false, "TIMER_UNAVAILABLE", "Timer scheduling is not available")
            val result = controller.setTimer(action.seconds, action.label)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Adding a calendar event is likewise service-free: it is dispatched through the
        // platform CalendarContract ACTION_INSERT intent, so it is resolved before the
        // a11y-service gate. This is the "add lunch with Sam tomorrow at 1pm" primitive
        // the planner advertises. Like set_alarm it reports only that the intent was
        // delivered to a calendar app (the event is created by a separate process).
        if (action is AgentAction.AddCalendarEvent) {
            if (action.title.isBlank()) {
                return@withContext ActionResult(false, "INVALID_EVENT_TITLE", "Calendar event title must not be blank")
            }
            if (action.dayOffset !in 0..com.example.agent.ToolRegistry.MAX_EVENT_DAY_OFFSET) {
                return@withContext ActionResult(false, "INVALID_EVENT_DAY", "Invalid event day offset ${action.dayOffset}")
            }
            if (action.startHour !in 0..23 || action.startMinute !in 0..59) {
                return@withContext ActionResult(false, "INVALID_EVENT_TIME", "Invalid event start time ${action.startHour}:${action.startMinute}")
            }
            if (action.durationMinutes <= 0 || action.durationMinutes > com.example.agent.ToolRegistry.MAX_EVENT_DURATION_MINUTES) {
                return@withContext ActionResult(false, "INVALID_EVENT_DURATION", "Invalid event duration ${action.durationMinutes}m")
            }
            val controller = calendarController
                ?: return@withContext ActionResult(false, "CALENDAR_UNAVAILABLE", "Calendar event insert is not available")
            val result = controller.addEvent(
                action.title,
                action.dayOffset,
                action.startHour,
                action.startMinute,
                action.durationMinutes,
                action.location
            )
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Adding a contact is likewise service-free: it is dispatched through the
        // platform ContactsContract insert intent, so it is resolved before the
        // a11y-service gate. This is the "save Priya's number 98xxxxxx" primitive.
        // Like add_calendar_event it reports only that the intent was delivered to a
        // contacts app (the contact is created by a separate process).
        if (action is AgentAction.AddContact) {
            if (action.name.isBlank()) {
                return@withContext ActionResult(false, "INVALID_CONTACT_NAME", "Contact name must not be blank")
            }
            if (action.phone.isNullOrBlank() && action.email.isNullOrBlank()) {
                return@withContext ActionResult(false, "INVALID_CONTACT_DETAILS", "A contact needs at least a phone number or an email")
            }
            val controller = contactController
                ?: return@withContext ActionResult(false, "CONTACT_UNAVAILABLE", "Contact insert is not available")
            val result = controller.addContact(action.name, action.phone, action.email)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Opening the dialer is likewise service-free: it is dispatched through the
        // platform ACTION_DIAL intent with a `tel:` URI, so it is resolved before the
        // a11y-service gate. This is the "dial 9876543210" / "call Priya" primitive.
        // It opens the dialer for the user to confirm and never places the call, so it
        // reports only that the intent was delivered to a dialer (like add_contact).
        if (action is AgentAction.DialNumber) {
            if (action.number.isBlank()) {
                return@withContext ActionResult(false, "INVALID_DIAL_NUMBER", "Dial number must not be blank")
            }
            val controller = dialerController
                ?: return@withContext ActionResult(false, "DIAL_UNAVAILABLE", "Dialer dispatch is not available")
            val result = controller.dial(action.number)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Opening the messaging compose screen is likewise service-free: it is
        // dispatched through the platform ACTION_SENDTO intent (`smsto:` URI +
        // `"sms_body"`), so it is resolved before the a11y-service gate. This is the
        // "text Priya that I'll be late" primitive. It opens the compose screen
        // pre-filled for the user to press send and never sends the message, so it
        // reports only that the intent was delivered to a messaging app.
        if (action is AgentAction.SendSms) {
            if (action.number.isBlank()) {
                return@withContext ActionResult(false, "INVALID_SMS_NUMBER", "SMS number must not be blank")
            }
            if (action.message.isBlank()) {
                return@withContext ActionResult(false, "INVALID_SMS_MESSAGE", "SMS message must not be blank")
            }
            val controller = smsController
                ?: return@withContext ActionResult(false, "SMS_UNAVAILABLE", "SMS compose dispatch is not available")
            val result = controller.compose(action.number, action.message)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Sharing text is likewise service-free: it is dispatched through the platform
        // ACTION_SEND intent with a `text/plain` payload, so it is resolved before the
        // a11y-service gate. This is the "share this with WhatsApp" primitive. It opens
        // the share sheet (optionally addressed to a named app) for the user to choose a
        // destination and confirm and never posts anywhere by itself, so it reports only
        // that the intent was delivered.
        if (action is AgentAction.ShareText) {
            if (action.text.isBlank()) {
                return@withContext ActionResult(false, "INVALID_SHARE_TEXT", "Share text must not be blank")
            }
            val controller = shareController
                ?: return@withContext ActionResult(false, "SHARE_UNAVAILABLE", "Share dispatch is not available")
            val result = controller.share(action.text, action.targetPackage)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Composing an email is likewise service-free: it is dispatched through the
        // platform ACTION_SENDTO intent (`mailto:` URI), so it is resolved before the
        // a11y-service gate. This is the "email Sam that the report is ready" primitive.
        // It opens the mail compose screen pre-filled for the user to press send and
        // never sends the message, so it reports only that the intent was delivered.
        if (action is AgentAction.SendEmail) {
            if (action.to.isBlank()) {
                return@withContext ActionResult(false, "INVALID_EMAIL_ADDRESS", "Email recipient must not be blank")
            }
            val controller = emailController
                ?: return@withContext ActionResult(false, "EMAIL_UNAVAILABLE", "Email compose dispatch is not available")
            val result = controller.compose(action.to, action.subject, action.body)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Opening the map is likewise service-free: it is dispatched through the
        // platform ACTION_VIEW intent with a `geo:0,0?q=` URI, so it is resolved before
        // the a11y-service gate. This is the "navigate to a place" primitive. It opens
        // the map showing the place for the user to start directions and never begins
        // navigating by itself, so it reports only that the intent was delivered.
        if (action is AgentAction.NavigateTo) {
            if (action.target.isBlank()) {
                return@withContext ActionResult(false, "INVALID_NAV_TARGET", "Navigation target must not be blank")
            }
            val controller = navigationController
                ?: return@withContext ActionResult(false, "NAV_UNAVAILABLE", "Navigation dispatch is not available")
            val result = controller.navigate(action.target)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Opening an app's Settings/App-info page is likewise service-free: it is
        // dispatched through the platform ACTION_APPLICATION_DETAILS_SETTINGS intent, so
        // it is resolved before the a11y-service gate. This is the "open Chrome's
        // settings" / "show WhatsApp app info" primitive. The plan may carry an app
        // alias (e.g. "chrome"); it is resolved to a real installed package through the
        // same AppDirectory used by launch_app, so the model can name the app naturally.
        // It opens the page for the user to act on and changes nothing itself, so it
        // reports only that the intent was delivered.
        if (action is AgentAction.OpenAppInfo) {
            if (action.packageName.isBlank()) {
                return@withContext ActionResult(false, "INVALID_APP_INFO_PACKAGE", "App package must not be blank")
            }
            val controller = appInfoController
                ?: return@withContext ActionResult(false, "APP_INFO_UNAVAILABLE", "App-info dispatch is not available")
            // If the plan carried a package name, use it as-is; otherwise resolve the
            // value as an installed app (label/alias/package substring) via AppDirectory.
            val resolved = if (action.packageName.contains('.')) {
                action.packageName
            } else {
                appDirectory.resolvePackage(action.packageName) ?: action.packageName
            }
            val result = controller.openAppInfo(resolved)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // The torch/flashlight is likewise service-free: it is driven through the
        // CameraManager, so it is resolved before the a11y-service gate. This is the
        // "turn on the flashlight" / "switch the torch off" primitive. The controller
        // reads the torch state back, so an ignored request surfaces as TORCH_UNCHANGED
        // (a failure) rather than a fake success.
        if (action is AgentAction.SetTorch) {
            val controller = torchController
                ?: return@withContext ActionResult(false, "TORCH_UNAVAILABLE", "Flashlight control is not available")
            val result = controller.setTorch(action.on)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // set_device_control drives a device control from an explicit control/value pair,
        // so a scheduled automation has a declarative action it can replay. It resolves the
        // pair through DeviceControl first (an unknown control or a value invalid for that
        // control is refused honestly, no dispatch), then delegates to the existing honest
        // read-back controller for the named control: torch -> TorchController, ringer_mode
        // -> RingerModeController, dnd -> DndController, brightness -> ScreenBrightnessController,
        // audio_device -> AudioDeviceController, auto_rotate -> AutoRotateController. Like
        // set_torch/set_ringer_mode it is resolved before the a11y-service gate (all are
        // system-service backed, not tree-backed).
        if (action is AgentAction.SetDeviceControl) {
            when (val op = DeviceControl.resolve(action.control, action.value)) {
                is DeviceControlOp.Torch -> {
                    val controller = torchController
                        ?: return@withContext ActionResult(false, "TORCH_UNAVAILABLE", "Flashlight control is not available")
                    val result = controller.setTorch(op.on)
                    return@withContext ActionResult(result.success, result.method, result.message)
                }
                is DeviceControlOp.RingerMode -> {
                    val controller = ringerModeController
                        ?: return@withContext ActionResult(false, "RINGER_UNAVAILABLE", "Ringer mode control is not available")
                    val result = controller.set(op.mode)
                    return@withContext ActionResult(result.success, result.method, result.message)
                }
                is DeviceControlOp.Dnd -> {
                    val controller = dndController
                        ?: return@withContext ActionResult(false, "DND_UNAVAILABLE", "Do Not Disturb control is not available")
                    val result = controller.set(op.mode)
                    return@withContext ActionResult(result.success, result.method, result.message)
                }
                is DeviceControlOp.Brightness -> {
                    val controller = screenBrightnessController
                        ?: return@withContext ActionResult(false, "BRIGHTNESS_UNAVAILABLE", "Screen brightness control is not available")
                    val result = controller.set(op.target)
                    return@withContext ActionResult(result.success, result.method, result.message)
                }
                is DeviceControlOp.AudioDevice -> {
                    val controller = audioDeviceController
                        ?: return@withContext ActionResult(false, "AUDIO_DEVICE_UNAVAILABLE", "Audio output control is not available")
                    val result = controller.set(op.mode)
                    return@withContext ActionResult(result.success, result.method, result.message)
                }
                is DeviceControlOp.AutoRotate -> {
                    val controller = autoRotateController
                        ?: return@withContext ActionResult(false, "AUTO_ROTATE_UNAVAILABLE", "Screen auto-rotation control is not available")
                    val result = controller.set(op.mode)
                    return@withContext ActionResult(result.success, result.method, result.message)
                }
                null -> return@withContext ActionResult(
                    false, "UNSUPPORTED_DEVICE_CONTROL",
                    "Unknown device control '${action.control}'='${action.value}'; expected control=${DeviceControl.keys().joinToString("/")} and a value valid for it"
                )
            }
        }
        // Speech output is likewise service-free: it drives the platform TTS engine, so
        // it is resolved before the a11y-service gate. This is the "say good morning" /
        // "tell me what it says" primitive. The controller reports success only once the
        // engine accepted (and finished) the utterance, so a broken/disabled engine is an
        // honest failure, never a fake "spoken".
        if (action is AgentAction.Speak) {
            val controller = speakController
                ?: return@withContext ActionResult(false, "SPEAK_UNAVAILABLE", "Speech output is not available")
            val result = controller.speak(action.text)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Opening a named system Settings panel is likewise service-free: it is dispatched
        // through the platform's documented Settings.ACTION_* intent, so it is resolved
        // before the a11y-service gate. This is the "open wifi settings" / "show bluetooth
        // settings" / "open display settings" primitive. The panel is a closed set
        // (SettingsPanel); an unknown panel is refused honestly and no intent is built.
        // It only opens the panel for the user to act on and changes nothing itself, so it
        // reports only that the intent was delivered.
        if (action is AgentAction.OpenSettings) {
            val panel = SettingsPanel.fromKey(action.panel)
                ?: return@withContext ActionResult(
                    false, "INVALID_SETTINGS_PANEL",
                    "Unknown settings panel '${action.panel}'; expected one of: ${SettingsPanel.keys().joinToString(", ")}"
                )
            val controller = settingsController
                ?: return@withContext ActionResult(false, "SETTINGS_UNAVAILABLE", "Settings dispatch is not available")
            val result = controller.openPanel(panel)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // Opening the calendar is likewise service-free: it is dispatched through the
        // platform's documented calendar time URI (with a calendar-app launch fallback),
        // so it is resolved before the a11y-service gate. This is the "open my calendar"
        // primitive; it only opens the calendar for the user to look at and changes
        // nothing itself, so it reports only that the intent was delivered.
        if (action is AgentAction.OpenCalendar) {
            val viewer = calendarViewer
                ?: return@withContext ActionResult(false, "CALENDAR_UNAVAILABLE", "Calendar dispatch is not available")
            val result = viewer.openCalendar(System.currentTimeMillis(), action.dayOffset)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // create_note is an intent-dispatch primitive like add_contact/add_calendar_event:
        // the notes app's own editor is a separate process, and no screen read or tap adds a
        // note reliably on an arbitrary screen, so it is resolved before the a11y-service
        // gate. It reports only that the compose intent was delivered.
        if (action is AgentAction.Note) {
            val creator = noteController
                ?: return@withContext ActionResult(false, "NOTE_UNAVAILABLE", "Note dispatch is not available")
            val result = creator.createNote(action.title, action.content)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // dictate_note is the same intent-dispatch primitive as create_note (the notes app's
        // own editor, a separate process), but it carries only an optional title and no body,
        // so the notes app opens its editor ready for the user to speak. Resolved before the
        // a11y-service gate; it reports only that the intent was delivered.
        if (action is AgentAction.DictateNote) {
            val creator = noteController
                ?: return@withContext ActionResult(false, "NOTE_UNAVAILABLE", "Note dispatch is not available")
            val result = creator.dictateNote(action.title)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // pick_file is an intent-dispatch primitive like create_note: the platform document
        // picker is a separate process and no screen read or tap opens it reliably on an
        // arbitrary screen, so it is resolved before the a11y-service gate. It reports only
        // that the picker intent was delivered; it never reads or deletes a file.
        if (action is AgentAction.PickFile) {
            val picker = filePickerController
                ?: return@withContext ActionResult(false, "FILE_PICKER_UNAVAILABLE", "File picker dispatch is not available")
            val result = picker.pickFile(action.mimeType, action.allowMultiple)
            return@withContext ActionResult(result.success, result.method, result.message)
        }
        // summarize_text condenses a block of text through the configured summarizer (the
        // remote LLM API), not the accessibility tree, so like Speak it is resolved before
        // the a11y-service gate. The digest is carried as readText so it flows into a later
        // step as a `{{name}}` variable and self-evidences this NONE-verified step; an
        // unwired summarizer and a failed/empty generation are honest failures, never a
        // fabricated summary. The input is validated (non-blank, bounded) before any call.
        if (action is AgentAction.SummarizeText) {
            if (action.text.isBlank()) {
                return@withContext ActionResult(false, "INVALID_SUMMARY_INPUT", "summarize_text needs non-blank text")
            }
            if (!nanoSummarizer.isAvailable()) {
                return@withContext ActionResult(
                    false, "SUMMARY_UNAVAILABLE",
                    "No summarizer is configured. Add an API key in Setup to summarise text."
                )
            }
            val maxChars = action.maxChars.coerceIn(ToolRegistry.MIN_SUMMARY_CHARS, ToolRegistry.MAX_SUMMARY_CHARS)
            val digest = try {
                nanoSummarizer.summarize(action.text, maxChars)
            } catch (e: Exception) {
                null
            }
            return@withContext if (digest.isNullOrBlank()) {
                ActionResult(false, "SUMMARY_FAILED", "The summarizer returned no summary")
            } else {
                ActionResult(
                    success = true,
                    method = "SUMMARY_OK",
                    message = digest,
                    readText = digest.take(ActionResult.MAX_CAPTURED_CHARS)
                )
            }
        }
        val service = serviceProvider()
            ?: return@withContext ActionResult(false, "NO_SERVICE", "Accessibility Service unavailable")

        val preFingerprint = service.dumpHierarchy().getFingerprint()
        val uiChanged: suspend () -> Boolean = {
            delay(250)
            service.dumpHierarchy().getFingerprint() != preFingerprint
        }

        when (action) {
            is AgentAction.OpenApp -> executeOpenApp(action, service)
            is AgentAction.Tap -> {
                val r = service.performTap(action.targetText, action.x, action.y, uiChanged)
                if (r.success) {
                    ActionResult(true, r.method, "Tap succeeded via ${r.method}")
                } else {
                    // 7.2: the tree could not represent the target. Try vision grounding
                    // (OCR-backed) once and tap the located point instead of giving up.
                    val grounded = visionTap(action.targetText, service, uiChanged)
                    if (grounded != null) {
                        grounded
                    } else {
                        ActionResult(false, r.method, r.failureReason ?: "Tap failed")
                    }
                }
            }
            is AgentAction.OpenItem -> {
                // Reacquire the node from the live tree by its label; never reuse a stale node.
                val r = service.performTap(action.targetText, null, null, uiChanged)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Opened item '${action.targetText}' via ${r.method}" else (r.failureReason ?: "Open item failed")
                )
            }
            is AgentAction.TypeText -> {
                val r = service.performTextInput(action.text, action.targetNodeId)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Typed \"${action.text}\" via ${r.method}" else (r.failureReason ?: "Text input failed")
                )
            }
            is AgentAction.ClearText -> {
                // ACTION_SET_TEXT with an empty value empties the field and is verified
                // the same way as a normal entry (the field must read back empty).
                val r = service.performTextInput("", action.targetText)
                ActionResult(
                    success = r.success,
                    method = "CLEAR_TEXT",
                    message = if (r.success) "Cleared '${action.targetText ?: "focused field"}'" else (r.failureReason ?: "Clear field failed")
                )
            }
            is AgentAction.OpenSearch -> {
                val r = service.openSearchInterface(action.query)
                if (r.success) {
                    ActionResult(true, r.method, r.message)
                } else if (!action.query.isNullOrBlank()) {
                    // Bounded fallback: the accessibility tree exposed no search
                    // affordance. Use the app's own ACTION_SEARCH intent (existing
                    // supported capability) rather than tapping blind coordinates.
                    val foreground = service.dumpHierarchy().packageName
                    val launched = foreground != null && appDirectory.launchSearch(action.query, foreground)
                    if (launched) {
                        delay(800)
                        ActionResult(true, "NATIVE_SEARCH_INTENT", "Opened search via ACTION_SEARCH for '${action.query}'")
                    } else {
                        ActionResult(false, r.method, r.message)
                    }
                } else {
                    ActionResult(false, r.method, r.message)
                }
            }
            is AgentAction.Scroll -> ActionResult(service.scroll(action.direction), "SCROLL", "Scrolled ${action.direction}")
            is AgentAction.LongPress -> {
                val r = service.performLongPress(action.targetText.orEmpty(), action.durationMs)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Long-pressed '${action.targetText}' via ${r.method}" else (r.failureReason ?: "Long press failed")
                )
            }
            is AgentAction.ScrollUntilFound -> executeScrollUntilFound(action, service)
            is AgentAction.FindElement -> executeFindElement(action, service)
            is AgentAction.ReadText -> {
                val text = service.readVisibleText()
                ActionResult(
                    success = text.isNotBlank(),
                    method = "READ_TEXT",
                    message = if (text.isNotBlank()) "Read ${text.length} chars of visible text" else "No visible text found on screen",
                    readText = text.take(ActionResult.MAX_CAPTURED_CHARS).takeIf { it.isNotBlank() }
                )
            }
            is AgentAction.WaitForText -> {
                val deadline = System.currentTimeMillis() + action.timeoutMs
                var found = service.isTextVisible(action.targetText)
                while (!found && System.currentTimeMillis() < deadline) {
                    delay(250)
                    found = service.isTextVisible(action.targetText)
                }
                ActionResult(
                    success = found,
                    method = "WAIT_FOR_TEXT",
                    message = if (found) "Text '${action.targetText}' appeared" else "Text '${action.targetText}' did not appear within ${action.timeoutMs}ms"
                )
            }
            is AgentAction.OpenNotifications -> ActionResult(service.openNotifications(), "GLOBAL_NOTIFICATIONS", "Opened notification shade")
            is AgentAction.OpenQuickSettings -> ActionResult(service.openQuickSettings(), "GLOBAL_QUICK_SETTINGS", "Opened quick settings")
            is AgentAction.PressRecents -> ActionResult(service.pressRecents(), "GLOBAL_RECENTS", "Opened recents")
            is AgentAction.OpenPowerDialog -> ActionResult(service.pressPowerDialog(), "GLOBAL_POWER_DIALOG", "Opened the power menu")
            is AgentAction.Back -> ActionResult(service.pressBack(), "GLOBAL_BACK", "Pressed Back")
            is AgentAction.Home -> ActionResult(service.pressHome(), "GLOBAL_HOME", "Pressed Home")
            is AgentAction.Wait -> {
                delay(action.durationMs)
                ActionResult(true, "WAIT", "Waited ${action.durationMs}ms")
            }
            is AgentAction.ReadScreen -> ActionResult(true, "READ", "Screen hierarchy refreshed")
            is AgentAction.Screenshot -> {
                val bmp = service.takeScreenshotFallback()
                ActionResult(bmp != null, "SCREENSHOT", if (bmp != null) "Screenshot captured" else "Screenshot unavailable")
            }
            is AgentAction.Submit -> {
                val r = service.performSubmit(action.targetText)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Submitted via ${r.method}" else (r.failureReason ?: "Submit failed")
                )
            }
            is AgentAction.OpenUrl -> {
                val ok = appDirectory.openUrl(action.url)
                if (ok) delay(800)
                ActionResult(ok, "OPEN_URL", if (ok) "Opened URL ${action.url}" else "No activity handled ${action.url}")
            }
            is AgentAction.DeepLink -> {
                val ok = appDirectory.openDeepLink(action.uri)
                if (ok) delay(800)
                ActionResult(ok, "DEEP_LINK", if (ok) "Opened deep link ${action.uri}" else "No activity handled ${action.uri}")
            }
            is AgentAction.Drag -> {
                val r = service.performDrag(action.fromText, action.toText)
                ActionResult(
                    success = r.success,
                    method = r.method,
                    message = if (r.success) "Dragged '${action.fromText}' -> '${action.toText}'" else (r.failureReason ?: "Drag failed")
                )
            }
            is AgentAction.CopyText -> {
                val ok = service.performCopy(action.targetText)
                ActionResult(ok, "COPY", if (ok) "Copied text from '${action.targetText ?: "focused field"}'" else "Copy action unavailable")
            }
            is AgentAction.PasteText -> {
                val ok = service.performPaste()
                ActionResult(ok, "PASTE", if (ok) "Pasted clipboard into focused field" else "Paste action unavailable")
            }
            is AgentAction.ReadPageContent -> {
                val text = service.readPageContent()
                ActionResult(
                    success = text.isNotBlank(),
                    method = "READ_PAGE_CONTENT",
                    message = if (text.isNotBlank()) "Extracted ${text.length} chars of page content" else "No page content found",
                    readText = text.take(ActionResult.MAX_CAPTURED_CHARS).takeIf { it.isNotBlank() }
                )
            }
            else -> ActionResult(
                false,
                "UNSUPPORTED_ACTION",
                "Action '${action.getDisplayName()}' has no native executor; the model must not have been offered it"
            )
        }
    }

    /**
     * 9.9: reads the bounded, on-device notification digest. Kept free of the a11y
     * service so triage works (and is testable) without a bound accessibility tree.
     */
    private fun readNotificationDigest(): ActionResult {
        val items = notificationSource.recent(NotificationSource.DEFAULT_LIMIT)
        val digest = items.joinToString(" | ") { "${it.packageName}: ${it.title} — ${it.text}".take(120) }
        return ActionResult(
            success = items.isNotEmpty(),
            method = "READ_NOTIFICATIONS",
            message = if (items.isEmpty()) {
                "No recent notifications available (grant notification access to enable this)"
            } else {
                digest
            },
            // Carry the digest like the other read actions so a read_notifications
            // step is self-evidencing (its own non-empty text is the goal evidence)
            // rather than stuck at a NONE postcondition it can never satisfy.
            readText = digest.take(ActionResult.MAX_CAPTURED_CHARS).takeIf { it.isNotBlank() }
        )
    }

    /**
     * 7.2: one bounded vision-grounding attempt when the a11y tree cannot represent
     * the target. Takes a screenshot, asks the grounder to locate the label, and taps
     * the point via the normal coordinate path. Returns null (never blind-taps) when no
     * grounder is configured or the label cannot be located.
     */
    private suspend fun visionTap(
        targetText: String?,
        service: AgentAccessibilityService,
        uiChanged: suspend () -> Boolean
    ): ActionResult? {
        if (targetText.isNullOrBlank()) return null
        if (visionGrounder is com.example.perception.UnavailableVisionGrounder) return null
        val bitmap = try { service.takeScreenshotFallback() } catch (e: Exception) { null } ?: return null
        val point = try { visionGrounder.locate(targetText, bitmap) } catch (e: Exception) { null } ?: return null
        val r = service.performTap(null, point.x, point.y, uiChanged)
        return if (r.success) {
            ActionResult(true, "VISION_GROUNDED", "Located '$targetText' via vision at (${point.x}, ${point.y})")
        } else {
            null
        }
    }

    /**
     * Scrolls the current surface until [AgentAction.ScrollUntilFound.targetText]
     * becomes visible. Bounded by `maxSwipes` so a missing label cannot loop forever.
     * Stops early when a swipe no longer changes the screen (end of list reached).
     */
    private suspend fun executeScrollUntilFound(
        action: AgentAction.ScrollUntilFound,
        service: AgentAccessibilityService
    ): ActionResult {
        return when (val reveal = revealByScrolling(service, action.targetText, action.direction, action.maxSwipes)) {
            is RevealResult.AlreadyVisible ->
                ActionResult(true, "ALREADY_VISIBLE", "'${action.targetText}' is already visible")
            is RevealResult.Revealed ->
                ActionResult(true, "SCROLL_UNTIL_FOUND", "Found '${action.targetText}' after ${reveal.swipes} swipe(s)")
            is RevealResult.Exhausted ->
                ActionResult(false, "SCROLL_EXHAUSTED", reveal.reason)
        }
    }

    /**
     * The general "find and press" primitive: reveal [AgentAction.FindElement.targetText]
     * (scrolling if necessary, bounded) and tap it once from a fresh observation, so a
     * target that is scrolled off-screen is reachable in a single step. A tap that is
     * dispatched but cannot be confirmed is reported honestly rather than as success.
     */
    private suspend fun executeFindElement(
        action: AgentAction.FindElement,
        service: AgentAccessibilityService
    ): ActionResult {
        val reveal = revealByScrolling(service, action.targetText, action.direction, action.maxSwipes)
        if (reveal is RevealResult.Exhausted) {
            return ActionResult(false, "FIND_EXHAUSTED", reveal.reason)
        }
        // Verify the tap against the screen state at the moment of the tap: a dispatched
        // gesture that leaves the screen identical is not proof the element was activated.
        val before = service.dumpHierarchy().getFingerprint()
        val tap = service.performTap(action.targetText, null, null) {
            delay(250)
            service.dumpHierarchy().getFingerprint() != before
        }
        if (!tap.success) {
            return ActionResult(false, tap.method, tap.failureReason ?: "Found '${action.targetText}' but could not tap it")
        }
        return ActionResult(
            success = true,
            method = "FIND_TAP_${tap.method}",
            message = "Found and tapped '${action.targetText}' via ${tap.method}"
        )
    }

    private sealed class RevealResult {
        object AlreadyVisible : RevealResult()
        data class Revealed(val swipes: Int) : RevealResult()
        data class Exhausted(val reason: String) : RevealResult()
    }

    /**
     * Reveals a label by scrolling [direction] up to [maxSwipes] times. Stops as soon
     * as the label is visible, and stops early when a swipe no longer moves the screen
     * (the end of the list has been reached), so a missing label cannot scroll forever.
     */
    private suspend fun revealByScrolling(
        service: AgentAccessibilityService,
        targetText: String,
        direction: String,
        maxSwipes: Int
    ): RevealResult {
        if (service.isTextVisible(targetText)) return RevealResult.AlreadyVisible
        var swipes = 0
        while (swipes < maxSwipes) {
            val before = service.dumpHierarchy().getFingerprint()
            service.scroll(direction)
            delay(350)
            if (service.isTextVisible(targetText)) return RevealResult.Revealed(swipes + 1)
            val after = service.dumpHierarchy().getFingerprint()
            swipes++
            if (before == after) {
                return RevealResult.Exhausted("Reached the end of the list without finding '$targetText'")
            }
        }
        return RevealResult.Exhausted("'$targetText' not found after $maxSwipes swipes")
    }

    private suspend fun executeOpenApp(
        action: AgentAction.OpenApp,
        service: AgentAccessibilityService
    ): ActionResult {
        val targetPkg = action.packageName ?: appDirectory.resolvePackage(action.appQuery)
        if (targetPkg == null) {
            return ActionResult(false, "RESOLVE_APP", "App '${action.appQuery}' not found on device")
        }
        if (ForegroundPackageVerifier.matches(targetPkg, service.dumpHierarchy().packageName)) {
            return ActionResult(true, "FOREGROUND_ALREADY_ACTIVE", "Verified $targetPkg already foregrounded; launch skipped")
        }
        if (!appDirectory.launchApp(targetPkg)) {
            return ActionResult(false, "LAUNCH_INTENT", "Launch intent dispatch failed for $targetPkg")
        }
        // Cold starts on a mid-range device regularly take longer than one second, so
        // allow a wider bounded window before declaring the launch unverified.
        val observed = ForegroundPackageVerifier.awaitForegroundPackage(
            targetPackage = targetPkg,
            attempts = 8,
            delayMillis = 300L
        ) { service.dumpHierarchy().packageName }
        return if (observed != null) {
            ActionResult(true, "LAUNCH_VERIFIED", "Verified foreground package $observed after launch")
        } else {
            ActionResult(false, "LAUNCH_UNVERIFIED", "Launch dispatched for $targetPkg but foreground package was not verified")
        }
    }
}
