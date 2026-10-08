package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.TaskOrchestrator
import com.example.data.local.AgentDatabase
import com.example.data.local.TaskEntity
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.MintSuccess
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHistoryScreen(
    database: AgentDatabase,
    orchestrator: TaskOrchestrator,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tasks by database.taskDao().getAllTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("Task History & Checkpoints", fontWeight = FontWeight.Bold) },
            actions = {
                if (tasks.isNotEmpty()) {
                    IconButton(onClick = {
                        scope.launch { database.taskDao().clearAllTasks() }
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear History")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.HistoryEdu,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No recorded task sessions yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Completed tasks and Room checkpoint snapshots will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks) { task ->
                    TaskHistoryCard(
                        task = task,
                        dateFormat = dateFormat,
                        onResume = { orchestrator.resumeTask(task.id) },
                        onDelete = {
                            scope.launch { database.taskDao().deleteTask(task.id) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TaskHistoryCard(
    task: TaskEntity,
    dateFormat: SimpleDateFormat,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_card_${task.id}"),
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
                Text(
                    text = dateFormat.format(Date(task.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AssistChip(
                    onClick = {},
                    label = { Text(task.status, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = when (task.status) {
                            "COMPLETED" -> MintSuccess
                            "RUNNING" -> CyanPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = task.userGoal,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!task.resultSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Outcome: ${task.resultSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Progress: Step ${task.currentStep} / ${task.maxSteps}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row {
                    if (task.status == "PAUSED" || task.status == "FAILED") {
                        FilledTonalButton(
                            onClick = onResume,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
