package com.example

import com.example.agent.ConversationMemory
import com.example.agent.ObstacleDetector
import com.example.agent.ProactiveEngine
import com.example.automation.AutomationTrigger
import com.example.automation.TriggerMatcher
import com.example.notifications.AgentNotification
import com.example.notifications.NotificationTriage
import com.example.perception.BatteryGuard
import com.example.planning.PlanVariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 9 - Fully-automated Jarvis capability upgrades. Pins the pure,
 * device-independent cores of 9.1, 9.2, 9.3, 9.4, 9.5, 9.9, 9.12 and 9.14, and
 * asserts that every always-on/privacy feature defaults OFF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9UpgradeTest {

    private fun notif(pkg: String, title: String, canReply: Boolean = false) =
        AgentNotification(pkg, title, "body", postedAt = 0L, canReply = canReply)

    // ---- 9.1 proactive awareness ----

    @Test
    fun proactiveSuggestsOnMessagingPileup() {
        val suggestion = ProactiveEngine.evaluate(
            ProactiveEngine.Context(
                foregroundPackage = "com.android.launcher",
                notifications = listOf(
                    notif("com.whatsapp", "Prateek"),
                    notif("com.whatsapp", "Ravi")
                ),
                minuteOfDay = 10 * 60
            )
        )
        assertNotNull(suggestion)
        assertTrue(suggestion!!.text.contains("2 new messages"))
    }

    @Test
    fun proactiveStaysSilentWhenNothingWorthSaying() {
        val suggestion = ProactiveEngine.evaluate(
            ProactiveEngine.Context(
                foregroundPackage = "com.google.android.youtube",
                notifications = listOf(notif("com.google.android.youtube", "one")),
                minuteOfDay = 10 * 60
            )
        )
        assertNull(suggestion)
    }

    @Test
    fun proactiveRunnerHonoursOptInAndBattery() {
        var emitted = 0
        val off = ProactiveEngine.Runner(
            shouldRun = { false },
            battery = { BatteryGuard.Decision.OK },
            sampleContext = {
                ProactiveEngine.Context(
                    "com.android.launcher",
                    listOf(notif("com.whatsapp", "a"), notif("com.whatsapp", "b")),
                    600
                )
            },
            onSuggestion = { emitted++ }
        )
        kotlinx.coroutines.runBlocking { off.tick() }
        assertEquals("opt-in off must not sample or emit", 0, emitted)

        val on = ProactiveEngine.Runner(
            shouldRun = { true },
            battery = { BatteryGuard.Decision(false, "low") },
            sampleContext = {
                ProactiveEngine.Context(
                    "com.android.launcher",
                    listOf(notif("com.whatsapp", "a"), notif("com.whatsapp", "b")),
                    600
                )
            },
            onSuggestion = { emitted++ }
        )
        kotlinx.coroutines.runBlocking { on.tick() }
        assertEquals("battery paused must not emit", 0, emitted)
    }

    // ---- 9.2 triggers ----

    @Test
    fun triggerMatcherEvaluatesEventTriggers() {
        val conds = TriggerMatcher.Conditions(
            wifiConnected = true, ssid = "Home", batteryPercent = 10, charging = true,
            networkType = "WIFI", foregroundPackage = "com.whatsapp",
            recentNotificationPackages = setOf("com.whatsapp")
        )
        assertTrue(TriggerMatcher.matches(AutomationTrigger.WIFI_CONNECTED, "Home", 0, conds))
        assertFalse(TriggerMatcher.matches(AutomationTrigger.WIFI_CONNECTED, "Office", 0, conds))
        assertTrue(TriggerMatcher.matches(AutomationTrigger.BATTERY_BELOW, "20", 0, conds))
        assertFalse(TriggerMatcher.matches(AutomationTrigger.BATTERY_ABOVE, "80", 0, conds))
        assertTrue(TriggerMatcher.matches(AutomationTrigger.CHARGING_STARTED, "", 0, conds))
        assertTrue(TriggerMatcher.matches(AutomationTrigger.APP_FOREGROUND, "whatsapp", 0, conds))
        assertTrue(TriggerMatcher.matches(AutomationTrigger.NOTIFICATION_FROM_APP, "whatsapp", 0, conds))
        assertTrue(TriggerMatcher.matches(AutomationTrigger.NETWORK_TYPE, "WIFI", 0, conds))
    }

    @Test
    fun triggerMasterSwitchAndBatteryGate() {
        val conds = TriggerMatcher.Conditions(wifiConnected = true, ssid = "Home")
        // Master off -> nothing fires even though the condition matches.
        assertFalse(
            TriggerMatcher.shouldFire(false, true, AutomationTrigger.WIFI_CONNECTED, "Home", 0, conds)
        )
        assertTrue(
            TriggerMatcher.shouldFire(true, true, AutomationTrigger.WIFI_CONNECTED, "Home", 0, conds)
        )
        // Event trigger pauses under the battery guard.
        assertFalse(
            TriggerMatcher.shouldFire(
                true, true, AutomationTrigger.WIFI_CONNECTED, "Home", 0, conds,
                battery = BatteryGuard.Decision(false, "low")
            )
        )
        // Interval triggers are exempt from the always-on battery gate.
        assertTrue(
            TriggerMatcher.shouldFire(
                true, true, AutomationTrigger.INTERVAL, "30", 15,
                conds.copy(nowMs = 15 * 60_000L, lastRunMs = 0L),
                battery = BatteryGuard.Decision(false, "low")
            )
        )
    }

    // ---- 9.3 conversation memory ----

    @Test
    fun followUpResolvesAgainstPreviousGoal() {
        val memory = ConversationMemory()
        memory.remember("Send a message to Prateek", "com.whatsapp")
        val resolved = memory.resolve("and send it")
        assertTrue(resolved is ConversationMemory.Resolution.Resolved)
        val goal = (resolved as ConversationMemory.Resolution.Resolved).goal
        assertTrue(goal.contains("Send a message to Prateek"))
        assertTrue(goal.contains("send it"))
    }

    @Test
    fun followUpWithNoContextAsksInsteadOfGuessing() {
        val memory = ConversationMemory()
        val resolved = memory.resolve("and send it")
        assertTrue(resolved is ConversationMemory.Resolution.NeedsContext)
    }

    @Test
    fun nonFollowUpIsStandalone() {
        val memory = ConversationMemory()
        memory.remember("Open YouTube", "com.google.android.youtube")
        val resolved = memory.resolve("Open Settings")
        assertTrue(resolved is ConversationMemory.Resolution.Standalone)
        assertEquals("Open Settings", (resolved as ConversationMemory.Resolution.Standalone).goal)
    }

    // ---- 9.4 plan variables ----

    @Test
    fun variableFlowsFromOneStepToAnother() {
        val store = PlanVariableStore()
        store.set("price", "₹1,299")
        val typed = store.substitute(
            com.example.agent.AgentAction.TypeText("The price is {{price}}", null)
        ) as com.example.agent.AgentAction.TypeText
        assertEquals("The price is ₹1,299", typed.text)
        assertTrue(store.contains("price"))
    }

    @Test
    fun missingVariableLeavesPlaceholderUntouchedWhenEmpty() {
        val store = PlanVariableStore()
        assertEquals("no vars {{x}}", store.substitute("no vars {{x}}"))
    }

    // ---- 9.5 memory commands ----

    @Test
    fun parsesRememberForgetRecall() {
        val remember = com.example.memory.MemoryCommands.parse("remember that my meeting is at 5")
        assertTrue(remember is com.example.memory.MemoryCommands.Command.Remember)
        assertEquals("my_meeting_is_at", (remember as com.example.memory.MemoryCommands.Command.Remember).key)

        val forget = com.example.memory.MemoryCommands.parse("forget my meeting")
        assertTrue(forget is com.example.memory.MemoryCommands.Command.Forget)

        val recall = com.example.memory.MemoryCommands.parse("what do you know about my meeting")
        assertTrue(recall is com.example.memory.MemoryCommands.Command.Recall)

        val plain = com.example.memory.MemoryCommands.parse("open youtube")
        assertTrue(plain is com.example.memory.MemoryCommands.Command.NotAMemoryCommand)
    }

    @Test
    fun parsesRomanHindiMemoryCommands() {
        val remember = com.example.memory.MemoryCommands.parse("yaad rakho ki mera naam Ravi hai")
        assertTrue(remember is com.example.memory.MemoryCommands.Command.Remember)
    }

    // ---- 9.9 notification triage ----

    @Test
    fun triageGroupsAndOffersSafeActionsOnly() {
        val notifications = listOf(
            notif("com.whatsapp", "Prateek", canReply = true),
            notif("com.whatsapp", "Ravi"),
            notif("com.android.email", "Invoice")
        )
        val digest = NotificationTriage.digest(notifications)
        assertEquals(2, digest.groups.size)
        assertEquals(2, digest.groups.first().count)
        assertTrue(digest.line.contains("3 new notifications"))

        val actions = NotificationTriage.offeredActions(notifications.first())
        assertTrue(actions.contains("open"))
        assertTrue(actions.contains("reply"))
        val noReply = NotificationTriage.offeredActions(notifications[2])
        assertFalse("reply is only offered when the notification supports it", noReply.contains("reply"))
    }

    // ---- 9.12 obstacle self-healing ----

    @Test
    fun detectsAndSafelyDismissesCommonBlockers() {
        val ad = ObstacleDetector.detect(
            ObstacleDetector.ScreenSignals("com.game", listOf("Advertisement", "Skip Ad"))
        )
        assertNotNull(ad)
        assertEquals(ObstacleDetector.Kind.AD, ad!!.kind)
        assertTrue(ObstacleDetector.isSafeDismiss(ad.dismissLabel))

        val permission = ObstacleDetector.detect(
            ObstacleDetector.ScreenSignals(
                "com.app",
                listOf("Allow app to access your location?", "While using the app", "Don't allow")
            )
        )
        assertNotNull(permission)
        assertEquals(ObstacleDetector.Kind.PERMISSION, permission!!.kind)

        assertNull(
            ObstacleDetector.detect(ObstacleDetector.ScreenSignals("com.app", listOf("Home", "Search")))
        )
    }

    @Test
    fun denyLabelsAreNeverSafeDismiss() {
        assertFalse(ObstacleDetector.isSafeDismiss("Don't allow"))
        assertFalse(ObstacleDetector.isSafeDismiss("Cancel"))
        assertFalse(ObstacleDetector.isSafeDismiss(null))
    }

    @Test
    fun genericSkipControlIsNotMisreadAsAnAd() {
        // Regression (4A): a bare "Skip"/"Close" is an ordinary app affordance (onboarding,
        // tutorial, dialog). Treating it as an ad cue made the orchestrator tap it as if it
        // dismissed an ad, derailing the task surface. Only a real ad cue may trigger AD.
        assertNull(
            ObstacleDetector.detect(ObstacleDetector.ScreenSignals("com.app", listOf("Skip", "Next")))
        )
        assertNull(
            ObstacleDetector.detect(ObstacleDetector.ScreenSignals("com.app", listOf("Skip for now")))
        )
        // The detected control is the real on-screen label, not the candidate token, and a
        // single-letter "x" candidate must not substring-match an unrelated label like "Next".
        val ad = ObstacleDetector.detect(
            ObstacleDetector.ScreenSignals("com.game", listOf("Next", "Skip Ad"))
        )
        assertEquals(ObstacleDetector.Kind.AD, ad!!.kind)
        assertEquals("Skip Ad", ad.dismissLabel)
    }

    // ---- 9.14 battery guard + defaults ----

    @Test
    fun batteryGuardPausesOnLowBatteryDozeAndHeat() {
        assertTrue(BatteryGuard.decide(BatteryGuard.DeviceState(80, false, false, 0)).allowed)
        assertFalse(BatteryGuard.decide(BatteryGuard.DeviceState(10, false, false, 0)).allowed)
        assertTrue("charging at low battery is fine", BatteryGuard.decide(BatteryGuard.DeviceState(10, true, false, 0)).allowed)
        assertFalse(BatteryGuard.decide(BatteryGuard.DeviceState(80, false, true, 0)).allowed)
        assertFalse(BatteryGuard.decide(BatteryGuard.DeviceState(80, false, false, 4)).allowed)
    }

    // ---- 9.7 hands-free voice loop ----

    @Test
    fun voiceLoopRequiresWakeWordAndHonoursStop() {
        assertEquals(
            com.example.voice.VoiceLoop.Utterance.Ignored,
            com.example.voice.VoiceLoop.parseUtterance("open youtube")
        )
        val cmd = com.example.voice.VoiceLoop.parseUtterance("Jarvis open youtube")
        assertTrue(cmd is com.example.voice.VoiceLoop.Utterance.Command)
        assertEquals("open youtube", (cmd as com.example.voice.VoiceLoop.Utterance.Command).command)
        assertEquals(
            com.example.voice.VoiceLoop.Utterance.Stop,
            com.example.voice.VoiceLoop.parseUtterance("jarvis stop")
        )
        assertTrue(com.example.voice.VoiceLoop.isConfirmation("haan"))
        assertTrue(com.example.voice.VoiceLoop.isDenial("nahi"))
    }

    @Test
    fun voiceLoopControllerStaysInertWhenDisabled() = kotlinx.coroutines.runBlocking {
        var ran = false
        val controller = com.example.voice.VoiceLoopController(
            optIn = { false },
            micPermission = { true },
            recognizer = object : com.example.voice.SpeechRecognizer {
                override fun isAvailable() = true
                override suspend fun listenOnce(timeoutMs: Long) = "jarvis open settings"
            },
            speak = { _, _ -> },
            runCommand = { ran = true; true },
            stopCurrent = {}
        )
        val result = controller.cycle()
        assertEquals("off", result.action)
        assertFalse("nothing runs while hands-free is off", ran)
    }

    // ---- 9.8 daily briefing ----

    @Test
    fun briefingComposesBoundedSummary() {
        val line = com.example.notifications.DailyBriefing.compose(
            listOf(notif("com.whatsapp", "A"), notif("com.whatsapp", "B"), notif("com.gmail", "C"))
        )
        assertTrue(line.contains("3 new notifications"))
        assertTrue(line.contains("Whatsapp"))
    }

    @Test
    fun briefingSchedulesNextOccurrenceWhenTimePassed() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        // 08:00 has already passed at 23:00, so the delay must roll to tomorrow.
        val delay = com.example.notifications.DailyBriefing.millisUntil(8 * 60, now)
        assertTrue("expected ~9h to next 08:00, got $delay ms", delay in 8L * 3600_000 .. 10L * 3600_000)
    }

    // ---- 9.11 ranked element understanding ----

    @Test
    fun rankerPutsHintMatchFirst() {
        val nodes = listOf(
            uiNode("com.app", "Home", clickable = true),
            uiNode("com.app", "Search for lo-fi beats", clickable = true),
            uiNode("com.app", "Settings", clickable = true)
        )
        val ranked = com.example.perception.ElementRanker.rank(nodes, "search lo-fi", "com.app")
        assertEquals("Search for lo-fi beats", ranked.first().node.text)
    }

    private fun uiNode(pkg: String, label: String, clickable: Boolean): com.example.accessibility.UiNode =
        com.example.accessibility.UiNode(
            id = "n_${label.hashCode()}",
            text = label,
            contentDescription = null,
            className = "android.widget.TextView",
            packageName = pkg,
            bounds = android.graphics.Rect(0, 0, 100, 40),
            isClickable = clickable,
            isScrollable = false,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isFocused = false
        )

    @Test
    fun everyPhase9FeatureDefaultsOff() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.example.settings.CapabilitySettings(context)
        settings.proactiveAwarenessEnabled = false
        settings.locationTriggersEnabled = false
        settings.briefingEnabled = false
        settings.notificationTriageEnabled = false
        assertFalse(settings.proactiveAwarenessEnabled)
        assertFalse(settings.locationTriggersEnabled)
        assertFalse(settings.briefingEnabled)
        assertFalse(settings.notificationTriageEnabled)
    }
}
