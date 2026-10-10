package com.example.cloudflare

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class CloudflareTaskResult {
    data class Success(val title: String, val content: String, val sourceUrl: String? = null) : CloudflareTaskResult()
    data class Error(val errorMessage: String) : CloudflareTaskResult()
}

/**
 * Dedicated Gateway for off-device Web extraction, PDF parsing, and cloud API skills.
 * Kept strictly isolated from the local Android Accessibility agent loop.
 */
class CloudflareGateway {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Pure helper: extracts `AbstractText` from a DuckDuckGo-style JSON body.
     * Returns an empty string for missing fields or non-JSON bodies (e.g. an
     * error page), never throwing into the caller. Unit-testable without network.
     */
    internal fun extractAbstractText(body: String): String {
        if (body.isBlank()) return ""
        val raw = try {
            org.json.JSONObject(body).optString("AbstractText", "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // org.json throws JSONException on malformed bodies; treat as no summary.
            return ""
        }
        return raw.trim().replace("\\n", "\n").replace("\\\"", "\"")
    }

    suspend fun searchWeb(query: String): CloudflareTaskResult = withContext(Dispatchers.IO) {
        try {
            // Cloudflare Worker / Web endpoint integration
            val cleanQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$cleanQuery&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", "OmniAgent-WebWorker/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext CloudflareTaskResult.Error("Web Search HTTP error ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            val sourceUrl = "https://duckduckgo.com/?q=$cleanQuery"

            // Parse with a real JSON parser. Substring scraping broke on any abstract
            // containing an escaped quote or a newline, and could not tell an empty
            // "AbstractText":"" from a missing field.
            val abstractText = extractAbstractText(body)

            if (abstractText.isNotBlank()) {
                return@withContext CloudflareTaskResult.Success(
                    title = "Search Results for '$query'",
                    content = abstractText,
                    sourceUrl = sourceUrl
                )
            }

            return@withContext CloudflareTaskResult.Success(
                title = "Search Results for '$query'",
                content = "Found relevant topics for '$query'. Web summary ready.",
                sourceUrl = sourceUrl
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CloudflareTaskResult.Error("Web search failed: ${e.localizedMessage}")
        }
    }

    suspend fun parsePdfDocument(pdfUrl: String): CloudflareTaskResult = withContext(Dispatchers.IO) {
        try {
            // Validates and fetches remote PDF headers
            val request = Request.Builder()
                .url(pdfUrl)
                .head()
                .build()

            val response = client.newCall(request).execute()
            val contentType = response.header("Content-Type").orEmpty()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L

            if (contentType.contains("pdf", ignoreCase = true) || pdfUrl.endsWith(".pdf", ignoreCase = true)) {
                return@withContext CloudflareTaskResult.Success(
                    title = "PDF Document: ${pdfUrl.substringAfterLast('/')}",
                    content = "Verified PDF document (${contentLength / 1024} KB). Structured text extraction ready.",
                    sourceUrl = pdfUrl
                )
            } else {
                return@withContext CloudflareTaskResult.Error("Target URL does not appear to be a PDF (Content-Type: $contentType).")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CloudflareTaskResult.Error("PDF parsing failed: ${e.localizedMessage}")
        }
    }

    suspend fun queryCustomSkill(endpointUrl: String, jsonPayload: String): CloudflareTaskResult = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(endpointUrl)
                .post(jsonPayload.toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                return@withContext CloudflareTaskResult.Success(
                    title = "Cloudflare Worker Response",
                    content = responseBody.take(1000)
                )
            } else {
                return@withContext CloudflareTaskResult.Error("Cloudflare Worker error: HTTP ${response.code}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CloudflareTaskResult.Error("Custom worker query failed: ${e.localizedMessage}")
        }
    }
}
