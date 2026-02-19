package com.arabseed

import com.lagradost.api.Log
import com.lagradost.cloudstream3.TvType
import com.cloudstream.shared.extractors.LinkResolvers
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.parsing.ParserInterface.ParsedItem
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode

/**
 * Arabseed specific parsing logic.
 * Keeps all selectors and extraction patterns in one place.
 * CLONED 1:1 FROM LEGACY PROVIDER.
 */
class ArabseedParser : BaseParser() {
    override val providerName = "ArabseedPlugin"
    override val mainUrl = "https://arabseed.show"

    override val isMovieSelector = "span.category:not(:contains(مسلسلات))"

    /**
     * Checks if the current document is a watch page (contains video player or quality list).
     */
    fun isWatchPage(doc: Document): Boolean {
        return doc.select("ul > li[data-link], ul > h3").isNotEmpty() || 
               doc.select("iframe[name=player_iframe]").isNotEmpty()
    }

    /**
     * Extracts the watch URL from a movie details page.
     * @return URL string or empty string if not found.
     */
    fun getWatchUrl(doc: Document): String {
        return extractMovieWatchUrl(doc)
    }

    /**
     * Identifies the default quality from the quality list or falls back to the highest available.
     * @return Quality value (e.g. 1080, 720) or 480 default.
     */
    fun extractDefaultQuality(doc: Document, availableQualities: List<QualityData>): Int {
        return doc.selectFirst("ul.qualities__list li.active")
            ?.attr("data-quality")?.toIntOrNull() 
            ?: availableQualities.lastOrNull()?.quality 
            ?: 480
    }

    // ================= MAIN PAGE & SEARCH =================
    
    // Exact selectors from built-in ArabseedParser.kt:56
    private val mainPageItemSelectors = listOf(
        "div.item__contents",
        "div.MovieBlock",
        "div.poster__single",
        "ul.Blocks-UL > div",
        "div.Blocks-UL > div",
        "div.BlockItem",
        "div.series__box",
        "div.search__res__container > div"
    )

    override fun parseMainPage(doc: Document): List<ParsedItem> {
        return parseValidItems(doc)
    }

    override fun parseSearch(doc: Document): List<ParsedItem> {
        return parseValidItems(doc)
    }

    private fun parseValidItems(doc: Document): List<ParsedItem> {
        val unitedSelector = mainPageItemSelectors.joinToString(", ")
        return doc.select(unitedSelector).mapNotNull { el ->
            // TITLE SELECTORS
            // a.movie__block (attr title), h4, h3, div.title, div.title___, a (attr title)
            val title = el.select("a.movie__block").attr("title")
                .ifEmpty { el.select("h4").text() }
                .ifEmpty { el.select("h3").text() }
                .ifEmpty { el.select("div.title").text() }
                .ifEmpty { el.select("div.title___").text() }
                .ifEmpty { el.selectFirst("a")?.attr("title") ?: "" }
            
            if (title.isBlank()) return@mapNotNull null

            // URL SELECTORS
            // a.movie__block, a[href], href attr
            val url = el.select("a.movie__block").attr("href")
                .ifBlank { el.select("a[href]").attr("href") }
                .ifBlank { el.attr("href") }
            
            if (url.isBlank()) return@mapNotNull null

            // POSTER SELECTORS
            // div.post__image img, img.imgOptimzer, div.Poster img, div.image__poster img, img
            val img = el.selectFirst("div.post__image img")
                ?: el.selectFirst("img.imgOptimzer")
                ?: el.selectFirst("div.Poster img")
                ?: el.selectFirst("div.image__poster img")
                ?: el.selectFirst("img")

            val poster = img?.let { 
                it.attr("data-src")
                    .ifBlank { it.attr("data-image") }
                    .ifBlank { it.attr("src") }
            } ?: ""

            ParsedItem(
                title = title,
                url = fixUrl(url),
                posterUrl = fixUrl(poster),
                isMovie = isMovie(el)
            )
        }
    }
    
    // Explicitly implementing isMovie just for safety although BaseParser has it, to ensure correct selector usage
    override fun isMovie(element: Element): Boolean {
        // New logic: check category text
        val category = element.select("div.post__category").text()
        if (category.isNotBlank()) {
             return !category.contains("مسلسلات") && !category.contains("أنمي")
        }
        
        // Fallback
        val oldCategory = element.select("span.category").text()
        return !oldCategory.contains("مسلسلات")
    }

    // ================= LOAD PAGE (DETAILS) =================

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        return parseLoadPageData(doc, url)
    }
    
    override fun parseLoadPageData(doc: Document, url: String): ParsedLoadData? {
        Log.d(providerName, "[parseLoadPageData] Parsing: ${url.take(60)}...")
        
        // TITLE SELECTORS
        var title = doc.selectFirst("div.title h1")?.text() 
            ?: doc.selectFirst("h1.postTitle")?.text()
            ?: doc.selectFirst("div.h1-title h1")?.text()
        
        if (title.isNullOrBlank()) {
             title = doc.title().replace(" - عرب سيد", "").replace("مترجم اون لاين", "").trim()
        }
        
        if (title.isNullOrBlank()) {
            Log.e(providerName, "[parseLoadPageData] Failed to parse title!")
            return null
        }

        // POSTER SELECTORS
        val posterImg = doc.selectFirst("div.posterDiv img")
            ?: doc.selectFirst("div.poster img")
            ?: doc.selectFirst("img.poster")
            ?: doc.selectFirst("div.single-poster img")
            ?: doc.selectFirst("div.postDiv a div img")
            ?: doc.selectFirst("div.postDiv img")
            ?: doc.selectFirst(".moviePoster img")
            
        var poster = posterImg?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        
        // Fallback Poster: wp-content
        if (poster.isBlank()) {
            val contentImg = doc.select("img").firstOrNull { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                src.contains("/wp-content/uploads/") && src.length > 10
            }
            poster = contentImg?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        }

        // PLOT SELECTORS
        val plot = doc.selectFirst("div.singleInfo span:contains(القصة) p")?.text()
            ?: doc.selectFirst("div.singleDesc p")?.text()
            ?: doc.selectFirst("div.story p")?.text()
            ?: doc.selectFirst("div.post__story p")?.text()
            ?: doc.selectFirst("div.postContent p")?.text()
            ?: doc.select("meta[property='og:description']").attr("content")
            ?: ""

        // YEAR SELECTORS
        var year = doc.select("div.singleInfo span:contains(السنة) a").text().toIntOrNull()
        if (year == null) year = doc.select("div.info__area li:has(span:contains(سنة العرض)) ul.tags__list a").text().toIntOrNull()
        if (year == null) year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()
        
        // TYPE DETECTION (matches built-in logic)
        val hasEpisodes = doc.select("div.epAll, div.episodes-list, ul.episodes, div.seasonDiv, div.seasonEpsCont, div.seasons--list, a.episode__item, div.series-episodes, div#seasons__list, div.list__sub__cats, div.epi__num, ul.episodes__list").isNotEmpty()
        val seriesKeywords = listOf("انمي", "مسلسل", "موسم", "برنامج", "سلسلة")
        val isSeriesTitle = seriesKeywords.any { title.contains(it, ignoreCase = true) }
        val isSeriesUrl = url.contains("/seasons/") || url.contains("/series/") || url.contains("/selary") || url.contains("/anime/")
        
        val isMovie = !hasEpisodes && !isSeriesUrl && !isSeriesTitle
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        // TAGS
        var tags = doc.select("div.singleInfo span:contains(النوع) a").map { it.text() }
        if (tags.isEmpty()) tags = doc.select("div.info__area li:has(span:contains(نوع العرض)) ul.tags__list a").map { it.text() }
        
        // CSRF Token (for AJAX)
        val csrfToken = extractCsrfToken(doc)
        
        Log.d(providerName, "[parseLoadPageData] title='$title', isMovie=$isMovie, hasEpisodes=$hasEpisodes, csrfToken=${if(csrfToken!=null) "FOUND" else "NULL"}")
        
        return if (isMovie) {
            // MOVIE: Extract watch URL
            val watchUrl = extractMovieWatchUrl(doc)
            Log.d(providerName, "[parseLoadPageData] Movie. watchUrl='${watchUrl.take(60)}'")
            
            ParsedLoadData(
                title = title,
                url = url,
                posterUrl = fixUrl(poster),
                plot = plot,
                year = year,
                type = type,
                tags = tags,
                watchUrl = watchUrl.ifBlank { null },
                episodes = null,
                csrfToken = csrfToken
            )
        } else {
            // SERIES: Parse episodes
            val episodes = parseEpisodes(doc, null)
            Log.d(providerName, "[parseLoadPageData] Series. episodes=${episodes.size}")
            
            ParsedLoadData(
                title = title,
                url = url,
                posterUrl = fixUrl(poster),
                plot = plot,
                year = year,
                type = type,
                tags = tags,
                watchUrl = null,
                episodes = episodes,
                csrfToken = csrfToken
            )
        }
    }
    
    /** Extract movie watch URL from various sources */
    private fun extractMovieWatchUrl(doc: Document): String {
        // Try iframe with specific name
        var watchUrl = doc.select("iframe[name=player_iframe]").attr("src")
        
        // Try any iframe that might be the player
        if (watchUrl.isBlank()) {
             watchUrl = doc.select("div.Container iframe, div.Content iframe, div.embed-player iframe")
                 .attr("src")
        }

        // Fallback: Watch Button (critical for loadLinks)
        if (watchUrl.isBlank()) {
            watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) Log.d(providerName, "Found watch button URL: $watchUrl")
        }

        // Fallback: onclick
        if (watchUrl.isBlank()) {
            val onClick = doc.select("ul.tabs-ul li.active").attr("onclick")
            watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
        }
        
        // Fallback: any list item
        if (watchUrl.isBlank()) {
            doc.select("ul.tabs-ul li").forEach { li ->
                if (watchUrl.isBlank()) {
                    val onClick = li.attr("onclick")
                    watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
                }
            }
        }
        
        return watchUrl
    }

    // ================= EPISODES & SEASONS =================

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        val episodes = mutableListOf<ParsedEpisode>()
        
        // DETECT SEASON
        var activeSeasonNum = seasonNum ?: 1
        if (seasonNum == null) {
            // Try explicit selection
            val seasonList = doc.selectFirst("div#seasons__list, div.list__sub__cats")
            if (seasonList != null) {
                 val selected = seasonList.selectFirst("li.selected")
                 if (selected != null) activeSeasonNum = parseSeasonNumber(selected.text())
            } else {
                 val seasonTabs = doc.select("div.seasonDiv, div.seasons--list")
                 val activeTab = seasonTabs.find { it.hasClass("active") }
                 if (activeTab != null) {
                     activeSeasonNum = Regex("""\d+""").find(activeTab.text())?.value?.toIntOrNull() ?: 1
                 } else {
                     val t = doc.select("div.seasonDiv.active .title").text()
                     activeSeasonNum = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 1
                 }
            }
        }
        
        // EPISODE SELECTORS
        // ul.episodes__list li a, a:has(div.epi__num)
        // div.epAll a, div.episodes-list a, ul.episodes li a, a.episode__item, div.series-episodes a
        var epElements = doc.select("ul.episodes__list li a, a:has(div.epi__num)")
        if (epElements.isEmpty()) {
             epElements = doc.select("div.epAll a, div.episodes-list a, ul.episodes li a, a.episode__item, div.series-episodes a")
        }
        
        epElements.forEach { ep ->
            val epUrl = ep.attr("href")
            val epName = ep.text()
            
            // Ep Num Extraction
            val numText = ep.select("div.epi__num b").text()
            val epNum = if (numText.isNotEmpty()) {
                numText.trim().toIntOrNull() ?: 0
            } else {
                 Regex("""\d+""").find(epName.replace("الحلقة", ""))?.value?.toIntOrNull() 
                 ?: Regex("""\d+""").find(epUrl)?.value?.toIntOrNull() 
                 ?: 0
            }
            
            if (epUrl.isNotBlank()) {
                episodes.add(ParsedEpisode(
                    url = fixUrl(epUrl),
                    name = epName,
                    season = activeSeasonNum,
                    episode = epNum
                ))
            }
        }
        return episodes.distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }
    
    private fun parseSeasonNumber(text: String): Int {
        if (text.contains("الأول")) return 1
        if (text.contains("الثاني")) return 2
        if (text.contains("الثالث")) return 3
        if (text.contains("الرابع")) return 4
        if (text.contains("الخامس")) return 5
        if (text.contains("السادس")) return 6
        if (text.contains("السابع")) return 7
        if (text.contains("الثامن")) return 8
        if (text.contains("التاسع")) return 9
        if (text.contains("العاشر")) return 10
        return Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: 1
    }

    // ================= HELPERS (CSRF, ETC) =================
    
    fun extractCsrfToken(doc: Document): String? {
        val html = doc.html()
        // Regex for 'csrf__token': "..." which is common in their JS
        // "csrf__token":"..." or 'csrf__token':'...'
        return Regex("""["']?csrf__token["']?\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""var\s+csrf_token\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
    }
    
    fun extractPostId(doc: Document): String? {
        // 1. input name='post_id', input#post_id
        var postId = doc.select("input[name='post_id'], input#post_id").attr("value")
        
        // 2. link rel='shortlink' href='...?p=123'
        if (postId.isBlank()) {
            val shortlink = doc.select("link[rel='shortlink']").attr("href")
            postId = Regex("""\?p=(\d+)""").find(shortlink)?.groupValues?.get(1) ?: ""
        }
        
        // 3. var post_id = ...
        if (postId.isBlank()) {
             postId = Regex("""(?:var|let|const)\s+post_id\s*=\s*['"]?(\d+)['"]?""").find(doc.html())?.groupValues?.get(1) ?: ""
        }
        
        // 4. div#report-video input, data-post-id, data-id
        if (postId.isBlank()) postId = doc.select("div#report-video input[name='post_id']").attr("value")
        if (postId.isBlank()) postId = doc.select("*[data-post-id]").attr("data-post-id")
        if (postId.isBlank()) postId = doc.select("*[data-id]").firstOrNull { it.attr("data-id").matches(Regex("""\d+""")) }?.attr("data-id") ?: ""
        
        // 5. Body class postid-123
        if (postId.isBlank()) {
            postId = Regex("""postid-(\d+)""").find(doc.body().className())?.groupValues?.get(1) ?: ""
        }
        
        // 6. Try parsing from ajax calls in script
        if (postId.isBlank()) {
            postId = Regex("""post_id\s*:\s*(\d+)""").find(doc.html())?.groupValues?.get(1) ?: ""
            if (postId.isBlank()) {
                 postId = Regex("""post_id["']\s*:\s*["']?(\d+)""").find(doc.html())?.groupValues?.get(1) ?: ""
            }
        }

        return postId.ifBlank { null }
    }

    override fun extractWatchServersUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        
        // Embeds
        val embeds = LinkResolvers.extractJwPlayerSources(doc.html())
        if (embeds.isNotEmpty()) urls.addAll(embeds)
        
        // Iframes
        // iframe[name=player_iframe]
        // div.Container iframe, div.Content iframe, div.embed-player iframe
        var iframeSrc = doc.select("iframe[name=player_iframe]").attr("src")
        if (iframeSrc.isBlank()) iframeSrc = doc.select("div.Container iframe, div.Content iframe, div.embed-player iframe").attr("src")
        if (iframeSrc.isNotBlank()) urls.add(iframeSrc)
        
        // Watch Button
        val watchBtn = doc.select("a.watch__btn").attr("href")
        if (watchBtn.isNotBlank()) urls.add(watchBtn)
        
        // OnClick Extraction (Tabs)
        // ul.tabs-ul li.active -> onclick -> href='...'
        // ul.tabs-ul li -> onclick
        val tabs = doc.select("ul.tabs-ul li")
        tabs.forEach { li ->
            val onclick = li.attr("onclick")
            val match = Regex("""href\s*=\s*['"]([^'"]+)['"]""").find(onclick)
            if (match != null) {
                urls.add(match.groupValues[1])
            }
        }

        return urls.distinct()
    }
    
    override fun resolveServerLink(serverUrl: String): String? {
        return LinkResolvers.extractUrlParam(serverUrl) ?: serverUrl
    }
    
    // ==================== AJAX PARSING ====================

    fun parseEpisodesFromAjax(json: String, seasonNum: Int): List<ParsedEpisode> {
        var htmlContent = ""
        try {
            val jsonObject = JSONObject(json)
            htmlContent = jsonObject.getString("html")
        } catch (e: Exception) { htmlContent = json }
        
        val doc = Jsoup.parse(htmlContent)
        
        return doc.select("li a").mapNotNull { ep ->
            val epUrl = ep.attr("href")
            val epName = ep.text().trim()
            if (epUrl.isBlank()) return@mapNotNull null
            
            val numFromB = ep.select("b").text().trim().toIntOrNull()
            val epNum = numFromB ?: Regex("""\d+""").find(epName.replace("الحلقة", ""))?.value?.toIntOrNull() ?: 0
            
            ParsedEpisode(
                url = fixUrl(epUrl),
                name = epName,
                season = seasonNum,
                episode = epNum
            )
        }
    }

    fun parseQualityListFromAjax(json: String): List<String> {
        var htmlContent = ""
        try {
            val jsonObject = JSONObject(json)
            if (jsonObject.has("html")) htmlContent = jsonObject.getString("html")
        } catch (e: Exception) { htmlContent = json }
        
        if (htmlContent.isBlank()) return emptyList()
        return extractWatchServersUrls(Jsoup.parse(htmlContent))
    }

    fun parseServerListFromAjax(json: String): List<ServerData> {
        var htmlContent = ""
        try {
            val jsonObject = JSONObject(json)
            if (jsonObject.has("html")) htmlContent = jsonObject.getString("html")
        } catch (e: Exception) { htmlContent = json }
        
        if (htmlContent.isBlank()) return emptyList()
        return extractVisibleServers(Jsoup.parse(htmlContent))
    }
    
    data class QualityData(val quality: Int, val title: String)
    
    fun extractQualities(doc: Document): List<QualityData> {
        val qualities = mutableListOf<QualityData>()
        // ul.qualities__list li
        doc.select("ul.qualities__list li").forEach { li ->
            val q = li.attr("data-quality").toIntOrNull() ?: 0
            val title = li.attr("data-title")
            if (q > 0) qualities.add(QualityData(q, title))
        }
        return qualities.sortedByDescending { it.quality }
    }

    data class ServerData(
        val postId: String, 
        val quality: Int, 
        val serverId: String, 
        val title: String,
        val dataLink: String = ""  // Fallback direct URL from data-link attribute
    )

    fun extractVisibleServers(doc: Document): List<ServerData> {
        val servers = mutableListOf<ServerData>()
        // li[data-server]
        doc.select("li[data-server]").forEach { li ->
            val postId = li.attr("data-post")
            val serverId = li.attr("data-server")
            val quality = li.attr("data-qu").toIntOrNull() ?: 0
            val title = li.select("span").text()
            val dataLink = li.attr("data-link")  // Direct link fallback
            
            if (serverId.isNotBlank() && quality > 0) {
                servers.add(ServerData(postId, quality, serverId, title, dataLink))
            }
        }
        return servers
    }

    data class SeasonData(val season: Int, val postId: String)

    fun parseSeasonsWithPostId(doc: Document): List<SeasonData> {
        val list = mutableListOf<SeasonData>()
        
        // div.SeasonsListHolder ul > li
        doc.select("div.SeasonsListHolder ul > li").forEach { li ->
            val season = li.attr("data-season").toIntOrNull()
            val postId = li.attr("data-id")
            if (season != null && postId.isNotBlank()) list.add(SeasonData(season, postId))
        }
        
        // div#seasons__list ul li, div.list__sub__cats ul li
        if (list.isEmpty()) {
            doc.select("div#seasons__list ul li, div.list__sub__cats ul li").forEach { li ->
                val termId = li.attr("data-term")
                if (termId.isNotBlank()) {
                    val sNum = parseSeasonNumber(li.text())
                    list.add(SeasonData(sNum, termId))
                }
            }
        }
        return list.distinctBy { it.season }
    }

    fun extractDirectEmbeds(doc: Document): List<String> {
        // Broaden selector to find ANY video iframe, filtering out known ads/socials
        return doc.select("iframe[src]").mapNotNull { 
            var src = fixUrl(it.attr("src"))
            if (src.isBlank()) return@mapNotNull null
            
            // Handle /play.php?url=BASE64
            if (src.contains("url=")) {
                val param = src.substringAfter("url=").substringBefore("&")
                try {
                    val decoded = String(android.util.Base64.decode(param, android.util.Base64.DEFAULT))
                    if (decoded.startsWith("http")) {
                        src = decoded
                    }
                } catch (e: Exception) {
                    // Failed to decode, keep original
                }
            }
            
            if (src.isNotBlank() && 
                !src.contains("facebook") && 
                !src.contains("twitter") && 
                !src.contains("instagram") && 
                !src.contains("google")
            ) src else null
        }
    }

}
