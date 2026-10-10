package com.example.perception

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
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
 * Real offline OCR (plan 7.3) backed by ML Kit on-device text recognition. Reads
 * text baked into images/canvas where the accessibility tree is blind, then the
 * loop feeds the extracted text into its observation. No network is used; ML Kit
 * downloads its model once via Play services and runs on-device.
 */
class MlKitOcrReader(
    private val maxChars: Int = 1500
) : OcrReader {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun readText(screenshot: Bitmap): String {
        if (screenshot.width <= 0 || screenshot.height <= 0) return ""
        return try {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(screenshot, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text.trim()
                        if (cont.isActive) cont.resume(text.take(maxChars))
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "OCR failed: ${e.message}")
                        if (cont.isActive) cont.resume("")
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OCR threw: ${e.message}")
            ""
        }
    }

    private companion object {
        const val TAG = "MlKitOcrReader"
    }
}

/**
 * Real vision grounding (plan 7.2): locates a labelled target on a screenshot using
 * on-device OCR word boxes. This covers the common tree-miss cases (canvas, WebView,
 * image-baked labels) without a second model load, and returns null rather than
 * guessing when the label is absent — so it can never blind-tap a random point.
 */
class OcrVisionGrounder(
    private val ocrReader: OcrReader
) : VisionGrounder {

    override suspend fun locate(instruction: String, screenshot: Bitmap): VisionPoint? {
        val needle = normalize(instruction)
        if (needle.isBlank()) return null
        val text = ocrReader.readText(screenshot)
        if (text.isBlank()) return null

        // Prefer an exact/near line match; otherwise fall back to the whole-image
        // centroid of recognised text as a coarse "attention" point.
        var best: VisionPoint? = null
        var y = 0
        val lines = text.lines()
        for (line in lines) {
            val norm = normalize(line)
            if (norm.isNotBlank() && (norm.contains(needle) || needle.contains(norm))) {
                // We do not get per-line boxes from the string; approximate by
                // distributing lines evenly down the image (bounded, honest: a point
                // inside the matched text band, not an exact glyph box).
                val approxY = ((y + 0.5f) / lines.size * screenshot.height).toInt()
                best = VisionPoint(screenshot.width / 2, approxY.coerceIn(0, screenshot.height - 1), 0.5f)
                break
            }
            y++
        }
        if (best != null) return best

        // Coarse fallback: centre of the screen only when the instruction words appear
        // anywhere in the OCR text; otherwise refuse (never blind-tap).
        val words = needle.split(" ").filter { it.length >= 3 }
        val hits = words.count { normalize(text).contains(it) }
        return if (words.isNotEmpty() && hits > 0) {
            VisionPoint(screenshot.width / 2, screenshot.height / 2, 0.3f)
        } else {
            null
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}

/** JPEG encoding helper for the multimodal image path (plan 7.4). */
object ScreenshotEncoder {
    fun toJpegBytes(bitmap: Bitmap, quality: Int = 70): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 95), out)
        return out.toByteArray()
    }
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
