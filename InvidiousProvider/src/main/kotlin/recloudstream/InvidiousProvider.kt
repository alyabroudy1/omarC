package recloudstream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser

class InvidiousProvider : BaseProvider() {
    override val providerName get() = "Invidious"
    override val baseDomain get() = "inv.nadeko.net"
    override val githubConfigUrl get() = ""
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    override fun getParser(): NewBaseParser {
        return InvidiousParser()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val popular = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/api/v1/popular?fields=videoId,title").text
        )
        val trending = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/api/v1/trending?fields=videoId,title").text
        )
        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Popular",
                    popular?.map { it.toSearchResponse(this) } ?: emptyList(),
                    true
                ),
                HomePageList(
                    "Trending",
                    trending?.map { it.toSearchResponse(this) } ?: emptyList(),
                    true
                )
            ),
            false
        )
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val res = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/api/v1/search?q=$encodedQuery&type=video&fields=videoId,title").text
        )
        return res?.map { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)").find(url)?.groups?.get(1)?.value
        val res = tryParseJson<VideoEntry>(
            app.get("$mainUrl/api/v1/videos/$videoId?fields=videoId,title,description,recommendedVideos,author,authorThumbnails,formatStreams").text
        )
        return res?.toLoadResponse(this)
    }

    private data class SearchEntry(
        val title: String,
        val videoId: String
    ) {
        fun toSearchResponse(provider: InvidiousProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/watch?v=$videoId",
                TvType.Movie
            ) {
                this.posterUrl = "${provider.mainUrl}/vi/$videoId/mqdefault.jpg"
            }
        }
    }

    private data class VideoEntry(
        val title: String,
        val description: String,
        val videoId: String,
        val recommendedVideos: List<SearchEntry>,
        val author: String,
        val authorThumbnails: List<Thumbnail>
    ) {
        suspend fun toLoadResponse(provider: InvidiousProvider): LoadResponse {
            return provider.newMovieLoadResponse(
                title,
                "${provider.mainUrl}/watch?v=$videoId",
                TvType.Movie,
                videoId
            ) {
                plot = description
                posterUrl = "${provider.mainUrl}/vi/$videoId/hqdefault.jpg"
                recommendations = recommendedVideos.map { it.toSearchResponse(provider) }
                actors = listOf(
                    ActorData(
                        Actor(author, authorThumbnails.getOrNull(authorThumbnails.size - 1)?.url ?: ""),
                        roleString = "Author"
                    )
                )
            }
        }
    }

    private data class Thumbnail(
        val url: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(
            "https://youtube.com/watch?v=$data",
            subtitleCallback,
            callback
        )
        callback(
            newExtractorLink(this.name, this.name, "$mainUrl/api/manifest/dash/id/$data") {
                quality = Qualities.Unknown.value
                type = ExtractorLinkType.DASH
                referer = ""
            }
        )
        return true
    }
}
