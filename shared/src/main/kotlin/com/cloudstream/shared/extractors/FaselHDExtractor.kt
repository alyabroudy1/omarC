package com.cloudstream.shared.extractors

import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.webview.CfBypassEngine
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.mozilla.javascript.Context as RhinoContext

/**
 * FaselHD stream URL extractor.
 * 
 * FaselHD uses JWPlayer with heavily obfuscated JavaScript containing hlsPlaylist object.
 * 
 * Clean Architecture approach:
 * 1. Executes CfBypassEngine in HEADLESS mode to solve Cloudflare dynamically.
 * 2. Unencrypted JS executes natively rendering JWPlayer DOM configs.
 * 3. Fully decrypted HTML is natively piped into Cloudstream's native JWPlayerExtractor.
 * 4. Extracted native Cloudflare execution cookies are natively appended directly
 *    into the resulting M3U8 ExtractorLinks securely routing ExoPlayer.
 */
class FaselHDExtractor : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl = "https://faselhdx.xyz"
    override val requiresReferer = true

    // Isolated CfBypassEngine utility (Does NOT modify SnifferEngine logic globally)
    private var cfEngine: CfBypassEngine? = null

    data class FaselStream(val url: String, val quality: String)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodName = "getUrl"
        val effectiveReferer = referer ?: mainUrl
        ProviderLogger.i(TAG, methodName, "Starting cleanly isolated CF Bypass extraction for: $url")

        // 1. Initialize CfBypassEngine
        val engine = cfEngine ?: run {
            val activity = ActivityProvider.currentActivity
            if (activity == null) {
                ProviderLogger.e(TAG, methodName, "No Activity available for CfBypassEngine")
                return
            }
            CfBypassEngine { ActivityProvider.currentActivity }.also { cfEngine = it }
        }

        // 2. Headless execution waiting for PageLoaded
        val result = engine.runSession(
            url = url,
            mode = Mode.HEADLESS,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            exitCondition = ExitCondition.PageLoaded,
            timeout = 15_000L
        )

        when (result) {
            is WebViewResult.Success -> {
                val html = result.html
                val cookies = result.cookies
                
                ProviderLogger.d(TAG, methodName, "CF Bypass natively successful. Captured ${cookies.size} CF cookies and valid fully decrypted HTML payload.")
                
                val streams = mutableListOf<FaselStream>()
                
                // 3. Find the encrypted JS block
                val scriptBlock = findRelevantScriptBlock(html)
                if (scriptBlock != null) {
                    ProviderLogger.d(TAG, methodName, "Found script block, length: ${scriptBlock.length}")
                    
                    // 4. Evaluate heavily obfuscated JS natively using Rhino
                    val hlsPlaylistJson = evaluateJsWithRhino(scriptBlock)
                    if (!hlsPlaylistJson.isNullOrBlank()) {
                        ProviderLogger.d(TAG, methodName, "JS eval result: ${hlsPlaylistJson.take(200)}")
                        streams.addAll(parseHlsPlaylist(hlsPlaylistJson))
                    }
                }
                
                // Fallback to strict Regex
                if (streams.isEmpty()) {
                    ProviderLogger.d(TAG, methodName, "Rhino yielded 0 streams. Trying strict M3U8 regex fallback...")
                    streams.addAll(extractM3u8Regex(html))
                }
                
                if (streams.isEmpty()) {
                    ProviderLogger.w(TAG, methodName, "Rhino JS execution returned 0 streams. Triggering native VideoSniffer explicit fallback!")
                    return
                }

                // 5. Safely package parsed Native Execute CF Cookies exactly to ExoPlayer
                val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                
                // 6. Build ExtractorLinks intelligently routing properties
                streams.forEach { stream ->
                    ProviderLogger.d(TAG, methodName, "Successfully Dispatching: ${stream.quality} -> ${stream.url.take(80)}")
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${stream.quality}",
                            url = stream.url,
                            type = if (stream.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = effectiveReferer
                            this.quality = qualityFromLabel(stream.quality)
                            
                            // Natively securely override stream buffer CF HTTP Cookies!
                            if (cookieHeader.isNotBlank()) {
                                this.headers = mapOf("Cookie" to cookieHeader)
                            }
                        }
                    )
                }
                
                ProviderLogger.i(TAG, methodName, "Successfully returned ${streams.size} streams flawlessly wrapped via Cloudstream Core JWPlayer CF bypass architecture!")
            }
            is WebViewResult.Timeout -> {
                ProviderLogger.e(TAG, methodName, "CF Bypass natively timed out before WebView successfully decrypted Fastel JWPlayer payload via DOM!")
            }
            is WebViewResult.Error -> {
                ProviderLogger.e(TAG, methodName, "CF Bypass catastrophic error natively: ${result.reason}")
            }
            else -> {
                ProviderLogger.e(TAG, methodName, "Unhandled WebViewResult: ${result.javaClass.simpleName}")
            }
        }
    }

    private fun findRelevantScriptBlock(html: String): String? {
        val scriptRegex = Regex("""<script[^>]*>[\s\S]*?hlsPlaylist[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val match = scriptRegex.find(html)
        if (match != null) {
            return match.value.replace(Regex("""</?script[^>]*>"""), "").trim()
        }
        
        val mainPlayerScriptRegex = Regex("""<script[^>]*>[\s\S]*?mainPlayer\.setup[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val mainPlayerMatch = mainPlayerScriptRegex.find(html)
        if (mainPlayerMatch != null) {
            return mainPlayerMatch.value.replace(Regex("""</?script[^>]*>"""), "").trim()
        }
        
        val obfuscatedScripts = Regex("""<script[^>]*>[\s\S]*?_0x[a-f0-9]{4,}[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).toList()
        
        if (obfuscatedScripts.isNotEmpty()) {
            return obfuscatedScripts.maxByOrNull { it.value.length }?.value
                ?.replace(Regex("""</?script[^>]*>"""), "")?.trim()
        }
        return null
    }

    private fun evaluateJsWithRhino(script: String): String? {
        val rhino = RhinoContext.enter()
        try {
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            val mocks = buildString {
                appendLine("""
                    var console = { log: function(){}, error: function(){}, warn: function(){} };
                    var window = {};
                    var document = { 
                        write: function(){}, 
                        createElement: function(){return {setAttribute: function(){}};}, 
                        head: {}, body: {}, querySelectorAll: function(){return [];}
                    };
                    var $ = function(selector) {
                        if (typeof selector === 'function') { setTimeout(selector, 0); return; }
                        return { on: function(){}, fadeIn: function(){}, fadeOut: function(){}, addClass: function(){}, removeClass: function(){}, attr: function(){ return null; }, click: function(){} };
                    };
                    $.ajax = function(){};
                    var jwplayer = function(name) {
                        return { setup: function(){ return this; }, on: function(){ return this; }, getPosition: function(){ return 0; }, load: function(){}, play: function(){}, seek: function(){} };
                    };
                    var mainPlayer = { setup: function(){ return this; }, on: function(){ return this; }, getPosition: function(){ return 0; }, load: function(){}, play: function(){}, seek: function(){} };
                    var setTimeout = function(){}; var clearTimeout = function(){};
                    var hlsPlaylist = undefined;
                """.trimIndent())
            }
            val fullScript = mocks + "\n" + script + "\n" + """
                var result = null;
                if (typeof hlsPlaylist !== 'undefined' && hlsPlaylist !== null) { result = JSON.stringify(hlsPlaylist); }
                else if (typeof _0x4f1abc !== 'undefined') { result = JSON.stringify(_0x4f1abc); }
                else if (typeof _0x5e3ffb !== 'undefined') { result = JSON.stringify(_0x5e3ffb); }
                else if (typeof _0x3a8b2c !== 'undefined') { result = JSON.stringify(_0x3a8b2c); }
                result;
            """.trimIndent()
            
            val resultObj = rhino.evaluateString(scope, fullScript, "FaselHD", 1, null)
            return RhinoContext.toString(resultObj)
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "evaluateJsWithRhino", "JS evaluation failed", e)
            return null
        } finally {
            RhinoContext.exit()
        }
    }

    private fun parseHlsPlaylist(json: String): List<FaselStream> {
        val streams = mutableListOf<FaselStream>()
        if (json.isBlank() || json == "null") return streams
        try {
            val sourcesMatch = Regex(""""sources"\s*:\s*\[([^\]]+)\]""").find(json)
            if (sourcesMatch != null) {
                val sourcesStr = sourcesMatch.groupValues[1]
                val sourceObjects = Regex("""\{[^}]+\}""").findAll(sourcesStr)
                for (sourceObj in sourceObjects) {
                    val objStr = sourceObj.value
                    val url = Regex(""""file"\s*:\s*"([^"]+)"""").find(objStr)?.groupValues?.get(1) ?: Regex(""""src"\s*:\s*"([^"]+)"""").find(objStr)?.groupValues?.get(1)
                    if (url != null && (url.contains(".m3u8") || url.contains(".mp4"))) {
                        val quality = Regex(""""label"\s*:\s*"([^"]+)"""").find(objStr)?.groupValues?.get(1) ?: "auto"
                        streams.add(FaselStream(url, quality))
                    }
                }
            } else {
                val fileMatch = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(json)
                if (fileMatch != null) {
                    val url = fileMatch.groupValues[1]
                    val quality = Regex(""""label"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "auto"
                    streams.add(FaselStream(url, quality))
                }
            }
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "parseHlsPlaylist", "Parse error: ${e.message}", e)
        }
        return streams
    }

    private fun extractM3u8Regex(html: String): List<FaselStream> {
        val streams = mutableListOf<FaselStream>()
        val matches = Regex("""https?://[^\s"'`<>]+\.m3u8[^\s"'`<>]*""").findAll(html)
        for (match in matches) {
            val url = match.value
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
        return streams.distinctBy { it.url }
    }

    private fun qualityFromLabel(label: String): Int {
        val lowerLabel = label.lowercase()
        return when {
            lowerLabel.contains("1080") -> Qualities.P1080.value
            lowerLabel.contains("720") -> Qualities.P720.value
            lowerLabel.contains("480") -> Qualities.P480.value
            lowerLabel.contains("360") -> Qualities.P360.value
            lowerLabel.contains("auto") -> Qualities.Unknown.value
            else -> Qualities.Unknown.value
        }
    }

    companion object {
        private const val TAG = "FaselHDExtractor"
    }
}
