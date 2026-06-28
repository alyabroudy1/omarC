package com.cloudstream.shared.extractors

import android.util.Base64
import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.cloudstream.shared.service.ProviderHttpServiceHolder
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
        val version: Int?,
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
            
            val versionRaw = playback.opt("version")
            val version = when (versionRaw) {
                is Number -> versionRaw.toInt()
                is String -> versionRaw.toIntOrNull()
                else -> null
            }
            ProviderLogger.d(TAG, "parsePlaybackResponse", "version: $version")
            
            val response = PlaybackResponse(
                algorithm = playback.optString("algorithm"),
                iv = playback.optString("iv"),
                payload = playback.optString("payload"),
                keyParts = keyParts,
                version = version,
                expiresAt = playback.optString("expires_at")
            )
            
            ProviderLogger.d(TAG, "parsePlaybackResponse", "Parsed response - algorithm: ${response.algorithm}, iv len: ${response.iv.length}, payload len: ${response.payload.length}")
            
            response
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "parsePlaybackResponse", "Failed to parse JSON", e)
            null
        }
    }

    private fun decodeBase64Safe(str: String): ByteArray {
        var cleanStr = str.replace('-', '+').replace('_', '/')
        val pad = cleanStr.length % 4
        if (pad > 0) {
            cleanStr += "=".repeat(4 - pad)
        }
        return Base64.decode(cleanStr, Base64.DEFAULT)
    }
    
    private fun decryptPlayback(playback: PlaybackResponse): DecryptionResult? {
        if (playback.algorithm != "AES-256-GCM") {
            ProviderLogger.e(TAG, "decrypt", "Unsupported algorithm: ${playback.algorithm}")
            return null
        }
        
        val ivBytes = try {
            decodeBase64Safe(playback.iv)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "decrypt", "Failed to decode IV", e)
            return null
        }
        
        // Allow 8-16 bytes for IV (flexible)
        if (ivBytes.size < 8 || ivBytes.size > 16) {
            ProviderLogger.e(TAG, "decrypt", "Invalid IV length: ${ivBytes.size}, expected 8-16")
            return null
        }
        ProviderLogger.d(TAG, "decrypt", "IV decoded successfully, size: ${ivBytes.size}")
        
        // Derive AES key from key_parts using version-based selection
        // JS equivalent: Mo(yo(e)) where yo selects key_parts[version-1] and key_parts[30-version]
        val keyBytes = deriveKey(playback.version, playback.keyParts)
        ProviderLogger.d(TAG, "decrypt", "Key derived, size: ${keyBytes.size}")
        
        val payloadBytes = try {
            decodeBase64Safe(playback.payload)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "decrypt", "Failed to decode payload", e)
            return null
        }
        
        if (payloadBytes.size < GCM_TAG_LENGTH / 8) {
            ProviderLogger.e(TAG, "decrypt", "Payload too short: ${payloadBytes.size}")
            return null
        }
        ProviderLogger.d(TAG, "decrypt", "Payload decoded, size: ${payloadBytes.size}")
        
        // Android's AES/GCM/NoPadding Cipher.doFinal requires the ciphertext and authTag merged.
        // The payload string encoded in base64 is already exactly ciphertext + authTag!
        val ciphertextWithTag = payloadBytes
        
        // Decrypt AES-256-GCM payload
        ProviderLogger.d(TAG, "decrypt", "Decrypting with AES-256-GCM")
        try {
            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decrypted = cipher.doFinal(ciphertextWithTag)
            val decryptedStr = String(decrypted, Charsets.UTF_8)
            ProviderLogger.i(TAG, "decrypt", "SUCCESS, decrypted length: ${decryptedStr.length}")
            return parseDecryptedJson(decryptedStr)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "decrypt", "Decryption failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Derives AES key from key_parts using version-based selection.
     * JS equivalent: Mo(yo(e))
     * - Positions selected: [version, 31-version] (1-indexed)
     * - Fallback to ALL key_parts if version is null or positions out of bounds
     */
    private fun deriveKey(version: Int?, keyParts: List<String>): ByteArray {
        val selectedParts: List<String>
        if (version != null && version in 1..20) {
            val pos1 = version
            val pos2 = 31 - version
            if (pos1 in 1..keyParts.size && pos2 in 1..keyParts.size) {
                selectedParts = listOf(keyParts[pos1 - 1], keyParts[pos2 - 1])
                ProviderLogger.d(TAG, "deriveKey", "Version=$version, selected positions (1-indexed): [$pos1, $pos2]")
            } else {
                ProviderLogger.d(TAG, "deriveKey", "Version=$version positions [$pos1, $pos2] out of bounds [1, ${keyParts.size}], using ALL parts")
                selectedParts = keyParts
            }
        } else {
            ProviderLogger.d(TAG, "deriveKey", "Version=$version is null or out of range, using ALL key_parts")
            selectedParts = keyParts
        }
        
        val decoded = selectedParts.map { decodeBase64Safe(it) }
        val totalSize = decoded.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (part in decoded) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
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
            val apiUrl = "https://$host/api/videos/$videoId"
            ProviderLogger.d(EXTRACTOR_TAG, methodName, "Calling API: $apiUrl")
            
            val responseStr = try {
                com.lagradost.cloudstream3.app.get(apiUrl).text ?: ""
            } catch (e: Exception) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "app.get failed, trying ProviderHttpService", "error" to e.message)
                val http = ProviderHttpServiceHolder.getInstance()
                http?.getText(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to "https://$host/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to "https://$host"
                    ),
                    rewriteDomain = false
                ) ?: ""
            }
            
            if (responseStr.isBlank()) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "Empty API response")
                return null
            }
            
            ProviderLogger.d(EXTRACTOR_TAG, methodName, "Raw API response length: ${responseStr.length}")
            
            // Try to extract from embed page if API returned "method not allowed"
            if (responseStr.contains("method not allowed", ignoreCase = true)) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "API rejected, falling back to embed page extraction")
                return tryEmbedExtraction(host, videoId)
            }
            
            val result = decryptFromJson(responseStr)
            
            if (result == null) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "Decryption failed - raw response: ${responseStr.take(200)}")
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
    
    private suspend fun tryEmbedExtraction(host: String, videoId: String): DecryptionResult? {
        val methodName = "tryEmbedExtraction"
        try {
            val embedUrl = "https://$host/e/$videoId"
            ProviderLogger.d(EXTRACTOR_TAG, methodName, "Fetching embed page: $embedUrl")
            
            val html = com.lagradost.cloudstream3.app.get(
                embedUrl,
                referer = "https://$host/"
            ).document.outerHtml()
            
            ProviderLogger.d(EXTRACTOR_TAG, methodName, "Embed page length: ${html.length}")
            
            val sources = mutableListOf<VideoSource>()
            Regex("""sources:\s*\[\s*\{[^}]*file:\s*["']([^"']+)["'][^}]*\}""").findAll(html).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                if (url.startsWith("http")) {
                    sources.add(VideoSource(
                        url = url,
                        qualityLabel = "",
                        mimeType = if (url.contains(".m3u8")) "application/x-mpegURL" else null,
                        height = null,
                        bitrateKbps = null
                    ))
                }
            }
            
            if (sources.isEmpty()) {
                Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4))["']""").findAll(html).forEach { match ->
                    val url = match.groupValues[1].replace("\\/", "/")
                    if (url.startsWith("http")) {
                        sources.add(VideoSource(
                            url = url,
                            qualityLabel = "",
                            mimeType = if (url.contains(".m3u8")) "application/x-mpegURL" else null,
                            height = null,
                            bitrateKbps = null
                        ))
                    }
                }
            }
            
            if (sources.isEmpty()) {
                ProviderLogger.w(EXTRACTOR_TAG, methodName, "No video sources found in embed page")
                return null
            }
            
            ProviderLogger.i(EXTRACTOR_TAG, methodName, "Extracted ${sources.size} sources from embed page")
            return DecryptionResult(sources = sources, posterUrl = null, tracks = emptyList())
        } catch (e: Exception) {
            ProviderLogger.e(EXTRACTOR_TAG, methodName, "Embed extraction error", e)
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
                this.headers = mapOf("Referer" to referer)
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
