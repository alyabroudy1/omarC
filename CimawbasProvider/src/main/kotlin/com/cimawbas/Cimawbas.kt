package com.cimawbas

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Cimawbas : BaseProvider() {
    override val baseDomain get() = "vid.mycima.cc"
    override val providerName get() = "Cimawbas"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/cimawbas.json"
    override val paginationFormat get() = "?&page=%d"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/movies.php" to "أفلام",
        "/all-series.php" to "مسلسلات",
    )

    override fun getParser(): NewBaseParser = CimawbasParser()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val methodTag = "[$name] [getMainPage]"
        Log.i(methodTag, "START page=$page, data=${request.data}, name=${request.name}")
        try {
            httpService.ensureInitialized()
            val items = mutableListOf<HomePageList>()
            val urlPath = request.data
            val sectionName = request.name
            val fullUrl = if (urlPath.startsWith("http")) urlPath else "$mainUrl$urlPath"
            val fmt = paginationFormat
            val pageUrl = if (page > 1 && fmt != null) "${fullUrl}${fmt.format(page)}" else fullUrl
            val doc = httpService.getDocument(pageUrl, checkDomainChange = true, rewriteDomain = true)
            if (doc != null) {
                val parsedItems = getParser().parseMainPage(doc)
                if (parsedItems.isNotEmpty()) {
                    val searchResponses = parsedItems.map { item ->
                        val type = if (item.isMovie) TvType.Movie else TvType.TvSeries
                        newMovieSearchResponse(item.title, item.url, type) {
                            this.posterUrl = item.posterUrl
                            this.posterHeaders = httpService.getImageHeadersFull()
                        }
                    }
                    items.add(HomePageList(sectionName, searchResponses))
                }
            }
            return newHomePageResponse(items)
        } catch (e: Exception) {
            Log.e(methodTag, "Error: ${e.message}")
            return null
        }
    }

    override suspend fun searchNormal(query: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val methodTag = "[$name] [searchNormal]"
        try {
            httpService.ensureInitialized()
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = getParser().getSearchUrl(mainUrl, encoded)
            val doc = httpService.getDocument(url, checkDomainChange = true, rewriteDomain = true) ?: return emptyList()
            val items = getParser().parseSearch(doc)
            return items.map { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeadersFull()
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun searchLazy(query: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val methodTag = "[$name] [searchLazy]"
        Log.i(methodTag, "START query='$query'")
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = getParser().getSearchUrl(mainUrl, encoded)
        val doc = httpService.getDocumentNoFallback(url, checkDomainChange = true, rewriteDomain = true) ?: return emptyList()
        val items = getParser().parseSearch(doc)
        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeadersFull()
            }
        }
    }

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val methodTag = "[$name] [load]"
        Log.i(methodTag, "START url='$url'")
        try {
            httpService.ensureInitialized()
            val doc = httpService.getDocument(url, rewriteDomain = true) ?: return null
            var actualDoc = doc
            var actualUrl = url
            val metaResolved = resolveMetaRefresh(actualDoc, actualUrl, methodTag)
            actualDoc = metaResolved.first
            actualUrl = metaResolved.second
            var data = getParser().parseLoadPageData(actualDoc, actualUrl) ?: return null
            data = resolveParentSeries(data, methodTag)
            val allEpisodes = fetchExtraEpisodes(actualDoc, actualUrl, data)
                .distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            val finalType = if (data.type == TvType.TvSeries && allEpisodes.isEmpty()) {
                TvType.Movie
            } else {
                data.type
            }
            return if (finalType == TvType.Movie) {
                val movieDataUrl = data.watchUrl ?: data.url
                newMovieLoadResponse(data.title, data.url, TvType.Movie, movieDataUrl) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeadersFull()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                }
            } else {
                val episodeList = allEpisodes.map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                    }
                }

                val seasonNames = episodeList.mapNotNull { it.season }.distinct().sorted()
                    .map { SeasonData(it, getSeasonName(it)) }

                newTvSeriesLoadResponse(data.title, data.url, TvType.TvSeries, episodeList) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeadersFull()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                    if (seasonNames.isNotEmpty()) this.seasonNames = seasonNames
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedRegex = Regex("""src=['"]([^'"]+)['"]""")
        val vidRegex = Regex("""[?&]vid=([^&]+)""")

        suspend fun extractServers(doc: org.jsoup.nodes.Document): Boolean {
            var found = false
            val selectors = listOf(
                "ul.list_servers.list_embedded li[data-embed]",
                "ul.WatchServersList li[data-watch]",
                "ul#watch li[data-watch]",
                "ul.list_servers li[data-embed]",
                ".WatchServersList li",
                ".Links--Content ul li",
                ".serverList li"
            )
            for (selector in selectors) {
                val servers = doc.select(selector)
                if (servers.isEmpty()) continue
                for (server in servers) {
                    val embedRaw = server.attr("data-embed")
                        .ifEmpty { server.attr("data-watch") }
                        .ifEmpty { server.attr("data-url") }
                    val embedUrl = if (!embedRaw.isNullOrBlank()) {
                        val m = embedRegex.find(embedRaw)
                        if (m != null) m.groupValues[1] else embedRaw
                    } else {
                        server.selectFirst("a")?.attr("href") ?: ""
                    }
                    if (!embedUrl.isNullOrBlank()) {
                        found = true
                        loadExtractor(embedUrl, subtitleCallback, callback)
                    }
                }
                if (found) return true
            }
            return false
        }

        val doc = httpService.getDocument(data, rewriteDomain = true) ?: return false
        if (extractServers(doc)) return true

        val playLink = doc.selectFirst("a[href*='play.php'], a.play-video, a.btn-watch")
        if (playLink != null) {
            val href = playLink.attr("href")
            if (!href.isNullOrBlank()) {
                val playDoc = httpService.getDocument(href, rewriteDomain = true)
                if (playDoc != null && extractServers(playDoc)) return true
            }
        }

        val vidMatch = vidRegex.find(data)
        if (vidMatch != null) {
            val vid = vidMatch.groupValues[1]
            val playUrl = "https://${baseDomain}/play.php?vid=$vid"
            val playDoc = httpService.getDocument(playUrl, rewriteDomain = true)
            if (playDoc != null && extractServers(playDoc)) return true
        }

        return false
    }
}
