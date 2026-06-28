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
            Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|mkv))""", RegexOption.IGNORE_CASE)
        )
        private fun detectQualityFromUrl(url: String): String? {
            val lower = url.lowercase()
            return when {
                lower.contains("1080") -> "1080p"
                lower.contains("720") -> "720p"
                lower.contains("480") -> "480p"
                lower.contains("360") -> "360p"
                lower.contains("240") -> "240p"
                else -> null
            }
        }
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
            rewriteDomain = false
        ) ?: return

        val csrfToken = Regex("""['"]csrf_token['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)

        if (csrfToken.isNullOrBlank()) {
            val seen = mutableSetOf<String>()
            for (pattern in VIDEO_PATTERNS) {
                pattern.findAll(html).forEach { m ->
                    val videoUrl = m.groupValues[1].replace("\\/", "/")
                    if (!videoUrl.startsWith("http") || !seen.add(videoUrl)) return@forEach

                    if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                        parseM3u8(videoUrl, cleanUrl, qualityStr, displayName).forEach { callback(it) }
                    } else {
                        val labelQuality = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                        val urlQuality = detectQualityFromUrl(videoUrl)
                        val linkQuality = qualityStr.ifBlank { labelQuality ?: urlQuality ?: "" }
                        val linkName = if (linkQuality.isNotBlank()) "$displayName - $linkQuality" else displayName
                        callback(
                            newExtractorLink(
                                source = name,
                                name = linkName,
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = cleanUrl
                                if (linkQuality.isNotBlank()) this.quality = getQualityFromName(linkQuality)
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
            rewriteDomain = false
        ) ?: return

        Regex("""src=["'](https?://[^"']+)["']""").findAll(postResponse).forEach { match ->
            loadExtractor(match.groupValues[1], cleanUrl, subtitleCallback, callback)
        }

        Regex("""(https?://[^\s"']+\.m3u8)""", RegexOption.IGNORE_CASE).findAll(postResponse).forEach { m ->
            val m3u8 = m.groupValues[1]
            parseM3u8(m3u8, cleanUrl, qualityStr, displayName).forEach { callback(it) }
        }
    }

    private suspend fun parseM3u8(
        m3u8Url: String,
        pageUrl: String,
        qualityStr: String,
        displayName: String
    ): List<ExtractorLink> {
        val http = ProviderHttpServiceHolder.getInstance() ?: return emptyList()
        val m3u8Content = http.getText(
            m3u8Url,
            headers = mapOf("Referer" to pageUrl, "Accept" to "*/*"),
            rewriteDomain = false
        ) ?: return emptyList()

        if (!m3u8Content.contains("#EXT-X-STREAM-INF")) {
            return listOf(
                newExtractorLink(
                    source = name, name = "$displayName M3U8", url = m3u8Url, type = ExtractorLinkType.M3U8
                ) {
                    this.referer = pageUrl
                    if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                }
            )
        }

        val links = mutableListOf<ExtractorLink>()
        val lines = m3u8Content.lines()
        val baseUrl = m3u8Url.substringBeforeLast("/")

        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val height = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues?.get(2)?.toIntOrNull()
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()

                for (i in index + 1 until lines.size) {
                    val nextLine = lines[i].trim()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        val streamUrl = if (nextLine.startsWith("http")) nextLine else "$baseUrl/$nextLine"
                        val label = height?.let { "${it}p" } ?: bandwidth?.let { "${it / 1000}kbps" } ?: "Auto"
                        links.add(
                            newExtractorLink(
                                source = name, name = "$displayName - $label", url = streamUrl, type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = pageUrl
                                this.quality = height ?: (bandwidth?.div(1000)?.toInt()) ?: Qualities.Unknown.value
                            }
                        )
                        break
                    }
                }
            }
        }

        return if (links.isEmpty()) {
            listOf(
                newExtractorLink(
                    source = name, name = "$displayName M3U8", url = m3u8Url, type = ExtractorLinkType.M3U8
                ) {
                    this.referer = pageUrl
                    if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                }
            )
        } else {
            links.sortedByDescending { it.quality }
        }
    }
}
