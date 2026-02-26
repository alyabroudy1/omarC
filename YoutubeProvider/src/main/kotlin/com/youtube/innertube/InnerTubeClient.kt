package com.youtube.innertube

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client for YouTube's InnerTube API.
 * Single responsibility: make POST requests and return raw JSON responses.
 * No parsing logic — that belongs in InnerTubeParser.
 */
object InnerTubeClient {

    private const val TAG = "InnerTubeClient"
    
    private val mapper = jacksonObjectMapper().apply {
        configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    
    private val defaultHeaders = mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to InnerTubeConfig.USER_AGENT,
        "Cookie" to InnerTubeConfig.CONSENT_COOKIE,
        "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
        "X-YouTube-Client-Name" to "1",
        "X-YouTube-Client-Version" to InnerTubeConfig.CLIENT_VERSION
    )

    /**
     * Search YouTube for videos and/or playlists.
     * 
     * @param query The search query string
     * @param params Optional search filter params (e.g., SearchFilters.VIDEOS_ONLY)
     * @param hl Language code (default: Arabic)
     * @param gl Country code (default: Saudi Arabia)
     * @return Raw JSON response or null on failure
     */
    suspend fun search(
        query: String, 
        params: String? = null,
        hl: String = "ar",
        gl: String = "SA"
    ): JsonNode? {
        val body = buildMap<String, Any> {
            putAll(InnerTubeConfig.buildContext(hl, gl))
            put("query", query)
            if (params != null) {
                put("params", params)
            }
        }
        return post(InnerTubeConfig.SEARCH_ENDPOINT, body)
    }

    /**
     * Browse a YouTube page (trending, subscriptions, etc.)
     * 
     * @param browseId The browse ID (e.g., "FEtrending")
     * @param params Optional params for sub-sections
     * @return Raw JSON response or null on failure
     */
    suspend fun browse(
        browseId: String,
        params: String? = null,
        hl: String = "ar",
        gl: String = "SA"
    ): JsonNode? {
        val body = buildMap<String, Any> {
            putAll(InnerTubeConfig.buildContext(hl, gl))
            put("browseId", browseId)
            if (params != null) {
                put("params", params)
            }
        }
        return post(InnerTubeConfig.BROWSE_ENDPOINT, body)
    }

    /**
     * Get video details (title, description, etc.)
     * Uses the "next" endpoint which returns richer metadata than "player".
     * 
     * @param videoId YouTube video ID
     * @return Raw JSON response or null on failure
     */
    suspend fun getVideoDetails(videoId: String, hl: String = "ar"): JsonNode? {
        val body = buildMap<String, Any> {
            putAll(InnerTubeConfig.buildContext(hl))
            put("videoId", videoId)
        }
        return post(InnerTubeConfig.NEXT_ENDPOINT, body)
    }

    /**
     * Get playlist contents with all videos.
     * 
     * @param playlistId YouTube playlist ID
     * @return Raw JSON response or null on failure
     */
    suspend fun getPlaylistVideos(playlistId: String, hl: String = "ar"): JsonNode? {
        val body = buildMap<String, Any> {
            putAll(InnerTubeConfig.buildContext(hl))
            put("browseId", "VL$playlistId")
        }
        return post(InnerTubeConfig.BROWSE_ENDPOINT, body)
    }

    // ==================== INTERNAL ====================

    private suspend fun post(url: String, body: Map<String, Any>): JsonNode? {
        return try {
            val jsonBody = mapper.writeValueAsString(body)
            Log.d(TAG, "POST ${url.substringBefore("?")} | body=${jsonBody.take(120)}")
            
            val response = app.post(
                url,
                headers = defaultHeaders,
                requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                referer = "https://www.youtube.com/",
                timeout = 15
            )
            
            val text = response.text
            if (text.isBlank()) {
                Log.w(TAG, "Empty response from $url")
                return null
            }
            
            val json = mapper.readTree(text)
            
            // Check for API errors
            val error = json["error"]
            if (error != null) {
                Log.e(TAG, "API error: ${error["message"]?.textValue()}")
                return null
            }
            
            json
        } catch (e: Exception) {
            Log.e(TAG, "POST failed: ${e.message}")
            null
        }
    }
}
