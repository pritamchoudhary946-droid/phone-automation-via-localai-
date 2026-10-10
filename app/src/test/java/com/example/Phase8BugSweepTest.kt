package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.planning.HttpCloudPlannerProvider
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

    // --- BS4: cloud fallback configurable end to end ---------------------------

    @Test
    fun cloudFallbackIsUnreachableUntilEndpointAndKeyAreSaved() {
        val provider = HttpCloudPlannerProvider(context)
        // Toggle on alone is not enough: the provider still refuses without config.
        com.example.settings.CapabilitySettings(context).cloudFallbackEnabled = true
        assertFalse("no endpoint/key yet: must not be enabled", provider.isEnabled())

        provider.saveConfiguration("https://api.example.com/v1/chat/completions", "sk-test-123", "gpt-4o-mini")

        assertTrue(provider.isEnabled())
        assertEquals("https://api.example.com/v1/chat/completions", provider.currentEndpoint())
        assertEquals("sk-test-123", provider.currentApiKey())
        assertEquals("gpt-4o-mini", provider.currentModel())
    }

    @Test
    fun cloudFallbackOffByDefaultEvenAfterConfiguration() {
        val provider = HttpCloudPlannerProvider(context)
        provider.saveConfiguration("https://api.example.com/v1/chat/completions", "sk-test-123", "gpt-4o-mini")
        // Config is present but the master toggle is off (default): still disabled.
        assertFalse(provider.isEnabled())
        assertNull(kotlinx.coroutines.runBlocking { provider.complete("hi", null) })
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
        assertFalse(com.example.planning.DisabledCloudPlannerProvider.isEnabled())
        assertNull(
            kotlinx.coroutines.runBlocking { com.example.planning.DisabledCloudPlannerProvider.complete("x", null) }
        )
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
