package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.example.model.LlmProvider
import com.example.model.LlmProviderConfig
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.MintSuccess

/**
 * Setup surface. The reasoning runs through a remote LLM API, so this screen connects
 * one (any provider, with the user's own key) instead of downloading a multi-GB model.
 */
@Composable
fun SetupScreen(
    config: LlmProviderConfig,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as? com.example.OmniAgentApplication

    var selectedProvider by remember { mutableStateOf(config.provider) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var baseUrl by remember { mutableStateOf(config.baseUrlOverride) }
    var model by remember { mutableStateOf(config.modelOverride) }
    var saved by remember { mutableStateOf(false) }

    var isAccessibilityEnabled by remember {
        mutableStateOf(AgentAccessibilityService.isAccessibilitySettingsEnabled(context))
    }
    var isOverlayEnabled by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    // Refresh permissions state periodically
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = AgentAccessibilityService.isAccessibilitySettingsEnabled(context)
            isOverlayEnabled = Settings.canDrawOverlays(context)
            kotlinx.coroutines.delay(1500)
        }
    }

    val isConfigured = apiKey.isNotBlank() && (baseUrl.isNotBlank() || selectedProvider.defaultBaseUrl.isNotBlank())

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
            text = "Jarvis Initialization",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Connect an LLM API",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Step 1: LLM API configuration card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("llm_api_card"),
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
                        imageVector = Icons.Default.Key,
                        contentDescription = "API key",
                        tint = ElectricBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "1. LLM API Setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Pick any provider and paste your own API key. Planning then runs through that API, so it is fast and needs no on-device model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Provider chooser
                Text(
                    text = "Provider",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                LlmProviderDropdown(
                    selected = selectedProvider,
                    onSelected = { p ->
                        selectedProvider = p
                        // Prefill the model with the provider default when the user has
                        // not typed their own; leave a custom model untouched.
                        if (model.isBlank() || model == selectedProvider.defaultModel) {
                            model = p.defaultModel
                        }
                        saved = false
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; saved = false },
                    label = { Text("API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("llm_api_key_field")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; saved = false },
                    label = { Text("Base URL (optional)") },
                    placeholder = { Text(selectedProvider.defaultBaseUrl.ifBlank { "https://your-endpoint/v1" }) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("llm_base_url_field")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; saved = false },
                    label = { Text("Model (optional)") },
                    placeholder = { Text(selectedProvider.defaultModel.ifBlank { "model-name" }) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("llm_model_field")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            config.save(selectedProvider, apiKey, baseUrl, model)
                            saved = true
                            app?.reloadEngineForConfigChange()
                        },
                        enabled = isConfigured,
                        modifier = Modifier.testTag("llm_config_save_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save & Connect", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (saved) {
                        Text(
                            "Saved. Requests will use this provider.",
                            fontSize = 12.sp,
                            color = MintSuccess
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Engine load status - the real reason it is (or isn't) running, with retry.
        EngineLoadStatusCard(app = app)

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
                    text = "Allows Jarvis to inspect UI elements (UI tree-first) and perform safe clicks, typing, and navigation.",
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
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(CyanPrimary, ElectricBlue)))
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Accessibility Settings", color = CyanPrimary)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Step 3: Gol Dot (Display Over Other Apps Permission) Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("overlay_permission_card"),
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
                        imageVector = Icons.Default.RadioButtonChecked,
                        contentDescription = "Gol Dot",
                        tint = CyanPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "3. Gol Dot (Display Over Apps)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Gol dot ko kisi bhi app ke upar floating bubble ki tarah chalane ke liye Display Over Other Apps permission zaroori hai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isOverlayEnabled) MintSuccess.copy(alpha = 0.15f)
                            else AmberWarning.copy(alpha = 0.15f)
                        )
                        .border(
                            1.dp,
                            if (isOverlayEnabled) MintSuccess.copy(alpha = 0.4f)
                            else AmberWarning.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isOverlayEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isOverlayEnabled) MintSuccess else AmberWarning
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isOverlayEnabled) "Overlay Permission Granted" else "Overlay Permission Needed",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isOverlayEnabled) MintSuccess else AmberWarning
                        )
                        Text(
                            text = if (isOverlayEnabled) "Gol dot screen par floating controller ke liye ready hai" else "Settings me toggle karke allow karein",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_overlay_settings_button"),
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(CyanPrimary, ElectricBlue)))
                ) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Display Over Apps", color = CyanPrimary)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Complete / Launch Button
        val canEnter = saved || config.isConfigured() || isAccessibilityEnabled
        Button(
            onClick = onSetupComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("launch_agent_dashboard_button"),
            enabled = canEnter,
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Enter Jarvis Dashboard",
                color = if (canEnter) Color.White else Color.Gray,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = if (canEnter) Color.White else Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** Dropdown listing every supported provider; the user can pick any of them. */
@Composable
private fun LlmProviderDropdown(
    selected: LlmProvider,
    onSelected: (LlmProvider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("llm_provider_dropdown"),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = selected.displayName,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose provider", tint = CyanPrimary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            LlmProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        onSelected(provider)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Shows the live engine lifecycle (loading / ready / failed) with the real failure text
 * and a retry, so Setup never leaves the user guessing.
 */
@Composable
private fun EngineLoadStatusCard(app: com.example.OmniAgentApplication?) {
    if (app == null) return
    val engineState by app.engineState.collectAsState()

    val (accent, title, detail) = when (val s = engineState) {
        is com.example.model.EngineState.Ready ->
            Triple(MintSuccess, "Jarvis engine ready (${s.backend})", "Planning runs through your configured LLM API.")
        is com.example.model.EngineState.Loading ->
            Triple(ElectricBlue, "Connecting engine…", "Verifying the API configuration and running a smoke test.")
        is com.example.model.EngineState.Failed ->
            Triple(MaterialTheme.colorScheme.error, "Engine not available", s.message)
        com.example.model.EngineState.NotInitialized ->
            Triple(AmberWarning, "Engine not connected", "Add a provider and API key above, then connect.")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("engine_load_status_card"),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (engineState) {
                        is com.example.model.EngineState.Ready -> Icons.Default.CheckCircle
                        is com.example.model.EngineState.Loading -> Icons.Default.Autorenew
                        is com.example.model.EngineState.Failed -> Icons.Default.ErrorOutline
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (engineState is com.example.model.EngineState.Failed) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (engineState !is com.example.model.EngineState.Ready) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { app.initializeEngineIfConfigured() },
                    modifier = Modifier.testTag("setup_engine_retry_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Connect / Retry engine", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
