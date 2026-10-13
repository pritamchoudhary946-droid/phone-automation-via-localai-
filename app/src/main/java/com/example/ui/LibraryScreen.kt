package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.OmniAgentApplication
import com.example.automation.AutomationGate
import com.example.data.local.AutomationEntity
import com.example.data.local.SavedSkillEntity
import com.example.data.local.UserMemoryEntity

/**
 * Plan 9.6 + 9.5 + 7.8 surface: the user's learned skills, the facts Jarvis
 * remembers, and the skills Jarvis is scheduled to run — all first-class, editable
 * things. Everything here is on-device only.
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? OmniAgentApplication
    var tab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Your learned routines, the facts Jarvis remembers, and what it runs on a schedule — all on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Skills") },
                modifier = Modifier.testTag("library_tab_skills"))
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Memory") },
                modifier = Modifier.testTag("library_tab_memory"))
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Automations") },
                modifier = Modifier.testTag("library_tab_automations"))
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            0 -> SkillsTab(app)
            1 -> MemoryTab(app)
            else -> AutomationsTab(app)
        }
    }
}

@Composable
private fun SkillsTab(app: OmniAgentApplication?) {
    val dao = app?.database?.skillDao()
    val skills by (dao?.getAllSkills() ?: kotlinx.coroutines.flow.flowOf(emptyList<SavedSkillEntity>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<SavedSkillEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var scheduleTarget by remember { mutableStateOf<SavedSkillEntity?>(null) }
    var scheduleMinutes by remember { mutableStateOf("60") }
    val orchestrator = app?.orchestrator
    val context = LocalContext.current
    val appSettings = app?.settings

    if (skills.isEmpty()) {
        EmptyHint("No saved skills yet. Run a task cleanly, or use \"Watch me\" on the Agent tab to record one.")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(skills, key = { it.name }) { skill ->
            Card(shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.SemiBold)
                        Text(skill.goal, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Ran ${skill.runCount} time(s)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            if (appSettings?.automationsEnabled != true) {
                                // Replay drives real UI automation; make the requirement explicit.
                                android.widget.Toast.makeText(
                                    context, "Enable scheduled automations to replay skills", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            orchestrator?.runSkill(skill)
                        },
                        modifier = Modifier.testTag("skill_replay_${skill.name}")
                    ) { Icon(Icons.Default.PlayArrow, contentDescription = "Replay") }
                    IconButton(
                        onClick = { scheduleTarget = skill; scheduleMinutes = "60" },
                        modifier = Modifier.testTag("skill_schedule_${skill.name}")
                    ) { Icon(Icons.Default.Add, contentDescription = "Schedule") }
                    IconButton(
                        onClick = { renameTarget = skill; renameText = skill.name },
                        modifier = Modifier.testTag("skill_rename_${skill.name}")
                    ) { Text("Aa", fontSize = 12.sp) }
                    IconButton(
                        onClick = { scope.launch { orchestrator?.deleteSkill(skill.name) } },
                        modifier = Modifier.testTag("skill_delete_${skill.name}")
                    ) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }

    renameTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename skill") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    singleLine = true, modifier = Modifier.testTag("skill_rename_field")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty() && newName != skill.name) {
                        scope.launch {
                            dao?.deleteSkill(skill.name)
                            dao?.upsertSkill(skill.copy(name = newName))
                        }
                    }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    scheduleTarget?.let { skill ->
        val lastScheduled by produceState(initialValue = 0, skill.name) {
            value = app?.database?.automationDao()?.getAutomationsForSkill(skill.name)?.size ?: 0
        }
        AlertDialog(
            onDismissRequest = { scheduleTarget = null },
            title = { Text("Schedule \"${skill.name}\"") },
            text = {
                Column {
                    Text(
                        "Jarvis will replay this routine on a repeating interval while scheduled " +
                            "automations are enabled.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scheduleMinutes,
                        onValueChange = { scheduleMinutes = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text("Every how many minutes?") },
                        modifier = Modifier.testTag("automation_interval_field")
                    )
                    if (lastScheduled > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Already scheduled ${lastScheduled} time(s).",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = scheduleMinutes.toIntOrNull() ?: 0
                    if (minutes >= AutomationGate.MIN_INTERVAL_MINUTES) {
                        scope.launch { orchestrator?.scheduleAutomation(skill.name, minutes) }
                        android.widget.Toast.makeText(
                            context,
                            "Scheduled every ${AutomationGate.intervalMinutes(minutes)}m",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        scheduleTarget = null
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Minimum interval is ${AutomationGate.MIN_INTERVAL_MINUTES} minutes",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onClick = { scheduleTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AutomationsTab(app: OmniAgentApplication?) {
    val dao = app?.database?.automationDao()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = app?.settings
    val automations by (dao?.getAllAutomations() ?: kotlinx.coroutines.flow.flowOf(emptyList<AutomationEntity>()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val enabled = settings?.automationsEnabled == true

    Column {
        if (!enabled) {
            Text(
                "Automations are OFF. Turn on \"Scheduled automations\" in Settings to let Jarvis run " +
                    "these routines by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        if (automations.isEmpty()) {
            EmptyHint("Nothing scheduled yet. Open the Skills tab and tap + on a saved skill to schedule it.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(automations, key = { it.id }) { a ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.skillName, fontWeight = FontWeight.SemiBold)
                                val interval = a.triggerValue.toIntOrNull()
                                    ?.let { AutomationGate.intervalMinutes(it) }
                                Text(
                                    if (a.triggerType == com.example.automation.AutomationTrigger.INTERVAL && interval != null)
                                        "Every $interval minutes" else a.triggerType,
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (a.enabled) "Enabled" else "Disabled",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        orchestrator(app)?.cancelAutomation(a.id)
                                        android.widget.Toast.makeText(
                                            context, "Automation removed", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                modifier = Modifier.testTag("automation_delete_${a.id}")
                            ) { Icon(Icons.Default.Delete, contentDescription = "Remove automation") }
                        }
                    }
                }
            }
        }
    }
}

private fun orchestrator(app: OmniAgentApplication?) = app?.orchestrator

@Composable
private fun MemoryTab(app: OmniAgentApplication?) {
    val dao = app?.database?.memoryDao()
    val scope = rememberCoroutineScope()
    val memories by produceState(initialValue = emptyList<UserMemoryEntity>(), dao) {
        value = dao?.getRecentMemories(50) ?: emptyList()
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { dao?.clearMemories() } },
                modifier = Modifier.testTag("memory_clear_button")
            ) { Text("Forget everything") }
        }
        Spacer(Modifier.height(10.dp))
        if (memories.isEmpty()) {
            EmptyHint("Nothing remembered yet. Say \"remember that ...\" to add a fact.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(memories, key = { it.key + it.updatedAt }) { m ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.key, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(m.value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(m.kind, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
