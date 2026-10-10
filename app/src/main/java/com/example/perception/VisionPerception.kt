package com.example.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
data class OcrTextBox(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 1f
)

/**
 * OCR contract. Text-only readers remain valid for screen summaries, but coordinate
 * grounding is allowed only through real text boxes.
 */
interface OcrReader {
    suspend fun readText(screenshot: Bitmap): String

    suspend fun readTextBoxes(screenshot: Bitmap): List<OcrTextBox> = emptyList()
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
            recognize(screenshot)?.text?.trim()?.take(maxChars).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "OCR threw: ${e.message}")
            ""
        }
    }

    override suspend fun readTextBoxes(screenshot: Bitmap): List<OcrTextBox> {
        if (screenshot.width <= 0 || screenshot.height <= 0) return emptyList()
        return try {
            recognize(screenshot)?.textBlocks
                ?.flatMap { block ->
                    block.lines.flatMap { line ->
                        val elementBoxes = line.elements.mapNotNull { element ->
                            element.boundingBox?.let { box ->
                                OcrTextBox(element.text, Rect(box), 0.9f)
                            }
                        }
                        if (elementBoxes.isNotEmpty()) {
                            elementBoxes
                        } else {
                            line.boundingBox?.let { box ->
                                listOf(OcrTextBox(line.text, Rect(box), 0.85f))
                            }.orEmpty()
                        }
                    }
                }
                ?.filter { it.text.isNotBlank() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "OCR box extraction threw: ${e.message}")
            emptyList()
        }
    }

    private suspend fun recognize(screenshot: Bitmap): com.google.mlkit.vision.text.Text? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(screenshot, 0))
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
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

        // Coordinate grounding must use actual OCR boxes. A text-only OCR digest
        // cannot justify tapping the screen centre, so it deliberately returns null.
        val boxes = ocrReader.readTextBoxes(screenshot)
        if (boxes.isEmpty()) return null

        val needleTokens = needle.split(" ").filter { it.length >= 2 }.toSet()
        val candidate = boxes.mapNotNull { box ->
            val label = normalize(box.text)
            if (label.isBlank()) return@mapNotNull null
            val labelTokens = label.split(" ").filter { it.length >= 2 }.toSet()
            val overlap = needleTokens.intersect(labelTokens).size
            val exact = label.contains(needle) || needle.contains(label)
            val allTokens = needleTokens.isNotEmpty() && needleTokens.all { label.contains(it) }
            if (!exact && !allTokens) return@mapNotNull null
            val score = when {
                label == needle -> 1f
                allTokens -> 0.95f
                exact -> 0.9f
                else -> overlap.toFloat() / needleTokens.size.coerceAtLeast(1)
            }
            OcrTextBox(box.text, box.bounds, (box.confidence * score).coerceIn(0f, 1f))
        }.maxByOrNull { it.confidence } ?: return null

        val bounds = candidate.bounds
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val centerX = bounds.left + width / 2
        val centerY = bounds.top + height / 2
        if (width <= 0 || height <= 0 ||
            centerX !in 0 until screenshot.width ||
            centerY !in 0 until screenshot.height
        ) return null
        return VisionPoint(centerX, centerY, candidate.confidence)
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
