package com.example

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.perception.OcrReader
import com.example.perception.OcrTextBox
import com.example.perception.OcrVisionGrounder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionGroundingTest {

    @Test
    fun groundingUsesTheMatchedOcrBoundingBox() = runBlocking {
        val reader = object : OcrReader {
            override suspend fun readText(screenshot: Bitmap): String = "Play"
            override suspend fun readTextBoxes(screenshot: Bitmap): List<OcrTextBox> =
                listOf(OcrTextBox("Play", Rect(10, 20, 110, 70), 0.9f))
        }
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)
        val point = OcrVisionGrounder(reader).locate("Play", bitmap)

        assertEquals(60, point?.x)
        assertEquals(45, point?.y)
    }

    @Test
    fun textOnlyOcrNeverFallsBackToTheScreenCentre() = runBlocking {
        val reader = object : OcrReader {
            override suspend fun readText(screenshot: Bitmap): String = "Play"
        }
        val bitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888)

        assertNull(OcrVisionGrounder(reader).locate("Play", bitmap))
    }
}
