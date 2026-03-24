package com.mycimaclone

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.parsing.ParserInterface
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class MyCimaClone : BaseProvider() {
    override val baseDomain get() = "mycima.horse"
    override val providerName get() = "MyCimaClone"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/mycimaclone.json"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/" to "احدث الاضافات",
        "/movies/" to "الافلام",
        "/series/" to "المسلسلات",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "افلام انمي",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "مسلسلات انمي"
    )

    override fun getParser(): NewBaseParser = MyCimaCloneParser()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = super.getMainPage(page, request) ?: return null
        val newItems = response.items.map { homePageList ->
            val modifiedList = homePageList.list.map { item ->
                // MyCimaClone URL structure now contains مسلسل for TV series and فيلم for movies
                val isSeries = item.url.contains("مسلسل") || item.url.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84", ignoreCase = true)
                
                if (isSeries && item is MovieSearchResponse) {
                    newTvSeriesSearchResponse(item.name, item.url, TvType.TvSeries) {
                        this.posterUrl = item.posterUrl
                        this.year = item.year
                        this.posterHeaders = item.posterHeaders
                    }
                } else if (!isSeries && item is TvSeriesSearchResponse) {
                    newMovieSearchResponse(item.name, item.url, TvType.Movie) {
                        this.posterUrl = item.posterUrl
                        this.year = item.year
                        this.posterHeaders = item.posterHeaders
                    }
                } else item
            }
            homePageList.copy(list = modifiedList)
        }
        return response.copy(items = newItems)
    }



    override suspend fun fetchExtraEpisodes(
        doc: org.jsoup.nodes.Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val seasonsEl = doc.select(".SeasonsList ul li a")
        if (seasonsEl.isEmpty()) return data.episodes ?: emptyList()
        
        val postId = doc.selectFirst("input[name=post_id], meta[name=post_id]")?.attr("value")?.ifEmpty {
            doc.selectFirst("meta[name=post_id]")?.attr("content")
        }?.ifEmpty {
            doc.selectFirst("[data-postid]")?.attr("data-postid")
        } ?: Regex("""post_id\s*[:=]\s*['"]?(\d+)""").find(doc.html())?.groupValues?.get(1)

        if (postId == null) return data.episodes ?: emptyList()

        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        seasonsEl.forEach { seasonAnchor ->
            val seasonLabel = seasonAnchor.text()
            val seasonNum = Regex("الموسم (\\d+)").find(seasonLabel)?.groupValues?.get(1)?.toIntOrNull() 
                            ?: Regex("(\\d+)").find(seasonLabel)?.groupValues?.get(1)?.toIntOrNull() 
                            ?: 1
                            
            val dataSeason = seasonAnchor.attr("data-season")
            if (dataSeason.isBlank()) return@forEach

            try {
                val ajaxUrl = "$mainUrl/wp-content/themes/mycima/Ajaxt/Single/Episodes.php"
                val seasonHtml = httpService.postText(ajaxUrl, mapOf("season" to dataSeason, "post_id" to postId), referer = url)
                
                if (seasonHtml != null) {
                    val seasonDoc = Jsoup.parse(seasonHtml)
                    seasonDoc.select("a").forEach { epEl ->
                        val epUrl = epEl.attr("href")
                        val epTitle = epEl.selectFirst("episodetitle")?.text()?.trim() ?: "Episode"
                        val epNum = Regex("الحلقة (\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        
                        episodes.add(
                            ParserInterface.ParsedEpisode(
                                url = epUrl,
                                name = epTitle,
                                season = seasonNum,
                                episode = epNum
                            )
                        )
                    }
                }
            } catch (e: Exception) {}
        }
        
        return if (episodes.isNotEmpty()) episodes else (data.episodes ?: emptyList())
    }

}
