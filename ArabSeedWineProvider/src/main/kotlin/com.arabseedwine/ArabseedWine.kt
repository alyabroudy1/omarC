package com.arabseedwine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.cloudstream.shared.extractors.MegamaxDownloader
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import kotlinx.coroutines.*
import org.jsoup.nodes.Document

class ArabseedWine : BaseProvider() {

    override var name = "عرب سيد Wine"
    override val baseDomain get() = "arabseed.wine"
    override val providerName get() = "ArabseedWine"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseedwine.json"
    override val paginationFormat get() = "page/%d/"

    /**
     * 60 s, because this origin really is that slow: measured **~40 s to load in a desktop browser**
     * (2026-07-30). Against CloudStream's ~10 s client default every request died before the site
     * answered — the watch-page GET, and all five `get__watch__server/` POSTs, which surfaced as
     * "No Links Found" ten seconds after pressing play.
     *
     * Deliberately set here and not in the shared default: the cost of a high ceiling is that genuine
     * failures take a minute to report, and no other provider should pay that for this site.
     */
    override val requestTimeoutMs get() = 60_000L

    override val mainPage = mainPageOf(
        "/category/films/" to "مضاف حديثا",
        "/category/films/foreign-movies/" to "أفلام أجنبية",
        "/category/films/turkish-movies/" to "أفلام تركية",
        "/category/tv/" to "المسلسلات",
        "/category/anime/anime-movies/" to "افلام انيميشن"
    )

    override fun getParser(): NewBaseParser {
        return ArabseedWineParser()
    }

    // ================= SEARCH (PARALLEL MOVIES + SERIES) =================

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        
        return coroutineScope {
            listOf("movies", "series").map { type ->
                async {
                    val url = "$mainUrl/?s=$encoded&type=$type"
                    android.util.Log.d("ArabseedWine", "searchLazy: requesting $url")
                    try {
                        val doc = httpService.getDocumentNoFallback(url, rewriteDomain = true)
                        if (doc != null) getParser().parseSearch(doc) else emptyList()
                    } catch (e: Exception) {
                        android.util.Log.e("ArabseedWine", "searchLazy: throwing $e")
                        throw e
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url }.map { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            }
        }
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        android.util.Log.d("ArabseedWine", "searchNormal: Executing parallel search for movies and series")
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        
        return coroutineScope {
            listOf("movies", "series").map { type ->
                async {
                    val url = "$mainUrl/find/?word=$encoded&type=$type"
                    val doc = httpService.getDocument(url, rewriteDomain = true)
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

        val parser = getParser() as ArabseedWineParser
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

    // ================= LOAD LINKS (WATCH + DOWNLOAD PATHS) =================

    /**
     * Watch path runs first via the standard BaseProvider flow (lazy AJAX +
     * direct embeds -> loadExtractor). The download path (/download/ page ->
     * MegaMax mirrors) runs ALONGSIDE it: every mirror feeds the standard
     * extractor system too. Either path delivering links counts as success.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[ArabseedWine] [loadLinks]"
        android.util.Log.i(methodTag, "START data='$data'")
        httpService.ensureInitialized()

        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        val countingCb: (ExtractorLink) -> Unit = { delivered.incrementAndGet(); callback(it) }

        // Watch path (standard flow).
        val watchOk = try {
            super.loadLinks(data, isCasting, subtitleCallback, countingCb)
        } catch (e: Exception) {
            android.util.Log.e(methodTag, "Watch path error: ${e.message}")
            false
        }
        android.util.Log.d(methodTag, "Watch path ok=$watchOk delivered=${delivered.get()}")

        // Download path (alongside).
        try {
            val base = data.trimEnd('/').removeSuffix("/watch").trimEnd('/')
            val dlPageUrl = "$base/download/"
            android.util.Log.d(methodTag, "Download page: $dlPageUrl")
            val dlDoc = httpService.getDocument(dlPageUrl, rewriteDomain = true)
            if (dlDoc != null) {
                val dlHrefs = dlDoc.select("ul.downloads__links__list a[href], a.download__item[href]")
                    .map { it.attr("abs:href").ifBlank { it.attr("href") }.trim() }
                    .filter { it.startsWith("http") && it.length > 10 }
                    .distinct()
                android.util.Log.d(methodTag, "Download targets: ${dlHrefs.size}")
                for (href in dlHrefs) {
                    try {
                        if (href.contains("/download/")) {
                            for ((mirrorUrl, label) in MegamaxDownloader.resolveMirrors(href, dlPageUrl)) {
                                try {
                                    android.util.Log.d(methodTag, "Download mirror [$label]: ${mirrorUrl.take(80)}")
                                    loadExtractor(mirrorUrl, href, subtitleCallback, countingCb)
                                } catch (e: Exception) {
                                    android.util.Log.d(methodTag, "Extractor failed: ${e.message}")
                                }
                            }
                        } else {
                            loadExtractor(href, dlPageUrl, subtitleCallback, countingCb)
                        }
                    } catch (e: Exception) {
                        android.util.Log.d(methodTag, "Download target failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(methodTag, "Download path error: ${e.message}")
        }

        android.util.Log.i(methodTag, "END delivered=${delivered.get()}")
        return delivered.get() > 0
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