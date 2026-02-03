package com.arabseed.extractors

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLDecoder

typealias JsonFetcher = suspend (url: String, data: Map<String, String>, referer: String) -> String?

class ArabseedLazyExtractor(private val jsonFetcher: JsonFetcher? = null) : ExtractorApi() {
    override val name = "ArabseedLazy"
    override val mainUrl = "https://arabseed.show"
    override val requiresReferer = true

    companion object {
        private const val TAG = "ArabseedLazyExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "[getUrl] Processing: $url")
        
        // Parse virtual URL parameters
        // format: .../get__watch__server/?post_id=...
        val postId = getQueryParam(url, "post_id") ?: return
        val quality = getQueryParam(url, "quality") ?: "720"
        val server = getQueryParam(url, "server") ?: "0"
        val csrfToken = getQueryParam(url, "csrf_token") ?: ""
        val rawReferer = getQueryParam(url, "referer")
        val pageReferer = rawReferer?.let { 
             try { URLDecoder.decode(it, "UTF-8") } catch(e: Exception) { it }
        } ?: referer ?: ""
        
        // Derive base URL
        val baseUrl = url.substringBefore("/get__watch__server")
        
        Log.d(TAG, "[getUrl] postId=$postId, quality=$quality, server=$server, baseUrl=$baseUrl")
        
        // Make POST request
        // Use relative path if jsonFetcher is available (ProviderHttpService)
        val embedUrl = fetchEmbedUrl(baseUrl, postId, quality, server, csrfToken, pageReferer)
        
        if (embedUrl.isBlank()) {
            Log.e(TAG, "[getUrl] Failed to get embed URL")
            return
        }
        
        Log.d(TAG, "[getUrl] Got embed URL: $embedUrl")
        
        var foundVideo = false
        // Try global extractors
        loadExtractor(embedUrl, pageReferer, subtitleCallback) { link ->
            callback(link)
            foundVideo = true
        }
        
        // Manual extraction fallback (e.g. for ReviewRate/Arabseed special embeds)
        if (!foundVideo) {
            Log.d(TAG, "[getUrl] loadExtractor failed, trying manual extraction...")
            val directUrl = extractDirectVideoUrl(embedUrl)
            if (directUrl.isNotBlank()) {
                Log.d(TAG, "[getUrl] Manual extraction found: $directUrl")
                callback(
                    newExtractorLink(
                        source = "ArabSeed",
                        name = "ArabSeed ${quality}p",
                        url = directUrl,
                        type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = quality.toIntOrNull() ?: 0
                    }
                )
            } else {
                 // Log.e(TAG, "[getUrl] Manual extraction also failed")
            }
        }
    }
    
    private fun getQueryParam(url: String, key: String): String? {
        return Regex("[?&]$key=([^&]+)").find(url)?.groupValues?.get(1)
    }

    private suspend fun fetchEmbedUrl(
        baseUrl: String,
        postId: String,
        quality: String,
        server: String,
        csrfToken: String,
        referer: String
    ): String {
        Log.d(TAG, "[fetchEmbedUrl] Requesting embed content")
        try {
            val data = mapOf(
                "post_id" to postId,
                "quality" to quality,
                "server" to server,
                "csrf_token" to csrfToken
            )

            // Use delegated fetcher if available
            if (jsonFetcher != null) {
                Log.d(TAG, "[fetchEmbedUrl] Using delegated JsonFetcher with relative path")
                // Pass RELATIVE path so ProviderHttpService can prepend current domain
                val json = jsonFetcher.invoke("/get__watch__server/", data, referer)
                if (!json.isNullOrBlank()) {
                     return parseEmbedUrlFromJson(json)
                }
                return ""
            }

            // Fallback to app.post
            val endpoint = "$baseUrl/get__watch__server/"
            val response = app.post(
                endpoint,
                headers = mapOf(
                    "User-Agent" to com.lagradost.cloudstream3.USER_AGENT,
                    "Referer" to referer,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = data
            )
            return parseEmbedUrlFromJson(response.text)
        } catch (e: Exception) {
            Log.e(TAG, "[fetchEmbedUrl] Error: ${e.message}")
            return ""
        }
    }
    
    private fun parseEmbedUrlFromJson(json: String): String {
        try {
            val jsonObject = JSONObject(json)
            if (jsonObject.has("embed_url")) {
                return jsonObject.getString("embed_url")
            }
            if (jsonObject.has("server")) {
                return jsonObject.getString("server").replace("\\/", "/")
            }
            
            // Handle error messages
            if (jsonObject.optString("type") == "error") {
                 Log.e(TAG, "[parseEmbedUrlFromJson] Error: ${jsonObject.optString("message")}")
            }
        } catch (e: Exception) {
             Log.e(TAG, "[parseEmbedUrlFromJson] JSON parse error: $e")
        }
        return ""
    }
    
    // Extracted from original file logic, kept for fallback
    private suspend fun extractDirectVideoUrl(embedUrl: String): String {
        try {
            if (embedUrl.contains("reviewrate") || embedUrl.contains("cdn.boutique")) {
                 val response = app.get(embedUrl).text
                 val html = response ?: ""
                 var foundUrl: String? = null
                           
                 // Pattern 1: file: "..."
                 if (foundUrl == null) foundUrl = Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                 // Pattern 2: <source src="...">
                 if (foundUrl == null) foundUrl = Regex("""<source[^>]+src=["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                 // Pattern 3: sources: [{file:"..."}]
                 if (foundUrl == null) foundUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                 // Pattern 4: source: "..."
                 if (foundUrl == null) foundUrl = Regex("""source:\s*["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                 // Pattern 5: URL inside script variable
                 if (foundUrl == null) foundUrl = Regex("""var\s+url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                 
                 return foundUrl ?: ""
            }
        } catch (e: Exception) {
            // ignore
        }
        return ""
    }
}


