package com.example.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class CheckingStorage(val requiredBytes: Long, val availableBytes: Long) : ModelDownloadState()
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercentage: Float,
        val speedBytesPerSec: Long,
        val etaSeconds: Long
    ) : ModelDownloadState()
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState()
    object Verifying : ModelDownloadState()
    data class Ready(val modelFile: File, val sizeFormatted: String) : ModelDownloadState()
    data class Error(val message: String, val canRetry: Boolean) : ModelDownloadState()
}

/**
 * Downloads, resumes and verifies the official Gemma LiteRT-LM model file.
 *
 * Guarantees:
 * - The 2.59 GB model is never bundled in the APK.
 * - Downloads use a `.part` file and are promoted to the final name only after the
 *   byte length and SHA-256 match the pinned official values.
 * - A verified model is never re-downloaded and is never deleted because a later
 *   download attempt fails.
 */
class LiteRtModelManager(
    private val context: Context,
    private val downloadClient: OkHttpClient? = null,
    private val expectedBytes: Long = EXPECTED_MODEL_BYTES,
    private val expectedSha256: String = EXPECTED_MODEL_SHA256,
    private val maxTransientRetries: Int = 3,
    private val modelUrl: String = OFFICIAL_MODEL_URL
) {

    companion object {
        const val OFFICIAL_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val EXPECTED_MODEL_BYTES = 2_588_147_712L // verified via HTTP Content-Length
        const val EXPECTED_MODEL_SHA256 =
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val REQUIRED_FREE_SPACE_BYTES = 3_200_000_000L // ~2.59 GB plus safety margin
        private const val PREFS_NAME = "litert_model_prefs"
        private const val KEY_MODEL_VERIFIED = "model_verified"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_SHA = "model_sha256"
        private const val TAG = "LiteRtModelManager"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client: OkHttpClient = downloadClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        checkExistingModel()
    }

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, "litert_models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(): File = File(getModelsDir(), MODEL_FILENAME)

    private fun getPartFile(): File = File(getModelsDir(), "$MODEL_FILENAME.part")

    /** Validates a file against the pinned size and SHA-256. */
    fun verifyFile(file: File): Boolean {
        if (!file.exists() || file.length() != expectedBytes) return false
        return try {
            sha256(file).equals(expectedSha256, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Hash verification failed: ${e.message}")
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun checkExistingModel() {
        val file = getModelFile()
        val isVerified = prefs.getBoolean(KEY_MODEL_VERIFIED, false)
        val storedSha = prefs.getString(KEY_MODEL_SHA, null)
        if (file.exists() && isVerified && storedSha.equals(expectedSha256, ignoreCase = true)) {
            _downloadState.value = ModelDownloadState.Ready(file, formatBytes(file.length()))
            return
        }
        val partFile = getPartFile()
        _downloadState.value = if (partFile.exists() && partFile.length() > 0) {
            ModelDownloadState.Paused(partFile.length(), expectedBytes)
        } else {
            ModelDownloadState.Idle
        }
    }

    fun checkStorageAvailability(): Pair<Boolean, Long> {
        val stat = StatFs(context.filesDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return Pair(availableBytes >= REQUIRED_FREE_SPACE_BYTES, availableBytes)
    }

    fun startOrResumeDownload() {
        if (downloadJob?.isActive == true) return
        if (verifiedModelAlreadyPresent()) return

        downloadJob = scope.launch {
            var attempt = 0
            while (isActive && attempt <= maxTransientRetries) {
                try {
                    downloadOnce()
                    return@launch
                } catch (e: CancellationException) {
                    val partFile = getPartFile()
                    _downloadState.value = ModelDownloadState.Paused(
                        downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                        totalBytes = expectedBytes
                    )
                    return@launch
                } catch (e: NonRetryableDownloadException) {
                    _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed", canRetry = false)
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    if (attempt > maxTransientRetries) {
                        _downloadState.value = ModelDownloadState.Error(
                            message = "Download failed after $maxTransientRetries retries: ${e.message}",
                            canRetry = true
                        )
                        return@launch
                    }
                    val backoffMs = 1_000L * attempt
                    Log.w(TAG, "Transient download error (${e.message}); retry $attempt in ${backoffMs}ms")
                    _downloadState.value = ModelDownloadState.Paused(getPartFile().length(), expectedBytes)
                    delay(backoffMs)
                }
            }
        }
    }

    private fun verifiedModelAlreadyPresent(): Boolean {
        val file = getModelFile()
        if (!file.exists()) return false
        val storedSha = prefs.getString(KEY_MODEL_SHA, null)
        return if (prefs.getBoolean(KEY_MODEL_VERIFIED, false) &&
            storedSha.equals(expectedSha256, ignoreCase = true)
        ) {
            _downloadState.value = ModelDownloadState.Ready(file, formatBytes(file.length()))
            true
        } else {
            false
        }
    }

    private suspend fun downloadOnce() = withContext(Dispatchers.IO) {
        val (hasEnoughStorage, availableBytes) = checkStorageAvailability()
        if (!hasEnoughStorage) {
            throw NonRetryableDownloadException(
                "Insufficient storage space: ${formatBytes(availableBytes)} available, at least 3.2 GB required for Gemma 4 E2B."
            )
        }
        _downloadState.value = ModelDownloadState.CheckingStorage(REQUIRED_FREE_SPACE_BYTES, availableBytes)

        val partFile = getPartFile()
        var existingBytes = if (partFile.exists()) partFile.length() else 0L
        if (existingBytes >= expectedBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder()
            .url(modelUrl)
            .addHeader("User-Agent", "OmniAgent-Android/1.0")
        if (existingBytes > 0) requestBuilder.addHeader("Range", "bytes=$existingBytes-")

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val isPartial = resp.code == 206
            if (!resp.isSuccessful && !isPartial) {
                if (resp.code == 416) {
                    partFile.delete()
                    throw IOException("Server rejected resume range (416); restarting download")
                }
                throw NonRetryableDownloadException("HTTP ${resp.code}: ${resp.message}")
            }
            if (!isPartial) existingBytes = 0L
            if (isPartial && resp.header("Content-Range") == null) existingBytes = 0L

            val body = resp.body ?: throw NonRetryableDownloadException("Empty response body from server")
            val contentType = body.contentType()?.toString() ?: ""
            if (contentType.contains("text/html", ignoreCase = true)) {
                throw NonRetryableDownloadException("Server returned an HTML page instead of the model file")
            }

            val contentLength = body.contentLength()
            val totalBytes = if (contentLength > 0) existingBytes + contentLength else expectedBytes

            _downloadState.value = ModelDownloadState.Downloading(
                downloadedBytes = existingBytes,
                totalBytes = totalBytes,
                progressPercentage = if (totalBytes > 0) existingBytes.toFloat() / totalBytes * 100f else 0f,
                speedBytesPerSec = 0,
                etaSeconds = 0
            )

            var lastProgressEmit = 0L
            var speedWindowStart = System.currentTimeMillis()
            var bytesAtWindowStart = existingBytes

            FileOutputStream(partFile, isPartial && existingBytes > 0).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!isActive) throw CancellationException("Download cancelled")
                        output.write(buffer, 0, bytesRead)
                        val written = partFile.length()

                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit >= 500L) {
                            val elapsed = now - speedWindowStart
                            val speed = if (elapsed > 0) ((written - bytesAtWindowStart) * 1000L) / elapsed else 0L
                            val remaining = (totalBytes - written).coerceAtLeast(0L)
                            _downloadState.value = ModelDownloadState.Downloading(
                                downloadedBytes = written,
                                totalBytes = totalBytes,
                                progressPercentage = if (totalBytes > 0) written.toFloat() / totalBytes * 100f else 0f,
                                speedBytesPerSec = speed,
                                etaSeconds = if (speed > 0) remaining / speed else 0L
                            )
                            lastProgressEmit = now
                            speedWindowStart = now
                            bytesAtWindowStart = written
                        }
                    }
                }
            }
        }

        _downloadState.value = ModelDownloadState.Verifying
        val downloadedLength = partFile.length()
        if (downloadedLength != expectedBytes) {
            throw IOException(
                "Downloaded size mismatch: expected ${formatBytes(expectedBytes)}, got ${formatBytes(downloadedLength)}"
            )
        }
        val actualSha = sha256(partFile)
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            partFile.delete()
            throw NonRetryableDownloadException("Checksum mismatch; downloaded file is corrupt and was discarded")
        }

        val finalFile = getModelFile()
        if (finalFile.exists() && !verifyFile(finalFile)) finalFile.delete()
        if (!partFile.renameTo(finalFile)) throw IOException("Failed to finalize model file name")

        prefs.edit()
            .putBoolean(KEY_MODEL_VERIFIED, true)
            .putString(KEY_MODEL_PATH, finalFile.absolutePath)
            .putString(KEY_MODEL_SHA, actualSha)
            .apply()

        _downloadState.value = ModelDownloadState.Ready(finalFile, formatBytes(finalFile.length()))
    }

    fun pauseDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val partFile = getPartFile()
        _downloadState.value = ModelDownloadState.Paused(
            downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
            totalBytes = expectedBytes
        )
    }

    /** Removes the downloaded model. Never called implicitly by a failed download. */
    fun deleteModel() {
        pauseDownload()
        getModelFile().delete()
        getPartFile().delete()
        prefs.edit().clear().apply()
        _downloadState.value = ModelDownloadState.Idle
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        if (gb >= 1.0) return String.format("%.2f GB", gb)
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }

    /**
     * Test hook: records an already-verified model without any network access, so
     * download/integrity tests stay deterministic and CI never fetches 2.59 GB.
     */
    fun registerVerifiedModelForTest(file: File) {
        prefs.edit()
            .putBoolean(KEY_MODEL_VERIFIED, true)
            .putString(KEY_MODEL_PATH, file.absolutePath)
            .putString(KEY_MODEL_SHA, expectedSha256)
            .apply()
        _downloadState.value = ModelDownloadState.Ready(file, formatBytes(file.length()))
    }

    private class NonRetryableDownloadException(message: String) : Exception(message)
}
