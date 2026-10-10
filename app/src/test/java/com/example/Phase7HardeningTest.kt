package com.example

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.automation.AutomationGate
import com.example.cloudflare.CloudflareGateway
import com.example.perception.OcrReader
import com.example.perception.OcrVisionGrounder
import com.example.privileged.ShizukuGate
import com.example.voice.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 7 - Completion & Hardening. These pin the pure, device-independent parts of
 * the seams that were made real: web-search result parsing (7.11), OCR-backed vision
 * grounding refusal/locate behaviour (7.2/7.3), the Shizuku permit gate (7.7), the
 * automation gate (7.8), and Hindi/Roman-Hindi end-to-end detection (7.17).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase7HardeningTest {

    // --- 7.11 web-search quality ----------------------------------------------

    @Test
    fun parsesDuckDuckGoResultLinksAndResolvesRedirects() {
        val gateway = CloudflareGateway()
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=abc">Example Page</a>
              <a class="result__a" href="https://direct.example.org">Direct Result</a>
            </div>
        """.trimIndent()
        val links = gateway.parseResultLinks(html)
        assertEquals(2, links.size)
        assertEquals("Example Page", links[0].first)
        assertEquals("https://example.com/page", links[0].second)
        assertEquals("Direct Result", links[1].first)
        assertEquals("https://direct.example.org", links[1].second)
    }

    @Test
    fun resultParsingIsEmptySafeOnJunk() {
        val gateway = CloudflareGateway()
        assertTrue(gateway.parseResultLinks("").isEmpty())
        assertTrue(gateway.parseResultLinks("<html><body>no results</body></html>").isEmpty())
    }

    // --- 7.2 / 7.3 OCR-backed grounding ---------------------------------------

    private class FakeOcr(private val text: String) : OcrReader {
        override suspend fun readText(screenshot: Bitmap): String = text

        override suspend fun readTextBoxes(screenshot: Bitmap): List<com.example.perception.OcrTextBox> =
            text.split("\\n").mapIndexed { index, line ->
                com.example.perception.OcrTextBox(
                    text = line,
                    bounds = Rect(10, 20 + index * 40, 90, 50 + index * 40),
                    confidence = 0.95f
                )
            }
    }

    @Test
    fun visionGrounderRefusesWhenLabelAbsent() {
        val grounder = OcrVisionGrounder(FakeOcr("Home  Settings  Photos"))
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        kotlinx.coroutines.runBlocking {
            // The label is not on screen: must refuse, never blind-tap.
            assertNull(grounder.locate("Submit", bitmap))
        }
    }

    @Test
    fun visionGrounderLocatesPresentLabel() {
        val grounder = OcrVisionGrounder(FakeOcr("Home\nSubmit\nPhotos"))
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        kotlinx.coroutines.runBlocking {
            val point = grounder.locate("Submit", bitmap)
            assertNotNull(point)
            assertTrue(point!!.y in 0 until bitmap.height)
        }
    }

    // --- 7.7 Shizuku gate ------------------------------------------------------

    @Test
    fun shizukuPermitRequiresBothOptInAndRunning() {
        assertFalse(ShizukuGate.permit(optIn = false, shizukuRunning = true))
        assertFalse(ShizukuGate.permit(optIn = true, shizukuRunning = false))
        assertFalse(ShizukuGate.permit(optIn = false, shizukuRunning = false))
        assertTrue(ShizukuGate.permit(optIn = true, shizukuRunning = true))
    }

    // --- 7.8 automation gate ---------------------------------------------------

    @Test
    fun automationsOnlyScheduleWhenMasterAndItemEnabled() {
        assertFalse(AutomationGate.isSchedulable(masterEnabled = false, automationEnabled = true))
        assertFalse(AutomationGate.isSchedulable(masterEnabled = true, automationEnabled = false))
        assertTrue(AutomationGate.isSchedulable(masterEnabled = true, automationEnabled = true))
    }

    @Test
    fun automationIntervalClampsToWorkManagerMinimum() {
        assertEquals(15L, AutomationGate.intervalMinutes(1))
        assertEquals(15L, AutomationGate.intervalMinutes(15))
        assertEquals(60L, AutomationGate.intervalMinutes(60))
    }

    // --- 7.17 Hindi / Roman-Hindi end-to-end ----------------------------------

    @Test
    fun detectsDevanagariHindi() {
        assertTrue(LanguageDetector.isHindi("वाईफाई बंद करो"))
        assertTrue(LanguageDetector.prefersHindi("वाईफाई बंद करो"))
        assertEquals(LanguageDetector.Language.HINDI, LanguageDetector.detect("वाईफाई बंद करो"))
    }

    @Test
    fun detectsRomanHindiCommands() {
        assertTrue(LanguageDetector.prefersHindi("wifi band karo"))
        assertTrue(LanguageDetector.prefersHindi("gaana chalao"))
        assertEquals(LanguageDetector.Language.ROMAN_HINDI, LanguageDetector.detect("settings kholo"))
    }

    @Test
    fun plainEnglishStaysEnglish() {
        assertFalse(LanguageDetector.prefersHindi("open chrome and search for news"))
        assertFalse(LanguageDetector.prefersHindi("turn on the wifi"))
        assertEquals(LanguageDetector.Language.ENGLISH, LanguageDetector.detect("open youtube"))
    }
}
