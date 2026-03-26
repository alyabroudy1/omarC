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
        val effectiveReferer = referer ?: "$mainUrl/"
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
        var result = engine.runSession(
            url = url,
            mode = Mode.HEADLESS,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            exitCondition = ExitCondition.PageLoaded,
            timeout = 18_000L
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
                            
                            // Only inject cookies when the stream HOST is a fasel domain
                            // (CDN URLs like scdns.io embed faselhdx.xyz in the PATH, not the host)
                            val streamHost = try { java.net.URI(stream.url).host ?: "" } catch (_: Exception) { "" }
                            if (cookieHeader.isNotBlank() && streamHost.contains("fasel", ignoreCase = true)) {
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
        // Extract content inside every script tag
        val allScripts = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        // 1. Look for hlsPlaylist
        val playlistScript = allScripts.firstOrNull { it.contains("hlsPlaylist", ignoreCase = true) }
        if (playlistScript != null) {
            return playlistScript.trim()
        }

        // 2. Look for mainPlayer.setup
        val mainPlayerScript = allScripts.firstOrNull { it.contains("mainPlayer.setup", ignoreCase = true) }
        if (mainPlayerScript != null) {
            return mainPlayerScript.trim()
        }

        // 3. Look for obfuscated arrays (_0x...)
        val obfuscatedScripts = allScripts.filter { Regex("""_0x[a-f0-9]{4,}""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (obfuscatedScripts.isNotEmpty()) {
            // Find the longest obfuscated script (likely the decryptor + payload)
            return obfuscatedScripts.maxByOrNull { it.length }?.trim()
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
                    var console = { log: function(){}, error: function(){}, warn: function(){}, info: function(){}, debug: function(){} };
                    var window = { navigator: { userAgent: '' }, location: { href: '', hostname: '' }, addEventListener: function(){}, removeEventListener: function(){}, innerWidth: 1920, innerHeight: 1080, screen: { width: 1920, height: 1080 } };
                    var _noop = function(){ return _mockEl; };
                    var _mockEl = {
                        setAttribute:_noop, getAttribute:function(){return null;}, removeAttribute:_noop,
                        style:{}, classList:{add:_noop, remove:_noop, contains:function(){return false;}, toggle:_noop},
                        innerHTML:'', textContent:'', outerHTML:'', className:'', id:'', tagName:'DIV',
                        appendChild:_noop, removeChild:_noop, insertBefore:_noop, replaceChild:_noop, cloneNode:function(){return _mockEl;},
                        addEventListener:_noop, removeEventListener:_noop, dispatchEvent:_noop,
                        parentNode:null, parentElement:null, children:[], childNodes:[], firstChild:null, lastChild:null,
                        nextSibling:null, previousSibling:null, querySelector:function(){return _mockEl;}, querySelectorAll:function(){return [];},
                        getBoundingClientRect:function(){return {top:0,left:0,right:0,bottom:0,width:0,height:0};},
                        offsetWidth:0, offsetHeight:0, clientWidth:0, clientHeight:0, scrollWidth:0, scrollHeight:0,
                        // HTMLMediaElement methods (video/audio)
                        canPlayType:function(){return 'maybe';}, play:_noop, pause:_noop, load:_noop,
                        src:'', currentSrc:'', currentTime:0, duration:0, paused:true, ended:false, volume:1, muted:false,
                        // HTMLInputElement
                        value:'', checked:false, focus:_noop, blur:_noop, click:_noop, select:_noop,
                        // Dataset
                        dataset:{}
                    };
                    var document = { 
                        write: _noop, writeln: _noop,
                        createElement: function(tag){ var el = {}; for(var k in _mockEl) el[k]=_mockEl[k]; el.tagName=tag.toUpperCase(); return el; },
                        createElementNS: function(ns,tag){ return document.createElement(tag); },
                        createTextNode: function(){ return _mockEl; },
                        createDocumentFragment: function(){ return _mockEl; },
                        getElementById: function(){return _mockEl;},
                        getElementsByClassName: function(){return [];},
                        getElementsByTagName: function(){return [];},
                        querySelector: function(){return _mockEl;},
                        querySelectorAll: function(){return [];},
                        head: _mockEl, body: _mockEl, documentElement: _mockEl,
                        addEventListener: _noop, removeEventListener: _noop,
                        cookie: '', title: '', readyState: 'complete',
                        location: window.location
                    };
                    var $ = function(selector) {
                        if (typeof selector === 'function') { setTimeout(selector, 0); return; }
                        return { on: function(){}, fadeIn: function(){}, fadeOut: function(){}, addClass: function(){}, removeClass: function(){}, attr: function(){ return null; }, click: function(){} };
                    };
                    $.ajax = function(){};
                    var _capturedConfig = null;
                    var jwplayer = function(name) {
                        return {
                            setup: function(cfg){ _capturedConfig = cfg; return this; },
                            on: function(){ return this; }, getPosition: function(){ return 0; },
                            load: function(){}, play: function(){}, seek: function(){}
                        };
                    };
                    var mainPlayer = {
                        setup: function(cfg){ _capturedConfig = cfg; return this; },
                        on: function(){ return this; }, getPosition: function(){ return 0; },
                        load: function(){}, play: function(){}, seek: function(){}
                    };
                    var setTimeout = function(fn){ if(typeof fn === 'function') try{fn();}catch(e){} };
                    var clearTimeout = function(){};
                    var hlsPlaylist = undefined;
                """.trimIndent())
            }
            val fullScript = mocks + "\n" + script + "\n" + """
                var result = null;
                if (typeof _capturedConfig !== 'undefined' && _capturedConfig !== null) { result = JSON.stringify(_capturedConfig); }
                else if (typeof hlsPlaylist !== 'undefined' && hlsPlaylist !== null) { result = JSON.stringify(hlsPlaylist); }
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
        // Only extract master.m3u8 URLs - variant playlist URLs (e.g. 160_hd1080b_playlist.m3u8)
        // always get 403'd by the scdns.io CDN when played directly
        val matches = Regex("""https?://[^\s"'`<>]+master\.m3u8[^\s"'`<>]*""").findAll(html)
        for (match in matches) {
            val url = match.value
            if (url.contains("stream") || url.contains("scdns")) {
                streams.add(FaselStream(url, "Auto"))
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
