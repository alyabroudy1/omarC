package com.arabseedv4

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class ArabseedV4Parser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/find/?word=$query"
    }
    
    override val mainPageConfig = MainPageConfig(
        container = "div.MovieBlock, div.item__contents, div.poster__single, div.BlockItem, div.series__box",
        title = CssSelector(query = "a.movie__block, h4, h3, div.title, div.title___", attr = "title, text"),
        url = CssSelector(query = "a.movie__block, a", attr = "href"),
        poster = CssSelector(query = "div.post__image img, img.imgOptimzer, div.Poster img, img", attr = "data-src, data-image, src")
    )

    override val searchConfig = mainPageConfig

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.title h1, h1.postTitle, div.h1-title h1", attr = "text"),
        poster = CssSelector(query = "div.posterDiv img, div.poster img, img.poster, div.single-poster img, div.postDiv a div img, div.postDiv img, .moviePoster img", attr = "data-src, src"),
        plot = CssSelector(
            query = "div.singleInfo span:contains(القصة) p, div.singleDesc p, div.story p, div.post__story p, div.postContent p, meta[property='og:description']", 
            attr = "text, content"
        ),
        year = CssSelector(query = "div.singleInfo span:contains(السنة) a, div.info__area li:has(span:contains(سنة العرض)) ul.tags__list a", attr = "text", regex = "(\\d{4})"),
        tags = CssSelector(query = "div.singleInfo span:contains(النوع) a, div.info__area li:has(span:contains(نوع العرض)) ul.tags__list a", attr = "text"),
        seriesIndicator = CssSelector(query = "div.post__category, span.category", attr = "text") // Logic check needed in isSeries
    )

    override val episodeConfig = EpisodeConfig(
        container = "ul.episodes__list li a, div.epAll a, div.episodes-list a, ul.episodes li a, a.episode__item",
        title = CssSelector(query = "this", attr = "text"),
        url = CssSelector(query = "this", attr = "href"),
        episode = CssSelector(query = "div.epi__num b, this", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "ul.watch__servers li[data-link], li[data-link]", attr = "data-link"),
        title = CssSelector(query = "span, this", attr = "text"),
        iframe = CssSelector(query = "iframe[src]", attr = "src")
    )
    
    // Series Detection Logic override to match V2
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // 1. Explicit Movie Checks (Fail-fast)
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false

        // 2. Element Context Checks
        if (element != null) {
            // Check category text
             val category = element.select("div.post__category, span.category").text()
             if (category.contains("مسلسلات") || category.contains("أنمي")) return true
             
             if (element is Document) {
                 // Check for seasons/episodes containers
                 if (element.select("div.epAll, div.episodes-list, ul.episodes, div.seasonDiv, div.series-episodes").isNotEmpty()) return true
             }
        }
        
        // 3. Keyword check
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("/seasons/") || url.contains("/series/") || url.contains("/selary") || url.contains("/anime/")) return true
        
        return false
    }

    // ================= HELPERS FOR ARABSEED LOGIC =================

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (!url.startsWith("http")) return "https://$url"
        return url
    }

    fun extractCsrfToken(doc: Document): String? {
        val html = doc.html()
        return Regex("""["']?csrf__token["']?\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""var\s+csrf_token\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
    }

    fun extractPostId(doc: Document): String? {
        var postId = doc.select("input[name='post_id'], input#post_id").attr("value")
        if (postId.isBlank()) {
            val shortlink = doc.select("link[rel='shortlink']").attr("href")
            postId = Regex("""\?p=(\d+)""").find(shortlink)?.groupValues?.get(1) ?: ""
        }
        if (postId.isBlank()) {
             postId = Regex("""(?:var|let|const)\s+post_id\s*=\s*['"]?(\d+)['"]?""").find(doc.html())?.groupValues?.get(1) ?: ""
        }
        return postId.ifBlank { null }
    }

    data class SeasonData(val season: Int, val postId: String)

    fun parseSeasonsWithPostId(doc: Document): List<SeasonData> {
        val list = mutableListOf<SeasonData>()
        doc.select("div.SeasonsListHolder ul > li").forEach { li ->
            val season = li.attr("data-season").toIntOrNull()
            val postId = li.attr("data-id")
            if (season != null && postId.isNotBlank()) list.add(SeasonData(season, postId))
        }
        return list.distinctBy { it.season }
    }

    fun parseEpisodesFromAjax(html: String, seasonNum: Int): List<ParserInterface.ParsedEpisode> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select("li a").mapNotNull { ep ->
            val epUrl = ep.attr("href")
            val epName = ep.text().trim()
            if (epUrl.isBlank()) return@mapNotNull null
            
            val numFromB = ep.select("b").text().trim().toIntOrNull()
            val epNum = numFromB ?: Regex("""\d+""").find(epName.replace("الحلقة", ""))?.value?.toIntOrNull() ?: 0
            
            ParserInterface.ParsedEpisode(
                url = fixUrl(epUrl),
                name = epName,
                season = seasonNum,
                episode = epNum
            )
        }
    }

    // ================= LOAD LINKS HELPERS =================

    fun isWatchPage(doc: Document): Boolean {
        return doc.select("ul > li[data-link], ul > h3").isNotEmpty() || 
               doc.select("iframe[name=player_iframe]").isNotEmpty()
    }

    fun getWatchUrl(doc: Document): String {
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
        }

        // Fallback: onclick
        if (watchUrl.isBlank()) {
            val onClick = doc.select("ul.tabs-ul li.active").attr("onclick")
            watchUrl = Regex("""href\s*=\s*'([^']+)'""").find(onClick)?.groupValues?.get(1) ?: ""
        }
        
        return watchUrl
    }
    
    data class QualityData(val quality: Int, val title: String)
    
    fun extractQualities(doc: Document): List<QualityData> {
        val qualities = mutableListOf<QualityData>()
        doc.select("ul.qualities__list li").forEach { li ->
            val q = li.attr("data-quality").toIntOrNull() ?: 0
            val title = li.attr("data-title")
            if (q > 0) qualities.add(QualityData(q, title))
        }
        return qualities.sortedByDescending { it.quality }
    }
    
    fun extractDefaultQuality(doc: Document, availableQualities: List<QualityData>): Int {
        return doc.selectFirst("ul.qualities__list li.active")
            ?.attr("data-quality")?.toIntOrNull() 
            ?: availableQualities.lastOrNull()?.quality 
            ?: 480
    }

    data class ServerData(
        val postId: String, 
        val quality: Int, 
        val serverId: String, 
        val title: String,
        val dataLink: String = "" 
    )

    fun extractVisibleServers(doc: Document): List<ServerData> {
        val servers = mutableListOf<ServerData>()
        doc.select("li[data-server]").forEach { li ->
            val postId = li.attr("data-post")
            val serverId = li.attr("data-server")
            val quality = li.attr("data-qu").toIntOrNull() ?: 0
            val title = li.select("span").text()
            val dataLink = li.attr("data-link")
            
            if (serverId.isNotBlank() && quality > 0) {
                servers.add(ServerData(postId, quality, serverId, title, dataLink))
            }
        }
        return servers
    }

    fun extractDirectEmbeds(doc: Document): List<String> {
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
                } catch (e: Exception) { }
            }
            
            if (src.isNotBlank() && 
                !src.contains("facebook") && 
                !src.contains("twitter") && 
                !src.contains("instagram") && 
                !src.contains("google")
            ) src else null
        }
    }
    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null
        
        // Custom Fallback for Poster (Exact match to V2 logic)
        var finalPoster: String? = data.posterUrl
        if (finalPoster.isNullOrBlank()) {
            val contentImg = doc.select("img").firstOrNull { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                src.contains("/wp-content/uploads/") && src.length > 10
            }
            finalPoster = contentImg?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        }
        
        return if (finalPoster != data.posterUrl) {
            data.copy(posterUrl = fixUrl(finalPoster ?: ""))
        } else {
            data
        }
    }
}