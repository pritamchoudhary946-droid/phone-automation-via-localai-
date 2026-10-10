package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.OmniAgentApplication
import com.example.settings.CapabilitySettings
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.MintSuccess

/**
 * One opt-in capability settings surface (plan 7.15). Every toggle defaults OFF and
 * shows its current state; nothing proactive, cloud, voice, or privileged runs until
 * the user turns it on here.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as? OmniAgentApplication
    val settings = remember { app?.settings }

    var voiceOutput by remember { mutableStateOf(settings?.voiceOutputEnabled == true) }
    var handsFree by remember { mutableStateOf(settings?.handsFreeEnabled == true) }
    var shizuku by remember { mutableStateOf(settings?.shizukuEnabled == true) }
    var automations by remember { mutableStateOf(settings?.automationsEnabled == true) }
    var basicFallback by remember { mutableStateOf(settings?.basicFallbackExplicit == true) }
    var cloudFallback by remember { mutableStateOf(settings?.cloudFallbackEnabled == true) }
    // 8.7/BS4: cloud endpoint/key/model entry, so the opt-in fallback is reachable.
    val cloudProvider = app?.cloudPlannerProvider
    var cloudEndpoint by remember { mutableStateOf(cloudProvider?.currentEndpoint() ?: "") }
    var cloudApiKey by remember { mutableStateOf(cloudProvider?.currentApiKey() ?: "") }
    var cloudModel by remember { mutableStateOf(cloudProvider?.currentModel() ?: "") }
    var cloudSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fit = remember { app?.modelManager?.isFitForDevice() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Capabilities",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Every capability is off by default. Turn on only what you need.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (fit != null) {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Device fit", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("RAM: ${fit.totalRamMb} MB · Free storage: ${fit.availableStorageMb} MB", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = fit.recommendation,
                        fontSize = 12.sp,
                        color = if (fit.fits) MintSuccess else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        ToggleRow(
            title = "Voice output",
            subtitle = "Speak task status aloud in your language",
            checked = voiceOutput,
            testTag = "toggle_voice_output"
        ) {
            voiceOutput = it
            app?.applyVoiceOutputSetting(it) ?: settings?.let { s -> s.voiceOutputEnabled = it }
        }
        ToggleRow(
            title = "Hands-free listening",
            subtitle = "Keep the microphone open for continuous voice commands (needs mic permission)",
            checked = handsFree,
            testTag = "toggle_hands_free"
        ) {
            handsFree = it
            settings?.handsFreeEnabled = it
        }
        ToggleRow(
            title = "Shizuku privileged actions",
            subtitle = "Advanced: allow ADB-level actions when Shizuku is running",
            checked = shizuku,
            testTag = "toggle_shizuku"
        ) {
            shizuku = it
            settings?.shizukuEnabled = it
        }
        ToggleRow(
            title = "Scheduled automations",
            subtitle = "Run saved skills on a schedule in the background",
            checked = automations,
            testTag = "toggle_automations"
        ) {
            automations = it
            settings?.automationsEnabled = it
            // Re-arm or cancel scheduled jobs immediately (7.8).
            app?.let { a -> scope.launch { a.syncAutomations() } }
        }
        ToggleRow(
            title = "Allow keyword fallback",
            subtitle = "When the local model is unavailable, run an explicitly-labelled basic mode instead of blocking",
            checked = basicFallback,
            testTag = "toggle_basic_fallback"
        ) {
            basicFallback = it
            app?.applyBasicFallbackSetting(it) ?: settings?.let { s -> s.basicFallbackExplicit = it }
        }
        ToggleRow(
            title = "Cloud planner fallback",
            subtitle = "Opt-in: only used after repeated local failures. Off by default; no data leaves the device unless enabled",
            checked = cloudFallback,
            testTag = "toggle_cloud_fallback"
        ) {
            cloudFallback = it
            settings?.cloudFallbackEnabled = it
        }

        // 8.7/BS4: without somewhere to enter these, the cloud fallback could never be
        // turned on end to end. Endpoint/key/model are stored locally and only used
        // after repeated local failures when the toggle above is on.
        if (cloudFallback && cloudProvider != null) {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Cloud planner configuration", fontWeight = FontWeight.SemiBold)
                    Text(
                        "OpenAI-style chat-completions endpoint. The key is stored only on this device.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = cloudEndpoint,
                        onValueChange = { cloudEndpoint = it; cloudSaved = false },
                        label = { Text("Endpoint URL") },
                        placeholder = { Text("https://api.example.com/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_endpoint_field")
                    )
                    OutlinedTextField(
                        value = cloudApiKey,
                        onValueChange = { cloudApiKey = it; cloudSaved = false },
                        label = { Text("API key") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_api_key_field")
                    )
                    OutlinedTextField(
                        value = cloudModel,
                        onValueChange = { cloudModel = it; cloudSaved = false },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_model_field")
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                cloudProvider.saveConfiguration(cloudEndpoint, cloudApiKey, cloudModel)
                                cloudSaved = true
                            },
                            enabled = cloudEndpoint.isNotBlank() && cloudApiKey.isNotBlank(),
                            modifier = Modifier.testTag("cloud_config_save_button")
                        ) {
                            Text("Save")
                        }
                        if (cloudSaved) {
                            Text(
                                "Saved. The fallback will use these credentials.",
                                fontSize = 12.sp,
                                color = MintSuccess
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SelfTestCard(app = app)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag)
            )
        }
    }
}

/**
 * 7.16: in-app self-test. Reports pass/fail per capability from the real app state
 * (accessibility service, model engine, overlay, OCR backend) rather than claiming
 * success.
 */
@Composable
private fun SelfTestCard(app: OmniAgentApplication?) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Self-test", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    val lines = mutableListOf<String>()
                    val a11y = com.example.accessibility.AgentAccessibilityService.isAccessibilitySettingsEnabled(context)
                    lines += "Accessibility service: ${if (a11y) "PASS" else "FAIL"}"
                    val overlay = android.provider.Settings.canDrawOverlays(context)
                    lines += "Overlay permission: ${if (overlay) "PASS" else "FAIL"}"
                    val model = app?.modelManager?.downloadState?.value
                    lines += "Model ready: ${if (model is com.example.model.ModelDownloadState.Ready) "PASS" else "FAIL"}"
                    val engine = app?.liteRtEngine?.isReady() == true
                    lines += "Engine ready: ${if (engine) "PASS" else "FAIL"}"
                    val fit = app?.modelManager?.isFitForDevice()
                    lines += "Device fit: ${if (fit?.fits == true) "PASS" else "WARN"}"
                    // 8.7/BS2: report the real Shizuku bridge status instead of leaving
                    // privilegedActions as dead wiring. Available only when opted in AND
                    // Shizuku is running; refused (false) otherwise.
                    val privileged = app?.privilegedActions?.isAvailable() == true
                    lines += "Privileged (Shizuku): ${if (privileged) "PASS" else "OFF/absent"}"
                    result = lines.joinToString("\n")
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.testTag("run_self_test_button")
            ) {
                Text("Run self-test", color = Color.White)
            }
            result?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.testTag("self_test_result"))
            }
        }
    }
}
