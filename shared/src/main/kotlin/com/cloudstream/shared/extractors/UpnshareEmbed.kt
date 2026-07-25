package com.cloudstream.shared.extractors

import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.VideoSnifferEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class UpnshareEmbed : ExtractorApi() {
    override val name = "Upnshare"
    override val mainUrl = "https://cimanow.upns.online"
    override val requiresReferer = false

    private val TAG = "UpnshareEmbed"
    var videoSnifferEngine: VideoSnifferEngine? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = url.substringAfter("#").takeIf { it.isNotBlank() }
        ProviderLogger.i(TAG, "getUrl", "Processing upnshare URL hash=$hash")

        if (hash == null) {
            ProviderLogger.w(TAG, "getUrl", "No hash fragment found in URL: $url")
            return
        }

        val baseUrl = Regex("(https?://[^/#]+)").find(url)?.groupValues?.get(1) ?: return

        // NOTE: there is deliberately no HTTP fast path here. Checked against the live host on
        // 2026-07-25: the video id lives in the URL *fragment*, which is never sent to the server,
        // so fetching the base URL only ever returns the ~1.3KB SPA shell (a <script type=module>
        // and two analytics tags — no stream anywhere in it). The player's own JSON endpoints
        // (/api/v1/video?id=, /api/v1/info?id=, /api/v1/player?t=) answer with AES ciphertext
        // (measured entropy 7.93 bits/byte, no repeating-key structure) whose key the bundle
        // derives at runtime from window.location, and the bundle filename is content-hashed so
        // any reimplementation would break on their next deploy. Rendering the page is the only
        // durable option — but see VideoUrlClassifier: the sniff was previously ended within 1.6s
        // by a mc.yandex.com beacon being mistaken for the stream, which is what actually broke
        // this extractor. That filter, not an HTTP shortcut, is the fix.
        val engine = videoSnifferEngine ?: VideoSnifferEngine { ActivityProvider.currentActivity }
        val snifferUa = try {
            val ctx = ActivityProvider.currentActivity
            if (ctx != null) android.webkit.WebSettings.getDefaultUserAgent(ctx)
                .replace("; wv)", ")") else "Mozilla/5.0"
        } catch (e: Exception) { "Mozilla/5.0" }

        // Referer must be the page that legitimately embeds the player (the CimaNow watch URL we
        // were called with), NOT the player's own origin. In the real flow this thing runs in an
        // iframe on cimanow.cc, so document.referrer is that page; the bundle ships `restrictEmbed`
        // and reads `location.ancestorOrigins`, so handing it its own origin is a context it never
        // sees in the wild. [referer] was previously accepted and then thrown away here.
        val embedReferer = referer?.takeIf { it.isNotBlank() } ?: baseUrl

        ProviderLogger.i(TAG, "getUrl", "Starting WebView sniff for upnshare URL: $url (referer=$embedReferer)")
        val result = engine.runSession(
            url = url,
            mode = Mode.HEADLESS,   // sniff in the background — no visible WebView popping up
            userAgent = snifferUa,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 30000L,
            delayMs = 3000,
            referer = embedReferer
        )

        when (result) {
            is WebViewResult.Success -> {
                ProviderLogger.i(TAG, "getUrl", "WebView found ${result.foundLinks.size} links")
                for (link in result.foundLinks) {
                    val outLink = newExtractorLink(
                        source = name,
                        name = "$name ${link.qualityLabel.takeIf { it.isNotBlank() } ?: ""}",
                        url = link.url,
                        type = if (link.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = baseUrl
                    }
                    callback(outLink)
                }
            }
            is WebViewResult.Error -> {
                ProviderLogger.e(TAG, "getUrl", "WebView error: ${result.reason}")
            }
            is WebViewResult.Timeout -> {
                ProviderLogger.e(TAG, "getUrl", "WebView timeout: ${result.lastUrl}")
            }
            else -> {}
        }
    }
}
