package com.arabseedv4

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.cloudstream.shared.extractors.MegamaxDownloader
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import kotlinx.coroutines.*
import org.jsoup.nodes.Document

class ArabseedV4 : BaseProvider() {

    override var name = "عرب سيد"
    override val baseDomain get() = "arabseed.trade"
    override val providerName get() = "ArabseedV4"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseedv4.json"
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
        "/%D8%A7%D9%81%D9%84%D8%A7%D9%85/" to "الافلام",
        "/recently/" to "مضاف حديثا",
        "/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/" to "المسلسلات",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "افلام عربي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "افلام انمي"
    )

    override fun getParser(): NewBaseParser {
        return ArabseedV4Parser()
    }

    // ================= SEARCH (PARALLEL MOVIES + SERIES) =================
    // Uses the site's own ?s=<query>&type= endpoint: proven to return real
    // per-query results. (The /find/?word= endpoint ignores the word and the
    // base ?s= fallback returned the homepage — both showed the same items
    // for every query.)

    private suspend fun searchSite(query: String, page: Int, withFallback: Boolean): SearchResponseList {
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        val pageData = coroutineScope {
            listOf("movies", "series").map { type ->
                async {
                    // WordPress puts the page segment before the query string.
                    val base = if (page > 1) "$mainUrl/page/$page/" else mainUrl
                    val url = "$base/?s=$encoded&type=$type"
                    android.util.Log.d("ArabseedV4", "searchSite: requesting $url")
                    try {
                        val doc = if (withFallback) {
                            httpService.getDocument(url, rewriteDomain = true)
                        } else {
                            httpService.getDocumentNoFallback(url, rewriteDomain = true)
                        }
                        if (doc != null) {
                            getParser().parseSearch(doc) to
                                (doc.selectFirst("link[rel=next], a.next.page-numbers, a.next") != null)
                        } else {
                            emptyList<com.cloudstream.shared.parsing.ParserInterface.ParsedItem>() to false
                        }
                    } catch (e: com.cloudstream.shared.service.CloudflareBlockedSearchException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("ArabseedV4", "searchSite error: ${e.message}")
                        emptyList<com.cloudstream.shared.parsing.ParserInterface.ParsedItem>() to false
                    }
                }
            }.awaitAll()
        }
        val items = pageData.flatMap { it.first }.distinctBy { it.url }
        android.util.Log.d("ArabseedV4", "searchSite: '${query}' -> ${items.size} items")
        val results = items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
        return newSearchResponseList(results, pageData.any { it.second })
    }

    override suspend fun searchLazy(query: String, page: Int): SearchResponseList {
        return searchSite(query, page, withFallback = false)
    }

    override suspend fun searchNormal(query: String, page: Int): SearchResponseList {
        return searchSite(query, page, withFallback = true)
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

    // ================= LOAD LINKS (WATCH + DOWNLOAD PATHS) =================

    /**
     * Download path runs FIRST: it is fast plain HTTP with working mirrors,
     * while the watch path can burn the whole loadLinks time budget inside
     * its sniffer WebView (leaving the download path starved — observed as a
     * single WebView then failure). Either path delivering links counts.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[ArabseedV4] [loadLinks]"
        android.util.Log.i(methodTag, "START data='$data'")
        httpService.ensureInitialized()

        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        val countingCb: (ExtractorLink) -> Unit = { delivered.incrementAndGet(); callback(it) }

        // Download path first (fast).
        // Mirrors fan out to extractors IN PARALLEL (same as the standard
        // Phase 2); the sequential one-by-one sniffer stays inside the
        // shared system, never here.
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
                val jobs = mutableListOf<Pair<String, String>>()
                for (href in dlHrefs) {
                    try {
                        if (href.contains("/download/")) {
                            for ((mirrorUrl, label) in MegamaxDownloader.resolveMirrors(href, dlPageUrl)) {
                                android.util.Log.d(methodTag, "Download mirror [$label]: ${mirrorUrl.take(80)}")
                                jobs.add(mirrorUrl to href)
                            }
                        } else {
                            jobs.add(href to dlPageUrl)
                        }
                    } catch (e: Exception) {
                        android.util.Log.d(methodTag, "Download target failed: ${e.message}")
                    }
                }
                coroutineScope {
                    jobs.distinctBy { it.first }.map { (mirrorUrl, ref) ->
                        async {
                            try {
                                loadExtractor(mirrorUrl, ref, subtitleCallback, countingCb)
                            } catch (e: Exception) {
                                android.util.Log.d(methodTag, "Extractor failed: ${e.message}")
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(methodTag, "Download path error: ${e.message}")
        }

        if (delivered.get() > 0) {
            android.util.Log.i(methodTag, "END delivered=${delivered.get()} via download path")
            return true
        }

        // Watch path fallback (standard flow; may open the sniffer WebView).
        val watchOk = try {
            super.loadLinks(data, isCasting, subtitleCallback, countingCb)
        } catch (e: Exception) {
            android.util.Log.e(methodTag, "Watch path error: ${e.message}")
            false
        }

        android.util.Log.i(methodTag, "END delivered=${delivered.get()} watchOk=$watchOk")
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