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

    /**
     * Get player data including streaming URLs for a video.
     *
     * Strategy: Try IOS client first (most reliable for direct URLs),
     * fall back to ANDROID_TESTSUITE if IOS returns no usable streams.
     *
     * Why IOS?
     * - Apple devices don't support signature-based cipher decryption,
     *   so YouTube serves direct playable URLs
     * - Returns both adaptive (video-only + audio-only) and muxed formats
     * - Much less likely to return signatureCipher-protected streams
     *
     * Why not ANDROID?
     * - YouTube increasingly returns cipher-protected URLs for the
     *   ANDROID client, especially with stale client versions
     * - The old ANDROID v19.09.37 is frequently blocked or degraded
     *
     * @param videoId YouTube video ID
     * @return Raw JSON response with streamingData or null on failure
     */
    suspend fun getPlayer(videoId: String): JsonNode? {
        // 1. Fetch from TV_EMBEDDED first (Best for restricted VODs, provides extremely stable TV HTML5 DASH links)
        val tvResult = getPlayerWithClient(videoId, ClientProfile.TV_EMBEDDED)
        
        // 2. Fetch from ANDROID_VR (Backup for stable DASH adaptiveFormats)
        val vrResult = getPlayerWithClient(videoId, ClientProfile.ANDROID_VR)
        
        // 3. Fetch from IOS (Provides progressive MP4 muxedFormats if available)
        val iosResult = getPlayerWithClient(videoId, ClientProfile.IOS)

        // Find the best baseline object (preferably TV_EMBEDDED or VR)
        val bestResult = if (tvResult != null && hasUsableStreams(tvResult)) {
            Log.d(TAG, "getPlayer: TV_EMBEDDED returned usable streams")
            tvResult
        } else if (vrResult != null && hasUsableStreams(vrResult)) {
            Log.d(TAG, "getPlayer: ANDROID_VR returned usable streams")
            vrResult
        } else if (iosResult != null && hasUsableStreams(iosResult)) {
            Log.d(TAG, "getPlayer: IOS returned usable streams")
            iosResult
        } else {
            // 4. Last resort: ANDROID_TESTSUITE (legacy embedded client)
            val testsuiteResult = getPlayerWithClient(videoId, ClientProfile.ANDROID_TESTSUITE)
            if (testsuiteResult != null && hasUsableStreams(testsuiteResult)) {
                Log.d(TAG, "getPlayer: ANDROID_TESTSUITE returned usable streams")
                return testsuiteResult
            }
            return tvResult ?: vrResult ?: iosResult ?: testsuiteResult
        }

        // --- MERGE MUXED FORMATS ---
        // ALWAYS inject IOS muxed formats into the final result if IOS found them, because TV/VR usually strip them out
        val bestStreamingData = bestResult.path("streamingData") as? com.fasterxml.jackson.databind.node.ObjectNode
        val iosFormats = iosResult?.path("streamingData")?.path("formats")
        
        if (bestStreamingData != null && iosFormats != null && !iosFormats.isMissingNode && iosFormats.size() > 0) {
            bestStreamingData.set<JsonNode>("formats", iosFormats)
            Log.d(TAG, "getPlayer: Injected ${iosFormats.size()} IOS progressive muxedFormats into final payload")
        }

        return bestResult
    }


    /** Check if a player response contains any formats with direct URLs. */
    private fun hasUsableStreams(json: JsonNode): Boolean {
        val sd = json.path("streamingData")
        if (sd.isMissingNode) return false

        // Check for HLS (live) — always usable
        if (sd.path("hlsManifestUrl").textValue() != null) return true

        // Check adaptive formats for at least one with a direct "url"
        val adaptive = sd.path("adaptiveFormats")
        if (adaptive.isArray) {
            for (fmt in adaptive) {
                if (fmt.path("url").textValue() != null) return true
            }
        }

        // Check muxed formats
        val muxed = sd.path("formats")
        if (muxed.isArray) {
            for (fmt in muxed) {
                if (fmt.path("url").textValue() != null) return true
            }
        }

        return false
    }

    /** Client profiles for the player API. */
    private enum class ClientProfile(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val headerClientName: String
    ) {
        IOS(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            headerClientName = "5"
        ),
        TV_EMBEDDED(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.143 Safari/537.36; SmartTv",
            headerClientName = "67"
        ),
        ANDROID_VR(
            clientName = "ANDROID_VR",
            clientVersion = "1.56.29",
            userAgent = "com.google.android.apps.youtube.vr/1.56.29 (Linux; U; Android 10) gzip",
            headerClientName = "28"
        ),
        ANDROID_TESTSUITE(
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            headerClientName = "30"
        )
    }

    /** Make a player API call with a specific client profile. */
    private suspend fun getPlayerWithClient(videoId: String, profile: ClientProfile): JsonNode? {
        val body = buildMap<String, Any> {
            put("context", mapOf(
                "client" to buildMap {
                    put("clientName", profile.clientName)
                    put("clientVersion", profile.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    put("userAgent", profile.userAgent)
                    if (profile == ClientProfile.ANDROID_TESTSUITE) {
                        put("androidSdkVersion", 30)
                    }
                    if (profile == ClientProfile.IOS) {
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iPhone")
                        put("osVersion", "18.1.0.22B83")
                    }
                }
            ))
            put("videoId", videoId)
            put("playbackContext", mapOf(
                "contentPlaybackContext" to mapOf(
                    "html5Preference" to "HTML5_PREF_WANTS"
                )
            ))
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to profile.userAgent,
            "X-YouTube-Client-Name" to profile.headerClientName,
            "X-YouTube-Client-Version" to profile.clientVersion
        )

        return try {
            val jsonBody = mapper.writeValueAsString(body)
            Log.d(TAG, "POST player [${profile.clientName}] | videoId=$videoId")

            val response = app.post(
                InnerTubeConfig.PLAYER_ENDPOINT,
                headers = headers,
                requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                referer = "https://www.youtube.com/",
                timeout = 15
            )

            val text = response.text
            if (text.isBlank()) {
                Log.w(TAG, "Empty player response [${profile.clientName}] for $videoId")
                return null
            }

            val json = mapper.readTree(text)
            val error = json["error"]
            if (error != null) {
                Log.e(TAG, "Player API error [${profile.clientName}]: ${error["message"]?.textValue()}")
                return null
            }

            // Log stream counts for diagnostics
            val sd = json.path("streamingData")
            val adaptiveCount = if (sd.path("adaptiveFormats").isArray) sd.path("adaptiveFormats").size() else 0
            val muxedCount = if (sd.path("formats").isArray) sd.path("formats").size() else 0
            val hasHls = sd.path("hlsManifestUrl").textValue() != null
            Log.d(TAG, "Player [${profile.clientName}]: adaptive=$adaptiveCount muxed=$muxedCount hls=$hasHls")

            json
        } catch (e: Exception) {
            Log.e(TAG, "getPlayer [${profile.clientName}] failed: ${e.message}")
            null
        }
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
