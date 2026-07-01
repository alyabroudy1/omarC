package com.cloudstream.shared.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class OdnoklassnikiApiExtractor(
    override val mainUrl: String = "https://ok.ru",
    override val name: String = "Odnoklassniki API"
) : ExtractorApi() {
    override val requiresReferer = false

    companion object {
        private const val TAG = "OkRuApiExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""/(\d+)/?$""").find(url)?.groupValues?.get(1)
            ?: return

        Log.d(TAG, "getUrl | url=$url | videoId=$videoId")

        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0",
        )

        // Primary: videoPlayerMetadata API
        val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
        Log.d(TAG, "Trying API: $apiUrl")
        try {
            val apiResponse = app.post(apiUrl, headers = headers).text
            Log.d(TAG, "API response: ${apiResponse.take(500)}")
            val metadata = AppUtils.tryParseJson<VideoPlayerMetadata>(apiResponse)
            if (metadata?.videos != null && metadata.videos.isNotEmpty()) {
                Log.d(TAG, "API returned ${metadata.videos.size} videos")
                for (video in metadata.videos) {
                    val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
                    val quality = video.name.uppercase()
                        .replace("MOBILE", "144p")
                        .replace("LOWEST", "240p")
                        .replace("LOW", "360p")
                        .replace("SD", "480p")
                        .replace("HD", "720p")
                        .replace("FULL", "1080p")
                        .replace("QUAD", "1440p")
                        .replace("ULTRA", "4k")
                    Log.d(TAG, "API video: quality=$quality url=$videoUrl")
                    callback.invoke(
                        newExtractorLink(source = name, name = name, url = videoUrl, type = INFER_TYPE) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName(quality)
                            this.headers = headers
                        }
                    )
                }
                return
            }
            if (!metadata?.error.isNullOrBlank()) {
                Log.w(TAG, "API returned error: ${metadata!!.error}")
            } else {
                Log.w(TAG, "API returned no videos and no error, unexpected JSON")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed: ${e.message}")
        }

        // Fallback: legacy embed page scraping (same approach as built-in Odnoklassniki extractor)
        Log.d(TAG, "Falling back to embed page scraping")
        val embedUrl = "https://ok.ru/videoembed/$videoId"
        val embedText = try {
            app.get(embedUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "Embed page fetch failed: ${e.message}")
            return
        }

        val cleanedText = embedText
            .replace("\\&quot;", "\"").replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(cleanedText)?.groupValues?.get(1)
        val videos = videosStr?.let { AppUtils.tryParseJson<List<OkRuVideo>>(it) }
            ?: return

        Log.d(TAG, "Embed fallback found ${videos.size} videos")
        for (video in videos) {
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "4k")
            callback.invoke(
                newExtractorLink(source = name, name = name, url = videoUrl, type = INFER_TYPE) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    data class OkRuVideo(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
    )

    data class VideoPlayerMetadata(
        @JsonProperty("videos") val videos: List<OkRuVideo>?,
        @JsonProperty("error") val error: String?,
    )
}
