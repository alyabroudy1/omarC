package com.tvgarden

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
class FamelackProvider : MainAPI() {
    override var mainUrl = "https://famelack.com"
    override var name = "TVgarden(Famelack)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true

    private val allChannelsUrl = "https://raw.githubusercontent.com/famelack/famelack-data/refs/heads/main/tv/raw/categories/all.json"
    private val countriesMetadataUrl = "https://raw.githubusercontent.com/famelack/famelack-data/refs/heads/main/tv/raw/countries_metadata.json"

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get(allChannelsUrl).text
            val channels = parseJson<List<RawChannel>>(response)

            channels.filter { ch ->

                (ch.stream_urls?.isNotEmpty() == true || ch.youtube_urls?.isNotEmpty() == true) &&
                        (ch.name?.contains(query, ignoreCase = true) == true)
            }.mapNotNull { ch ->

                val targetUrl = ch.stream_urls?.firstOrNull() ?: ch.youtube_urls?.firstOrNull()
                if (targetUrl.isNullOrEmpty()) return@mapNotNull null

                newLiveSearchResponse(
                    name = ch.name ?: "Unknown Channel",
                    url = targetUrl,
                ) {
                    type = TvType.Live

                    posterUrl = "https://famelack.com/assets/favicons/favicon-512.png"
                    lang = "en"
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        try {

            val countriesRes = app.get(countriesMetadataUrl).text

            val countriesMap = parseJson<Map<String, CountryMeta>>(countriesRes)

            val channelsRes = app.get(allChannelsUrl).text
            val allChannels = parseJson<List<RawChannel>>(channelsRes)

            val channelsByCountry = allChannels.groupBy { it.country?.lowercase() }

            for ((countryCode, meta) in countriesMap) {

                if (meta.hasChannels) {
                    val codeLower = countryCode.lowercase()
                    val countryChannels = channelsByCountry[codeLower] ?: emptyList()

                    val searchResponses = countryChannels.mapNotNull { ch ->
                        val targetUrl = ch.stream_urls?.firstOrNull() ?: ch.youtube_urls?.firstOrNull()
                        if (targetUrl.isNullOrEmpty()) return@mapNotNull null

                        newLiveSearchResponse(
                            name = ch.name ?: "Unknown",
                            url = targetUrl,
                        ) {
                            type = TvType.Live
                            posterUrl = "https://famelack.com/assets/favicons/favicon-512.png"
                            lang = "en"
                        }
                    }

                    if (searchResponses.isNotEmpty()) {
                        items.add(HomePageList(meta.country, searchResponses))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(items)
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            dataUrl = url
        ) {

            posterUrl = "https://famelack.com/assets/favicons/favicon-512.png"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var urlToLoad = data

        if (data.contains("youtube-nocookie.com/embed/")) {

            val videoId = data.substringAfterLast("/")

            if (videoId.isNotBlank()) {
                urlToLoad = "https://m.youtube.com/watch?v=$videoId"
            }

            return loadExtractor(urlToLoad, subtitleCallback, callback)
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
            ) {
                referer = ""
                quality = Qualities.Unknown.value
            }
        )

        return true
    }


    data class RawChannel(
        val nanoid: String? = null,
        val name: String? = null,
        val stream_urls: List<String>? = emptyList(),
        val youtube_urls: List<String>? = emptyList(),
        val country: String? = null
    )

    data class CountryMeta(
        val country: String,
        val hasChannels: Boolean
    )
}