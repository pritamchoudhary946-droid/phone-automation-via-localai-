package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.AgentDashboardScreen
import com.example.ui.AgentLiveMonitorScreen
import com.example.ui.SetupScreen
import com.example.ui.TaskHistoryScreen
import com.example.ui.theme.MyApplicationTheme

enum class AgentTab(val title: String, val icon: ImageVector) {
    AGENT("Agent", Icons.Default.SmartToy),
    MONITOR("Live Monitor", Icons.Default.Visibility),
    SETUP("Setup & API", Icons.Default.Key),
    SETTINGS("Capabilities", Icons.Default.Tune),
    LIBRARY("Library", Icons.Default.Bookmarks),
    HISTORY("History", Icons.Default.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as OmniAgentApplication

        setContent {
            MyApplicationTheme {
                val isConfigured = app.llmProviderConfig.isConfigured()

                // Initialize the engine once an LLM API is configured, and whenever the
                // app starts with a saved configuration.
                LaunchedEffect(isConfigured) {
                    if (isConfigured) app.initializeEngineIfConfigured()
                }

                var selectedTab by remember {
                    mutableStateOf(if (isConfigured) AgentTab.AGENT else AgentTab.SETUP)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("main_navigation_bar"),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp
                        ) {
                            AgentTab.values().forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = tab.title) },
                                    label = { Text(tab.title) },
                                    modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            AgentTab.AGENT -> {
                                AgentDashboardScreen(
                                    orchestrator = app.orchestrator,
                                    agentEngine = app.agentEngine,
                                    onNavigateToSetup = { selectedTab = AgentTab.SETUP }
                                )
                            }
                            AgentTab.MONITOR -> {
                                AgentLiveMonitorScreen(
                                    orchestrator = app.orchestrator,
                                    onNavigateBack = { selectedTab = AgentTab.AGENT }
                                )
                            }
                            AgentTab.SETUP -> {
                                SetupScreen(
                                    config = app.llmProviderConfig,
                                    onSetupComplete = { selectedTab = AgentTab.AGENT }
                                )
                            }
                            AgentTab.SETTINGS -> {
                                com.example.ui.SettingsScreen()
                            }
                            AgentTab.LIBRARY -> {
                                com.example.ui.LibraryScreen()
                            }
                            AgentTab.HISTORY -> {
                                TaskHistoryScreen(
                                    database = app.database,
                                    orchestrator = app.orchestrator,
                                    onNavigateBack = { selectedTab = AgentTab.AGENT }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
