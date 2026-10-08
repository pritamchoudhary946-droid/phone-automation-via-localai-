package com.example.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.RandomAccessFile
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

class LiteRtModelManager(private val context: Context) {

    companion object {
        const val OFFICIAL_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val ESTIMATED_MODEL_BYTES = 2_780_000_000L // ~2.59 GB
        const val REQUIRED_FREE_SPACE_BYTES = 3_200_000_000L // ~3.0 GB safety margin
        private const val PREFS_NAME = "litert_model_prefs"
        private const val KEY_MODEL_VERIFIED = "model_verified"
        private const val KEY_MODEL_PATH = "model_path"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        checkExistingModel()
    }

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, "litert_models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(): File {
        return File(getModelsDir(), MODEL_FILENAME)
    }

    private fun getPartFile(): File {
        return File(getModelsDir(), "$MODEL_FILENAME.part")
    }

    fun checkExistingModel() {
        val file = getModelFile()
        val isVerified = prefs.getBoolean(KEY_MODEL_VERIFIED, false)
        if (file.exists() && file.length() > 1_000_000_000L && isVerified) {
            _downloadState.value = ModelDownloadState.Ready(
                modelFile = file,
                sizeFormatted = formatBytes(file.length())
            )
        } else {
            val partFile = getPartFile()
            if (partFile.exists() && partFile.length() > 0) {
                _downloadState.value = ModelDownloadState.Paused(
                    downloadedBytes = partFile.length(),
                    totalBytes = ESTIMATED_MODEL_BYTES
                )
            } else {
                _downloadState.value = ModelDownloadState.Idle
            }
        }
    }

    fun checkStorageAvailability(): Pair<Boolean, Long> {
        val stat = StatFs(context.filesDir.absolutePath)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return Pair(availableBytes >= REQUIRED_FREE_SPACE_BYTES, availableBytes)
    }

    fun startOrResumeDownload() {
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            try {
                // 1. Verify storage
                val (hasEnoughStorage, availableBytes) = checkStorageAvailability()
                if (!hasEnoughStorage) {
                    _downloadState.value = ModelDownloadState.Error(
                        "Insufficient storage space: ${formatBytes(availableBytes)} available, at least 3.2 GB required for Gemma 4 E2B.",
                        canRetry = true
                    )
                    return@launch
                }

                _downloadState.value = ModelDownloadState.CheckingStorage(
                    requiredBytes = REQUIRED_FREE_SPACE_BYTES,
                    availableBytes = availableBytes
                )

                val partFile = getPartFile()
                var existingBytes = if (partFile.exists()) partFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(OFFICIAL_MODEL_URL)
                    .addHeader("User-Agent", "OmniAgent-Android/1.0")

                if (existingBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful && response.code != 206) {
                    // Range might be invalid or server doesn't support range
                    if (response.code == 416) {
                        // Range satisfied or invalid, reset
                        partFile.delete()
                        existingBytes = 0L
                    } else {
                        throw RuntimeException("HTTP ${response.code}: ${response.message}")
                    }
                }

                val body = response.body ?: throw RuntimeException("Empty response body from server")
                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0) existingBytes + contentLength else ESTIMATED_MODEL_BYTES

                _downloadState.value = ModelDownloadState.Downloading(
                    downloadedBytes = existingBytes,
                    totalBytes = totalBytes,
                    progressPercentage = (existingBytes.toFloat() / totalBytes.toFloat()) * 100f,
                    speedBytesPerSec = 0,
                    etaSeconds = 0
                )

                val outputStream = FileOutputStream(partFile, existingBytes > 0)
                val buffer = ByteArray(64 * 1024) // 64 KB buffer
                var bytesRead: Int
                var downloadedSinceStart = 0L
                val startTime = System.currentTimeMillis()
                var lastSpeedCalcTime = startTime
                var bytesAtLastCalc = existingBytes

                body.byteStream().use { inputStream ->
                    outputStream.use { out ->
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                _downloadState.value = ModelDownloadState.Paused(partFile.length(), totalBytes)
                                return@launch
                            }
                            out.write(buffer, 0, bytesRead)
                            downloadedSinceStart += bytesRead
                            val currentTotalDownloaded = existingBytes + downloadedSinceStart

                            val now = System.currentTimeMillis()
                            val timeDiff = now - lastSpeedCalcTime
                            if (timeDiff >= 800) {
                                val bytesInWindow = currentTotalDownloaded - bytesAtLastCalc
                                val speed = (bytesInWindow * 1000L) / timeDiff
                                val remainingBytes = (totalBytes - currentTotalDownloaded).coerceAtLeast(0)
                                val etaSec = if (speed > 0) remainingBytes / speed else 0

                                _downloadState.value = ModelDownloadState.Downloading(
                                    downloadedBytes = currentTotalDownloaded,
                                    totalBytes = totalBytes,
                                    progressPercentage = (currentTotalDownloaded.toFloat() / totalBytes.toFloat()) * 100f,
                                    speedBytesPerSec = speed,
                                    etaSeconds = etaSec
                                )

                                lastSpeedCalcTime = now
                                bytesAtLastCalc = currentTotalDownloaded
                            }
                        }
                    }
                }

                // Download completed, verify
                _downloadState.value = ModelDownloadState.Verifying
                val finalFile = getModelFile()
                if (finalFile.exists()) {
                    finalFile.delete()
                }

                val renamed = partFile.renameTo(finalFile)
                if (!renamed) {
                    throw RuntimeException("Failed to finalize model file name.")
                }

                // Verify file header and non-empty
                if (finalFile.length() < 100_000_000L) {
                    throw RuntimeException("Downloaded file size unexpectedly small (${formatBytes(finalFile.length())}).")
                }

                prefs.edit()
                    .putBoolean(KEY_MODEL_VERIFIED, true)
                    .putString(KEY_MODEL_PATH, finalFile.absolutePath)
                    .apply()

                _downloadState.value = ModelDownloadState.Ready(
                    modelFile = finalFile,
                    sizeFormatted = formatBytes(finalFile.length())
                )

            } catch (e: CancellationException) {
                val partFile = getPartFile()
                _downloadState.value = ModelDownloadState.Paused(
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                    totalBytes = ESTIMATED_MODEL_BYTES
                )
            } catch (e: Exception) {
                _downloadState.value = ModelDownloadState.Error(
                    message = "Download failed: ${e.localizedMessage ?: e.message}",
                    canRetry = true
                )
            }
        }
    }

    fun pauseDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val partFile = getPartFile()
        _downloadState.value = ModelDownloadState.Paused(
            downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
            totalBytes = ESTIMATED_MODEL_BYTES
        )
    }

    fun deleteModel() {
        pauseDownload()
        getModelFile().delete()
        getPartFile().delete()
        prefs.edit().clear().apply()
        _downloadState.value = ModelDownloadState.Idle
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        if (gb >= 1.0) {
            return String.format("%.2f GB", gb)
        }
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }
}
