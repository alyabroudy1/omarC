package com.cloudstream.shared.extractors

import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.session.SessionProvider
import com.cloudstream.shared.webview.ExitCondition
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.VideoSnifferEngine
import com.cloudstream.shared.webview.WebViewResult
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class VKVideoEmbed : ExtractorApi() {
    override val name = "VKVideo"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = true

    private val TAG = "VKVideoEmbed"
    var videoSnifferEngine: VideoSnifferEngine? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.i(TAG, "getUrl", "Processing VK video URL: ${url.take(80)}")

        val http = ProviderHttpServiceHolder.getInstance()

        val ua = try {
            val ctx = ActivityProvider.currentActivity
            if (ctx != null) android.webkit.WebSettings.getDefaultUserAgent(ctx) else SessionProvider.getUserAgent()
        } catch (e: Exception) { SessionProvider.getUserAgent() }

        // ── Fast path: video_ext.php returns the player params inline ────────────────────────
        // vk serves this endpoint ONLY to requests that look like an embedded iframe. Without
        // `Sec-Fetch-Dest: iframe` it answers 302 → 429 (measured: every other header combination,
        // desktop or mobile UA, with or without Referer/Accept, gets rate-limited), and on-device
        // that rejection manifests as a ~60s stall rather than a fast error. With the header it is
        // a ~0.4s 200. Verified against live vkvideo.ru on 2026-07-25.
        val html = http?.getText(
            url,
            headers = mapOf(
                "Referer" to (referer ?: "https://vk.com/"),
                "User-Agent" to (ua ?: "Mozilla/5.0"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            ),
            rewriteDomain = false
        )

        if (html != null && html.length > 500) {
            ProviderLogger.i(TAG, "getUrl", "HTTP got ${html.length} chars")
            val links = parsePlayerParams(html, url)
            if (links.isNotEmpty()) {
                ProviderLogger.i(TAG, "getUrl", "HTTP found ${links.size} stream(s) — no WebView needed")
                links.forEach(callback)
                return
            }
            ProviderLogger.w(TAG, "getUrl", "No player params in response (rate-limited page?) — falling back to WebView")
        } else {
            ProviderLogger.w(TAG, "getUrl", "HTTP returned null or too short: ${html?.length}")
        }

        val engine = videoSnifferEngine ?: VideoSnifferEngine { ActivityProvider.currentActivity }
        val snifferUa = try {
            val ctx = ActivityProvider.currentActivity
            if (ctx != null) android.webkit.WebSettings.getDefaultUserAgent(ctx)
                .replace("; wv)", ")") else (ua ?: "Mozilla/5.0")
        } catch (e: Exception) { ua ?: "Mozilla/5.0" }

        ProviderLogger.i(TAG, "getUrl", "Starting WebView sniff for VK URL: ${url.take(80)}")
        val result = engine.runSession(
            url = url,
            mode = Mode.HEADLESS,   // sniff in the background — no visible WebView popping up
            userAgent = snifferUa,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 45000L,
            delayMs = 5000,
            referer = referer ?: "https://vk.com/"
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
                        this.referer = url
                    }
                    callback(outLink)
                }
            }
            is WebViewResult.Error -> ProviderLogger.e(TAG, "getUrl", "WebView error: ${result.reason}")
            is WebViewResult.Timeout -> ProviderLogger.e(TAG, "getUrl", "WebView timeout: ${result.lastUrl}")
            else -> {}
        }
    }

    /**
     * Same parsing as [getUrl], but from HTML the caller already holds — **no network request**.
     *
     * For an embed that a WebView has just loaded, this is the only reliable path. Re-fetching
     * `video_ext.php` fails two ways (measured 2026-07-30): VK rate-limits the repeat caller and the
     * request stalls to timeout, and loaded as a top-level document instead of an iframe it answers
     * with `video_embed_error` no matter what. The bytes the iframe already received have neither
     * problem. See `NavigationEngine.fetchEmbedDocument` for the capture side.
     *
     * @return true if any link was produced.
     */
    suspend fun getUrlFromHtml(
        html: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (html.length < 500) {
            ProviderLogger.w(TAG, "getUrlFromHtml", "HTML too short to parse: ${html.length}")
            return false
        }
        val links = parsePlayerParams(html, embedUrl)
        ProviderLogger.i(TAG, "getUrlFromHtml",
            "Parsed ${links.size} stream(s) from ${html.length} chars of captured embed HTML")
        links.forEach(callback)
        return links.isNotEmpty()
    }

    /**
     * Pulls the stream URLs out of the player params embedded in video_ext.php.
     *
     * The params are HTML-escaped JSON, so the URLs arrive as `\"hls\":\"https:\/\/…\"` — the old
     * regexes required a literal `.m3u8`/`.mp4` and unescaped slashes, so they matched nothing even
     * when the fetch succeeded. Two shapes exist and both are handled:
     *   "hls"     → one master playlist (all qualities; this is what VK serves nowadays)
     *   "url720"  → progressive MP4 per quality (older/short uploads)
     * The signed CDN URLs are IP-bound (srcIp), not UA-bound, so they play as-is.
     */
    private suspend fun parsePlayerParams(html: String, embedUrl: String): List<ExtractorLink> {
        fun unescape(s: String) = s
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val out = mutableListOf<ExtractorLink>()

        // Verified against a live video_ext.php dump: the body is `\"hls\":\"https:\/\/host\/…\"`,
        // so the URL run is (escaped-slash | any char that is neither quote nor backslash).
        Regex("""\\?"hls\\?":\\?"(https?:(?:\\/|[^"\\])+)""").find(html)
            ?.groupValues?.get(1)?.let { raw ->
                val hls = unescape(raw)
                ProviderLogger.i(TAG, "parsePlayerParams", "hls master: ${hls.take(90)}")
                out += newExtractorLink(
                    source = name, name = "$name HLS", url = hls, type = ExtractorLinkType.M3U8
                ) { this.referer = embedUrl }
            }

        Regex("""\\?"url(\d{3,4})\\?":\\?"(https?:(?:\\/|[^"\\])+)""").findAll(html).forEach { m ->
            val quality = m.groupValues[1].toIntOrNull() ?: 0
            val mp4 = unescape(m.groupValues[2])
            out += newExtractorLink(
                source = name, name = "$name ${quality}p", url = mp4, type = ExtractorLinkType.VIDEO
            ) {
                this.referer = embedUrl
                this.quality = quality
            }
        }

        return out.distinctBy { it.url }
    }
}
