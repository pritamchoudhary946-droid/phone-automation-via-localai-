package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.OrchestratorState
import com.example.agent.TaskOrchestrator
import com.example.data.local.TaskStepEntity
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SimplifiedAgentStatus(val label: String, val color: Color, val icon: ImageVector) {
    IDLE("Idle", MintSuccess, Icons.Default.CheckCircle),
    THINKING("Thinking", CyanPrimary, Icons.Default.Psychology),
    ACTING("Acting", ElectricBlue, Icons.Default.TouchApp),
    REPLANNING("Replanning", AmberWarning, Icons.Default.Autorenew),
    AWAITING_CONFIRMATION("Awaiting Confirmation", CrimsonDanger, Icons.Default.Security),
    PAUSED("Paused", AmberWarning, Icons.Default.PauseCircleOutline)
}

/**
 * Composable screen monitoring live agent status (Idle / Thinking / Acting),
 * presenting the last executed actions with verification badges, and providing
 * a prominent 'Cancel Task' control to safely halt execution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLiveMonitorScreen(
    orchestrator: TaskOrchestrator,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val orchestratorState by orchestrator.orchestratorState.collectAsState()
    val currentTask by orchestrator.currentTask.collectAsState()
    val steps by orchestrator.currentSteps.collectAsState()

    // Map rich orchestrator state to simplified Status (Idle / Thinking / Acting / etc.)
    val simplifiedStatus = when (orchestratorState) {
        is OrchestratorState.Idle, is OrchestratorState.Completed -> SimplifiedAgentStatus.IDLE
        is OrchestratorState.Planning -> SimplifiedAgentStatus.THINKING
        is OrchestratorState.Executing -> SimplifiedAgentStatus.ACTING
        is OrchestratorState.Replanning -> SimplifiedAgentStatus.REPLANNING
        is OrchestratorState.WaitingConfirmation -> SimplifiedAgentStatus.AWAITING_CONFIRMATION
        is OrchestratorState.Paused -> SimplifiedAgentStatus.PAUSED
        is OrchestratorState.Failed -> SimplifiedAgentStatus.IDLE
    }

    val isAgentBusy = orchestratorState is OrchestratorState.Planning ||
            orchestratorState is OrchestratorState.Executing ||
            orchestratorState is OrchestratorState.Replanning

    // Pulsing animation for active status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Live Agent Monitor",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            AssistChip(
                onClick = {},
                label = { Text("Gemma 4 E2B", fontSize = 11.sp) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(CyanPrimary)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Hero Status Card (Idle / Thinking / Acting)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("agent_status_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pulsing Status Orb
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .scale(if (isAgentBusy) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(simplifiedStatus.color.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )
                        .border(2.dp, simplifiedStatus.color, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = simplifiedStatus.icon,
                        contentDescription = simplifiedStatus.label,
                        tint = simplifiedStatus.color,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Status: ${simplifiedStatus.label}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = simplifiedStatus.color,
                    modifier = Modifier.testTag("agent_status_text")
                )

                Spacer(modifier = Modifier.height(4.dp))

                val subText = when (val state = orchestratorState) {
                    is OrchestratorState.Idle -> "Standing by for user tasks."
                    is OrchestratorState.Planning -> "Gemma 4 E2B is reasoning on UI hierarchy (Step ${state.stepIndex})."
                    is OrchestratorState.Executing -> "Performing ${state.action.getDisplayName()}."
                    is OrchestratorState.Replanning -> "Resolving stuck loop: ${state.reason}"
                    is OrchestratorState.WaitingConfirmation -> "Waiting for safety approval for: ${state.riskType}"
                    is OrchestratorState.Paused -> "Task paused at step ${state.stepIndex}."
                    is OrchestratorState.Completed -> "Task completed successfully: ${state.summary}"
                    is OrchestratorState.Failed -> "Task halted: ${state.reason}"
                }

                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )

                if (currentTask != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Flag, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Active Goal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = currentTask?.userGoal ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action List Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Last Executed Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "${steps.size} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Last Actions List (Reverse Chronological: newest first)
        val recentSteps = remember(steps) { steps.reversed() }

        if (recentSteps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No actions executed yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recentSteps, key = { it.id }) { step ->
                    LiveActionItemCard(step = step)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Prominent 'Cancel Task' Control Button
        Button(
            onClick = { orchestrator.cancelCurrentTask() },
            enabled = isAgentBusy || orchestratorState is OrchestratorState.WaitingConfirmation || orchestratorState is OrchestratorState.Paused,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("cancel_task_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = CrimsonDanger,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = "Cancel Task", tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Cancel Task",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
fun LiveActionItemCard(step: TaskStepEntity) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("step_card_${step.stepIndex}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Action Icon
            val (icon, iconTint) = when {
                step.actionPayloadJson.contains("Tap") -> Icons.Default.TouchApp to CyanPrimary
                step.actionPayloadJson.contains("Type") -> Icons.Default.Keyboard to ElectricBlue
                step.actionPayloadJson.contains("Scroll") -> Icons.Default.Swipe to IndigoAccent
                step.actionPayloadJson.contains("Open") -> Icons.Default.Launch to MintSuccess
                step.actionPayloadJson.contains("Back") -> Icons.Default.ArrowBack to AmberWarning
                else -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Step ${step.stepIndex + 1}: ${step.actionType}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = timeFormat.format(Date(step.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = step.actionPayloadJson,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = CyanPrimary
                )

                if (step.reasoning.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Thinking: ${step.reasoning}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Verification Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isSuccess = step.verificationResult.startsWith("Success")
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isSuccess) MintSuccess else AmberWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = step.verificationResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSuccess) MintSuccess else AmberWarning
                    )
                }
            }
        }
    }
}
