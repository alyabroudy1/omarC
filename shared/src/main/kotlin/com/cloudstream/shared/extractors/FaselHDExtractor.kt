package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.cloudstream.shared.logging.ProviderLogger
import org.mozilla.javascript.Context as RhinoContext

/**
 * FaselHD stream URL extractor.
 * 
 * FaselHD uses jwplayer with obfuscated JavaScript containing hlsPlaylist object.
 * The hlsPlaylist.sources array contains the stream URLs.
 * 
 * Tested and working extraction approach using Mozilla Rhino JS engine.
 */
class FaselHDExtractor : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl = "https://faselhdx.xyz"
    override val requiresReferer = true

    data class FaselStream(val url: String, val quality: String)

    /**
     * Extract video URLs from the player page HTML.
     * Uses Rhino JS engine to evaluate obfuscated JavaScript and extract hlsPlaylist.
     */
    fun extractFromHtml(html: String): List<FaselStream> {
        ProviderLogger.d(TAG, "extractFromHtml", "Starting extraction, HTML length: ${html.length}")
        
        val streams = mutableListOf<FaselStream>()
        
        // Step 1: Find the script block containing hlsPlaylist or mainPlayer.setup
        val scriptBlock = findRelevantScriptBlock(html)
        if (scriptBlock == null) {
            ProviderLogger.e(TAG, "extractFromHtml", "Could not find relevant script block")
            return streams
        }
        ProviderLogger.d(TAG, "extractFromHtml", "Found script block, length: ${scriptBlock.length}")
        
        // Step 2: Evaluate the JavaScript with Rhino
        val hlsPlaylistJson = evaluateJsWithRhino(scriptBlock)
        if (hlsPlaylistJson.isNullOrBlank()) {
            ProviderLogger.e(TAG, "extractFromHtml", "JS evaluation returned empty")
            return streams
        }
        ProviderLogger.d(TAG, "extractFromHtml", "JS eval result: ${hlsPlaylistJson.take(200)}")
        
        // Step 3: Parse the hlsPlaylist JSON
        val parsedStreams = parseHlsPlaylist(hlsPlaylistJson)
        ProviderLogger.d(TAG, "extractFromHtml", "Parsed ${parsedStreams.size} streams from hlsPlaylist")
        
        streams.addAll(parsedStreams)
        
        // Fallback: try regex if hlsPlaylist parsing failed
        if (streams.isEmpty()) {
            ProviderLogger.d(TAG, "extractFromHtml", "Trying fallback regex extraction")
            val fallbackStreams = extractM3u8Regex(html)
            ProviderLogger.d(TAG, "extractFromHtml", "Regex found ${fallbackStreams.size} streams")
            streams.addAll(fallbackStreams)
        }
        
        return streams
    }

    /**
     * Find the script block containing the obfuscated hlsPlaylist code.
     */
    private fun findRelevantScriptBlock(html: String): String? {
        // Look for script with hlsPlaylist or mainPlayer.setup
        val scriptRegex = Regex("""<script[^>]*>[\s\S]*?hlsPlaylist[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val match = scriptRegex.find(html)
        
        if (match != null) {
            val script = match.value
            // Remove script tags
            return script
                .replace(Regex("""</?script[^>]*>"""), "")
                .trim()
        }
        
        // Alternative: find script with mainPlayer.setup
        val mainPlayerScriptRegex = Regex("""<script[^>]*>[\s\S]*?mainPlayer\.setup[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val mainPlayerMatch = mainPlayerScriptRegex.find(html)
        
        if (mainPlayerMatch != null) {
            return mainPlayerMatch.value
                .replace(Regex("""</?script[^>]*>"""), "")
                .trim()
        }
        
        ProviderLogger.d(TAG, "findRelevantScriptBlock", "No hlsPlaylist or mainPlayer script found")
        
        // Last resort: try to find any script with _0x that contains obfuscated code
        val obfuscatedScripts = Regex("""<script[^>]*>[\s\S]*?_0x[a-f0-9]{4,}[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .toList()
        
        if (obfuscatedScripts.isNotEmpty()) {
            ProviderLogger.d(TAG, "findRelevantScriptBlock", "Found ${obfuscatedScripts.size} obfuscated scripts")
            // Return the longest one as it likely contains the playlist
            return obfuscatedScripts.maxByOrNull { it.value.length }?.value
                ?.replace(Regex("""</?script[^>]*>"""), "")
                ?.trim()
        }
        
        return null
    }

    /**
     * Evaluate JavaScript with Rhino engine, mocking browser APIs.
     */
    private fun evaluateJsWithRhino(script: String): String? {
        val rhino = RhinoContext.enter()
        
        try {
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            
            // Mock browser APIs
            val mocks = buildString {
                appendLine("""
                    var console = { 
                        log: function(){}, 
                        error: function(){},
                        warn: function(){} 
                    };
                    var window = {};
                    var document = { 
                        write: function(){}, 
                        createElement: function(){return {setAttribute: function(){}};}, 
                        head: {}, 
                        body: {},
                        querySelectorAll: function(){return [];}
                    };
                    
                    // Mock jQuery
                    var $ = function(selector) {
                        if (typeof selector === 'function') {
                            setTimeout(selector, 0);
                            return;
                        }
                        return {
                            on: function(){},
                            fadeIn: function(){},
                            fadeOut: function(){},
                            addClass: function(){},
                            removeClass: function(){},
                            attr: function(){ return null; },
                            click: function(){}
                        };
                    };
                    $.ajax = function(){};
                    
                    // Mock jwplayer
                    var jwplayer = function(name) {
                        return {
                            setup: function(){ return this; },
                            on: function(){ return this; },
                            getPosition: function(){ return 0; },
                            load: function(){},
                            play: function(){},
                            seek: function(){}
                        };
                    };
                    
                    // Mock mainPlayer
                    var mainPlayer = {
                        setup: function(){ return this; },
                        on: function(){ return this; },
                        getPosition: function(){ return 0; },
                        load: function(){},
                        play: function(){},
                        seek: function(){}
                    };
                    
                    var setTimeout = function(){};
                    var clearTimeout = function(){};
                    
                    var hlsPlaylist = undefined;
                """.trimIndent())
            }
            
            // Full evaluation script
            val fullScript = mocks + "\n" + script + "\n" + """
                // Try to get hlsPlaylist
                var result = null;
                if (typeof hlsPlaylist !== 'undefined' && hlsPlaylist !== null) {
                    result = JSON.stringify(hlsPlaylist);
                } else if (typeof _0x4f1abc !== 'undefined') {
                    result = JSON.stringify(_0x4f1abc);
                } else if (typeof _0x5e3ffb !== 'undefined') {
                    result = JSON.stringify(_0x5e3ffb);
                } else if (typeof _0x3a8b2c !== 'undefined') {
                    result = JSON.stringify(_0x3a8b2c);
                }
                result;
            """.trimIndent()
            
            ProviderLogger.d(TAG, "evaluateJsWithRhino", "Evaluating JS, script length: ${fullScript.length}")
            
            val result = rhino.evaluateString(scope, fullScript, "FaselHD", 1, null)
            val resultStr = RhinoContext.toString(result)
            
            ProviderLogger.d(TAG, "evaluateJsWithRhino", "Result: ${resultStr?.take(100)}")
            
            return resultStr
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "evaluateJsWithRhino", "JS evaluation failed", e)
            return null
        } finally {
            RhinoContext.exit()
        }
    }

    /**
     * Parse hlsPlaylist JSON to extract stream URLs.
     * The format is: {"sources":[{"file":"https://...m3u8","label":"1080p"},...]}
     */
    private fun parseHlsPlaylist(json: String): List<FaselStream> {
        val streams = mutableListOf<FaselStream>()
        
        if (json.isBlank() || json == "null") {
            ProviderLogger.d(TAG, "parseHlsPlaylist", "JSON is empty or null")
            return streams
        }
        
        try {
            // Try to parse as JSON object
            // Look for sources array pattern
            val sourcesMatch = Regex(""""sources"\s*:\s*\[([^\]]+)\]""").find(json)
            
            if (sourcesMatch != null) {
                val sourcesStr = sourcesMatch.groupValues[1]
                ProviderLogger.d(TAG, "parseHlsPlaylist", "Found sources: ${sourcesStr.take(100)}")
                
                // Parse each source object
                val sourceObjects = Regex("""\{[^}]+\}""").findAll(sourcesStr)
                
                for (sourceObj in sourceObjects) {
                    val objStr = sourceObj.value
                    
                    // Extract file URL
                    val fileMatch = Regex(""""file"\s*:\s*"([^"]+)"""").find(objStr)
                    val srcMatch = Regex(""""src"\s*:\s*"([^"]+)"""").find(objStr)
                    val url = fileMatch?.groupValues?.get(1) ?: srcMatch?.groupValues?.get(1)
                    
                    if (url != null && (url.contains(".m3u8") || url.contains(".mp4"))) {
                        // Extract quality label
                        val labelMatch = Regex(""""label"\s*:\s*"([^"]+)"""").find(objStr)
                        val quality = labelMatch?.groupValues?.get(1) ?: "auto"
                        
                        ProviderLogger.d(TAG, "parseHlsPlaylist", "Found stream: $quality -> ${url.take(50)}")
                        streams.add(FaselStream(url, quality))
                    }
                }
            } else {
                // Try alternative format: direct array or single source
                ProviderLogger.d(TAG, "parseHlsPlaylist", "Trying alternative format parsing")
                
                val fileMatch = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(json)
                if (fileMatch != null) {
                    val url = fileMatch.groupValues[1]
                    val labelMatch = Regex(""""label"\s*:\s*"([^"]+)"""").find(json)
                    val quality = labelMatch?.groupValues?.get(1) ?: "auto"
                    streams.add(FaselStream(url, quality))
                }
            }
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "parseHlsPlaylist", "Parse error: ${e.message}", e)
        }
        
        return streams
    }

    /**
     * Fallback: extract m3u8 URLs using regex directly from HTML/JS.
     */
    private fun extractM3u8Regex(html: String): List<FaselStream> {
        val streams = mutableListOf<FaselStream>()
        
        // Look for m3u8 URLs in the HTML
        val m3u8Pattern = Regex("""https?://[^\s"'`<>]+\.m3u8[^\s"'`<>]*""")
        val matches = m3u8Pattern.findAll(html)
        
        for (match in matches) {
            val url = match.value
            // Filter out invalid URLs
            if (url.contains("stream") || url.contains("scdns")) {
                val quality = when {
                    url.contains("1080") || url.contains("hd1080") -> "1080p"
                    url.contains("720") || url.contains("hd720") -> "720p"
                    url.contains("480") -> "480p"
                    url.contains("360") || url.contains("sd") -> "360p"
                    else -> "auto"
                }
                streams.add(FaselStream(url, quality))
            }
        }
        
        ProviderLogger.d(TAG, "extractM3u8Regex", "Found ${streams.size} m3u8 URLs via regex")
        return streams.distinctBy { it.url }
    }

    /**
     * Map quality label to Qualities enum value.
     */
    private fun qualityFromLabel(label: String): Int {
        return when {
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            label.contains("auto") -> Qualities.Unknown.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodName = "getUrl"
        
        try {
            // Use the base URL as referer if none provided
            val effectiveReferer = referer ?: mainUrl
            val startMsg = "Starting extraction for: " + url
            ProviderLogger.i(TAG, methodName, startMsg)
            val refererMsg = "Using referer: " + effectiveReferer
            ProviderLogger.d(TAG, methodName, refererMsg)
            
            // Fetch the player page
            val response = app.get(url, referer = effectiveReferer).text
            val fetchMsg = "Fetched " + response.length + " bytes from " + url
            ProviderLogger.d(TAG, methodName, fetchMsg)
            
            // Extract streams
            val streams = extractFromHtml(response)
            val extractMsg = "Extraction returned " + streams.size + " streams"
            ProviderLogger.d(TAG, methodName, extractMsg)
            
            // Build proper referer - must be base domain, not player URL
            val streamReferer = if (effectiveReferer.contains("/video_player") || effectiveReferer.contains("/player")) {
                mainUrl
            } else {
                effectiveReferer
            }
            ProviderLogger.d(TAG, methodName, "Stream referer: " + streamReferer)
            
            if (streams.isEmpty()) {
                val emptyMsg = "No streams found for url: " + url
                ProviderLogger.e(TAG, methodName, emptyMsg)
                
                // Debug: save HTML for analysis
                val sample = response.take(500)
                val sampleMsg = "HTML sample: " + sample
                ProviderLogger.d(TAG, methodName, sampleMsg)
                return
            }
            
            // Return the streams
            for (stream in streams) {
                val streamInfo = "quality=" + stream.quality + ", url=" + stream.url.take(80)
                val foundMsg = "Found stream: " + streamInfo
                ProviderLogger.d(TAG, methodName, foundMsg)
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ${stream.quality}",
                        url = stream.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = streamReferer
                        this.quality = qualityFromLabel(stream.quality)
                    }
                )
            }
            
            val successMsg = "Successfully returned " + streams.size + " streams"
            ProviderLogger.i(TAG, methodName, successMsg)
            
        } catch (e: Exception) {
            val errorMsg = "Error extracting streams: " + (e.message ?: "unknown")
            ProviderLogger.e(TAG, methodName, errorMsg)
            throw e
        }
    }

    companion object {
        private const val TAG = "FaselHDExtractor"
    }
}
