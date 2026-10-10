package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.data.local.SavedSkillEntity
import com.example.data.local.UserMemoryEntity

/**
 * Plan 9.6 + 9.5 surface: the user's learned skills and personal memory as
 * first-class, editable things. Skills can be replayed or deleted; memory facts
 * can be removed. Both are on-device only.
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? OmniAgentApplication
    var tab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Your learned routines and the facts Jarvis remembers, all on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Skills") },
                modifier = Modifier.testTag("library_tab_skills"))
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Memory") },
                modifier = Modifier.testTag("library_tab_memory"))
        }
        Spacer(Modifier.height(10.dp))
        if (tab == 0) SkillsTab(app) else MemoryTab(app)
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
                        onClick = { renameTarget = skill; renameText = skill.name },
                        modifier = Modifier.testTag("skill_rename_${skill.name}")
                    ) { Text("Aa", fontSize = 12.sp) }
                    IconButton(
                        onClick = { scope.launch { dao?.deleteSkill(skill.name) } },
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
}

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
