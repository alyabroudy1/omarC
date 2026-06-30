package com.cloudstream.shared.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class EstreamExtractor(
    override val mainUrl: String = "https://arabveturk.com",
    override val name: String = "estream"
) : ExtractorApi() {
    override val requiresReferer = true

    companion object {
        private const val TAG = "EstreamExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "──────────────────────────────────")
        Log.d(TAG, "getUrl called  | url=$url  referer=$referer")

        val pageText = try {
            Log.d(TAG, "Fetching embed page (no referer on fetch)...")
            app.get(url).text.also {
                Log.d(TAG, "Page fetched OK  | length=${it.length}  preview=${it.take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch page: $url -> ${e.message}")
            return
        }

        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).+?split\(\s*['"]\|['"]\s*\)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val packedMatch = packedRegex.find(pageText)
        if (packedMatch == null) {
            Log.w(TAG, "No packed eval() pattern found in page text")
            Log.d(TAG, "Page snippet around 'eval': ${pageText.substringAfter("eval").take(100)}")
            return
        }
        Log.d(TAG, "Found packed eval()  | matchLength=${packedMatch.value.length}")

        val unpacked = JsUnpacker(packedMatch.value).unpack()
        if (unpacked == null) {
            Log.w(TAG, "JsUnpacker returned null — unpack failed")
            Log.d(TAG, "Packed JS preview: ${packedMatch.value.take(300)}")
            return
        }
        Log.d(TAG, "Unpacked OK  | length=${unpacked.length}  preview=${unpacked.take(300)}")

        val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
        val fileMatch = fileRegex.find(unpacked)
        if (fileMatch == null) {
            Log.w(TAG, "file: URL regex failed on unpacked JS")
            Log.d(TAG, "Searching alternate patterns...")
            val altRegex = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""", RegexOption.IGNORE_CASE)
            val altMatch = altRegex.find(unpacked)
            if (altMatch != null) {
                val altUrl = altMatch.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Found URL via alternate regex: $altUrl")
                emitVideo(altUrl, url, callback)
                return
            }
            Log.e(TAG, "No video URL found by any method")
            return
        }

        val videoUrl = fileMatch.groupValues[1].replace("\\/", "/")
        Log.d(TAG, "Extracted file URL: $videoUrl")
        emitVideo(videoUrl, url, callback)
    }

    private suspend fun emitVideo(
        videoUrl: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to embedUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        )
        val quality = getQualityFromName(videoUrl)
        Log.d(TAG, "Emitting link  | quality=$quality  headers=$headers  url=${videoUrl.take(120)}")
        callback(
            newExtractorLink(source = name, name = name, url = videoUrl) {
                this.headers = headers
                this.quality = quality
            }
        )
        Log.d(TAG, "──────────────────────────────────")
    }
}
