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
            // Native desktop spoofing
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            exitCondition = ExitCondition.PageLoaded,
            timeout = 15_000L
        )

        when (result) {
            is WebViewResult.Success -> {
                val html = result.html
                val cookies = result.cookies
                
                ProviderLogger.d(TAG, methodName, "CF Bypass natively successful. Captured ${cookies.size} CF cookies and valid fully decrypted HTML payload.")
                
                // 3. Extract JWPlayer sources natively via internal JWPlayerExtractor Utility
                val streams = JWPlayerExtractor.extractFromHtml(html)
                
                if (streams.isEmpty()) {
                    ProviderLogger.w(TAG, methodName, "JWPlayerExtractor returned 0 streams natively. HTML snippet: ${html.take(500)}")
                    return
                }

                // 4. Safely package parsed Native Execute CF Cookies exactly to ExoPlayer
                val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                
                // 5. Build ExtractorLinks intelligently routing properties
                streams.forEach { stream ->
                    ProviderLogger.d(TAG, methodName, "Successfully Dispatching: ${stream.label} -> ${stream.url.take(80)}")
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${stream.label}",
                            url = stream.url,
                            type = stream.type ?: if (stream.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = qualityFromLabel(stream.label)
                            
                            // Natively securely override stream buffer CF HTTP Cookies header injection natively bypassing ExoPlayer 403s!
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
