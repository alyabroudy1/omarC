package com.cloudstream.shared.extractors

import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class GameHubExtractor : ExtractorApi() {
    override var name = "سيرفر عرب سيد"
    override var mainUrl = "https://m.reviewrate.net"
    override val requiresReferer = true

    companion object {
        private val VIDEO_PATTERNS = listOf(
            Regex("""sources:\s*\[\s*\{[^}]*file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["'][^}]*(?:label:\s*["']([^"']*)["'])?[^}]*\}""", RegexOption.IGNORE_CASE),
            Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["'][^>]*>""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:var|let|const)\s+(?:url|source|file|stream|video_url)\s*=\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""source:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"']+\.(m3u8|mp4|mkv)""", RegexOption.IGNORE_CASE)
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityStr = url.substringAfter("#quality=", "").substringBefore("#")
        val cleanUrl = url.substringBefore("#quality=")
        val displayName = if (qualityStr.isNotBlank()) "$name - ${qualityStr}p" else name
        val actualReferer = referer ?: mainUrl

        val http = ProviderHttpServiceHolder.getInstance() ?: return
        val html = http.getText(
            cleanUrl,
            headers = mapOf("Referer" to actualReferer),
            skipRewrite = true
        ) ?: return

        val csrfToken = html.let { Regex("""['"]csrf_token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

        if (csrfToken.isNullOrBlank()) {
            val found = mutableSetOf<String>()
            for (pattern in VIDEO_PATTERNS) {
                pattern.findAll(html).forEach { m ->
                    val videoUrl = m.groupValues[1].replace("\\/", "/")
                    if (videoUrl.startsWith("http") && found.add(videoUrl)) {
                        val isM3U8 = videoUrl.contains(".m3u8", ignoreCase = true)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = if (isM3U8) "$displayName M3U8" else displayName,
                                url = videoUrl,
                                type = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = cleanUrl
                                if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                            }
                        )
                    }
                }
            }
            return
        }

        val objId = cleanUrl.substringAfter("embed-", "").substringBefore(".html")
        val ajaxUrl = "${mainUrl.trimEnd('/')}/get__watch__server/"

        val postResponse = http.postText(
            ajaxUrl,
            data = mapOf(
                "post_id" to objId,
                "csrf_token" to csrfToken
            ),
            referer = cleanUrl,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl
            ),
            skipRewrite = true
        ) ?: return

        Regex("""src=["'](https?://[^"']+)["']""").findAll(postResponse).forEach { match ->
            val iframeUrl = match.groupValues[1]
            loadExtractor(iframeUrl, cleanUrl, subtitleCallback, callback)
        }

        Regex("""https?://[^\s"']+\.m3u8""").findAll(postResponse).forEach { m ->
            val m3u8 = m.value
            callback(
                newExtractorLink(
                    source = name,
                    name = "$displayName M3U8",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = cleanUrl
                    if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                }
            )
        }
    }
}
