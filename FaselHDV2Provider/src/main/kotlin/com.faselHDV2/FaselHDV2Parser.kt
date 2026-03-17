package com.faselHDV2

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class FaselHDV2Parser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }
    
    // Selectors ported from old FaselHDv2.kt
    override val mainPageConfig = MainPageConfig(
        container = "div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]",
        title = CssSelector(query = "div.postDiv a div img", attr = "alt"),
        url = CssSelector(query = "div.postDiv a", attr = "href"),
        poster = CssSelector(query = "div.postDiv a div img", attr = "data-src, src")
    )

    override val searchConfig = mainPageConfig

    // Load page selectors ported from old FaselHDv2.kt
    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.title h1, h1.postTitle, div.h1-title h1", attr = "text"),
        poster = CssSelector(
            query = "div.posterDiv img, div.poster img, img.poster, div.single-poster img, div.postDiv a div img, div.postDiv img, .moviePoster img",
            attr = "data-src, src"
        ),
        plot = CssSelector(
            query = "div.singleInfo span:contains(القصة) p, div.singleDesc p, div.story p, div.post__story p, div.postContent p",
            attr = "text"
        ),
        year = CssSelector(
            query = "div.singleInfo span:contains(السنة) a",
            attr = "text",
            regex = "(\\d{4})"
        ),
        tags = CssSelector(
            query = "div.singleInfo span:contains(النوع) a",
            attr = "text"
        ),
        seriesIndicator = CssSelector(
            query = "div.seasonEpsCont, div.epAll",
            attr = "text"
        )
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.epAll a",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = "(\\d+)")
    )

    // Watch server selectors — supports both WatchList (new) and onclick (old) patterns
    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "ul.WatchList li[data-embed-url]", attr = "data-embed-url"),
        id = CssSelector(query = "ul.WatchList li[data-embed-url]", attr = "data-embed-id"),
        title = CssSelector(query = "ul.WatchList li[data-embed-url] strong", attr = "text"),
        iframe = CssSelector(query = ".brooks_player iframe, iframe[name=player_iframe]", attr = "src")
    )

    // ================= OVERRIDES =================

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null

        // Title cleaning (from old FaselHD)
        var title = data.title
        title = title.replace(Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي"), "")
            .replace("مترجم اون لاين", "")
            .replace(" - فاصل إعلاني", "")
            .trim()

        // Plot cleaning
        var plot = data.plot
        if (!plot.isNullOrBlank()) {
            val parts = plot.split(":")
            if (parts.size > 1 && parts.first().contains("تحميل")) {
                plot = parts.drop(1).joinToString(":").trim()
            }
        }

        // Poster fallback: find first image with /wp-content/uploads/ path
        var posterUrl = data.posterUrl
        if (posterUrl.isNullOrBlank()) {
            val contentImg = doc.select("img").firstOrNull { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                src.contains("/wp-content/uploads/") && src.length > 10
            }
            posterUrl = contentImg?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        }

        return data.copy(
            title = title,
            plot = plot,
            posterUrl = posterUrl ?: ""
        )
    }

    /**
     * Extract watch server URLs.
     * Handles two patterns:
     * 1. New WatchList: `li[data-embed-url]` attributes
     * 2. Old onclick: `.signleWatch ul.tabs-ul li[onclick]` with regex extraction
     * 3. Direct iframes
     */
    override fun extractWatchServersUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()

        // 1. New WatchList pattern (data-embed-url)
        doc.select("ul.WatchList li[data-embed-url]").forEach { li ->
            val embedUrl = li.attr("data-embed-url")
            if (embedUrl.isNotBlank()) urls.add(embedUrl)
        }

        // 2. Old onclick pattern (.signleWatch ul.tabs-ul li[onclick])
        val urlRegex = "'.*?'".toRegex()
        doc.select(".signleWatch ul.tabs-ul li[onclick], ul.tabs-ul li[onclick]").forEach { li ->
            val onclick = li.attr("onclick")
            val match = urlRegex.find(onclick)
            if (match != null) {
                val playerUrl = match.value.replace("'", "")
                if (playerUrl.isNotBlank()) urls.add(playerUrl)
            } else {
                // Fallback: data-url or data-link
                val fallback = li.attr("data-url").ifBlank { li.attr("data-link") }
                if (fallback.isNotBlank()) urls.add(fallback)
            }
        }

        // 3. Direct iframes
        doc.select(".brooks_player iframe[src], iframe[name=player_iframe][src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) urls.add(src)
        }

        Log.d("[FaselHDV2Parser]", "extractWatchServersUrls: found ${urls.size} URLs")
        return urls.distinct()
    }

    /**
     * Build server selectors for the sniffer to click the correct server tab.
     * Maps each extracted URL back to its `li[data-embed-url]` element in the WatchList.
     */
    override fun buildServerSelectors(
        doc: Document,
        urls: List<String>
    ): List<com.cloudstream.shared.extractors.SnifferSelector?> {
        val watchListItems = doc.select("ul.WatchList li[data-embed-url]")
        if (watchListItems.isEmpty()) return urls.map { null }

        return urls.map { url ->
            val matchingLi = watchListItems.firstOrNull { it.attr("data-embed-url") == url }
            if (matchingLi != null) {
                com.cloudstream.shared.extractors.SnifferSelector(
                    query = "ul.WatchList li[data-embed-url=\"$url\"]",
                    attr = "data-embed-url"
                )
            } else null
        }
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()

        // 1. Check for Mobile Structure (Dropdown Selects)
        val mobileEpisodeBlocks = doc.select("select.episodeoption, select[aria-label='Episodes']")

        if (mobileEpisodeBlocks.isNotEmpty()) {
            val seasonMap = mutableMapOf<String, Int>()
            doc.select("select#mobileselect option, select[aria-label='Seasons'] option").forEach { option ->
                val seasonId = option.attr("value")
                val seasonText = option.text()
                val extractedSeasonNum = Regex("(\\d+)").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                if (seasonId.isNotBlank() && extractedSeasonNum != null) {
                    seasonMap[seasonId] = extractedSeasonNum
                }
            }

            mobileEpisodeBlocks.forEach { block ->
                val blockId = block.attr("id")
                val actualSeasonNum = seasonMap[blockId] 
                    ?: Regex("(\\d+)").find(blockId)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: seasonNum 
                    ?: 1

                block.select("option:not([value='select-ep'])").forEach { option ->
                    val epTitle = option.text().trim()
                    val epUrl = option.attr("value").trim()

                    if (epUrl.isNotBlank()) {
                        val epNum = Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                        episodes.add(
                            ParserInterface.ParsedEpisode(
                                name = epTitle,
                                url = epUrl,
                                season = actualSeasonNum,
                                episode = epNum
                            )
                        )
                    }
                }
            }
            return episodes
        } 
        
        // 2. Check for Desktop Tabs Structure
        val desktopSeasonTabs = doc.select(".SeasonsEpisodesMains .tabcontent")
        
        if (desktopSeasonTabs.isNotEmpty()) {
            desktopSeasonTabs.forEach { tabBlock ->
                val tabId = tabBlock.attr("id")
                val actualSeasonNum = Regex("(\\d+)").find(tabId)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: seasonNum 
                    ?: 1
                    
                tabBlock.select("ul.pm-ul-browse-videos li").forEach { li ->
                    val epTitle = li.selectFirst("h3 a")?.text()?.trim() ?: ""
                    val epUrl = li.selectFirst("h3 a")?.attr("href")?.trim() ?: ""
                    
                    if (epUrl.isNotBlank()) {
                        val epNum = Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        
                        episodes.add(
                            ParserInterface.ParsedEpisode(
                                name = epTitle,
                                url = epUrl,
                                season = actualSeasonNum,
                                episode = epNum
                            )
                        )
                    }
                }
            }
            return episodes
        }

        // 3. Old FaselHD layout: div.epAll a (with season from seasonDiv)
        val activeSeasonNum = extractActiveSeasonNum(doc) ?: seasonNum ?: 1
        doc.select("div.epAll a").forEach { ep ->
            val epUrl = ep.attr("href")
            val epTitle = ep.text().trim()
            if (epUrl.isNotBlank()) {
                val epNum = epTitle.replace("الحلقة", "").trim().toIntOrNull()
                    ?: Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = epTitle,
                        url = epUrl,
                        season = activeSeasonNum,
                        episode = epNum
                    )
                )
            }
        }

        // 4. Fallback to base parser
        if (episodes.isEmpty()) {
            episodes.addAll(super.parseEpisodes(doc, seasonNum))
        }

        return episodes
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        // Look for play page link
        val playLink = doc.selectFirst("a[href*='play.php?vid=']")?.attr("href")
            ?: doc.selectFirst("#BiBplayer a[href*='play.php']")?.attr("href")
            ?: doc.selectFirst("#video-wrapper a[href*='play.php']")?.attr("href")

        Log.d("FaselHDV2Parser", "getPlayerPageUrl: found='$playLink'")
        return playLink
    }

    // Series Detection — uses seasonEpsCont presence (from old FaselHDv2.kt)
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // 0. Explicit Movie Checks (Fail-fast)
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false

        // 1. Element Context Checks
        if (element != null) {
            if (element is Document) {
                // Season container exists → definitely a series
                if (element.select("div.seasonEpsCont").isNotEmpty()) return true
                if (element.select("div.epAll").isNotEmpty()) return true
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                if (element.extract(loadPageConfig.seriesIndicator) != null) return true
            } else {
                val categoryText = element.select("span.label").text()
                if (categoryText.contains("مسلسلات")) return true
            }
        }
        
        // 2. Keyword check (Fallback)
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("/seasons/") || url.contains("/series/") || url.contains("ramadan")) return true
        
        return false
    }

    // ================= HELPERS =================

    /** Extract the active season number from the page (old FaselHD layout) */
    fun extractActiveSeasonNum(doc: Document): Int? {
        val seasonTabs = doc.select("div.seasonDiv")
        if (seasonTabs.isNotEmpty()) {
            val activeTab = seasonTabs.find { it.hasClass("active") }
            if (activeTab != null) {
                return Regex("\\d+").find(activeTab.select(".title").text())?.value?.toIntOrNull()
            }
        }
        // Fallback
        val text = doc.select("div.seasonDiv.active .title, div.seasonDiv.active div.title").text()
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    /** Parse season tabs and return (seasonNum, pageUrl) pairs for inactive seasons */
    fun parseSeasonTabs(doc: Document): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val seasonTabs = doc.select("div.seasonDiv")
        
        seasonTabs.filter { !it.hasClass("active") }.forEach { tab ->
            val title = tab.select(".title").text()
            val seasonNum = Regex("\\d+").find(title)?.value?.toIntOrNull() ?: return@forEach
            
            // Extract URL from onclick
            val onclick = tab.attr("onclick")
            
            // Pattern 1: onclick contains href='...'
            var pageUrl = Regex("""href\s*=\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
            
            // Pattern 2: onclick contains ?p=ID
            if (pageUrl == null) {
                val pId = Regex("""\?p=(\d+)""").find(onclick)?.groupValues?.get(1)
                    ?: onclick.replace(Regex(".*/?p=|'"), "").trim().takeIf { it.isNotBlank() }
                if (pId != null) {
                    pageUrl = "/?p=$pId"
                }
            }
            
            if (pageUrl != null) {
                results.add(seasonNum to pageUrl)
            }
        }
        
        return results
    }
}