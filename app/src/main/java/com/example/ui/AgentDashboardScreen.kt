package com.example.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentAction
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.data.local.TaskStepEntity
import com.example.model.LiteRtEngine
import com.example.ui.theme.*

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

    var goalInput by remember { mutableStateOf("") }
    var resourceStats by remember { mutableStateOf(liteRtEngine.getResourceStats()) }

    LaunchedEffect(Unit) {
        while (true) {
            resourceStats = liteRtEngine.getResourceStats()
            kotlinx.coroutines.delay(3000)
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
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CyanPrimary.copy(alpha = 0.2f))
                            .border(1.dp, CyanPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "OmniAgent",
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "OmniAgent",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Gemma 4 E2B • LiteRT-LM",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanPrimary
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
                            placeholder = { Text("e.g. Open YouTube and search for lo-fi chill music") },
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
                                onClick = { goalInput = "Open YouTube and search for lo-fi beats" },
                                label = { Text("YouTube Lo-Fi", fontSize = 12.sp) },
                                modifier = Modifier.testTag("chip_youtube")
                            )
                            SuggestionChip(
                                onClick = { goalInput = "Open Settings and turn on Dark Mode" },
                                label = { Text("Settings Dark Mode", fontSize = 12.sp) },
                                modifier = Modifier.testTag("chip_settings")
                            )
                            SuggestionChip(
                                onClick = { goalInput = "Open Maps and search for cafes" },
                                label = { Text("Maps Cafes", fontSize = 12.sp) },
                                modifier = Modifier.testTag("chip_maps")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (goalInput.isNotBlank()) {
                                    orchestrator.startTask(goalInput)
                                }
                            },
                            enabled = goalInput.isNotBlank() && orchestratorState !is OrchestratorState.Executing && orchestratorState !is OrchestratorState.Planning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("execute_agent_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Run Phone Agent (Gemma 4 E2B)",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
            "Gemma 4 E2B Reasoning • Step ${state.stepIndex}...",
            Icons.Default.Psychology
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
            "Task Failed: ${state.reason}",
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
