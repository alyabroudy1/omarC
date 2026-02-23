package com.arabseedv4

import com.lagradost.cloudstream3.*
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import kotlinx.coroutines.*
import org.jsoup.nodes.Document

class ArabseedV4 : BaseProvider() {

    override val baseDomain get() = "arabseed.show"
    override val providerName get() = "ArabseedV4"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseed.json"

    override val mainPage = mainPageOf(
        "/movies-1/" to "أفلام",
        "/series-1/" to "مسلسلات"
    )

    override fun getParser(): NewBaseParser {
        return ArabseedV4Parser()
    }

    // ================= SEARCH (PARALLEL MOVIES + SERIES) =================

    override suspend fun search(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        mainUrl = "https://${httpService.currentDomain}"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        
        return coroutineScope {
            listOf("movies", "series").map { type ->
                async {
                    val url = "$mainUrl/find/?word=$encoded&type=$type"
                    val doc = httpService.getDocument(url)
                    if (doc != null) getParser().parseSearch(doc) else emptyList()
                }
            }.awaitAll().flatten().distinctBy { it.url }.map { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            }
        }
    }

    // ================= LOAD HOOKS (AJAX SEASONS) =================

    override fun getSeasonName(seasonNum: Int): String = "الموسم $seasonNum"

    override suspend fun fetchExtraEpisodes(
        doc: Document, url: String, data: com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
    ): List<com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode>()
        if (data.episodes != null) episodes.addAll(data.episodes!!)

        val parser = getParser() as ArabseedV4Parser
        val seasonDataList = parser.parseSeasonsWithPostId(doc)
        val csrfToken = parser.extractCsrfToken(doc)
        val currentSeason = episodes.firstOrNull()?.season ?: 1

        if (seasonDataList.isNotEmpty() && !csrfToken.isNullOrBlank()) {
            coroutineScope {
                val otherEpisodes = seasonDataList.map { (seasonNum, postId) ->
                    async {
                        try {
                            if (seasonNum == currentSeason) return@async emptyList<ParsedEpisode>()
                            
                            val payload = mapOf(
                                "season_id" to postId,
                                "csrf_token" to csrfToken
                            )
                            
                            val result = httpService.postDebug(
                                url = "/season__episodes/",
                                data = payload,
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                referer = url
                            )
                            
                            if (result.success && result.html != null) {
                                parser.parseEpisodesFromAjax(result.html, seasonNum)
                            } else {
                                emptyList<ParsedEpisode>()
                            }
                        } catch (e: Exception) {
                            emptyList<ParsedEpisode>()
                        }
                    }
                }.awaitAll().flatten()
                episodes.addAll(otherEpisodes)
            }
        }
        
        return episodes
    }

    // ================= RESOLVE SERVER URL (LAZY AJAX) =================

    override suspend fun resolveServerUrl(url: String, referer: String): String? {
        // Normal URLs pass through
        if (!url.startsWith("arabseed-lazy://")) return url
        
        // Parse encoded lazy URL
        val query = url.substringAfter("?")
        val params = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else it to ""
        }
        
        val postId = params["post_id"] ?: return null
        val quality = params["quality"] ?: return null
        val serverId = params["server"] ?: return null
        val csrfToken = params["csrf"] ?: return null
        val baseUrl = params["base"] ?: "https://asd.pics"
        
        val result = httpService.postDebug(
            "$baseUrl/get__watch__server/",
            data = mapOf(
                "post_id" to postId,
                "quality" to quality,
                "server" to serverId,
                "csrf_token" to csrfToken
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to baseUrl,
                "Referer" to "$baseUrl/"
            )
        )
        
        if (!result.success || result.html == null) return null
        
        val serverResponse = result.html
        var embedUrl = ""
        
        if (serverResponse.trim().startsWith("{")) {
            val serverMatch = Regex("\"server\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
            if (serverMatch != null) {
                embedUrl = serverMatch.groupValues[1].replace("\\/", "/")
            } else if (serverResponse.contains("\"html\"")) {
                val htmlMatch = Regex("\"html\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
                val escapedHtml = htmlMatch?.groupValues?.get(1)
                if (escapedHtml != null) {
                    val unescaped = escapedHtml.replace("\\/", "/").replace("\\\"", "\"")
                    embedUrl = org.jsoup.Jsoup.parse(unescaped).select("iframe").attr("src")
                }
            }
        } else {
            val serverDoc = org.jsoup.Jsoup.parse(serverResponse, "$baseUrl/")
            embedUrl = serverDoc.select("iframe").attr("src")
        }
        
        return if (embedUrl.isNotBlank()) embedUrl else null
    }
}