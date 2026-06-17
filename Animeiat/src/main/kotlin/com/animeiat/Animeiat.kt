package com.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64 // <-- تم التصحيح: استخدام مكتبة أندرويد
import android.util.Log

class AnimeiatProvider : MainAPI() {
    override var mainUrl = "https://api.animeiat.co/v1"
    override var name = "Animeiat"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private fun String.toImgUrl(): String {
        return "https://api.animeiat.co/storage/$this"
    }

    data class Pagination(val current_page: Int, val last_page: Int)
    data class Link(val next: String?)
    data class AnimeInfo(val anime_name: String, val slug: String, val poster_path: String)
    data class AnimeListResponse(val data: List<AnimeInfo>, val meta: Pagination)
    data class EpisodeInfo(
        val title: String,
        val slug: String,
        val number: Float,
        val poster_path: String?
    )
    data class EpisodeListResponse(val data: List<EpisodeInfo>, val links: Link)
    data class Genre(val name: String)
    data class Studio(val name: String)
    data class AnimeDetails(
        val anime_name: String,
        val slug: String,
        val poster_path: String,
        val status: String,
        val story: String?,
        val genres: List<Genre>,
        val studios: List<Studio>
    )
    data class AnimeDetailsResponse(val data: AnimeDetails)
    data class VideoSource(val file: String, val label: String, val quality: String)
    data class VideoData(val sources: List<VideoSource>)
    data class VideoLinksResponse(val data: VideoData)


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        val popularResponse = app.get("$mainUrl/anime?page=$page").parsed<AnimeListResponse>()
        val popularAnimes = popularResponse.data.map {
            newAnimeSearchResponse(it.anime_name, "$mainUrl/anime/${it.slug}") {
                posterUrl = it.poster_path.toImgUrl()
            }
        }
        lists.add(HomePageList("أنميات شائعة", popularAnimes, true))


        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/anime?q=$query").parsed<AnimeListResponse>()
        return response.data.map {
            newAnimeSearchResponse(it.anime_name, "$mainUrl/anime/${it.slug}") {
                posterUrl = it.poster_path.toImgUrl()
            }
        }
    }



    override suspend fun load(url: String): LoadResponse {

        val detailsResponse = app.get(url).parsed<AnimeDetailsResponse>()
        val details = detailsResponse.data

        val episodes = mutableListOf<Episode>()
        var episodesUrl: String? = "$url/episodes" // الرابط الأول لصفحة الحلقات

        while (episodesUrl != null) {
            val response = app.get(episodesUrl).parsed<EpisodeListResponse>()

            response.data.forEach { episodeInfo ->
                episodes.add(
                    newEpisode(
                        data = "$mainUrl/episode/${episodeInfo.slug}" // رابط الحلقة لـ loadLinks
                    ) {
                        name = episodeInfo.title
                        episode = episodeInfo.number.toInt()
                        posterUrl = episodeInfo.poster_path?.toImgUrl()
                    }
                )
            }

            episodesUrl = response.links.next
        }

        return newTvSeriesLoadResponse(
            details.anime_name,
            url,
            TvType.Anime,
            episodes.reversed() // عكس ترتيب الحلقات لتبدأ من 1
        ) {

            this.posterUrl = details.poster_path.toImgUrl()
            this.plot = details.story
            this.tags = details.genres.map { it.name }
        }
    }




    override suspend fun loadLinks(
        data: String, // رابط الحلقة
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "Animeiat-LoadLinks"
        try {
            val episodePage = app.get(data).text
            val playerHash = episodePage.substringAfter("\"hash\":\"").substringBefore("\"")

            if (playerHash.isBlank()) {

                return false
            }


            val decodedBytes = Base64.decode(playerHash, Base64.DEFAULT)
            val decodedString = String(decodedBytes)


            val playerID =
                Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""").find(
                    decodedString
                )?.groupValues?.get(1)

            if (playerID.isNullOrBlank()) {

                return false
            }

            val videoApiUrl = "$mainUrl/video/$playerID"
            val linksResponse = app.get(
                videoApiUrl,
                referer = data
            ).parsed<VideoLinksResponse>()

            linksResponse.data.sources.forEach { source ->
                val quality = source.label.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                val sourceUrl = source.file

                if (sourceUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        sourceUrl,
                        referer = mainUrl
                    ).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            name,
                            "$name ",
                            sourceUrl,
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (e: Exception) {

            return false
        }
        return true
    }
}