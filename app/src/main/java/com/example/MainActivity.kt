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
import com.example.model.ModelDownloadState
import com.example.ui.AgentDashboardScreen
import com.example.ui.AgentLiveMonitorScreen
import com.example.ui.SetupScreen
import com.example.ui.TaskHistoryScreen
import com.example.ui.theme.MyApplicationTheme

enum class AgentTab(val title: String, val icon: ImageVector) {
    AGENT("Agent", Icons.Default.SmartToy),
    MONITOR("Live Monitor", Icons.Default.Visibility),
    SETUP("Setup & Model", Icons.Default.Download),
    SETTINGS("Capabilities", Icons.Default.Tune),
    HISTORY("History", Icons.Default.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as OmniAgentApplication

        setContent {
            MyApplicationTheme {
                val modelState by app.modelManager.downloadState.collectAsState()
                val isModelReady = modelState is ModelDownloadState.Ready

                // Initialize (and smoke-test) the real engine once the model is verified,
                // and whenever the app starts with a verified model on disk.
                LaunchedEffect(isModelReady) {
                    if (isModelReady) app.initializeEngineIfModelReady()
                }

                var selectedTab by remember {
                    mutableStateOf(if (isModelReady) AgentTab.AGENT else AgentTab.SETUP)
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
                                    liteRtEngine = app.liteRtEngine,
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
                                    modelManager = app.modelManager,
                                    onSetupComplete = { selectedTab = AgentTab.AGENT }
                                )
                            }
                            AgentTab.SETTINGS -> {
                                com.example.ui.SettingsScreen()
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
