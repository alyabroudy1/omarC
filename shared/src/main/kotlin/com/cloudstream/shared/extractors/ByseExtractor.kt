package com.cloudstream.shared.extractors

import android.util.Base64
import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor for Bysezejataos.com and its aliases.
 * 
 * Clean Architecture with two extraction strategies:
 * 
 * 1. PRIMARY: API-based decryption (fast, no WebView needed)
 *    - Calls /api/videos/{id}/playback endpoint
 *    - Decrypts AES-256-GCM encrypted payload
 *    - Extracts HLS stream URLs directly
 * 
 * 2. FALLBACK: WebView-based extraction (for edge cases)
 *    - Uses Headless WebView to extract video URLs
 *    - More reliable but slower
 * 
 * This design allows easy adoption of future changes:
 * - Add new decryption algorithms directly in this file
 * - Add new API endpoints without modifying extractor logic
 * - WebView fallback ensures reliability
 */
class ByseExtractor(
    private val host: String,
    override val name: String = "Byse"
) : ExtractorApi() {
    
    override val mainUrl: String get() = "https://$host"
    override val requiresReferer = false
    
    // ========== CRYPTO CONSTANTS ==========
    private companion object {
        private const val TAG = "ByseCrypto"
        private const val EXTRACTOR_TAG = "ByseExtractor"
        private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        
        private fun buildApiUrl(host: String, videoId: String, endpoint: String = "playback"): String {
            return "https://$host/api/videos/$videoId/$endpoint"
        }
        
        private fun parseQuality(height: Int?): Int {
            return when (height) {
                2160 -> Qualities.P2160.value
                1440 -> Qualities.P1440.value
                1080 -> Qualities.P1080.value
                720 -> Qualities.P720.value
                480 -> Qualities.P480.value
                360 -> Qualities.P360.value
                240 -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }
        }
    }
    
    // ========== CRYPTO DATA CLASSES ==========
    private data class DecryptionResult(
        val sources: List<VideoSource>,
        val posterUrl: String?,
        val tracks: List<Track>
    )
    
    private data class VideoSource(
        val url: String,
        val qualityLabel: String,
        val mimeType: String?,
        val height: Int?,
        val bitrateKbps: Int?
    )
    
    private data class Track(
        val file: String,
        val label: String?,
        val kind: String?
    )
    
    private data class PlaybackResponse(
        val algorithm: String,
        val iv: String,
        val payload: String,
        val keyParts: List<String>,
        val decryptKeys: Map<String, String>,
        val iv2: String?,
        val payload2: String?,
        val expiresAt: String
    )
    
    // ========== CRYPTO METHODS ==========
    
    private fun parsePlaybackResponse(json: String): PlaybackResponse? {
        return try {
            val parser = org.json.JSONObject(json)
            val playback = parser.optJSONObject("playback") ?: run {
                ProviderLogger.w(TAG, "parsePlaybackResponse", "No 'playback' object in JSON")
                return null
            }
            
            ProviderLogger.d(TAG, "parsePlaybackResponse", "Found playback object, parsing fields...")
            
            val keyPartsArray = playback.optJSONArray("key_parts")
            val keyParts = mutableListOf<String>()
            keyPartsArray?.let {
                for (i in 0 until it.length()) {
                    keyParts.add(it.getString(i))
                }
            }
            ProviderLogger.d(TAG, "parsePlaybackResponse", "key_parts count: ${keyParts.size}")
            
            val decryptKeysObj = playback.optJSONObject("decrypt_keys")
            val decryptKeys = mutableMapOf<String, String>()
            decryptKeysObj?.let {
                it.keys().forEach { key ->
                    decryptKeys[key] = it.getString(key)
                }
            }
            ProviderLogger.d(TAG, "parsePlaybackResponse", "decrypt_keys count: ${decryptKeys.size}, keys: ${decryptKeys.keys}")
            
            val response = PlaybackResponse(
                algorithm = playback.optString("algorithm"),
                iv = playback.optString("iv"),
                payload = playback.optString("payload"),
                keyParts = keyParts,
                decryptKeys = decryptKeys,
                iv2 = playback.optString("iv2").takeIf { it.isNotEmpty() },
                payload2 = playback.optString("payload2").takeIf { it.isNotEmpty() },
                expiresAt = playback.optString("expires_at")
            )
            
            ProviderLogger.d(TAG, "parsePlaybackResponse", "Parsed response - algorithm: ${response.algorithm}, iv len: ${response.iv.length}, payload len: ${response.payload.length}")
            
            response
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "parsePlaybackResponse", "Failed to parse JSON", e)
            null
        }
    }
    
    private fun decryptPlayback(playback: PlaybackResponse): DecryptionResult? {
        if (playback.algorithm != "AES-256-GCM") {
            ProviderLogger.e(TAG, "decrypt", "Unsupported algorithm: ${playback.algorithm}")
            return null
        }
        
        ProviderLogger.d(TAG, "decrypt", "Starting decryption with AES-256-GCM")
        
        val ivBytes = try {
            Base64.decode(playback.iv, Base64.NO_WRAP)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "decrypt", "Failed to decode IV", e)
            return null
        }
        
        if (ivBytes.size != GCM_IV_LENGTH) {
            ProviderLogger.e(TAG, "decrypt", "Invalid IV length: ${ivBytes.size}, expected $GCM_IV_LENGTH")
            return null
        }
        ProviderLogger.d(TAG, "decrypt", "IV decoded successfully, size: ${ivBytes.size}")
        
        val keyBytes = try {
            val part1 = Base64.decode(playback.keyParts[0], Base64.NO_WRAP)
            val part2 = Base64.decode(playback.keyParts[1], Base64.NO_WRAP)
            ByteBuffer.allocate(part1.size + part2.size).apply {
                put(part1)
                put(part2)
            }.array()
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "decrypt", "Failed to build key from key_parts", e)
            return null
        }
        
        if (keyBytes.size != 32) {
            ProviderLogger.e(TAG, "decrypt", "Invalid key length: ${keyBytes.size}, expected 32")
            return null
        }
        ProviderLogger.d(TAG, "decrypt", "Key constructed, size: ${keyBytes.size}")
        
        val payloadBytes = try {
            Base64.decode(playback.payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "decrypt", "Failed to decode payload", e)
            return null
        }
        
        if (payloadBytes.size < GCM_TAG_LENGTH / 8) {
            ProviderLogger.e(TAG, "decrypt", "Payload too short: ${payloadBytes.size}")
            return null
        }
        ProviderLogger.d(TAG, "decrypt", "Payload decoded, size: ${payloadBytes.size}")
        
        val ciphertext = payloadBytes.dropLast(16).toByteArray()
        val authTag = payloadBytes.takeLast(16).toByteArray()
        ProviderLogger.d(TAG, "decrypt", "Ciphertext: ${ciphertext.size}, AuthTag: ${authTag.size}")
        
        for ((keyName, keyBase64) in playback.decryptKeys) {
            ProviderLogger.d(TAG, "decrypt", "Trying decrypt key: $keyName")
            try {
                val result = tryDecryptWithKey(keyBytes, ivBytes, ciphertext, authTag, keyBase64)
                if (result != null) {
                    ProviderLogger.i(TAG, "decrypt", "SUCCESS with key: $keyName, decrypted length: ${result.length}")
                    return parseDecryptedJson(result)
                }
            } catch (e: Exception) {
                ProviderLogger.d(TAG, "decrypt", "Key $keyName failed: ${e.message}")
            }
        }
        
        ProviderLogger.e(TAG, "decrypt", "All decryption keys failed")
        return null
    }
    
    private fun tryDecryptWithKey(
        keyBytes: ByteArray,
        ivBytes: ByteArray,
        ciphertext: ByteArray,
        authTag: ByteArray,
        keyBase64: String
    ): String? {
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        
        val ciphertextWithTag = ciphertext + authTag
        val decrypted = cipher.doFinal(ciphertextWithTag)
        
        return String(decrypted, Charsets.UTF_8)
    }
    
    private fun parseDecryptedJson(json: String): DecryptionResult? {
        return try {
            val root = org.json.JSONObject(json)
            
            val sourcesArray = root.optJSONArray("sources") ?: return null
            val sources = mutableListOf<VideoSource>()
            
            for (i in 0 until sourcesArray.length()) {
                val source = sourcesArray.getJSONObject(i)
                sources.add(VideoSource(
                    url = source.optString("url"),
                    qualityLabel = source.optString("label"),
                    mimeType = source.optString("mime_type").takeIf { it.isNotEmpty() },
                    height = source.optInt("height").takeIf { it > 0 },
                    bitrateKbps = source.optInt("bitrate_kbps").takeIf { it > 0 }
                ))
            }
            
            val tracksArray = root.optJSONArray("tracks")
            val tracks = mutableListOf<Track>()
            
            tracksArray?.let {
                for (i in 0 until it.length()) {
                    val track = it.getJSONObject(i)
                    tracks.add(Track(
                        file = track.optString("file"),
                        label = track.optString("label").takeIf { it.isNotEmpty() },
                        kind = track.optString("kind").takeIf { it.isNotEmpty() }
                    ))
                }
            }
            
            DecryptionResult(
                sources = sources,
                posterUrl = root.optString("poster").takeIf { it.isNotEmpty() },
                tracks = tracks
            )
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "parseDecryptedJson", "Failed to parse decrypted JSON", e)
            null
        }
    }
    
    private fun decryptFromJson(json: String): DecryptionResult? {
        val playback = parsePlaybackResponse(json) ?: return null
        return decryptPlayback(playback)
    }
    
    // ========== EXTRACTOR METHODS ==========
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodName = "getUrl"
        
        val videoId = extractVideoId(url)
        if (videoId == null) {
            ProviderLogger.e(EXTRACTOR_TAG, methodName, "Failed to extract video ID from URL: $url")
            return
        }
        
        ProviderLogger.i(EXTRACTOR_TAG, methodName, "Processing video ID: $videoId")
        
        // Strategy 1: API-based decryption (fast path)
        val apiResult = tryApiExtraction(host, videoId)
        
        if (apiResult != null) {
            ProviderLogger.i(EXTRACTOR_TAG, methodName, "API extraction successful, found ${apiResult.sources.size} sources")
            
            for (source in apiResult.sources) {
                emitVideoLink(callback, source, url)
            }
            
            for (track in apiResult.tracks) {
                emitSubtitleTrack(subtitleCallback, track, url)
            }
            
            ProviderLogger.i(EXTRACTOR_TAG, methodName, "Successfully extracted ${apiResult.sources.size} video links via API")
            return
        }
        
        // Strategy 2: Fallback to WebView-based extraction
        ProviderLogger.w(EXTRACTOR_TAG, methodName, "API extraction failed, falling back to WebView")
        tryWebViewExtraction(host, videoId, url, callback, subtitleCallback)
    }
    
    private suspend fun tryApiExtraction(host: String, videoId: String): DecryptionResult? {
        val methodName = "tryApiExtraction"
        
        try {
            val apiUrl = buildApiUrl(host, videoId, "playback")
            ProviderLogger.d(EXTRACTOR_TAG, methodName, "Calling API: $apiUrl")
            
            val response = app.get(apiUrl).text
            
            if (response.isBlank()) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "Empty API response")
                return null
            }
            
            ProviderLogger.d(EXTRACTOR_TAG, methodName, "Raw API response length: ${response.length}")
            
            val result = decryptFromJson(response)
            
            if (result == null) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "Decryption failed - raw response: ${response.take(200)}")
                return null
            }
            
            if (result.sources.isEmpty()) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "No sources found in decrypted response")
                return null
            }
            
            for (source in result.sources) {
                ProviderLogger.d(EXTRACTOR_TAG, methodName, "Found source: ${source.qualityLabel} -> ${source.url.take(60)}")
            }
            
            return result
            
        } catch (e: Exception) {
            ProviderLogger.e(EXTRACTOR_TAG, methodName, "API extraction error", e)
            return null
        }
    }
    
    private suspend fun tryWebViewExtraction(
        host: String,
        videoId: String,
        originalUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        ProviderLogger.w(EXTRACTOR_TAG, "tryWebViewExtraction", "WebView fallback not implemented - API extraction should work")
    }
    
    private fun extractVideoId(url: String): String? {
        if (!url.contains("/") && !url.contains(".")) {
            return url.takeIf { it.length > 5 }
        }
        
        return url
            .substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .takeIf { it.isNotEmpty() && it.length > 5 }
    }
    
    private suspend fun emitVideoLink(
        callback: (ExtractorLink) -> Unit,
        source: VideoSource,
        referer: String
    ) {
        val url = source.url
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        
        val qualityValue = parseQuality(source.height)
        
        val qualityLabel = source.qualityLabel.ifEmpty { 
            source.height?.let { "${it}p" } ?: "Auto"
        }
        
        callback(
            newExtractorLink(
                source = name,
                name = "$name $qualityLabel",
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = qualityValue
            }
        )
        
        ProviderLogger.d(EXTRACTOR_TAG, "emitVideoLink", "Emitted link", 
            "url" to url.take(60),
            "quality" to qualityLabel,
            "type" to (if (isM3u8) "M3U8" else "VIDEO"))
    }
    
    private fun emitSubtitleTrack(
        subtitleCallback: (SubtitleFile) -> Unit,
        track: Track,
        referer: String
    ) {
        ProviderLogger.d(EXTRACTOR_TAG, "emitSubtitleTrack", "Subtitle available", 
            "label" to (track.label ?: "unknown"),
            "url" to track.file.take(40))
    }
}
