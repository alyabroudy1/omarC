package com.cloudstream.shared.extractors

import android.util.Log
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class GameHubExtractor : ExtractorApi() {
    override var name = "سيرفر عرب سيد"
    override var mainUrl = "https://m.reviewrate.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qualityStr = url.substringAfter("#quality=", "").substringBefore("#")

            val cleanUrl = url.substringBefore("#quality=")

            val displayName = if (qualityStr.isNotBlank()) "$name - ${qualityStr}p" else name

            val http = ProviderHttpServiceHolder.getInstance() ?: run {
                Log.w("GameHubExtractor", "ProviderHttpService not initialized")
                return
            }
            val html = http.getText(
                cleanUrl,
                headers = mapOf("Referer" to (referer ?: mainUrl)),
                skipRewrite = true
            ) ?: run {
                Log.w("GameHubExtractor", "getText returned null for URL: ${cleanUrl.take(80)}")
                return
            }

            val csrfToken = html.let { Regex("""['"]csrf_token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

            if (csrfToken.isNullOrBlank()) {
                Regex("""https?://[^\s"']+\.(m3u8|mp4|mkv)""").findAll(html).forEach { m ->
                    val link = m.value

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = displayName,
                            url = link,
                            type = if (link.endsWith("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cleanUrl
                        }
                    )
                }
                return
            }

            val objId = cleanUrl.substringAfter("embed-", "").substringBefore(".html")
            if (objId.isBlank()) {
                Log.w("GameHubExtractor", "objId is blank for URL: $cleanUrl")
            }

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
            ) ?: run {
                Log.w("GameHubExtractor", "postText returned null for AJAX URL: ${ajaxUrl.take(80)}")
                return
            }

            Regex("""src=["'](https?://[^"']+)["']""").findAll(postResponse).forEach { match ->
                val iframeUrl = match.groupValues[1]

                loadExtractor(iframeUrl, cleanUrl, subtitleCallback, callback)
            }

            Regex("""https?://[^\s"']+\.m3u8""").findAll(postResponse).forEach { m ->
                val m3u8 = m.value

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$displayName M3U8",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = cleanUrl
                        if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                    }
                )
            }
        } catch (e: Exception) {
            Log.w("GameHubExtractor", "Extraction failed for URL: ${url.take(80)} | ${e.message}")
        }
    }
}
