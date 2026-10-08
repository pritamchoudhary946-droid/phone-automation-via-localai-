package com.example.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accessibility.AgentAccessibilityService
import com.example.model.LiteRtModelManager
import com.example.model.ModelDownloadState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.MintSuccess

@Composable
fun SetupScreen(
    modelManager: LiteRtModelManager,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val downloadState by modelManager.downloadState.collectAsState()
    var isAccessibilityEnabled by remember {
        mutableStateOf(AgentAccessibilityService.isAccessibilitySettingsEnabled(context))
    }

    // Refresh accessibility state periodically
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = AgentAccessibilityService.isAccessibilitySettingsEnabled(context)
            kotlinx.coroutines.delay(1500)
        }
    }

    val (hasEnoughStorage, availableBytes) = remember { modelManager.checkStorageAvailability() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Hero Header
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CyanPrimary.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
                .border(1.5.dp, CyanPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "AI Agent Setup",
                tint = CyanPrimary,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "OmniAgent Initialization",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Local Gemma 4 E2B Engine Setup",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Step 1: Storage & Model Download Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_download_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Model Download",
                        tint = ElectricBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "1. Local Model Setup (Gemma 4 E2B)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Source: Hugging Face (LiteRT Community)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "File: gemma-4-E2B-it.litertlm (~2.59 GB)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = CyanPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Storage Status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Internal Storage:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${modelManager.formatBytes(availableBytes)} Free",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (hasEnoughStorage) MintSuccess else AmberWarning
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Download Progress & States
                when (val state = downloadState) {
                    is ModelDownloadState.Idle -> {
                        Button(
                            onClick = { modelManager.startOrResumeDownload() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_download_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download Gemma 4 E2B (~2.59 GB)", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    is ModelDownloadState.CheckingStorage -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = CyanPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Checking storage space...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ModelDownloadState.Downloading -> {
                        val animatedProgress by animateFloatAsState(
                            targetValue = state.progressPercentage / 100f,
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            label = "download_progress"
                        )

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = CyanPrimary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${String.format("%.1f", state.progressPercentage)}% (${modelManager.formatBytes(state.downloadedBytes)} / ${modelManager.formatBytes(state.totalBytes)})",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val speedStr = modelManager.formatBytes(state.speedBytesPerSec) + "/s"
                            Text(
                                text = speedStr,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = ElectricBlue
                            )
                        }

                        if (state.etaSeconds > 0) {
                            val etaMin = state.etaSeconds / 60
                            val etaSec = state.etaSeconds % 60
                            Text(
                                text = "Estimated time remaining: ${etaMin}m ${etaSec}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { modelManager.pauseDownload() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pause Download")
                        }
                    }
                    is ModelDownloadState.Paused -> {
                        Text(
                            text = "Download paused at ${modelManager.formatBytes(state.downloadedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { modelManager.startOrResumeDownload() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resume Download", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    is ModelDownloadState.Verifying -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.CenterHorizontally),
                            color = CyanPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Verifying LiteRT-LM model integrity...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ModelDownloadState.Ready -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MintSuccess.copy(alpha = 0.15f))
                                .border(1.dp, MintSuccess.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Ready",
                                tint = MintSuccess,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Model Ready (${state.sizeFormatted})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MintSuccess
                                )
                                Text(
                                    text = "LiteRT-LM Gemma 4 E2B loaded on device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    is ModelDownloadState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { modelManager.startOrResumeDownload() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Download", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Step 2: Accessibility Service Configuration Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("accessibility_service_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessibilityNew,
                        contentDescription = "Accessibility",
                        tint = CyanPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "2. Accessibility Service (UI Automation)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Allows OmniAgent to inspect UI elements (UI tree-first) and perform safe clicks, typing, and navigation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isAccessibilityEnabled) MintSuccess.copy(alpha = 0.15f)
                            else AmberWarning.copy(alpha = 0.15f)
                        )
                        .border(
                            1.dp,
                            if (isAccessibilityEnabled) MintSuccess.copy(alpha = 0.4f)
                            else AmberWarning.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAccessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccessibilityEnabled) MintSuccess else AmberWarning
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isAccessibilityEnabled) "Service Enabled & Active" else "Service Disabled",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isAccessibilityEnabled) MintSuccess else AmberWarning
                        )
                        Text(
                            text = if (isAccessibilityEnabled) "Ready to inspect and automate" else "Requires one-time toggle in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_accessibility_settings_button"),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.horizontalGradient(listOf(CyanPrimary, ElectricBlue)))
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Accessibility Settings", color = CyanPrimary)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Complete / Launch Button
        Button(
            onClick = onSetupComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("launch_agent_dashboard_button"),
            enabled = downloadState is ModelDownloadState.Ready || isAccessibilityEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Enter OmniAgent Dashboard",
                color = if (downloadState is ModelDownloadState.Ready || isAccessibilityEnabled) Color.Black else Color.Gray,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = if (downloadState is ModelDownloadState.Ready || isAccessibilityEnabled) Color.Black else Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
