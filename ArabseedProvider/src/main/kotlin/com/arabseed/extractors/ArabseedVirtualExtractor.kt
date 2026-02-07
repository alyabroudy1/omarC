package com.arabseed.extractors

import android.net.Uri
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking

/**
 * Virtual URL Extractor for Arabseed
 * 
 * Handles lazy:// URLs that need on-demand resolution via POST request.
 * This extractor is triggered when ExoPlayer tries to play a virtual URL,
 * resolves it to the actual video URL, and returns the resolved link.
 */
class ArabseedVirtualExtractor : ExtractorApi() {
    override val name = "ArabseedVirtual"
    override val mainUrl = "https://asd.pics"
    override val requiresReferer = true
    
    // Handle both lazy:// URLs and regular virtual URLs
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("ArabseedVirtual", "[getUrl] Processing URL: ${url.take(80)}")
        
        // Parse the URL to extract parameters
        val uri = Uri.parse(url)
        
        // Handle lazy:// scheme
        val actualUrl = if (url.startsWith("lazy://")) {
            // Convert lazy://asd.pics/path to https://asd.pics/path
            url.replace("lazy://", "https://")
        } else {
            url
        }
        
        // Only process virtual URLs (those containing /get__watch__server/)
        if (!actualUrl.contains("/get__watch__server/")) {
            android.util.Log.d("ArabseedVirtual", "[getUrl] Not a virtual URL, passing through")
            // Not a virtual URL, extract directly from this embed URL
            extractFromEmbedUrl(actualUrl, referer, callback)
            return
        }
        
        // Extract parameters from virtual URL
        val postId = uri.getQueryParameter("post_id")
        val quality = uri.getQueryParameter("quality") ?: "720"
        val server = uri.getQueryParameter("server") ?: "1"
        val csrfToken = uri.getQueryParameter("csrf_token") ?: ""
        val pageReferer = uri.getQueryParameter("referer")?.let {
            java.net.URLDecoder.decode(it, "UTF-8")
        } ?: referer ?: "https://asd.pics/"
        
        if (postId.isNullOrBlank()) {
            android.util.Log.e("ArabseedVirtual", "[getUrl] Missing post_id parameter")
            return
        }
        
        android.util.Log.d("ArabseedVirtual", "[getUrl] Resolving virtual URL: postId=$postId, quality=$quality, server=$server")
        
        // Use app.post to make the request (has proper headers and cookies from CloudStream)
        val data = mapOf(
            "post_id" to postId,
            "quality" to quality,
            "server" to server,
            "csrf_token" to csrfToken
        )
        
        try {
            // CRITICAL: Use singleton HTTP service to get CF session cookies
            val httpService = ProviderHttpServiceHolder.getInstance()
            
            if (httpService == null) {
                android.util.Log.e("ArabseedVirtual", "[getUrl] HTTP Service not initialized!")
                // Fallback to app.post
                val response = app.post(
                    url = actualUrl,
                    data = data,
                    referer = pageReferer,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    )
                )
                handleResponse(response.text, pageReferer, callback)
                return
            }
            
            android.util.Log.d("ArabseedVirtual", "[getUrl] Using singleton HTTP service with CF session")
            
            // Use HTTP service which has CF session cookies
            val jsonResponse = runBlocking {
                httpService.postText(
                    url = "/get__watch__server/",
                    data = data,
                    referer = pageReferer,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )
            }
            
            if (jsonResponse.isNullOrBlank()) {
                android.util.Log.e("ArabseedVirtual", "[getUrl] Empty response from HTTP service")
                return
            }
            
            android.util.Log.d("ArabseedVirtual", "[getUrl] Got response from HTTP service, length=${jsonResponse.length}")
            handleJsonResponse(jsonResponse, pageReferer, callback)
            
        } catch (e: Exception) {
            android.util.Log.e("ArabseedVirtual", "[getUrl] Error during resolution: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun handleResponse(responseText: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val embedUrl = parseEmbedUrlFromJson(responseText)
        
        if (embedUrl.isNullOrBlank()) {
            android.util.Log.e("ArabseedVirtual", "[handleResponse] Failed to parse embed URL")
            return
        }
        
        android.util.Log.i("ArabseedVirtual", "[handleResponse] Resolved to: ${embedUrl.take(60)}")
        extractFromEmbedUrl(embedUrl, referer, callback)
    }
    
    private suspend fun handleJsonResponse(jsonResponse: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val embedUrl = parseEmbedUrlFromJson(jsonResponse)
        
        if (embedUrl.isNullOrBlank()) {
            android.util.Log.e("ArabseedVirtual", "[handleJsonResponse] Failed to parse embed URL from JSON")
            android.util.Log.e("ArabseedVirtual", "[handleJsonResponse] Response: ${jsonResponse.take(200)}")
            return
        }
        
        android.util.Log.i("ArabseedVirtual", "[handleJsonResponse] Resolved to embed URL: ${embedUrl.take(60)}")
        extractFromEmbedUrl(embedUrl, referer, callback)
    }
    
    /**
     * Extract video URL from embed page (savefiles.com, vidara.to, etc.)
     */
    private suspend fun extractFromEmbedUrl(
        embedUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("ArabseedVirtual", "[extractFromEmbedUrl] Processing: ${embedUrl.take(60)}")
        
        // Use SnifferExtractor pattern or direct extraction
        // For now, try direct m3u8 extraction from common patterns
        try {
            val doc = app.get(embedUrl, referer = referer).document
            
            // Look for m3u8 URLs in scripts
            val scripts = doc.select("script")
            var m3u8Url: String? = null
            
            for (script in scripts) {
                val data = script.data()
                if (data.contains(".m3u8")) {
                    // Extract m3u8 URL using regex
                    val regex = Regex("(https?://[^\"']+\\.m3u8[^\"']*)")
                    val match = regex.find(data)
                    if (match != null) {
                        m3u8Url = match.groupValues[1]
                        break
                    }
                }
            }
            
            if (m3u8Url != null) {
                android.util.Log.i("ArabseedVirtual", "[extractFromEmbedUrl] Found M3U8: ${m3u8Url.take(60)}")
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ${if (embedUrl.contains("1080")) "1080p" else if (embedUrl.contains("720")) "720p" else "480p"}",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.quality = when {
                            embedUrl.contains("1080") -> com.lagradost.cloudstream3.utils.Qualities.P1080.value
                            embedUrl.contains("720") -> com.lagradost.cloudstream3.utils.Qualities.P720.value
                            else -> com.lagradost.cloudstream3.utils.Qualities.P480.value
                        }
                    }
                )
            } else {
                android.util.Log.w("ArabseedVirtual", "[extractFromEmbedUrl] No M3U8 found, trying alternative methods")
                // TODO: Implement alternative extraction methods
            }
            
        } catch (e: Exception) {
            android.util.Log.e("ArabseedVirtual", "[extractFromEmbedUrl] Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun parseEmbedUrlFromJson(json: String): String? {
        return try {
            when {
                json.contains(""""embed_url"""") -> {
                    Regex(""""embed_url"\s*:\s*"([^"]+)""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
                }
                json.contains(""""server"""") -> {
                    Regex(""""server"\s*:\s*"([^"]+)""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}