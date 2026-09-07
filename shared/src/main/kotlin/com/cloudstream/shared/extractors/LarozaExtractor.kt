package com.cloudstream.shared.extractors

import com.cloudstream.shared.core.Fingerprint
import com.cloudstream.shared.core.ProviderRuntime
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
    override val name: String,
    private val runtime: ProviderRuntime
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

        val customHeaders = mutableMapOf(
            "User-Agent" to Fingerprint.current().userAgent
        )
        if (referer != null) {
            customHeaders["Referer"] = referer
        }

        Log.d(TAG, "Fetching via the runtime (CF-bypass)...")
        val doc = runtime.document(url, customHeaders, rewrite = false)
        val pageText = doc?.outerHtml() ?: ""

        Log.d(TAG, "Page fetched  | length=${pageText.length}  preview=${pageText.take(200)}")

        if (pageText.isBlank()) {
            Log.e(TAG, "Empty page returned — CF bypass may have failed")
            return
        }

        if (pageText.length < 500) {
            Log.w(TAG, "Page too short (${pageText.length} chars) — likely a CF challenge page with no content")
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
                    this.headers = Fingerprint.current()
                        .playbackHeaders(url, mainUrl.trimEnd('/'), null)
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
