package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ActionResult
import com.example.agent.AgentAction
import com.example.agent.AndroidDeviceAutomation
import com.example.agent.AppDirectory
import com.example.agent.AudioAdjustResult
import com.example.agent.AudioController
import com.example.agent.AudioStream
import com.example.agent.FastPathDecision
import com.example.agent.HybridStepExecutor
import com.example.agent.MediaControlResult
import com.example.agent.MediaController
import com.example.agent.MediaTransport
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
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

    // --- Helpers ---

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
