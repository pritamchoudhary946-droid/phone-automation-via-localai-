package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ActionResult
import com.example.agent.AlarmController
import com.example.agent.AlarmResult
import com.example.agent.AgentAction
import com.example.agent.AndroidCalendarController
import com.example.agent.AndroidDeviceAutomation
import com.example.agent.AndroidDeviceStatusReader
import com.example.agent.AppDirectory
import com.example.agent.DeviceStatusReader
import com.example.agent.DeviceStatusResult
import com.example.agent.AudioAdjustResult
import com.example.agent.AudioController
import com.example.agent.AudioStream
import com.example.agent.CalendarController
import com.example.agent.CalendarResult
import com.example.agent.ContactController
import com.example.agent.ContactResult
import com.example.agent.AndroidDialerController
import com.example.agent.AndroidSmsController
import com.example.agent.DialResult
import com.example.agent.DialerController
import com.example.agent.SmsController
import com.example.agent.SmsResult
import com.example.agent.FastPathDecision
import com.example.agent.HybridStepExecutor
import com.example.agent.MediaControlResult
import com.example.agent.MediaController
import com.example.agent.MediaTransport
import com.example.agent.RingerMode
import com.example.agent.RingerModeController
import com.example.agent.RingerModeResult
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.agent.TimerController
import com.example.agent.TimerResult
import com.example.agent.ToolRegistry
import com.example.agent.ToolValidationResult
import com.example.model.LiteRtEngine
import com.example.planning.PlanParseResult
import com.example.planning.PlannerPromptBuilder
import com.example.planning.PlanningRequest
import com.example.planning.TaskPlan
import com.example.planning.TaskPlanParser
import com.example.planning.TaskPlanSerializer
import com.example.planning.VerificationMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the capability upgrade: the expanded action vocabulary, its planner
 * mapping, validation, deterministic fast path and the orchestrator's mid-task
 * replanning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityUpgradeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installLauncherPackage(context, "com.android.settings", "Settings")
    }

    // --- Planner mapping: the model's JSON for each new tool becomes the right action ---

    @Test
    fun parserMapsNewToolNamesToTheirActions() {
        val parser = TaskPlanParser()
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Hold item","action":"long_press","params":{"targetText":"Photo","durationMs":900},"expect":"menu","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Find row","action":"scroll_until_found","params":{"targetText":"Settings","direction":"down","maxSwipes":4},"expect":"visible","verify":"TEXT_PRESENT"},
          {"id":3,"seq":3,"desc":"Read it","action":"read_text","params":{},"expect":"text","verify":"NONE"},
          {"id":4,"seq":4,"desc":"Wait for it","action":"wait_for_text","params":{"targetText":"Done","timeoutMs":3000},"expect":"appears","verify":"TEXT_PRESENT"},
          {"id":5,"seq":5,"desc":"Shade","action":"open_notifications","params":{},"expect":"shade","verify":"NONE"},
          {"id":6,"seq":6,"desc":"Tiles","action":"open_quick_settings","params":{},"expect":"tiles","verify":"NONE"},
          {"id":7,"seq":7,"desc":"Recents","action":"open_recents","params":{},"expect":"recents","verify":"NONE"},
          {"id":8,"seq":8,"desc":"Find row","action":"find_element","params":{"targetText":"Wi-Fi","direction":"down","maxSwipes":4},"expect":"opened","verify":"NONE"}
        ]}
        """.trimIndent()

        val result = parser.parse(json, "test goal")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(8, steps.size)
        assertEquals(AgentAction.LongPress("Photo", 900L), steps[0].action)
        assertEquals(AgentAction.ScrollUntilFound("Settings", "down", 4), steps[1].action)
        assertEquals(AgentAction.ReadText, steps[2].action)
        assertEquals(AgentAction.WaitForText("Done", 3000L), steps[3].action)
        assertEquals(AgentAction.OpenNotifications, steps[4].action)
        assertEquals(AgentAction.OpenQuickSettings, steps[5].action)
        assertEquals(AgentAction.PressRecents, steps[6].action)
        assertEquals(AgentAction.FindElement("Wi-Fi", "down", 4), steps[7].action)
    }

    // --- Validation rejects malformed parameters for the new tools ---

    @Test
    fun validationRejectsMalformedNewActionParameters() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.LongPress(null)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.LongPress("x", durationMs = 10L)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.LongPress("x", durationMs = 9_000L)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.LongPress("x", durationMs = 700L)) is ToolValidationResult.Valid)

        assertTrue(registry.validateAction(AgentAction.ScrollUntilFound("")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.ScrollUntilFound("x", direction = "left")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.ScrollUntilFound("x", maxSwipes = 0)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.ScrollUntilFound("x", maxSwipes = 99)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.ScrollUntilFound("x", "up", 5)) is ToolValidationResult.Valid)

        assertTrue(registry.validateAction(AgentAction.FindElement("")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.FindElement("x", direction = "left")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.FindElement("x", maxSwipes = 0)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.FindElement("x", maxSwipes = 99)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.FindElement("x", "up", 5)) is ToolValidationResult.Valid)

        assertTrue(registry.validateAction(AgentAction.WaitForText("")) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.WaitForText("x", timeoutMs = 10L)) is ToolValidationResult.Invalid)
        assertTrue(registry.validateAction(AgentAction.WaitForText("x", timeoutMs = 40_000L)) is ToolValidationResult.Invalid)

        assertTrue(registry.validateAction(AgentAction.ReadText) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.OpenNotifications) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.OpenQuickSettings) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.PressRecents) is ToolValidationResult.Valid)
    }

    // --- Deterministic fast path: no model call for the new mechanical actions ---

    @Test
    fun fastPathHandlesNewMechanicalActionsWithoutModel() {
        val executor = HybridStepExecutor(AppDirectory(context))
        val obs = observation("com.android.settings", "Settings list", 5)
        val steps = listOf(
            planStep(1, AgentAction.OpenNotifications, "open_notifications"),
            planStep(2, AgentAction.OpenQuickSettings, "open_quick_settings"),
            planStep(3, AgentAction.PressRecents, "open_recents"),
            planStep(4, AgentAction.ReadText, "read_text"),
            planStep(5, AgentAction.ScrollUntilFound("Wi-Fi", "down", 5), "scroll_until_found"),
            planStep(6, AgentAction.WaitForText("Connected", 3_000L), "wait_for_text"),
            planStep(7, AgentAction.FindElement("Wi-Fi", "down", 5), "find_element")
        )
        steps.forEachIndexed { index, step ->
            val decision = executor.decide(steps, index, obs, nudgeUsed = false)
            assertTrue(
                "step ${step.rawActionName} should be deterministic, got $decision",
                decision is FastPathDecision.Execute
            )
        }
    }

    // --- The prompt advertises exactly the tools the native layer can execute ---

    @Test
    fun promptAdvertisesTheNewTools() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        listOf(
            ToolRegistry.TOOL_LONG_PRESS,
            ToolRegistry.TOOL_SCROLL_UNTIL_FOUND,
            ToolRegistry.TOOL_FIND_ELEMENT,
            ToolRegistry.TOOL_READ_TEXT,
            ToolRegistry.TOOL_WAIT_FOR_TEXT,
            ToolRegistry.TOOL_OPEN_NOTIFICATIONS,
            ToolRegistry.TOOL_OPEN_QUICK_SETTINGS,
            ToolRegistry.TOOL_OPEN_RECENTS
        ).forEach { tool ->
            assertTrue("advertised actions are missing $tool", actions.contains(tool))
        }
        // The tool that cannot be executed natively must stay out of the catalogue.
        assertTrue(!actions.contains(ToolRegistry.TOOL_ASK_CONFIRMATION))

        // And the prompt catalogue must document each of them with a parameter schema.
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "test",
                desiredOutcome = "test",
                foregroundPackage = "com.android.launcher",
                screenSummary = "Home",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        listOf("long_press", "scroll_until_found", "find_element", "read_text", "wait_for_text",
            "open_notifications", "open_quick_settings", "open_recents").forEach { tool ->
            assertTrue("prompt catalogue is missing $tool", prompt.contains(tool))
        }
    }

    // --- The deterministic fast path must defer, not execute, unrunnable steps ---

    @Test
    fun fastPathDefersTargetlessLongPressToTheModel() {
        val executor = HybridStepExecutor(AppDirectory(context))
        val obs = observation("com.android.settings", "Settings list", 7)
        // Validation always rejects a long press with no target, so the fast path must
        // not claim it can execute one; doing so would burn an attempt for nothing.
        val decision = executor.decide(
            listOf(planStep(1, AgentAction.LongPress(null), "long_press")),
            0, obs, nudgeUsed = false
        )
        assertTrue("expected ModelReason, got $decision", decision is FastPathDecision.ModelReason)
    }

    // --- Mid-task replanning: a stalled plan is rebuilt instead of failing outright ---

    @Test
    fun stalledPlanIsReplannedBeforeFailing() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            // Nothing the plan tries ever satisfies its postcondition.
            executeResult = { ActionResult(true, "DISPATCHED", "no screen change") }
        }
        // First plan: a single step that will never verify. The replan returns the same
        // shape, so the run must still terminate safely within its bounded budget.
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Launch Settings","action":"launch_app","params":{"appQuery":"settings"},"expect":"Settings foreground","verify":"FOREGROUND_PACKAGE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open Settings", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected a terminal failure, got $terminal", terminal is OrchestratorState.Failed)
        // Replanning is bounded: the action is not retried forever.
        assertTrue("unexpectedly many actions: ${automation.executed.size}", automation.executed.size in 1..8)
    }

    @Test
    fun replanContinuesWhenTheNewPlanMakesProgress() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.OpenApp -> {
                        defaultObservation = observation("com.android.settings", "Settings", 2)
                        ActionResult(true, "LAUNCH_VERIFIED", "verified")
                    }
                    is AgentAction.Home -> {
                        defaultObservation = observation("com.android.launcher", "Home screen", 3)
                        ActionResult(true, "GLOBAL_HOME", "home")
                    }
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        // Plan 1 ends in a report_status that cannot be verified (no screen change), so
        // the terminal path rebuilds the plan. Plan 2 actually reaches the goal.
        val firstPlan = """{"steps":[{"id":1,"seq":1,"desc":"Confirm","action":"report_status","params":{"status":"success","summary":"done"},"expect":"done","verify":"NONE"}]}"""
        val secondPlan = """{"steps":[
            {"id":1,"seq":1,"desc":"Launch Settings","action":"launch_app","params":{"appQuery":"settings"},"expect":"Settings foreground","verify":"FOREGROUND_PACKAGE"},
            {"id":2,"seq":2,"desc":"Go home","action":"navigate_home","params":{},"expect":"home","verify":"HOME_FOREGROUND"}
        ]}"""
        val engine = sequencedEngine(listOf(firstPlan, secondPlan))
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("Open Settings then go home", maxSteps = 6)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("expected completion after replan, got $terminal", terminal is OrchestratorState.Completed)
    }

    // --- adjust_volume: the advertised media-control primitive must actually execute ---

    @Test
    fun adjustVolumeExecutesWithoutAnAccessibilityService() {
        // Regression: adjust_volume was advertised to the model and validated by the
        // registry but had NO execution branch, so a planned volume change fell through
        // to the UNKNOWN arm and could never run. Audio is service-free (AudioManager),
        // so it must resolve before the a11y gate.
        val recorder = RecordingAudioController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            audioController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AdjustVolume("media", steps = 2, mute = false))
        }

        assertTrue("volume change must succeed without a bound a11y service: $result", result.success)
        assertEquals("MEDIA", result.method)
        assertEquals(1, recorder.calls.size)
        assertEquals(AudioStream.MEDIA to 2, recorder.calls[0].first to recorder.calls[0].second)
        assertFalse(recorder.calls[0].third)
    }

    @Test
    fun adjustVolumeMuteFlagReachesTheController() {
        val recorder = RecordingAudioController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            audioController = recorder
        )

        kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AdjustVolume("ring", steps = 0, mute = true))
        }

        assertEquals(AudioStream.RING, recorder.calls[0].first)
        assertTrue("mute=true must be honoured", recorder.calls[0].third)
    }

    @Test
    fun adjustVolumeRefusesHonestlyWhenAudioIsUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            audioController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AdjustVolume("media", 1))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("AUDIO_UNAVAILABLE", result.method)
    }

    @Test
    fun adjustVolumeRejectsAnUnknownStream() {
        val recorder = RecordingAudioController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            audioController = recorder
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AdjustVolume("telepathy", 1))
        }
        assertFalse(result.success)
        assertEquals("INVALID_STREAM", result.method)
        assertTrue("an invalid stream must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun adjustVolumeParserCarriesStreamStepsAndMute() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"mute the ringer","action":"adjust_volume","params":{"stream":"ring","steps":0,"mute":true},"expect":"silent","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "silence the phone")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val action = (result as PlanParseResult.Success).plan.steps[0].action
        assertEquals(AgentAction.AdjustVolume("ring", 0, mute = true), action)
    }

    @Test
    fun adjustVolumeSkillKeepsStreamStepsAndMuteOnReplay() {
        val plan = TaskPlan(
            goal = "silence the phone",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "mute ringer",
                    action = AgentAction.AdjustVolume("ring", 0, mute = true),
                    rawActionName = "adjust_volume", expectedPostcondition = "silent",
                    verification = VerificationMethod.NONE
                )
            )
        )
        val restored = TaskPlanSerializer.fromJson(
            TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved volume skill must be replayable", restored != null)
        assertEquals(AgentAction.AdjustVolume("ring", 0, mute = true), restored!!.steps[0].action)
    }

    // --- set_ringer_mode: the "put my phone on silent" primitive must execute ---

    @Test
    fun setRingerModeExecutesWithoutAnAccessibilityService() {
        // The ringer mode is a system audio setting (AudioManager), so like adjust_volume
        // it is service-free and must resolve before the a11y gate.
        val recorder = RecordingRingerModeController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            ringerModeController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetRingerMode("vibrate"))
        }

        assertTrue("ringer change must succeed without a bound a11y service: $result", result.success)
        assertEquals("RINGER_MODE", result.method)
        assertEquals(listOf(RingerMode.VIBRATE), recorder.calls)
    }

    @Test
    fun setRingerModeRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            ringerModeController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetRingerMode("silent"))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("RINGER_UNAVAILABLE", result.method)
    }

    @Test
    fun setRingerModeRejectsAnUnknownMode() {
        val recorder = RecordingRingerModeController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            ringerModeController = recorder
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetRingerMode("loud"))
        }
        assertFalse(result.success)
        assertEquals("INVALID_RINGER_MODE", result.method)
        assertTrue("an invalid mode must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun setRingerModeParserCarriesTheMode() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Silence the phone","action":"set_ringer_mode","params":{"mode":"silent"},"expect":"silent","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "silence the phone")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val action = (result as PlanParseResult.Success).plan.steps[0].action
        assertEquals(AgentAction.SetRingerMode("silent"), action)
    }

    @Test
    fun validationAcceptsSetRingerModeAndRejectsAnUnknownMode() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.SetRingerMode("vibrate")) is ToolValidationResult.Valid)
        assertTrue(
            "an unknown ringer mode must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SetRingerMode("loud")) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun setRingerModeSkillKeepsItsModeOnReplay() {
        // A learned skill that silences the phone must not replay as the default mode.
        val plan = com.example.planning.TaskPlan(
            goal = "silence the phone",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "silence the ringer",
                    action = AgentAction.SetRingerMode("silent"),
                    rawActionName = "set_ringer_mode", expectedPostcondition = "silent",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved ringer-mode skill must be replayable", restored != null)
        assertEquals(AgentAction.SetRingerMode("silent"), restored!!.steps[0].action)
    }

    @Test
    fun promptAdvertisesSetRingerMode() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_SET_RINGER_MODE}",
            actions.contains(ToolRegistry.TOOL_SET_RINGER_MODE)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "put my phone on silent",
                desiredOutcome = "phone silent",
                foregroundPackage = "com.android.settings",
                screenSummary = "Sound settings",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing set_ringer_mode", prompt.contains("set_ringer_mode"))
    }

    @Test
    fun setRingerModeStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The ringer-mode switch acts on the AudioManager, not the accessibility tree, so
        // its default NONE verification can never be satisfied from a fresh screen. The
        // executor's honest read-back result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SetRingerMode -> ActionResult(true, "RINGER_MODE", "Ringer mode set to silent")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Silence the phone","action":"set_ringer_mode","params":{"mode":"silent"},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("put my phone on silent", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("set_ringer_mode must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.SetRingerMode })
    }

    @Test
    fun setRingerModeFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a switch
        // the device ignored (RINGER_UNCHANGED / RINGER_PERMISSION) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SetRingerMode -> ActionResult(false, "RINGER_UNCHANGED", "ringer mode stayed at normal")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Silence the phone","action":"set_ringer_mode","params":{"mode":"silent"},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("put my phone on silent", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a refused ringer change must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- set_alarm: the "wake me at 7am" primitive must execute ---

    @Test
    fun setAlarmExecutesWithoutAnAccessibilityService() {
        // The alarm is dispatched through the platform AlarmClock intent, so like the
        // audio/ringer primitives it is service-free and must resolve before the a11y gate.
        val recorder = RecordingAlarmController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            alarmController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetAlarm(hour = 7, minute = 30, label = "Wake up"))
        }

        assertTrue("alarm must be settable without a bound a11y service: $result", result.success)
        assertEquals("ALARM_DISPATCHED", result.method)
        assertEquals(listOf(RecordingAlarmController.Call(7, 30, "Wake up", emptyList())), recorder.calls)
    }

    @Test
    fun setAlarmRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            alarmController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetAlarm(hour = 6, minute = 0))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("ALARM_UNAVAILABLE", result.method)
    }

    @Test
    fun setAlarmRejectsAnInvalidTime() {
        val recorder = RecordingAlarmController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            alarmController = recorder
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetAlarm(hour = 25, minute = 0))
        }
        assertFalse(result.success)
        assertEquals("INVALID_ALARM_TIME", result.method)
        assertTrue("an invalid time must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun setAlarmParserCarriesTimeLabelAndDays() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Wake me at 7","action":"set_alarm","params":{"hour":7,"minute":15,"label":"Gym","days":[2,4]},"expect":"alarm set","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "set an alarm for 7:15")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val action = (result as PlanParseResult.Success).plan.steps[0].action
        assertEquals(AgentAction.SetAlarm(hour = 7, minute = 15, label = "Gym", days = listOf(2, 4)), action)
    }

    @Test
    fun validationAcceptsSetAlarmAndRejectsAnInvalidTime() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.SetAlarm(hour = 7, minute = 0)) is ToolValidationResult.Valid)
        assertTrue(
            "an out-of-range hour must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SetAlarm(hour = 24, minute = 0)) !is ToolValidationResult.Valid
        )
        assertTrue(
            "an out-of-range minute must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SetAlarm(hour = 7, minute = 60)) !is ToolValidationResult.Valid
        )
        assertTrue(
            "an out-of-range weekday must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SetAlarm(hour = 7, minute = 0, days = listOf(9))) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun setAlarmSkillKeepsItsTimeLabelAndDaysOnReplay() {
        // A learned alarm skill must not replay at the default 07:00 with no label/days.
        val plan = com.example.planning.TaskPlan(
            goal = "set an alarm for 6:30 on weekdays",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "set the alarm",
                    action = AgentAction.SetAlarm(hour = 6, minute = 30, label = "Work", days = listOf(2, 3, 4, 5, 6)),
                    rawActionName = "set_alarm", expectedPostcondition = "alarm set",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved alarm skill must be replayable", restored != null)
        assertEquals(
            AgentAction.SetAlarm(hour = 6, minute = 30, label = "Work", days = listOf(2, 3, 4, 5, 6)),
            restored!!.steps[0].action
        )
    }

    @Test
    fun promptAdvertisesSetAlarm() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_SET_ALARM}",
            actions.contains(ToolRegistry.TOOL_SET_ALARM)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "set an alarm for 7am",
                desiredOutcome = "alarm at 07:00",
                foregroundPackage = "com.android.deskclock",
                screenSummary = "Clock",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing set_alarm", prompt.contains("set_alarm"))
    }

    @Test
    fun setAlarmStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The alarm is created by the clock app, a separate process no accessibility screen
        // reflects, so the step's default NONE verification can never be satisfied from a
        // fresh screen. The executor's honest dispatch result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SetAlarm -> ActionResult(true, "ALARM_DISPATCHED", "Alarm set for 07:00")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Set the alarm","action":"set_alarm","params":{"hour":7,"minute":0},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("set an alarm for 7am", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("set_alarm must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.SetAlarm })
    }

    @Test
    fun setAlarmFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a dispatch
        // that reached no clock app (ALARM_UNAVAILABLE) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SetAlarm -> ActionResult(false, "ALARM_UNAVAILABLE", "No clock app is available to set an alarm")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Set the alarm","action":"set_alarm","params":{"hour":7,"minute":0},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("set an alarm for 7am", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed alarm dispatch must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- set_timer: the "set a 10-minute timer" primitive must execute ---

    @Test
    fun setTimerExecutesWithoutAnAccessibilityService() {
        // The countdown timer is dispatched through the platform AlarmClock ACTION_SET_TIMER
        // intent, so like the alarm/audio/ringer primitives it is service-free and must
        // resolve before the a11y gate.
        val recorder = RecordingTimerController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            timerController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetTimer(seconds = 600, label = "Tea"))
        }

        assertTrue("timer must be settable without a bound a11y service: $result", result.success)
        assertEquals("TIMER_DISPATCHED", result.method)
        assertEquals(listOf(RecordingTimerController.Call(600, "Tea")), recorder.calls)
    }

    @Test
    fun setTimerRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            timerController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetTimer(seconds = 300))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("TIMER_UNAVAILABLE", result.method)
    }

    @Test
    fun setTimerRejectsAnInvalidDuration() {
        val recorder = RecordingTimerController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            timerController = recorder
        )
        val zero = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetTimer(seconds = 0))
        }
        assertFalse(zero.success)
        assertEquals("INVALID_TIMER_DURATION", zero.method)
        val tooLong = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SetTimer(seconds = ToolRegistry.MAX_TIMER_SECONDS + 1))
        }
        assertFalse(tooLong.success)
        assertEquals("INVALID_TIMER_DURATION", tooLong.method)
        assertTrue("an invalid duration must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun setTimerParserCarriesDurationLabelAndMinutesFallback() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Timer for ten minutes","action":"set_timer","params":{"seconds":600,"label":"Tea"},"expect":"timer set","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Timer via minutes","action":"set_timer","params":{"minutes":25},"expect":"timer set","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "set a timer")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(AgentAction.SetTimer(seconds = 600, label = "Tea"), steps[0].action)
        assertEquals(AgentAction.SetTimer(seconds = 25 * 60, label = null), steps[1].action)
    }

    @Test
    fun validationAcceptsSetTimerAndRejectsAnInvalidDuration() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.SetTimer(seconds = 600)) is ToolValidationResult.Valid)
        assertTrue(
            "a zero duration must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SetTimer(seconds = 0)) !is ToolValidationResult.Valid
        )
        assertTrue(
            "a duration beyond the 24-hour cap must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SetTimer(seconds = ToolRegistry.MAX_TIMER_SECONDS + 1)) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun setTimerSkillKeepsItsDurationAndLabelOnReplay() {
        // A learned timer skill must not replay at the default 300s with no label.
        val plan = com.example.planning.TaskPlan(
            goal = "set a 10-minute timer for tea",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "start the timer",
                    action = AgentAction.SetTimer(seconds = 600, label = "Tea"),
                    rawActionName = "set_timer", expectedPostcondition = "timer set",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved timer skill must be replayable", restored != null)
        assertEquals(
            AgentAction.SetTimer(seconds = 600, label = "Tea"),
            restored!!.steps[0].action
        )
    }

    @Test
    fun promptAdvertisesSetTimer() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_SET_TIMER}",
            actions.contains(ToolRegistry.TOOL_SET_TIMER)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "set a timer for 10 minutes",
                desiredOutcome = "timer counting down",
                foregroundPackage = "com.android.deskclock",
                screenSummary = "Clock",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing set_timer", prompt.contains("set_timer"))
    }

    @Test
    fun setTimerStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The timer is created by the clock app, a separate process no accessibility screen
        // reflects, so the step's default NONE verification can never be satisfied from a
        // fresh screen. The executor's honest dispatch result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SetTimer -> ActionResult(true, "TIMER_DISPATCHED", "Timer set for 10:00")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Set the timer","action":"set_timer","params":{"seconds":600},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("set a timer for 10 minutes", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("set_timer must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.SetTimer })
    }

    @Test
    fun setTimerFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a dispatch
        // that reached no clock app (TIMER_UNAVAILABLE) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SetTimer -> ActionResult(false, "TIMER_UNAVAILABLE", "No clock app is available to set a timer")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Set the timer","action":"set_timer","params":{"seconds":600},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("set a timer for 10 minutes", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed timer dispatch must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- add_calendar_event: the "add lunch with Sam tomorrow at 1pm" primitive ---

    @Test
    fun calendarEventBeginTimeResolvesRelativeDayAndTime() {
        // The calendar editor receives an absolute begin time; "tomorrow at 1pm" must
        // resolve to the next day at 13:00 in the device zone, not to today or to 01:00.
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val now = 1768435200000L // 2026-01-15T00:00:00Z
        assertEquals(1768467600000L, AndroidCalendarController.beginMillis(now, zone, 0, 9, 0))  // today 09:00
        assertEquals(1768568400000L, AndroidCalendarController.beginMillis(now, zone, 1, 13, 0)) // tomorrow 13:00
        assertEquals(1768608000000L, AndroidCalendarController.beginMillis(now, zone, 2, 0, 0))  // +2 days 00:00
    }

    @Test
    fun addCalendarEventExecutesWithoutAnAccessibilityService() {
        // The calendar insert is dispatched through the platform CalendarContract
        // ACTION_INSERT intent, so like the alarm/timer/audio primitives it is
        // service-free and must resolve before the a11y gate.
        val recorder = RecordingCalendarController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            calendarController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(
                AgentAction.AddCalendarEvent(
                    title = "Lunch with Sam",
                    dayOffset = 1,
                    startHour = 13,
                    startMinute = 0,
                    durationMinutes = 60,
                    location = "Cafe"
                )
            )
        }

        assertTrue("event must be addable without a bound a11y service: $result", result.success)
        assertEquals("CALENDAR_DISPATCHED", result.method)
        assertEquals(
            listOf(RecordingCalendarController.Call("Lunch with Sam", 1, 13, 0, 60, "Cafe")),
            recorder.calls
        )
    }

    @Test
    fun addCalendarEventRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            calendarController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddCalendarEvent(title = "Standup"))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("CALENDAR_UNAVAILABLE", result.method)
    }

    @Test
    fun addCalendarEventRejectsInvalidFields() {
        val recorder = RecordingCalendarController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            calendarController = recorder
        )
        val blank = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddCalendarEvent(title = "  "))
        }
        assertFalse(blank.success)
        assertEquals("INVALID_EVENT_TITLE", blank.method)
        val badDay = kotlinx.coroutines.runBlocking {
            automation.execute(
                AgentAction.AddCalendarEvent(title = "X", dayOffset = ToolRegistry.MAX_EVENT_DAY_OFFSET + 1)
            )
        }
        assertFalse(badDay.success)
        assertEquals("INVALID_EVENT_DAY", badDay.method)
        val badTime = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddCalendarEvent(title = "X", startHour = 24))
        }
        assertFalse(badTime.success)
        assertEquals("INVALID_EVENT_TIME", badTime.method)
        val badDuration = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddCalendarEvent(title = "X", durationMinutes = 0))
        }
        assertFalse(badDuration.success)
        assertEquals("INVALID_EVENT_DURATION", badDuration.method)
        assertTrue("an invalid event must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun addCalendarEventParserResolvesRelativeDayAndMeridiem() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Lunch tomorrow","action":"add_calendar_event","params":{"title":"Lunch with Sam","day":"tomorrow","startHour":1,"amPm":"pm","startMinute":0,"durationMinutes":60},"expect":"event added","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Standup today","action":"add_calendar_event","params":{"title":"Standup","startHour":9,"startMinute":30},"expect":"event added","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "add lunch with Sam tomorrow at 1pm")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(
            AgentAction.AddCalendarEvent(title = "Lunch with Sam", dayOffset = 1, startHour = 13, startMinute = 0, durationMinutes = 60),
            steps[0].action
        )
        assertEquals(
            AgentAction.AddCalendarEvent(title = "Standup", dayOffset = 0, startHour = 9, startMinute = 30, durationMinutes = 60),
            steps[1].action
        )
    }

    @Test
    fun validationAcceptsAddCalendarEventAndRejectsInvalidFields() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.AddCalendarEvent(title = "Standup")) is ToolValidationResult.Valid)
        assertTrue(
            "a blank title must be rejected at the tool boundary",
            registry.validateAction(AgentAction.AddCalendarEvent(title = "")) !is ToolValidationResult.Valid
        )
        assertTrue(
            "an out-of-range day offset must be rejected at the tool boundary",
            registry.validateAction(
                AgentAction.AddCalendarEvent(title = "X", dayOffset = ToolRegistry.MAX_EVENT_DAY_OFFSET + 1)
            ) !is ToolValidationResult.Valid
        )
        assertTrue(
            "an invalid 24-hour time must be rejected at the tool boundary",
            registry.validateAction(AgentAction.AddCalendarEvent(title = "X", startHour = 24)) !is ToolValidationResult.Valid
        )
        assertTrue(
            "a zero duration must be rejected at the tool boundary",
            registry.validateAction(AgentAction.AddCalendarEvent(title = "X", durationMinutes = 0)) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun addCalendarEventSkillKeepsItsFieldsOnReplay() {
        // A learned calendar skill must not replay at the defaults (today 09:00, 60m).
        val plan = com.example.planning.TaskPlan(
            goal = "add lunch with Sam tomorrow at 1pm",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "add the event",
                    action = AgentAction.AddCalendarEvent(
                        title = "Lunch with Sam", dayOffset = 1, startHour = 13,
                        startMinute = 0, durationMinutes = 90, location = "Cafe"
                    ),
                    rawActionName = "add_calendar_event", expectedPostcondition = "event added",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved calendar skill must be replayable", restored != null)
        assertEquals(
            AgentAction.AddCalendarEvent(
                title = "Lunch with Sam", dayOffset = 1, startHour = 13,
                startMinute = 0, durationMinutes = 90, location = "Cafe"
            ),
            restored!!.steps[0].action
        )
    }

    @Test
    fun promptAdvertisesAddCalendarEvent() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_ADD_CALENDAR_EVENT}",
            actions.contains(ToolRegistry.TOOL_ADD_CALENDAR_EVENT)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "add lunch with Sam tomorrow at 1pm",
                desiredOutcome = "event on the calendar",
                foregroundPackage = "com.android.calendar",
                screenSummary = "Calendar",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing add_calendar_event", prompt.contains("add_calendar_event"))
    }

    @Test
    fun addCalendarEventStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The event is created by the calendar app, a separate process no accessibility
        // screen reflects, so the step's default NONE verification can never be satisfied
        // from a fresh screen. The executor's honest dispatch result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.AddCalendarEvent -> ActionResult(true, "CALENDAR_DISPATCHED", "Opening calendar to add 'Lunch with Sam' at 13:00 tomorrow")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Add the event","action":"add_calendar_event","params":{"title":"Lunch with Sam","dayOffset":1,"startHour":13},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("add lunch with Sam tomorrow at 1pm", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("add_calendar_event must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.AddCalendarEvent })
    }

    @Test
    fun addCalendarEventFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a dispatch
        // that reached no calendar app (CALENDAR_UNAVAILABLE) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.AddCalendarEvent -> ActionResult(false, "CALENDAR_UNAVAILABLE", "No calendar app is available to add an event")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Add the event","action":"add_calendar_event","params":{"title":"Lunch"},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("add lunch tomorrow", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed calendar dispatch must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- add_contact: the "save Priya's number 98xxxxxx" primitive ---

    @Test
    fun addContactExecutesWithoutAnAccessibilityService() {
        // The contact insert is dispatched through the platform ContactsContract insert
        // intent, so like the calendar/alarm/timer primitives it is service-free and must
        // resolve before the a11y gate.
        val recorder = RecordingContactController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            contactController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(
                AgentAction.AddContact(name = "Priya", phone = "9876543210")
            )
        }

        assertTrue("a contact must be addable without a bound a11y service: $result", result.success)
        assertEquals("CONTACT_DISPATCHED", result.method)
        assertEquals(
            listOf(RecordingContactController.Call("Priya", "9876543210", null)),
            recorder.calls
        )
    }

    @Test
    fun addContactRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            contactController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddContact(name = "Priya", phone = "9876543210"))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("CONTACT_UNAVAILABLE", result.method)
    }

    @Test
    fun addContactRejectsInvalidFields() {
        val recorder = RecordingContactController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            contactController = recorder
        )
        val blank = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddContact(name = "  ", phone = "9876543210"))
        }
        assertFalse(blank.success)
        assertEquals("INVALID_CONTACT_NAME", blank.method)
        val noDetails = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.AddContact(name = "Priya"))
        }
        assertFalse(noDetails.success)
        assertEquals("INVALID_CONTACT_DETAILS", noDetails.method)
        assertTrue("an invalid contact must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun addContactParserCarriesNamePhoneAndEmail() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Save Priya","action":"add_contact","params":{"name":"Priya","phone":"9876543210"},"expect":"contact saved","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Save Sam email","action":"add_contact","params":{"name":"Sam","email":"sam@example.com"},"expect":"contact saved","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "save Priya's number")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(AgentAction.AddContact("Priya", "9876543210", null), steps[0].action)
        assertEquals(AgentAction.AddContact("Sam", null, "sam@example.com"), steps[1].action)
    }

    @Test
    fun validationAcceptsAddContactAndRejectsInvalidFields() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.AddContact(name = "Priya", phone = "123")) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.AddContact(name = "Sam", email = "s@e.com")) is ToolValidationResult.Valid)
        assertTrue(
            "a blank name must be rejected at the tool boundary",
            registry.validateAction(AgentAction.AddContact(name = "")) !is ToolValidationResult.Valid
        )
        assertTrue(
            "a contact with neither phone nor email must be rejected at the tool boundary",
            registry.validateAction(AgentAction.AddContact(name = "Priya")) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun addContactSkillKeepsItsFieldsOnReplay() {
        // A learned contact skill must not replay with a dropped phone/email.
        val plan = com.example.planning.TaskPlan(
            goal = "save Priya's number",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "add the contact",
                    action = AgentAction.AddContact(name = "Priya", phone = "9876543210", email = "priya@example.com"),
                    rawActionName = "add_contact", expectedPostcondition = "contact saved",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved contact skill must be replayable", restored != null)
        assertEquals(
            AgentAction.AddContact(name = "Priya", phone = "9876543210", email = "priya@example.com"),
            restored!!.steps[0].action
        )
    }

    @Test
    fun promptAdvertisesAddContact() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_ADD_CONTACT}",
            actions.contains(ToolRegistry.TOOL_ADD_CONTACT)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "save Priya's number 9876543210",
                desiredOutcome = "contact saved",
                foregroundPackage = "com.android.contacts",
                screenSummary = "Contacts",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing add_contact", prompt.contains("add_contact"))
    }

    @Test
    fun addContactStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The contact is created by the contacts app, a separate process no accessibility
        // screen reflects, so the step's default NONE verification can never be satisfied
        // from a fresh screen. The executor's honest dispatch result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.AddContact -> ActionResult(true, "CONTACT_DISPATCHED", "Opening contacts to add 'Priya' (9876543210)")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Add the contact","action":"add_contact","params":{"name":"Priya","phone":"9876543210"},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("save Priya's number 9876543210", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("add_contact must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.AddContact })
    }

    @Test
    fun addContactFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a dispatch
        // that reached no contacts app (CONTACT_UNAVAILABLE) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.AddContact -> ActionResult(false, "CONTACT_UNAVAILABLE", "No contacts app is available to add a contact")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Add the contact","action":"add_contact","params":{"name":"Priya","phone":"123"},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("save Priya's number", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed contact dispatch must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- dial_number: the "dial 9876543210" / "call Priya" primitive ---

    @Test
    fun dialNumberExecutesWithoutAnAccessibilityService() {
        // Opening the dialer is dispatched through the platform ACTION_DIAL intent, so
        // like the contact/calendar/alarm primitives it is service-free and must resolve
        // before the a11y gate.
        val recorder = RecordingDialerController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            dialerController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.DialNumber("9876543210"))
        }

        assertTrue("the dialer must open without a bound a11y service: $result", result.success)
        assertEquals("DIAL_DISPATCHED", result.method)
        assertEquals(listOf("9876543210"), recorder.calls)
    }

    @Test
    fun dialNumberRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            dialerController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.DialNumber("9876543210"))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("DIAL_UNAVAILABLE", result.method)
    }

    @Test
    fun dialNumberRejectsABlankNumber() {
        val recorder = RecordingDialerController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            dialerController = recorder
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.DialNumber("   "))
        }
        assertFalse(result.success)
        assertEquals("INVALID_DIAL_NUMBER", result.method)
        assertTrue("a blank number must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun dialNumberNormalizesAndRejectsUnsafeCharacters() {
        // The pure normalizer is the gate that keeps a bad plan value from opening a
        // nonsense tel: URI: it accepts digits plus + * # , ; and rejects everything else.
        assertEquals("+919876543210", AndroidDialerController.normalizeDialNumber(" +919876543210 "))
        assertEquals("*123#", AndroidDialerController.normalizeDialNumber("*123#"))
        assertNull(AndroidDialerController.normalizeDialNumber(""))
        assertNull(AndroidDialerController.normalizeDialNumber("   "))
        assertNull(AndroidDialerController.normalizeDialNumber("Priya"))
        assertNull(AndroidDialerController.normalizeDialNumber("tel:98765"))
        assertNull(AndroidDialerController.normalizeDialNumber("+"))
    }

    @Test
    fun dialNumberParserCarriesTheNumber() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Dial Priya","action":"dial_number","params":{"number":"9876543210"},"expect":"dialer open","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Dial support","action":"dial_number","params":{"phone":"+18005551234"},"expect":"dialer open","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "dial Priya")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(AgentAction.DialNumber("9876543210"), steps[0].action)
        assertEquals(AgentAction.DialNumber("+18005551234"), steps[1].action)
    }

    @Test
    fun validationAcceptsDialNumberAndRejectsABlankNumber() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.DialNumber("9876543210")) is ToolValidationResult.Valid)
        assertTrue(
            "a blank number must be rejected at the tool boundary",
            registry.validateAction(AgentAction.DialNumber("  ")) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun dialNumberSkillKeepsItsNumberOnReplay() {
        // A learned "dial this number" skill must not replay with a dropped number.
        val plan = com.example.planning.TaskPlan(
            goal = "dial Priya",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "open the dialer",
                    action = AgentAction.DialNumber("9876543210"),
                    rawActionName = "dial_number", expectedPostcondition = "dialer open",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved dialer skill must be replayable", restored != null)
        assertEquals(AgentAction.DialNumber("9876543210"), restored!!.steps[0].action)
    }

    @Test
    fun promptAdvertisesDialNumber() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_DIAL_NUMBER}",
            actions.contains(ToolRegistry.TOOL_DIAL_NUMBER)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "dial 9876543210",
                desiredOutcome = "dialer open",
                foregroundPackage = "com.android.dialer",
                screenSummary = "Dialer",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing dial_number", prompt.contains("dial_number"))
    }

    @Test
    fun dialNumberStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The dialer is a separate process no accessibility screen reflects, so the
        // step's default NONE verification can never be satisfied from a fresh screen.
        // The executor's honest dispatch result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.DialNumber -> ActionResult(true, "DIAL_DISPATCHED", "Opening the dialer with ${action.number}")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Open the dialer","action":"dial_number","params":{"number":"9876543210"},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("dial 9876543210", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("dial_number must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.DialNumber })
    }

    @Test
    fun dialNumberFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a dispatch
        // that reached no dialer (DIAL_UNAVAILABLE) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.DialNumber -> ActionResult(false, "DIAL_UNAVAILABLE", "No dialer app is available")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Open the dialer","action":"dial_number","params":{"number":"9876543210"},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("dial 9876543210", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed dialer dispatch must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- send_sms: the "text Priya that I'll be late" primitive ---

    @Test
    fun sendSmsExecutesWithoutAnAccessibilityService() {
        // Opening the messaging compose screen is dispatched through the platform
        // ACTION_SENDTO intent, so like the dialer/contact/calendar primitives it is
        // service-free and must resolve before the a11y gate.
        val recorder = RecordingSmsController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            smsController = recorder
        )

        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SendSms("9876543210", "I'll be late"))
        }

        assertTrue("the compose screen must open without a bound a11y service: $result", result.success)
        assertEquals("SMS_DISPATCHED", result.method)
        assertEquals(listOf(RecordingSmsController.Call("9876543210", "I'll be late")), recorder.calls)
    }

    @Test
    fun sendSmsRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            smsController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SendSms("9876543210", "I'll be late"))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("SMS_UNAVAILABLE", result.method)
    }

    @Test
    fun sendSmsRejectsBlankNumberOrMessage() {
        val recorder = RecordingSmsController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            smsController = recorder
        )
        val blankNumber = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SendSms("   ", "I'll be late"))
        }
        assertFalse(blankNumber.success)
        assertEquals("INVALID_SMS_NUMBER", blankNumber.method)
        val blankMessage = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.SendSms("9876543210", "  "))
        }
        assertFalse(blankMessage.success)
        assertEquals("INVALID_SMS_MESSAGE", blankMessage.method)
        assertTrue("an invalid SMS must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun sendSmsNormalizesAndRejectsUnsafeCharacters() {
        // The pure normalizer keeps a bad plan value from opening a nonsense smsto: URI:
        // it accepts digits plus + * # , ; and rejects everything else.
        assertEquals("+919876543210", AndroidSmsController.normalizeSmsNumber(" +919876543210 "))
        assertEquals("*123#", AndroidSmsController.normalizeSmsNumber("*123#"))
        assertNull(AndroidSmsController.normalizeSmsNumber(""))
        assertNull(AndroidSmsController.normalizeSmsNumber("   "))
        assertNull(AndroidSmsController.normalizeSmsNumber("Priya"))
        assertNull(AndroidSmsController.normalizeSmsNumber("smsto:98765"))
        assertNull(AndroidSmsController.normalizeSmsNumber("+"))
    }

    @Test
    fun sendSmsParserCarriesNumberAndMessage() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Text Priya","action":"send_sms","params":{"number":"9876543210","message":"I'll be late"},"expect":"compose open","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Text support","action":"send_sms","params":{"to":"+18005551234","body":"help"},"expect":"compose open","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "text Priya")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(AgentAction.SendSms("9876543210", "I'll be late"), steps[0].action)
        assertEquals(AgentAction.SendSms("+18005551234", "help"), steps[1].action)
    }

    @Test
    fun validationAcceptsSendSmsAndRejectsBlanks() {
        val registry = ToolRegistry()
        assertTrue(
            registry.validateAction(AgentAction.SendSms("9876543210", "hi")) is ToolValidationResult.Valid
        )
        assertTrue(
            "a blank number must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SendSms("  ", "hi")) !is ToolValidationResult.Valid
        )
        assertTrue(
            "a blank message must be rejected at the tool boundary",
            registry.validateAction(AgentAction.SendSms("9876543210", "  ")) !is ToolValidationResult.Valid
        )
    }

    @Test
    fun sendSmsSkillKeepsItsNumberAndMessageOnReplay() {
        // A learned "text this number" skill must not replay with a dropped body.
        val plan = com.example.planning.TaskPlan(
            goal = "text Priya",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "open messages",
                    action = AgentAction.SendSms("9876543210", "I'll be late"),
                    rawActionName = "send_sms", expectedPostcondition = "compose open",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )
        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )
        assertTrue("a saved SMS skill must be replayable", restored != null)
        assertEquals(AgentAction.SendSms("9876543210", "I'll be late"), restored!!.steps[0].action)
    }

    @Test
    fun promptAdvertisesSendSms() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue(
            "advertised actions are missing ${ToolRegistry.TOOL_SEND_SMS}",
            actions.contains(ToolRegistry.TOOL_SEND_SMS)
        )
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "text Priya 9876543210 that I'll be late",
                desiredOutcome = "compose open",
                foregroundPackage = "com.android.messaging",
                screenSummary = "Messages",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing send_sms", prompt.contains("send_sms"))
    }

    @Test
    fun sendSmsStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // The messaging app is a separate process no accessibility screen reflects, so
        // the step's default NONE verification can never be satisfied from a fresh
        // screen. The executor's honest dispatch result is the step's evidence.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SendSms -> ActionResult(true, "SMS_DISPATCHED", "Opening messages to text ${action.number}")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Open messages","action":"send_sms","params":{"number":"9876543210","message":"I'll be late"},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("text Priya that I'll be late", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("send_sms must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.SendSms })
    }

    @Test
    fun sendSmsFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule only accepts a genuinely successful result: a dispatch
        // that reached no messaging app (SMS_UNAVAILABLE) must fail the step.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.SendSms -> ActionResult(false, "SMS_UNAVAILABLE", "No messaging app is available")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Open messages","action":"send_sms","params":{"number":"9876543210","message":"I'll be late"},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("text Priya that I'll be late", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed SMS dispatch must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- clear_text: the "empty the field before retyping" primitive ---

    @Test
    fun parserMapsClearTextToTheClearAction() {
        val parser = TaskPlanParser()
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Empty the search box","action":"clear_text","params":{"targetText":"Search"},"expect":"empty","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Empty the focused field","action":"clear_text","params":{},"expect":"empty","verify":"NONE"}
        ]}
        """.trimIndent()

        val result = parser.parse(json, "clear the query")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(2, steps.size)
        assertEquals(AgentAction.ClearText("Search"), steps[0].action)
        // A missing target label is valid: it means "clear whichever field is focused".
        assertEquals(AgentAction.ClearText(null), steps[1].action)
    }

    @Test
    fun validationAcceptsClearTextWithOrWithoutATarget() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.ClearText()) is ToolValidationResult.Valid)
        assertTrue(registry.validateAction(AgentAction.ClearText("Search")) is ToolValidationResult.Valid)
    }

    @Test
    fun fastPathClearsOnlyWhenAnEditableFieldIsExposed() {
        val executor = HybridStepExecutor(AppDirectory(context))
        val step = planStep(1, AgentAction.ClearText(null), "clear_text")

        // A visible editable field: the clear is deterministic (no model call needed).
        val withField = executor.decide(listOf(step), 0, observation("com.android.settings", "Search", 3, editable = true), nudgeUsed = false)
        assertTrue("expected a deterministic Execute, got $withField", withField is FastPathDecision.Execute)

        // No editable field anywhere: defer to the model rather than dispatch a clear
        // that cannot target anything.
        val withoutField = executor.decide(listOf(step), 0, observation("com.android.settings", "Settings list", 4), nudgeUsed = false)
        assertTrue("expected ModelReason, got $withoutField", withoutField is FastPathDecision.ModelReason)
    }

    @Test
    fun promptAdvertisesClearText() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue("advertised actions are missing ${ToolRegistry.TOOL_CLEAR_TEXT}", actions.contains(ToolRegistry.TOOL_CLEAR_TEXT))

        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "clear the search box",
                desiredOutcome = "field is empty",
                foregroundPackage = "com.android.settings",
                screenSummary = "Search",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing clear_text", prompt.contains("clear_text"))
    }

    @Test
    fun clearTextSkillKeepsItsTargetOnReplay() {
        // A learned skill that clears a named field must not replay as "clear focused":
        // the serializer has to persist targetText, not drop it.
        val plan = com.example.planning.TaskPlan(
            goal = "empty the search box",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "clear the Search box",
                    action = AgentAction.ClearText("Search"),
                    rawActionName = "clear_text", expectedPostcondition = "Search is empty",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )

        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )

        assertTrue("a saved clear-text skill must be replayable", restored != null)
        assertEquals(AgentAction.ClearText("Search"), restored!!.steps[0].action)
    }

    // --- play_media: the transport-control primitive (no UI tap can reach an
    // arbitrary player's pause/next) ---

    @Test
    fun parserMapsPlayMediaToTheTransportAction() {
        val json = """
        {"steps":[
          {"id":1,"seq":1,"desc":"Pause the music","action":"play_media","params":{"transport":"pause"},"expect":"paused","verify":"NONE"},
          {"id":2,"seq":2,"desc":"Next track","action":"play_media","params":{},"expect":"next","verify":"NONE"}
        ]}
        """.trimIndent()
        val result = TaskPlanParser().parse(json, "pause the music")
        assertTrue("parse failed: $result", result is PlanParseResult.Success)
        val steps = (result as PlanParseResult.Success).plan.steps
        assertEquals(AgentAction.PlayMedia("pause"), steps[0].action)
        // Missing transport defaults to toggle (the safe, state-flipping default).
        assertEquals(AgentAction.PlayMedia("toggle"), steps[1].action)
    }

    @Test
    fun validationAcceptsPlayMedia() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.PlayMedia("play")) is ToolValidationResult.Valid)
    }

    @Test
    fun playMediaExecutesWithoutAnAccessibilityService() {
        // Media transport dispatches a standard media key through the AudioManager, so it
        // is service-free: a planned "pause" must run even with no bound a11y service.
        val recorder = RecordingMediaController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            mediaController = recorder
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.PlayMedia("pause"))
        }
        assertTrue("media control must succeed without a bound a11y service: $result", result.success)
        assertEquals("MEDIA_KEY_VERIFIED", result.method)
        assertEquals(listOf(MediaTransport.PAUSE), recorder.calls)
    }

    @Test
    fun playMediaRefusesHonestlyWhenMediaIsUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            mediaController = null
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.PlayMedia("play"))
        }
        assertFalse("no controller must not be a fake success", result.success)
        assertEquals("MEDIA_UNAVAILABLE", result.method)
    }

    @Test
    fun playMediaRejectsAnUnknownTransport() {
        val recorder = RecordingMediaController()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            mediaController = recorder
        )
        val result = kotlinx.coroutines.runBlocking {
            automation.execute(AgentAction.PlayMedia("rewind"))
        }
        assertFalse(result.success)
        assertEquals("INVALID_TRANSPORT", result.method)
        assertTrue("an invalid transport must not touch the controller", recorder.calls.isEmpty())
    }

    @Test
    fun promptAdvertisesPlayMedia() {
        val actions = PlannerPromptBuilder.availableActions(ToolRegistry())
        assertTrue("advertised actions are missing ${ToolRegistry.TOOL_PLAY_MEDIA}", actions.contains(ToolRegistry.TOOL_PLAY_MEDIA))

        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "pause the music",
                desiredOutcome = "playback paused",
                foregroundPackage = "com.android.settings",
                screenSummary = "Now playing",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = actions
            )
        )
        assertTrue("prompt catalogue is missing play_media", prompt.contains("play_media"))
    }

    @Test
    fun playMediaSkillKeepsItsTransportOnReplay() {
        // A learned skill that pauses must not replay as the default toggle: the
        // serializer has to persist the transport.
        val plan = com.example.planning.TaskPlan(
            goal = "pause the music",
            fromModel = true,
            steps = listOf(
                com.example.planning.PlanStep(
                    id = 1, sequence = 1, description = "pause playback",
                    action = AgentAction.PlayMedia("pause"),
                    rawActionName = "play_media", expectedPostcondition = "paused",
                    verification = com.example.planning.VerificationMethod.NONE
                )
            )
        )

        val restored = com.example.planning.TaskPlanSerializer.fromJson(
            com.example.planning.TaskPlanSerializer.toJson(plan),
            goalOverride = plan.goal
        )

        assertTrue("a saved media skill must be replayable", restored != null)
        assertEquals(AgentAction.PlayMedia("pause"), restored!!.steps[0].action)
    }

    // --- 9.4 cross-app variables: a read value must reach a later step's action ---

    @Test
    fun capturedReadTextReachesALaterStepInAnotherApp() = kotlinx.coroutines.runBlocking {
        // End-to-end 9.4: a value read in one app must be usable, via {{name}}, by a
        // later step in another app. This is the acceptance scenario
        // ("copy that price, open Notes, paste it").
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Notes", 1, editable = true)
            executeResult = { action ->
                when (action) {
                    is AgentAction.ReadText -> ActionResult(
                        true, "READ_TEXT", "read 8 chars", readText = "₹1,299"
                    )
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        // Step 1 reads the price into {{price}}; step 2 (enter_text) types it into the
        // next app's notes field. read_text keeps its default NONE verification.
        val engine = modelEngine(
            """{"steps":[
                {"id":1,"seq":1,"desc":"Read the price","action":"read_text","params":{},"captures":["price"]},
                {"id":2,"seq":2,"desc":"Type the price into Notes","action":"enter_text","params":{"text":"price: {{price}}"},"verify":"NONE"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("copy the price into Notes", maxSteps = 6)

        awaitTerminal(orchestrator)

        val typed = automation.executed.filterIsInstance<AgentAction.TypeText>()
        assertTrue("the read value never reached the later TypeText", typed.isNotEmpty())
        assertEquals("price: ₹1,299", typed.last().text)
    }

    @Test
    fun readTextStepCompletesFromItsOwnCapturedTextWithoutAScreenChange() = kotlinx.coroutines.runBlocking {
        // The gate that had made 9.4 dead: a read_text step defaults to NONE
        // verification, which is never satisfiable on its own, so the read could never
        // complete and could never capture. A read that returns text now satisfies itself.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.settings", "About phone", 5)
            executeResult = { action ->
                when (action) {
                    is AgentAction.ReadText -> ActionResult(
                        true, "READ_TEXT", "read 6 chars", readText = "Pixel 8"
                    )
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Read the device model","action":"read_text","params":{},"captures":["model"]}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("what model is this", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("read step with NONE verification must complete, got $terminal", terminal is OrchestratorState.Completed)
    }

    @Test
    fun readNotificationsStepCompletesFromItsOwnDigestWithoutAScreenChange() = kotlinx.coroutines.runBlocking {
        // 9.9 capability loss: read_notifications defaults to NONE verification, and the
        // executor's digest was not carried as readText, so verifyAction could never
        // satisfy the step. The step retried to exhaustion and every triage read failed,
        // making the goal gate's ReadNotifications allowance unreachable. The digest a
        // read returns must be its own evidence, exactly like read_text/read_page_content.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.ReadNotifications ->
                        ActionResult(true, "READ_NOTIFICATIONS", "com.app: Ping — hello", readText = "com.app: Ping — hello")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Read notifications","action":"read_notifications","params":{}}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("what notifications do I have", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("read_notifications with NONE verification must complete, got $terminal", terminal is OrchestratorState.Completed)
    }

    @Test
    fun failedReadDoesNotSatisfyACaptureStepAndTheRunFailsHonestly() = kotlinx.coroutines.runBlocking {
        // 9.4 freshness: an empty/failed read must not satisfy the capture step, so the
        // following step can never be handed a stale or blank value.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.ReadText -> ActionResult(true, "READ_TEXT", "no visible text", readText = null)
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[
                {"id":1,"seq":1,"desc":"Read the price","action":"read_text","params":{},"captures":["price"]},
                {"id":2,"seq":2,"desc":"Type it","action":"enter_text","params":{"text":"price: {{price}}"},"verify":"NONE"}
            ]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("copy the price into Notes", maxSteps = 4)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("an unreadable capture must fail the run, got $terminal", terminal is OrchestratorState.Failed)
        // The blank value must never be typed into the later app.
        val typed = automation.executed.filterIsInstance<AgentAction.TypeText>()
        assertTrue("no bogus value may be typed", typed.none { it.text == "price: " })
    }

    @Test
    fun variableStoreTracksSourceFreshnessAndRefusesSecrets() {
        val store = com.example.planning.PlanVariableStore()
        assertTrue(store.set("price", "₹100", source = "read_text", nowMs = 1_000))
        assertEquals("₹100", store.get("price"))
        assertEquals("read_text", store.variable("price")!!.source)
        assertEquals(1_000L, store.variable("price")!!.capturedAtMs)

        // A re-capture refreshes the value and its timestamp (freshness).
        assertTrue(store.set("price", "₹200", source = "read_text", nowMs = 2_000))
        assertEquals("₹200", store.get("price"))
        assertEquals(2_000L, store.variable("price")!!.capturedAtMs)

        // Sensitive content is refused, never stored, never substituted.
        assertFalse(store.set("card", "4111 1111 1111 1111"))
        assertFalse(store.set("pw", "password: hunter2"))
        assertNull(store.get("card"))
        assertNull(store.get("pw"))
    }

    @Test
    fun playMediaStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        // A media transport command acts on the AudioManager, not the accessibility
        // tree, so its default NONE verification can never be satisfied from a fresh
        // screen. The executor's honest read-back result is the step's evidence; without
        // this the step would be retried forever and the run could never complete.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.spotify.music", "Now playing", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.PlayMedia -> ActionResult(true, "MEDIA_PLAY", "playback started")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Play the music","action":"play_media","params":{"transport":"play"},"verify":"NONE"}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("play the music", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("play_media must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.PlayMedia })
    }

    @Test
    fun adjustVolumeStepCompletesFromItsOwnHonestResult() = kotlinx.coroutines.runBlocking {
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.AdjustVolume -> ActionResult(true, "AUDIO_MEDIA", "raised media volume by 2")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Turn it up","action":"adjust_volume","params":{"stream":"media","steps":2}}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("turn the volume up", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("adjust_volume must complete from its own result, got $terminal", terminal is OrchestratorState.Completed)
        assertTrue(automation.executed.any { it is AgentAction.AdjustVolume })
    }

    @Test
    fun playMediaFailureIsNotMaskedByTheSelfEvidencingRule() = kotlinx.coroutines.runBlocking {
        // The self-evidencing rule must only accept a genuinely successful result: a
        // transport command whose playback state did not move is reported as a failure
        // by the executor and must fail the step, never a fabricated success.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.spotify.music", "Paused", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.PlayMedia -> ActionResult(false, "MEDIA_UNCHANGED", "playback state did not change")
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Play the music","action":"play_media","params":{"transport":"play"},"verify":"NONE","maxRetries":0}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("play the music", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("a failed media command must fail the run, got $terminal", terminal is OrchestratorState.Failed)
    }

    // --- read_device_status: a device-status read is a capability no screen read can answer ---

    @Test
    fun readDeviceStatusExecutesWithoutAnAccessibilityService() {
        // Battery/screen/ringer/connectivity live on system services, not the a11y tree,
        // so the read must resolve before the a11y-service gate (like ReadNotifications).
        // Before this it would have fallen through to NO_SERVICE when the service was down.
        val reader = RecordingDeviceStatusReader()
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            deviceStatusReader = reader
        )

        val result = kotlinx.coroutines.runBlocking { automation.execute(AgentAction.ReadDeviceStatus) }

        assertTrue("device-status read must succeed without a bound a11y service: $result", result.success)
        assertEquals("READ_DEVICE_STATUS", result.method)
        assertEquals(1, reader.calls)
        assertEquals(
            "the digest must be carried as readText so a NONE step can self-satisfy",
            "Battery 80% | Screen on | Ringer silent | Network wifi",
            result.readText
        )
    }

    @Test
    fun readDeviceStatusRefusesHonestlyWhenUnwired() {
        val automation = AndroidDeviceAutomation(
            appDirectory = AppDirectory(context),
            serviceProvider = { null },
            deviceStatusReader = null
        )
        val result = kotlinx.coroutines.runBlocking { automation.execute(AgentAction.ReadDeviceStatus) }
        assertFalse("an unwired reader must not be a fake success", result.success)
        assertEquals("STATUS_UNAVAILABLE", result.method)
    }

    @Test
    fun readDeviceStatusStepCompletesFromItsOwnDigestWithoutAScreenChange() = kotlinx.coroutines.runBlocking {
        // Same self-evidencing class as read_notifications/read_text: the digest IS the
        // goal evidence, so a NONE-verified step must complete rather than retry to
        // exhaustion, and the goal gate must accept it.
        val automation = FakeDeviceAutomation().apply {
            defaultObservation = observation("com.android.launcher", "Home screen", 1)
            executeResult = { action ->
                when (action) {
                    is AgentAction.ReadDeviceStatus ->
                        ActionResult(
                            true,
                            "READ_DEVICE_STATUS",
                            "Battery 55% | Screen on | Ringer normal | Network wifi",
                            readText = "Battery 55% | Screen on | Ringer normal | Network wifi"
                        )
                    else -> ActionResult(true, "OK", "ok")
                }
            }
        }
        val engine = modelEngine(
            """{"steps":[{"id":1,"seq":1,"desc":"Read the battery level","action":"read_device_status","params":{}}]}"""
        )
        val orchestrator = newOrchestrator(engine, automation)
        orchestrator.startTask("how much battery do I have", maxSteps = 3)

        val terminal = awaitTerminal(orchestrator)
        assertTrue("read_device_status with NONE verification must complete, got $terminal", terminal is OrchestratorState.Completed)
    }

    @Test
    fun parserMapsReadDeviceStatusToTheAction() {
        val parsed = TaskPlanParser().parse(
            """{"steps":[{"id":1,"seq":1,"desc":"Battery?","action":"read_device_status","params":{}}]}""",
            "how much battery do I have"
        )
        assertTrue("plan must parse: $parsed", parsed is PlanParseResult.Success)
        val step = (parsed as PlanParseResult.Success).plan.steps.first()
        assertTrue("read_device_status must map to AgentAction.ReadDeviceStatus", step.action is AgentAction.ReadDeviceStatus)
    }

    @Test
    fun validationAcceptsReadDeviceStatus() {
        val registry = ToolRegistry()
        assertTrue(registry.validateAction(AgentAction.ReadDeviceStatus) is ToolValidationResult.Valid)
    }

    @Test
    fun promptAdvertisesReadDeviceStatus() {
        assertTrue(
            "the planner must be told about read_device_status",
            PlannerPromptBuilder.availableActions(ToolRegistry()).contains(ToolRegistry.TOOL_READ_DEVICE_STATUS)
        )
    }

    @Test
    fun readDeviceStatusDigestIsPureAndHonestAboutUnknownReadings() {
        // The digest text is the planner-visible evidence, so its formatting is asserted
        // directly; an unavailable reading must say "unknown" rather than invent a value.
        assertEquals(
            "Battery 42% (charging) | Screen off | Ringer vibrate | Network cellular",
            AndroidDeviceStatusReader.format(42, charging = true, screenOn = false, ringerMode = "vibrate", connectivity = "cellular")
        )
        assertEquals(
            "Battery unknown | Screen on | Ringer unknown | Network offline",
            AndroidDeviceStatusReader.format(-1, charging = false, screenOn = true, ringerMode = "unknown", connectivity = "offline")
        )
    }

    @Test
    fun readDeviceStatusSkillKeepsItsToolNameOnReplay() {
        val plan = TaskPlan(
            goal = "battery check",
            fromModel = true,
            steps = listOf(planStep(1, AgentAction.ReadDeviceStatus, "read_device_status"))
        )
        val restored = TaskPlanSerializer.fromJson(TaskPlanSerializer.toJson(plan), goalOverride = plan.goal)
        assertTrue("a saved skill must replay read_device_status", restored != null)
        assertTrue(
            "replayed step must be ReadDeviceStatus",
            restored!!.steps.first().action is AgentAction.ReadDeviceStatus
        )
    }

    // --- Current date/time (relative-time resolution for set_alarm) ---

    @Test
    fun formatDateTimeRendersTheLocalClockForThePrompt() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val text = PlannerPromptBuilder.formatDateTime(0L, utc)
        assertTrue("expected yyyy-MM-dd HH:mm + weekday, got '$text'", text.startsWith("1970-01-01 00:00"))
        assertTrue("expected a weekday name in '$text'", text.contains("Thursday"))
    }

    @Test
    fun planningPromptCarriesTheCurrentDateTimeForRelativeTimeResolution() {
        val prompt = PlannerPromptBuilder.buildPlanningPrompt(
            PlanningRequest(
                userCommand = "set an alarm for 7am",
                desiredOutcome = "alarm at 07:00",
                foregroundPackage = "com.android.deskclock",
                screenSummary = "Clock",
                completedStepsSummary = "",
                lastActionResult = null,
                remainingStepBudget = 10,
                availableActions = PlannerPromptBuilder.availableActions(ToolRegistry()),
                currentDateTime = "2026-10-12 01:31 Sunday"
            )
        )
        assertTrue(
            "the planner must see the current date/time to resolve 'in 20 minutes'",
            prompt.contains("2026-10-12 01:31 Sunday")
        )
    }

    @Test
    fun planTaskInjectsTheCurrentDateTimeWhenTheCallerOmitsIt() {
        val runtime = FakeLlmRuntime(output = """{"steps":[{"id":1,"action":"read_device_status"}]}""")
        val engine = buildTestEngine(context, runtimeFactory = { _, _ -> runtime })
        kotlinx.coroutines.runBlocking { engine.initialize() }

        kotlinx.coroutines.runBlocking {
            engine.planTask(
                goal = "what time is it",
                screenSummary = "Home",
                foregroundPackage = "com.example.home",
                historySummary = "",
                lastActionVerification = null,
                remainingStepBudget = 10
            )
        }

        val sentPrompt = runtime.prompts.lastOrNull()
        assertTrue("planner prompt was not generated", sentPrompt != null)
        assertTrue(
            "AgentPlanner must fill the current date/time so a relative time can be resolved",
            sentPrompt!!.contains("Current date/time: ") &&
                !sentPrompt.contains("Current date/time: \n")
        )
    }

    // --- Helpers ---

    private class RecordingDeviceStatusReader : DeviceStatusReader {
        var calls = 0
        override fun read(): DeviceStatusResult {
            calls++
            return DeviceStatusResult(
                success = true,
                method = "READ_DEVICE_STATUS",
                message = "Battery 80% | Screen on | Ringer silent | Network wifi"
            )
        }
    }

    private class RecordingAudioController : AudioController {
        val calls = mutableListOf<Triple<AudioStream, Int, Boolean>>()
        override fun adjust(stream: AudioStream, steps: Int, mute: Boolean): AudioAdjustResult {
            calls += Triple(stream, steps, mute)
            return AudioAdjustResult(true, stream.name, "ok")
        }
    }

    private class RecordingMediaController : MediaController {
        val calls = mutableListOf<MediaTransport>()
        override fun control(transport: MediaTransport): MediaControlResult {
            calls += transport
            return MediaControlResult(true, "MEDIA_KEY_VERIFIED", "ok")
        }
    }

    private class RecordingRingerModeController : RingerModeController {
        val calls = mutableListOf<RingerMode>()
        override fun set(mode: RingerMode): RingerModeResult {
            calls += mode
            return RingerModeResult(true, "RINGER_MODE", "Ringer mode set to ${mode.key}")
        }
    }

    private class RecordingAlarmController : AlarmController {
        data class Call(val hour: Int, val minute: Int, val label: String?, val days: List<Int>)

        val calls = mutableListOf<Call>()
        override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>): AlarmResult {
            calls += Call(hour, minute, label, days)
            return AlarmResult(true, "ALARM_DISPATCHED", "Alarm set for %02d:%02d".format(hour, minute))
        }
    }

    private class RecordingTimerController : TimerController {
        data class Call(val seconds: Int, val label: String?)

        val calls = mutableListOf<Call>()
        override fun setTimer(seconds: Int, label: String?): TimerResult {
            calls += Call(seconds, label)
            return TimerResult(true, "TIMER_DISPATCHED", "Timer set for ${seconds}s")
        }
    }

    private class RecordingCalendarController : CalendarController {
        data class Call(
            val title: String,
            val dayOffset: Int,
            val startHour: Int,
            val startMinute: Int,
            val durationMinutes: Int,
            val location: String?
        )

        val calls = mutableListOf<Call>()
        override fun addEvent(
            title: String,
            dayOffset: Int,
            startHour: Int,
            startMinute: Int,
            durationMinutes: Int,
            location: String?
        ): CalendarResult {
            calls += Call(title, dayOffset, startHour, startMinute, durationMinutes, location)
            return CalendarResult(true, "CALENDAR_DISPATCHED", "Opening calendar to add '$title'")
        }
    }

    private class RecordingContactController : ContactController {
        data class Call(
            val name: String,
            val phone: String?,
            val email: String?
        )

        val calls = mutableListOf<Call>()
        override fun addContact(name: String, phone: String?, email: String?): ContactResult {
            calls += Call(name, phone, email)
            return ContactResult(true, "CONTACT_DISPATCHED", "Opening contacts to add '$name'")
        }
    }

    private class RecordingDialerController : DialerController {
        val calls = mutableListOf<String>()
        override fun dial(number: String): DialResult {
            calls += number
            return DialResult(true, "DIAL_DISPATCHED", "Opening the dialer with $number")
        }
    }

    private class RecordingSmsController : SmsController {
        data class Call(val number: String, val message: String)

        val calls = mutableListOf<Call>()
        override fun compose(number: String, message: String): SmsResult {
            calls += Call(number, message)
            return SmsResult(true, "SMS_DISPATCHED", "Opening messages to text $number")
        }
    }

    private fun planStep(id: Int, action: AgentAction, rawName: String) = com.example.planning.PlanStep(
        id = id,
        sequence = id,
        description = rawName,
        action = action,
        rawActionName = rawName,
        expectedPostcondition = "",
        verification = com.example.planning.VerificationMethod.NONE
    )

    /**
     * An engine whose planner returns a different raw plan on each successive planning
     * call, so replanning can be observed deterministically.
     */
    private fun sequencedEngine(plans: List<String>): LiteRtEngine {
        val runtime = SequencedLlmRuntime(plans)
        return buildTestEngine(context, runtimeFactory = { _, _ -> runtime }).also { it.initializeBlocking() }
    }

    private fun modelEngine(json: String): LiteRtEngine =
        buildTestEngine(context, runtimeFactory = { _, _ -> FakeLlmRuntime(output = json) }).also { it.initializeBlocking() }

    private fun LiteRtEngine.initializeBlocking(): Boolean =
        kotlinx.coroutines.runBlocking { initialize() }

    private fun newOrchestrator(engine: LiteRtEngine, automation: FakeDeviceAutomation): TaskOrchestrator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return TaskOrchestrator(
            context = context,
            liteRtEngine = engine,
            appDirectory = AppDirectory(context),
            deviceAutomation = automation,
            scope = scope,
            toolRegistry = ToolRegistry()
        )
    }

    private suspend fun awaitTerminal(
        orchestrator: TaskOrchestrator,
        timeoutMs: Long = 8_000
    ): OrchestratorState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = orchestrator.orchestratorState.value
            when (state) {
                is OrchestratorState.Completed, is OrchestratorState.Failed -> return state
                else -> delay(50)
            }
        }
        return orchestrator.orchestratorState.value
    }
}
