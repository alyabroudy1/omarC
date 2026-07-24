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

        val http = ProviderHttpServiceHolder.getInstance()
        val html = http?.getText(
            baseUrl,
            headers = mapOf("Referer" to (referer ?: baseUrl)),
            rewriteDomain = false
        )

        if (html != null && html.length > 1000) {
            ProviderLogger.i(TAG, "getUrl", "HTTP fetch got ${html.length} chars, parsing for video URLs")

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
                        this.referer = baseUrl
                    }
                    callback(link)
                }
                return
            }
        }

        val engine = videoSnifferEngine ?: VideoSnifferEngine { ActivityProvider.currentActivity }
        val snifferUa = try {
            val ctx = ActivityProvider.currentActivity
            if (ctx != null) android.webkit.WebSettings.getDefaultUserAgent(ctx)
                .replace("; wv)", ")") else "Mozilla/5.0"
        } catch (e: Exception) { "Mozilla/5.0" }

        ProviderLogger.i(TAG, "getUrl", "Starting WebView sniff for upnshare URL: $url")
        val result = engine.runSession(
            url = url,
            mode = Mode.HEADLESS,   // sniff in the background — no visible WebView popping up
            userAgent = snifferUa,
            exitCondition = ExitCondition.VideoFound(minCount = 1),
            timeout = 30000L,
            delayMs = 3000,
            referer = baseUrl
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
