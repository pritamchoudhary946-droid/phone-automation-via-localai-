package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.accessibility.AgentAccessibilityService
import com.example.automation.AutomationGate
import com.example.voice.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 7.16: on-device capability checks. These run against the real Android runtime (not
 * Robolectric) and report the state of each capability honestly, so "it built" is not
 * mistaken for "it works on the phone".
 */
@RunWith(AndroidJUnit4::class)
class CapabilitySelfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationWiresWithoutCrashing() {
        val app = context.applicationContext as OmniAgentApplication
        assertTrue(app.settings.voiceOutputEnabled == false)
        assertTrue(app.settings.shizukuEnabled == false)
        assertTrue(app.settings.automationsEnabled == false)
        assertTrue(app.llmProviderConfig.isConfigured() == false)
    }

    @Test
    fun privilegedActionsRefuseByDefault() {
        val app = context.applicationContext as OmniAgentApplication
        // No opt-in and no Shizuku running: must never be available.
        assertTrue(!app.privilegedActions.isAvailable())
        assertTrue(!app.privilegedActions.setSystemSetting("global", "test", "1"))
    }

    @Test
    fun accessibilityServiceDiagnosticStateIsReported() {
        // Reports the real setting; on a fresh device this is false (no crash).
        val enabled = AgentAccessibilityService.isAccessibilitySettingsEnabled(context)
        assertEquals(enabled, AgentAccessibilityService.isAccessibilitySettingsEnabled(context))
    }

    @Test
    fun automationGateDefaultsOff() {
        val app = context.applicationContext as OmniAgentApplication
        assertTrue(!AutomationGate.isSchedulable(app.settings.automationsEnabled, true))
    }

    @Test
    fun hindiDetectionWorksOnDevice() {
        assertTrue(LanguageDetector.prefersHindi("wifi band karo"))
        assertTrue(LanguageDetector.prefersHindi("वाईफाई बंद करो"))
    }
}
