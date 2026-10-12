package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.canStartTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8.7 bug-sweep regression tests for the confirmed "written but not working"
 * defects (BS1 - BS10) that are unit-testable without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase8BugSweepTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- LLM API config is the single source of truth for planning --------------

    @Test
    fun llmApiIsUnconfiguredUntilProviderAndKeyAreSaved() {
        val config = com.example.model.LlmProviderConfig(context)
        config.clear()
        assertFalse("no key yet: must not be configured", config.isConfigured())

        config.save(
            com.example.model.LlmProvider.OPENAI,
            "sk-test-123",
            "",
            ""
        )

        assertTrue(config.isConfigured())
        assertEquals("https://api.openai.com/v1", config.effectiveBaseUrl())
        assertEquals("gpt-4o-mini", config.effectiveModel())
    }

    @Test
    fun openAiCompatibleProviderNeedsABaseUrlToBeConfigured() {
        val config = com.example.model.LlmProviderConfig(context)
        config.clear()
        // The catch-all provider has no default endpoint, so a key alone is not enough.
        config.save(com.example.model.LlmProvider.OPENAI_COMPATIBLE, "sk-test-123", "", "")
        assertFalse("no base URL for the catch-all provider: must not be configured", config.isConfigured())

        config.save(com.example.model.LlmProvider.OPENAI_COMPATIBLE, "sk-test-123", "https://my-gateway.local/v1", "my-model")
        assertTrue(config.isConfigured())
    }

    // --- BS8: honest start gating ----------------------------------------------

    @Test
    fun taskCanStartOnlyWithHealthyModelOrExplicitDegradedMode() {
        assertTrue("healthy model always permits", canStartTask(modelReady = true, basicFallbackExplicit = false))
        assertTrue("explicit degraded opt-in permits", canStartTask(modelReady = false, basicFallbackExplicit = true))
        assertFalse(
            "no model and no explicit opt-in must be blocked (6.1)",
            canStartTask(modelReady = false, basicFallbackExplicit = false)
        )
    }

    // --- BS10 / BS2: dead seams are inert and the live defaults refuse ---------

    @Test
    fun unavailableStubsAndDisabledProviderAreInert() {
        // The Unavailable* seams must never fabricate capability if ever reached.
        assertEquals(
            "",
            kotlinx.coroutines.runBlocking {
                com.example.perception.UnavailableOcrReader.readText(
                    android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                )
            }
        )
        assertNull(
            kotlinx.coroutines.runBlocking {
                com.example.perception.UnavailableVisionGrounder.locate(
                    "target",
                    android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                )
            }
        )
        assertFalse(com.example.model.UnavailableNanoSummarizer.isAvailable())
    }

    @Test
    fun privilegedBridgeRefusesByDefaultWithoutOptInOrShizuku() {
        // BS2: the live Shizuku bridge must refuse when not opted in / not running.
        val bridge = com.example.privileged.GatedPrivilegedBridge(
            optIn = { false },
            shizukuRunning = { true }
        )
        assertFalse(bridge.isAvailable())
        assertFalse(bridge.setSystemSetting("global", "x", "y"))
        assertFalse(bridge.setPackageEnabled("com.example", false))
    }
}
