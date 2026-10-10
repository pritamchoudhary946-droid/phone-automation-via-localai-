package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.overlay.FloatingAgentDotService
import com.example.agent.AgentAction
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.data.local.TaskStepEntity
import com.example.model.EngineState
import com.example.model.LiteRtEngine
import com.example.planning.PlanStepStatus
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDashboardScreen(
    orchestrator: TaskOrchestrator,
    liteRtEngine: LiteRtEngine,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orchestratorState by orchestrator.orchestratorState.collectAsState()
    val currentTask by orchestrator.currentTask.collectAsState()
    val steps by orchestrator.currentSteps.collectAsState()
    val engineState by liteRtEngine.engineStateFlow().collectAsState()
    val activePlan by orchestrator.activePlan.collectAsState()
    val usesModelPlanning by orchestrator.usesModelPlanning.collectAsState()

    var goalInput by remember { mutableStateOf("") }
    var resourceStats by remember { mutableStateOf(liteRtEngine.getResourceStats()) }
    val context = LocalContext.current
    var isDotEnabled by remember { mutableStateOf(FloatingAgentDotService.isRunning) }
    var isListeningVoice by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    // 7.5: hands-free listening state, refreshed from the live Settings toggle.
    var handsFreeEnabled by remember { mutableStateOf(false) }
    val appSettings = remember(context) {
        (context.applicationContext as? com.example.OmniAgentApplication)?.settings
    }

    // 8.7/BS8: with 6.1 the app BLOCKS when the model is not loaded unless the user
    // explicitly opted into the labelled degraded (keyword) mode. Reflect that truth
    // in the UI instead of advertising a "Basic mode" that never runs.
    val modelReady = engineState is EngineState.Ready
    val basicFallbackExplicit = appSettings?.basicFallbackExplicit == true
    val canPlan = canStartTask(modelReady, basicFallbackExplicit)

    DisposableEffect(Unit) {
        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
        }
    }

    // 4.17: learn-from-demonstration. "Watch me" records the actions of a real run so
    // it can be saved and replayed as a skill. Wired here because it was implemented
    // in the orchestrator but had no reachable entry point.
    val watching by orchestrator.watching.collectAsState()
    val dashboardScope = rememberCoroutineScope()
    var watchSkillName by remember { mutableStateOf("") }

    fun saveWatchedDemonstration() {
        val goal = goalInput.ifBlank { "demonstrated task" }
        val name = watchSkillName.ifBlank { goal.take(60) }
        dashboardScope.launch {
            val saved = orchestrator.saveWatchedSkill(name, goal)
            if (saved != null) {
                Toast.makeText(context, "Saved skill \"$saved\"", Toast.LENGTH_SHORT).show()
                watchSkillName = ""
            } else {
                Toast.makeText(context, "Nothing was demonstrated yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Microphone permission required for voice commands", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Speech recognition not available on device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak goal for Jarvis...")
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningVoice = true
                    Toast.makeText(context, "Listening... Speak your goal now", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    isListeningVoice = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        goalInput = matches[0]
                        Toast.makeText(context, "Heard: \"${matches[0]}\"", Toast.LENGTH_SHORT).show()
                    }
                    // 7.5 hands-free: re-arm so the next utterance is caught without a tap.
                    if (handsFreeEnabled) startListening()
                }
                override fun onError(error: Int) {
                    isListeningVoice = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                        else -> "Speech recognition error: $error"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    // Ignore transient no-match/speech timeouts while hands-free.
                    if (handsFreeEnabled && error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        startListening()
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListeningVoice = false }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
        } catch (e: Exception) {
            isListeningVoice = false
            Toast.makeText(context, "Failed to start microphone: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 8.7/BS3: the opt-in wake-word detector now fronts the hands-free flow (it was
    // previously dead code). It arms only with opt-in + mic permission, exactly like
    // the manual voice button, so no audio is ever captured silently.
    val wakeWordDetector = remember(context) {
        com.example.voice.OptInWakeWordDetector(
            optIn = { (context.applicationContext as? com.example.OmniAgentApplication)?.settings?.handsFreeEnabled == true },
            hasMicPermission = {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            resourceStats = liteRtEngine.getResourceStats()
            isDotEnabled = FloatingAgentDotService.isRunning
            val handsFreeNow = appSettings?.handsFreeEnabled == true
            // Arm/disarm the wake-word seam with the live toggle, then start listening
            // once when hands-free is switched on (the listeners re-arm themselves).
            if (handsFreeNow) wakeWordDetector.start() else wakeWordDetector.stop()
            if (handsFreeNow && !handsFreeEnabled) startListening()
            handsFreeEnabled = handsFreeNow
            kotlinx.coroutines.delay(2000)
        }
    }

    // High-Risk Confirmation Dialog
    val waitingConfirmation = orchestratorState as? OrchestratorState.WaitingConfirmation
    if (waitingConfirmation != null) {
        AlertDialog(
            onDismissRequest = { orchestrator.confirmRiskAction(false) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Risk Warning",
                    tint = CrimsonDanger,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Confirm Sensitive Action",
                    fontWeight = FontWeight.Bold,
                    color = CrimsonDanger
                )
            },
            text = {
                Column {
                    Text(
                        text = "Category: ${waitingConfirmation.riskType}",
                        fontWeight = FontWeight.SemiBold,
                        color = AmberWarning
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = waitingConfirmation.warningMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Action: ${waitingConfirmation.pendingAction.getDisplayName()}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { orchestrator.confirmRiskAction(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger),
                    modifier = Modifier.testTag("confirm_risk_button")
                ) {
                    Text("Allow & Proceed", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { orchestrator.confirmRiskAction(false) },
                    modifier = Modifier.testTag("cancel_risk_button")
                ) {
                    Text("Reject Action")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top App Bar with RAM & Gemma Status
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 8.4: pulsing status orb - the Jarvis signature. Its halo/ring color
                    // mirrors the live engine state (ready / loading / failed).
                    val orbPulse by rememberInfiniteTransition(label = "jarvis_orb").animateFloat(
                        initialValue = 0.35f,
                        targetValue = 0.95f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "jarvis_orb_pulse"
                    )
                    val orbStatusColor = when (engineState) {
                        is EngineState.Ready -> MintSuccess
                        is EngineState.Loading -> AmberWarning
                        is EngineState.Failed -> CrimsonDanger
                        else -> CyanPrimary
                    }
                    Box(
                        modifier = Modifier.size(46.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(orbStatusColor.copy(alpha = 0.10f + 0.22f * orbPulse))
                        )
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(CyanPrimary, IndigoAccent)))
                                .border(1.5.dp, orbStatusColor.copy(alpha = orbPulse), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // 8.5: the in-app brand mark mirrors the launcher icon.
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(com.example.R.drawable.ic_jarvis_logo),
                                contentDescription = "Jarvis",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Jarvis",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (engineState) {
                                is EngineState.Ready -> "Gemma 4 E2B • LiteRT-LM (${(engineState as EngineState.Ready).backend})"
                                is EngineState.Loading -> "Loading local Gemma engine…"
                                is EngineState.Failed -> "Model load failed"
                                EngineState.NotInitialized -> "Model not loaded"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (engineState) {
                                is EngineState.Ready -> CyanPrimary
                                is EngineState.Loading -> AmberWarning
                                is EngineState.Failed -> CrimsonDanger
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // RAM & Resource Stats Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (resourceStats.isLowMemory) AmberWarning else MintSuccess)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "RAM: ${resourceStats.usedRamMb / 1024}G / ${resourceStats.totalRamMb / 1024}G",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active State Banner
        StateBanner(state = orchestratorState, onPause = { orchestrator.pauseCurrentTask() }, onStop = { orchestrator.cancelCurrentTask() })

        // 8.2: if the model failed to load, show the real reason and a retry - never a
        // silent "not loaded".
        (engineState as? EngineState.Failed)?.let { failed ->
            ModelLoadErrorCard(
                reason = failed.message,
                onRetry = {
                    (context.applicationContext as? com.example.OmniAgentApplication)
                        ?.initializeEngineIfModelReady()
                }
            )
        }

        // Main Content Area
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // 8.6: prominent "what Jarvis is doing right now" line while a task runs.
                NowDoingCard(orchestratorState)
            }
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Gol Dot (Floating Controller) Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("floating_dot_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (isDotEnabled) CyanPrimary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant)
                                        .border(1.5.dp, if (isDotEnabled) CyanPrimary else Color.Gray, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RadioButtonChecked,
                                        contentDescription = "Gol Dot",
                                        tint = if (isDotEnabled) CyanPrimary else Color.Gray,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Gol Dot (Floating Controller)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isDotEnabled) "Active on Screen • Tap to Speak" else "Tap Switch to Enable on Screen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDotEnabled) MintSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Switch(
                                checked = isDotEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (Settings.canDrawOverlays(context)) {
                                            FloatingAgentDotService.start(context)
                                            isDotEnabled = true
                                        } else {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        FloatingAgentDotService.stop(context)
                                        isDotEnabled = false
                                    }
                                },
                                modifier = Modifier.testTag("floating_dot_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Gol dot screen ke upar floating bubble ban jata hai. Kisi bhi app me rehte hue is dot par tap karke voice/text command de sakte hain, double tap karke screen read kar sakte hain, aur phone control kar sakte hain.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                // Goal Input Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("goal_input_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Assign Autonomous Goal",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = goalInput,
                            onValueChange = { goalInput = it },
                            placeholder = { Text("e.g. Open YouTube, search for educational video, and return home") },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (isListeningVoice) {
                                            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                                            isListeningVoice = false
                                        } else {
                                            startListening()
                                        }
                                    },
                                    modifier = Modifier.testTag("voice_input_button")
                                ) {
                                    Icon(
                                        imageVector = if (isListeningVoice) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = "Voice Input",
                                        tint = if (isListeningVoice) CrimsonDanger else CyanPrimary
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("goal_input_field"),
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Quick Suggestion Chips
                        Text(
                            text = "Suggestions:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SuggestionChip(
                                onClick = { goalInput = "Open YouTube, search for lo-fi beats, open it, and return to home screen" },
                                label = { Text("YouTube & Return", fontSize = 12.sp) },
                                modifier = Modifier.testTag("chip_youtube")
                            )
                            SuggestionChip(
                                onClick = { goalInput = "Open Settings and turn on Dark Mode" },
                                label = { Text("Settings Dark Mode", fontSize = 12.sp) },
                                modifier = Modifier.testTag("chip_settings")
                            )
                            SuggestionChip(
                                onClick = { goalInput = "Open Chrome and search for Android News" },
                                label = { Text("Chrome Search", fontSize = 12.sp) },
                                modifier = Modifier.testTag("chip_maps")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val isRunning = orchestratorState is OrchestratorState.Executing || orchestratorState is OrchestratorState.Planning

                        if (isRunning) {
                            Button(
                                onClick = { orchestrator.cancelCurrentTask() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("stop_agent_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Stop Agent Execution",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (goalInput.isNotBlank() && canPlan) {
                                        orchestrator.startTask(goalInput)
                                    } else if (!canPlan) {
                                        onNavigateToSetup()
                                    }
                                },
                                enabled = goalInput.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("execute_agent_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (canPlan) CyanPrimary else AmberWarning
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        canPlan -> "Run Phone Agent (Gemma 4 E2B)"
                                        else -> "Set up the model to run tasks"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            item {
                // Planning Mode Banner: honest about whether Gemma or the explicitly
                // opted-in degraded fallback is driving. When neither can plan, the app
                // is BLOCKED (6.1) - say so instead of implying a "Basic mode" runs.
                val bannerAccent = when {
                    usesModelPlanning -> CyanPrimary
                    canPlan -> AmberWarning
                    else -> CrimsonDanger
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("planning_mode_banner"),
                    colors = CardDefaults.cardColors(containerColor = bannerAccent.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                usesModelPlanning -> Icons.Default.AutoAwesome
                                canPlan -> Icons.Default.Info
                                else -> Icons.Default.Block
                            },
                            contentDescription = null,
                            tint = bannerAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when {
                                    usesModelPlanning -> "Local Gemma AI planning active"
                                    basicFallbackExplicit -> "Basic (rule-based) plan • degraded mode"
                                    else -> "Model required • tasks are blocked"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = bannerAccent
                            )
                            Text(
                                text = when {
                                    usesModelPlanning ->
                                        "Plans and next actions are generated by the on-device model. Every step is verified before advancing."
                                    basicFallbackExplicit ->
                                        "You enabled the degraded keyword planner, so a deterministic rule-based plan runs instead of AI. This is not AI planning."
                                    else ->
                                        "The local Gemma model is not loaded, so a task cannot start. Open Setup to download/load it, or enable the degraded planner in Capabilities."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                // 4.17 learn-from-demonstration: record a real run, then save it as a
                // re-runnable skill. Off by default; nothing is captured until tapped.
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("demonstration_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (watching) Icons.Default.FiberManualRecord else Icons.Default.School,
                                contentDescription = null,
                                tint = if (watching) CrimsonDanger else IndigoAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (watching) "Watching this run…" else "Learn from a demonstration",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (watching) {
                                "Let Jarvis run the task once; its verified steps are recorded below. Tap Save to keep the sequence as a reusable skill."
                            } else {
                                "Tap Watch me, run a task once so its steps are recorded, then Save it to replay later as a skill."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (watching) {
                            OutlinedTextField(
                                value = watchSkillName,
                                onValueChange = { watchSkillName = it },
                                label = { Text("Skill name (optional)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("skill_name_field")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { saveWatchedDemonstration() },
                                    enabled = orchestrator.hasWatchedTurns(),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("save_skill_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save skill", color = Color.White)
                                }
                                OutlinedButton(
                                    onClick = {
                                        orchestrator.stopWatching()
                                        watchSkillName = ""
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("cancel_watch_button"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        } else {
                            Button(
                                onClick = { orchestrator.startWatching() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("watch_me_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Watch me", color = Color.White)
                            }
                        }
                    }
                }
            }

            val plan = activePlan
            if (plan != null && plan.steps.isNotEmpty()) {
                item {
                    Text(
                        text = "Current Task Plan",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                items(plan.steps) { planStep ->
                    val statusColor = when (planStep.status) {
                        PlanStepStatus.COMPLETED -> MintSuccess
                        PlanStepStatus.FAILED -> CrimsonDanger
                        PlanStepStatus.EXECUTING -> ElectricBlue
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${planStep.sequence}.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = statusColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = planStep.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = planStep.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Continuous Multi-Step Timeline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${steps.size} Steps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (steps.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timeline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No active task execution steps",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Type a goal above and click 'Run Phone Agent' to observe Gemma's live planning & execution.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(steps) { step ->
                    StepTimelineItem(step = step)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun StateBanner(
    state: OrchestratorState,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    val (bgColor, textColor, text, icon) = when (state) {
        is OrchestratorState.Idle -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Agent Ready for Next Command",
            Icons.Default.PauseCircleOutline
        )
        is OrchestratorState.Planning -> Quad(
            CyanPrimary.copy(alpha = 0.15f),
            CyanPrimary,
            if (state.fromModel) "Gemma 4 E2B Reasoning • Step ${state.stepIndex}..."
            else "Degraded (rule-based) planning • Step ${state.stepIndex}...",
            if (state.fromModel) Icons.Default.Psychology else Icons.Default.Info
        )
        is OrchestratorState.Executing -> Quad(
            ElectricBlue.copy(alpha = 0.15f),
            ElectricBlue,
            "Executing Step ${state.stepIndex}: ${state.action.getDisplayName()}",
            Icons.Default.PlayArrow
        )
        is OrchestratorState.WaitingConfirmation -> Quad(
            CrimsonDanger.copy(alpha = 0.15f),
            CrimsonDanger,
            "Awaiting Confirmation for: ${state.riskType}",
            Icons.Default.Security
        )
        is OrchestratorState.Replanning -> Quad(
            AmberWarning.copy(alpha = 0.15f),
            AmberWarning,
            "Stuck Loop Detected • Replanning: ${state.reason}",
            Icons.Default.Autorenew
        )
        is OrchestratorState.Paused -> Quad(
            AmberWarning.copy(alpha = 0.15f),
            AmberWarning,
            "Task Paused at Step ${state.stepIndex}",
            Icons.Default.Pause
        )
        is OrchestratorState.Completed -> Quad(
            MintSuccess.copy(alpha = 0.15f),
            MintSuccess,
            "Task Completed: ${state.summary}",
            Icons.Default.CheckCircle
        )
        is OrchestratorState.Failed -> Quad(
            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.error,
            if (state.escalated) {
                "Need your help: ${state.blockedOn ?: state.reason}\n${state.suggestion ?: ""}".trim()
            } else {
                "Task Failed: ${state.reason}"
            },
            Icons.Default.Error
        )
    }

    Surface(
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 2
                )
            }

            if (state is OrchestratorState.Executing || state is OrchestratorState.Planning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = textColor)
                    }
                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = CrimsonDanger)
                    }
                }
            }
        }
    }
}

/**
 * 8.7/BS8: single source of truth for whether a task may start. With 6.1 the app is
 * BLOCKED without a loaded model unless the user explicitly enabled the labelled
 * degraded planner. Pure so the dashboard gating is unit-testable.
 */
internal fun canStartTask(modelReady: Boolean, basicFallbackExplicit: Boolean): Boolean =
    modelReady || basicFallbackExplicit

@Composable
fun StepTimelineItem(step: TaskStepEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(CyanPrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${step.stepIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = step.actionPayloadJson,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                AssistChip(
                    onClick = {},
                    label = { Text(step.status, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (step.status == "SUCCESS") MintSuccess else MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Reasoning
            Text(
                text = "Reasoning: ${step.reasoning}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Verification Result
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = if (step.verificationResult.startsWith("Success")) MintSuccess else AmberWarning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Verification: ${step.verificationResult}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * 8.6: the live "what Jarvis is doing now" line, derived from the orchestrator state.
 */
@Composable
private fun NowDoingCard(state: OrchestratorState) {
    val active = state is OrchestratorState.Planning ||
        state is OrchestratorState.Executing ||
        state is OrchestratorState.Replanning ||
        state is OrchestratorState.Paused
    if (!active) return

    val (accent, headline, detail) = when (state) {
        is OrchestratorState.Planning -> Triple(
            CyanPrimary,
            "Thinking about the next step",
            buildString {
                append("Using ")
                append(if (state.fromModel) "the on-device model" else "the basic rule engine")
                state.activeSubGoal?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
            }
        )
        is OrchestratorState.Executing -> Triple(
            ElectricBlue,
            state.action.getDisplayName(),
            state.reasoning.takeIf { it.isNotBlank() } ?: "Letting the device settle…"
        )
        is OrchestratorState.Replanning -> Triple(AmberWarning, "Recovering", state.reason)
        is OrchestratorState.Paused -> Triple(AmberWarning, "Paused", "Task paused at step ${state.stepIndex}")
        else -> Triple(CyanPrimary, "Working", "")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("now_doing_card"),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = accent,
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelLoadErrorCard(reason: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("model_load_error_card"),
        colors = CardDefaults.cardColors(containerColor = CrimsonDanger.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonDanger.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = CrimsonDanger,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Jarvis could not load the model",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = CrimsonDanger
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("model_load_retry_button"),
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retry load")
            }
        }
    }
}
