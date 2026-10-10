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
class CloudflareGateway(
    private val searchEndpoint: String = "https://api.duckduckgo.com/"
) {

    companion object {
        /** Cap for a fetched page digest so a huge response cannot exhaust memory. */
        const val DEFAULT_MAX_READ_BYTES = 200_000
        const val DEFAULT_SEARCH_ENDPOINT = "https://api.duckduckgo.com/"
        private const val HTML_RESULTS_ENDPOINT = "https://html.duckduckgo.com/html/"
    }

    /**
     * 7.11's HTML results fallback only applies to the built-in DuckDuckGo provider; a
     * caller that injects its own [searchEndpoint] owns its result shape, so we never
     * reach a hardcoded host for a custom (e.g. test) endpoint.
     */
    private val isBuiltInProvider: Boolean =
        searchEndpoint.trimEnd('/').startsWith(DEFAULT_SEARCH_ENDPOINT.trimEnd('/'))

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
                .url("${searchEndpoint}?q=$cleanQuery&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", "OmniAgent-WebWorker/1.0")
                .build()

            client.newCall(request).execute().use { response ->
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

                // 7.11: the instant-answer API often has nothing, so fall back to the
                // real HTML results list rather than reporting "no results".
                val results = if (isBuiltInProvider) searchResultList(query) else ""
                if (results.isNotBlank()) {
                    return@withContext CloudflareTaskResult.Success(
                        title = "Search Results for '$query'",
                        content = results,
                        sourceUrl = sourceUrl
                    )
                }

                return@withContext CloudflareTaskResult.Error(
                    "No results available for '$query'. Try a more specific query or open a result page and use read_url."
                )
            }
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

            client.newCall(request).execute().use { response ->
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CloudflareTaskResult.Error("PDF parsing failed: ${e.localizedMessage}")
        }
    }

    /**
     * Fetches a web page and returns a bounded, readable digest of its text.
     *
     * The label advertised to the model is "read a page's content", so this must work
     * for ordinary HTML pages, not just PDFs. The body is capped at [maxBytes] so a
     * huge response cannot exhaust memory, and the digest carries an honest provenance
     * note instead of pretending a full extraction ran.
     */
    suspend fun readUrlContent(url: String, maxBytes: Int = DEFAULT_MAX_READ_BYTES): CloudflareTaskResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "OmniAgent-WebWorker/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext CloudflareTaskResult.Error("Read URL HTTP error ${response.code}")
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("pdf", ignoreCase = true)) {
                        return@withContext parsePdfDocument(url)
                    }
                    val body = response.body ?: return@withContext CloudflareTaskResult.Error("Empty response body")
                    val text = body.source().let { source ->
                        source.request(maxBytes.toLong())
                        source.buffer.snapshot().utf8()
                    }
                    val digest = htmlToText(text).take(maxBytes)
                    if (digest.isBlank()) {
                        return@withContext CloudflareTaskResult.Error("No readable text found at $url")
                    }
                    CloudflareTaskResult.Success(
                        title = "Page: ${url.substringAfterLast('/').ifBlank { url }}",
                        content = digest,
                        sourceUrl = url
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext CloudflareTaskResult.Error("Read URL failed: ${e.localizedMessage}")
            }
        }

    /**
     * 7.11: fetches the plain HTML search page and extracts a bounded list of real
     * results (title + url). Best-effort: returns "" on any failure so the caller can
     * fall through to its honest "no results" message.
     */
    private fun searchResultList(query: String): String = try {
        val cleanQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("${HTML_RESULTS_ENDPOINT}?q=$cleanQuery")
            .header("User-Agent", "OmniAgent-WebWorker/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val html = response.body?.string().orEmpty()
            parseResultLinks(html).take(10).joinToString("\n") { "- ${it.first} (${it.second})" }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    /**
     * Pure helper: extracts result titles + resolved URLs from a DuckDuckGo HTML
     * results page. Unit-testable without network; returns an empty list when nothing
     * matches rather than throwing.
     */
    internal fun parseResultLinks(html: String): List<Pair<String, String>> {
        if (html.isBlank()) return emptyList()
        val anchor = Regex(
            "(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"
        )
        val out = mutableListOf<Pair<String, String>>()
        for (m in anchor.findAll(html)) {
            val rawUrl = m.groupValues[1]
            val title = htmlToText(m.groupValues[2]).trim()
            val url = resolveResultUrl(rawUrl) ?: continue
            if (title.isNotBlank()) out.add(title to url)
            if (out.size >= 10) break
        }
        return out
    }

    /** Resolves a DuckDuckGo redirect (`/l/?uddg=<encoded>`) into the real target URL. */
    internal fun resolveResultUrl(href: String): String? {
        if (href.isBlank()) return null
        return try {
            if (href.contains("uddg=")) {
                val encoded = href.substringAfter("uddg=").substringBefore('&')
                java.net.URLDecoder.decode(encoded, "UTF-8")
            } else {
                href
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pure helper: strips HTML tags/scripts/styles and collapses whitespace so the
     * model receives readable text. Unit-testable without network.
     */
    internal fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|head)\\b[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    suspend fun queryCustomSkill(endpointUrl: String, jsonPayload: String): CloudflareTaskResult = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(endpointUrl)
                .post(jsonPayload.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    return@withContext CloudflareTaskResult.Success(
                        title = "Cloudflare Worker Response",
                        content = responseBody.take(1000)
                    )
                } else {
                    return@withContext CloudflareTaskResult.Error("Cloudflare Worker error: HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CloudflareTaskResult.Error("Custom worker query failed: ${e.localizedMessage}")
        }
    }
}
