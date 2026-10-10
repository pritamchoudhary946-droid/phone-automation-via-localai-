package com.example.perception

import android.graphics.Bitmap
import kotlin.math.abs

/** A coarse screen point located by vision grounding, in screen pixels. */
data class VisionPoint(val x: Int, val y: Int, val confidence: Float = 0f)

/**
 * Vision grounding fallback (plan 4.1): locates a target the accessibility tree
 * cannot represent (canvas/WebView/game surfaces). Implementations must be
 * best-effort and return null rather than guessing when unsure.
 */
interface VisionGrounder {
    suspend fun locate(instruction: String, screenshot: Bitmap): VisionPoint?
}

/**
 * OCR fallback (plan 4.2): reads text baked into images. Returns "" when no text
 * is found or the reader is unavailable, never throwing into the loop.
 */
interface OcrReader {
    suspend fun readText(screenshot: Bitmap): String
}

/** Honest no-op grounder used when no vision backend is configured. */
object UnavailableVisionGrounder : VisionGrounder {
    override suspend fun locate(instruction: String, screenshot: Bitmap): VisionPoint? = null
}

/** Honest no-op OCR reader used when no OCR backend is configured. */
object UnavailableOcrReader : OcrReader {
    override suspend fun readText(screenshot: Bitmap): String = ""
}

/**
 * Coarse before/after screenshot comparison (plan 4.3). Used only as extra
 * evidence when the accessibility fingerprint is unchanged: a real, bounded pixel
 * change counts as "the screen changed". Deliberately cheap (grid sampling) so it
 * can run on the agent thread without a vision model.
 */
object ScreenshotDiffer {

    private const val GRID = 12
    private const val PER_CELL_CHANNEL_DELTA = 24

    /**
     * True when the two bitmaps differ beyond a coarse threshold. Null inputs are
     * treated as "unknown" (false) so a missing screenshot never fabricates a change.
     */
    fun changed(before: Bitmap?, after: Bitmap?): Boolean {
        if (before == null || after == null) return false
        if (before.width != after.width || before.height != after.height) return true
        if (before.width == 0 || before.height == 0) return false

        var changedCells = 0
        var totalCells = 0
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val x = (gx * before.width / GRID).coerceIn(0, before.width - 1)
                val y = (gy * before.height / GRID).coerceIn(0, before.height - 1)
                totalCells++
                if (cellDiffers(before.getPixel(x, y), after.getPixel(x, y))) changedCells++
            }
        }
        // Require a meaningful fraction of cells to change so anti-aliasing or a
        // blinking cursor does not count as a screen change.
        return totalCells > 0 && changedCells * 100 / totalCells >= 8
    }

    private fun cellDiffers(before: Int, after: Int): Boolean {
        val dr = abs(((before shr 16) and 0xFF) - ((after shr 16) and 0xFF))
        val dg = abs(((before shr 8) and 0xFF) - ((after shr 8) and 0xFF))
        val db = abs((before and 0xFF) - (after and 0xFF))
        return dr + dg + db >= PER_CELL_CHANNEL_DELTA
    }
}
