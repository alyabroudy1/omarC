package com.cloudstream.shared.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class LarozaExtractor(
    override val mainUrl: String,
    override val name: String
) : ExtractorApi() {
    override val requiresReferer = true

    companion object {
        private const val TAG = "LarozaExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "──────────────────────────────────")
        Log.d(TAG, "getUrl called  | name=$name  url=$url  referer=$referer")

        val service = com.cloudstream.shared.service.ProviderHttpServiceHolder.getInstance()
        if (service == null) {
            Log.e(TAG, "ProviderHttpService not initialized — cannot bypass CF")
            return
        }

        val customHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        if (referer != null) {
            customHeaders["Referer"] = referer
        }

        Log.d(TAG, "Fetching via httpService (CF-bypass)...")
        val doc = service.getDocument(url, customHeaders)
        val pageText = doc?.outerHtml() ?: ""

        Log.d(TAG, "Page fetched  | length=${pageText.length}  preview=${pageText.take(200)}")

        if (pageText.isBlank()) {
            Log.e(TAG, "Empty page returned — CF bypass may have failed")
            return
        }

        if (pageText.contains("rocket-loader", ignoreCase = true) || pageText.contains("cf-browser-rentry", ignoreCase = true)) {
            Log.w(TAG, "Page still contains CF markers after httpService fetch — CF bypass incomplete")
            return
        }

        val unpacked = extractUnpackedJs(pageText)
        val searchText = unpacked ?: pageText

        val videoUrl = extractVideoUrl(searchText)
        if (videoUrl != null) {
            Log.d(TAG, "Extracted video URL: $videoUrl")
            val quality = getQualityFromName(videoUrl)
            val linkType = when {
                videoUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                videoUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(source = name, name = name, url = videoUrl, type = linkType) {
                    this.referer = url
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl.trimEnd('/'),
                        "User-Agent" to customHeaders["User-Agent"]!!
                    )
                }
            )
        } else {
            Log.w(TAG, "No video URL found in page")
        }

        Log.d(TAG, "──────────────────────────────────")
    }

    private fun extractUnpackedJs(pageText: String): String? {
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).+?split\(\s*['"]\|['"]\s*\)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val packedMatch = packedRegex.find(pageText) ?: return null

        Log.d(TAG, "Found packed eval()  | matchLength=${packedMatch.value.length}")
        val unpacked = JsUnpacker(packedMatch.value).unpack()
        if (unpacked != null) {
            Log.d(TAG, "Unpacked OK  | length=${unpacked.length}")
        } else {
            Log.w(TAG, "JsUnpacker returned null — unpack failed")
        }
        return unpacked
    }

    private fun extractVideoUrl(text: String): String? {
        val patterns = listOf(
            Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']"""),
            Regex("""["']?file["']?\s*:\s*["']([^"']+)["']"""),
            Regex("""["']?source["']?\s*:\s*["']([^"']+)["']"""),
            Regex("""src:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val url = match.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Found video URL via pattern: $url")
                return url
            }
        }

        return null
    }
}
