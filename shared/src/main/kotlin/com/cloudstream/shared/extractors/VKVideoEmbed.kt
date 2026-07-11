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

        val html = http?.getText(
            url,
            headers = mapOf(
                "Referer" to (referer ?: "https://vk.com/"),
                "User-Agent" to (ua ?: "Mozilla/5.0"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5"
            ),
            rewriteDomain = false
        )

        if (html != null && html.length > 500) {
            ProviderLogger.i(TAG, "getUrl", "HTTP got ${html.length} chars")
            val videoUrls = mutableListOf<String>()
            Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(html).forEach {
                videoUrls.add(it.groupValues[1].replace("\\/", "/"))
            }
            Regex("""src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(html).forEach {
                videoUrls.add(it.groupValues[1].replace("\\/", "/"))
            }
            Regex("""['"](https?://[^"']+\.(?:m3u8|mp4)[^"']*)['"]""").findAll(html).forEach {
                videoUrls.add(it.groupValues[1])
            }
            if (videoUrls.isNotEmpty()) {
                ProviderLogger.i(TAG, "getUrl", "HTTP found ${videoUrls.size} video URLs")
                for (videoUrl in videoUrls.distinct()) {
                    val link = newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                    }
                    callback(link)
                }
                return
            }
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
            mode = Mode.FULLSCREEN,
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
}
