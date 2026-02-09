package com.arabseed.extractors

import android.net.Uri
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.runBlocking
import com.lagradost.api.Log

/**
 * Virtual URL Extractor for Arabseed
 * 
 * Handles arabseed-virtual:// URLs that need on-demand resolution via POST request.
 * This extractor is triggered when ExoPlayer tries to play a virtual URL.
 * It resolves the virtual URL to a real embed URL (e.g., fastplay, vidbom),
 * and then recursively calls `loadExtractor` to resolve that embed to a video file.
 */
class ArabseedVirtualExtractor : ExtractorApi() {
    override val name = "ArabseedVirtual"
    override val mainUrl = "https://asd.pics"
    override val requiresReferer = true
    
    // Accept arabseed-virtual:// as a valid input for this extractor
    // We strictly use this prefix to route traffic here
    override fun getExtractorUrl(url: String): String {
        return if (url.startsWith("arabseed-virtual://")) {
            url
        } else {
            ""
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "ArabseedVirtual"
        Log.d(tag, "[getUrl] START Processing URL: ${url.take(80)}")
        
        // 1. Check schema
        if (!url.startsWith("arabseed-virtual://")) {
            Log.e(tag, "[getUrl] Invalid scheme, expected arabseed-virtual://")
            return
        }
        
        // 2. Parse URL parameters
        // Convert to standard http uri for parsing
        val httpUrl = url.replace("arabseed-virtual://", "https://")
        val uri = Uri.parse(httpUrl)
        
        val postId = uri.getQueryParameter("post_id")
        val quality = uri.getQueryParameter("quality") ?: "720"
        val server = uri.getQueryParameter("server") ?: "1"
        val csrfToken = uri.getQueryParameter("csrf_token") ?: ""
        // The original page referer (where we came from)
        val pageReferer = uri.getQueryParameter("referer")?.let {
            java.net.URLDecoder.decode(it, "UTF-8")
        } ?: referer ?: "https://asd.pics/"
        
        if (postId.isNullOrBlank()) {
            Log.e(tag, "[getUrl] Missing post_id parameter")
            return
        }
        
        Log.i(tag, "[getUrl] Resolving virtual URL: postId=$postId, quality=$quality, server=$server")
        
        // 3. Perform POST to get the real embed URL
        val data = mapOf(
            "post_id" to postId,
            "quality" to quality,
            "server" to server,
            "csrf_token" to csrfToken
        )
        
        try {
            // CRITICAL: Use singleton HTTP service to pass Cloudflare checks
            val httpService = ProviderHttpServiceHolder.getInstance()
            
            val jsonResponse = if (httpService != null) {
                Log.d(tag, "[getUrl] Using singleton HTTP service (CF-aware)")
                runBlocking {
                    httpService.postText(
                        url = "/get__watch__server/",
                        data = data,
                        referer = pageReferer,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                }
            } else {
                Log.w(tag, "[getUrl] HTTP Service not initialized! Fallback to app.post (might fail CF)")
                app.post(
                    url = "https://arabseed.show/get__watch__server/",
                    data = data,
                    referer = pageReferer,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    )
                ).text
            }
            
            if (jsonResponse.isNullOrBlank()) {
                Log.e(tag, "[getUrl] Empty response from server")
                return
            }
            
            // 4. Parse the embed URL from JSON
            Log.d(tag, "[getUrl] Response length: ${jsonResponse.length}")
            val embedUrl = parseEmbedUrlFromJson(jsonResponse)
            
            if (embedUrl.isNullOrBlank()) {
                Log.e(tag, "[getUrl] Failed to parse embed URL form response: ${jsonResponse.take(100)}")
                return
            }
            
            // 5. RECURSIVE EXTRACTION
            // Now we have the real embed URL (e.g. https://vidbom.com/embed...), 
            // we hand it back to CloudStream's extraction system.
            Log.i(tag, "[getUrl] Resolved to embed URL: ${embedUrl.take(60)}")
            Log.d(tag, "[getUrl] Calling loadExtractor on embed URL...")
            
            val extractionSuccess = loadExtractor(
                url = embedUrl,
                referer = pageReferer,
                subtitleCallback = subtitleCallback,
                callback = { link ->
                    // Wrap the callback to add logging and any metadata
                    Log.d(tag, "[getUrl] Extractor yielded link: ${link.name} (${link.url.take(40)}...)")
                    callback(link)
                }
            )
            
            if (extractionSuccess) {
                Log.i(tag, "[getUrl] Successfully extracted links from embed")
            } else {
                Log.w(tag, "[getUrl] loadExtractor returned false (no extractor found or failed)")
                // Optional: Try direct extraction as last resort?
                // For now, we trust the system extractors.
            }
            
        } catch (e: Exception) {
            Log.e(tag, "[getUrl] Error resolving URL: ${e.message}")
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