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
    // Phase 9 opt-in capabilities; all default OFF.
    var proactive by remember { mutableStateOf(settings?.proactiveAwarenessEnabled == true) }
    var locationTriggers by remember { mutableStateOf(settings?.locationTriggersEnabled == true) }
    var briefing by remember { mutableStateOf(settings?.briefingEnabled == true) }
    var triage by remember { mutableStateOf(settings?.notificationTriageEnabled == true) }
    val scope = rememberCoroutineScope()

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

        GoogleAccountCard()

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
            subtitle = "When the LLM API is unavailable, run an explicitly-labelled basic mode instead of blocking",
            checked = basicFallback,
            testTag = "toggle_basic_fallback"
        ) {
            basicFallback = it
            app?.applyBasicFallbackSetting(it) ?: settings?.let { s -> s.basicFallbackExplicit = it }
        }

        Spacer(Modifier.height(4.dp))
        // --- Phase 9: proactive, hands-free capabilities. All OFF by default. ---
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Proactive & ambient (Phase 9)", fontWeight = FontWeight.SemiBold)
                Text(
                    "These can watch context and act on your behalf. They stay off until you enable them.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ToggleRow(
            title = "Proactive awareness",
            subtitle = "Notice context (foreground app, notifications, time) and offer help. No screen capture.",
            checked = proactive,
            testTag = "toggle_proactive_awareness"
        ) {
            proactive = it
            settings?.proactiveAwarenessEnabled = it
        }
        ToggleRow(
            title = "Location triggers",
            subtitle = "Run automations when you arrive at or leave a place (needs location permission)",
            checked = locationTriggers,
            testTag = "toggle_location_triggers"
        ) {
            locationTriggers = it
            settings?.locationTriggersEnabled = it
        }
        ToggleRow(
            title = "Daily briefing",
            subtitle = "Speak a short summary of your notifications at a set time each day",
            checked = briefing,
            testTag = "toggle_briefing"
        ) {
            briefing = it
            settings?.let { s -> s.briefingEnabled = it }
            app?.let { a ->
                scope.launch {
                    com.example.notifications.BriefingScheduler.sync(a)
                }
            }
        }
        ToggleRow(
            title = "Smart notification triage",
            subtitle = "Summarise notifications on-device and offer one-tap actions. Never auto-sends.",
            checked = triage,
            testTag = "toggle_notification_triage"
        ) {
            triage = it
            settings?.notificationTriageEnabled = it
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
                    val configured = app?.llmProviderConfig?.isConfigured() == true
                    lines += "LLM API configured: ${if (configured) "PASS" else "FAIL"}"
                    val engine = app?.agentEngine?.isReady() == true
                    lines += "Engine ready: ${if (engine) "PASS" else "FAIL"}"
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
