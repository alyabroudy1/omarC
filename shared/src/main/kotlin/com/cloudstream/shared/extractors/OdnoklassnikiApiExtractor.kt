package com.cloudstream.shared.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class OdnoklassnikiApiExtractor(
    override val mainUrl: String = "https://ok.ru",
    override val name: String = "Odnoklassniki API"
) : ExtractorApi() {
    override val requiresReferer = false

    companion object {
        private const val TAG = "OkRuApiExtractor"

        /**
         * UA used to mint the signed CDN URLs. Deliberately Chrome-family: ok.ru signs links for
         * the *engine family* of the caller (srcAg=CHROME / GECKO — verified 2026-07-25: a
         * CHROME-minted link plays under desktop-Chrome, Android-Chrome AND ExoPlayer's own UA,
         * while a GECKO-minted one 400s under every one of those). Minting as CHROME therefore
         * still works if our header is ever dropped and the player falls back to its own UA;
         * minting as GECKO (what this extractor used to do) fails in exactly that case.
         */
        private const val MINT_UA =
            "Mozilla/5.0 (Linux; Android 16; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/150.0.7871.124 Mobile Safari/537.36"

        private const val GECKO_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /**
         * Playback headers for a signed URL. Reads back the srcAg the CDN actually stamped rather
         * than assuming our [MINT_UA] survived the HTTP layer, so the player is always told to use
         * a UA of the family the signature was issued for. Nothing else may be attached: adding
         * Origin/Sec-Fetch-* to the media request makes the CDN answer 400 even with the right UA.
         */
        private fun playbackHeadersFor(url: String): Map<String, String> {
            val agent = Regex("""[?&]srcAg=([A-Za-z]+)""").find(url)?.groupValues?.get(1)?.uppercase()
            return mapOf("User-Agent" to if (agent == "GECKO") GECKO_UA else MINT_UA)
        }
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

        // ── Why this extractor's links used to 404 ───────────────────────────────────────────
        // The CDN URLs ok.ru hands back are SIGNED FOR THE CALLER: the query carries srcIp=… and
        // srcAg=<UA family of whoever asked>. Measured against the live CDN on 2026-07-25:
        //   minted with Firefox UA → replayed with Firefox UA  = 206 video/mp4
        //   minted with Firefox UA → replayed with Chrome UA   = 400
        //   minted with Chrome  UA → replayed with Chrome  UA  = 206 video/mp4
        //   any UA + Origin/Sec-Fetch-* headers attached        = 400
        // So (a) the UA that mints the link MUST be the UA the player uses, and (b) the CORS-ish
        // headers needed for the API call must NOT be forwarded to the media request. Previously
        // the whole API header map (Origin, Sec-Fetch-*, a hardcoded Firefox UA) was attached to
        // the ExtractorLink while the API call itself went out under the app's own UA — both
        // failure modes at once, which is why every link died.
        val apiHeaders = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to "$mainUrl/",
            "User-Agent" to MINT_UA,
        )

        // Primary: videoPlayerMetadata API
        val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
        Log.d(TAG, "Trying API: $apiUrl")
        try {
            val apiResponse = app.post(apiUrl, headers = apiHeaders).text
            Log.d(TAG, "API response: ${apiResponse.take(500)}")
            val metadata = AppUtils.tryParseJson<VideoPlayerMetadata>(apiResponse)

            var emitted = 0

            // The HLS master carries every quality in one link and is what ok.ru's own player
            // uses; the `videos` array is frequently a single "full" entry, so prefer this.
            val hls = metadata?.ondemandHls?.takeIf { it.isNotBlank() }
            if (hls != null) {
                val hlsUrl = if (hls.startsWith("//")) "https:$hls" else hls
                Log.d(TAG, "API ondemandHls: ${hlsUrl.take(120)}")
                callback.invoke(
                    newExtractorLink(source = name, name = "$name HLS", url = hlsUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeadersFor(hlsUrl)
                    }
                )
                emitted++
            }

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
                        // These are progressive MP4s with no extension in the path, so INFER_TYPE
                        // would have to guess — say it outright.
                        newExtractorLink(source = name, name = "$name $quality", url = videoUrl, type = ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName(quality)
                            this.headers = playbackHeadersFor(videoUrl)
                        }
                    )
                    emitted++
                }
            }
            if (emitted > 0) return
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
            app.get(embedUrl, headers = apiHeaders).text
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
                newExtractorLink(source = name, name = "$name $quality", url = videoUrl, type = ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                    this.headers = playbackHeadersFor(videoUrl)
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
        @JsonProperty("ondemandHls") val ondemandHls: String? = null,
    )
}
