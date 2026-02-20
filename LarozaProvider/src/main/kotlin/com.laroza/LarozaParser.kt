package com.laroza

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class LarozaParser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search.php?keywords=$query"
    }
    
    override val mainPageConfig = MainPageConfig(
        container = "div.col-md-3 div.thumbnail, div.col-sm-4 div.thumbnail, div.thumbnail",
        // Container IS the item in this loop structure
        title = CssSelector(query = ".caption h3 a, h3 a", attr = "text"),
        url = CssSelector(query = ".caption h3 a, h3 a", attr = "href"),
        poster = CssSelector(query = ".thumbnail.a img, .pm-video-thumb img, img", attr = "data-echo, src")
    )

    override val searchConfig = MainPageConfig(
        container = "ul.pm-ul-browse-videos li",
        title = CssSelector(query = "h3 a", attr = "text"),
        url = CssSelector(query = "h3 a", attr = "href"),
        poster = CssSelector(query = ".pm-video-thumb img", attr = "data-echo, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image']", attr = "content"),
        plot = CssSelector(
            query = "div#video-description, meta[name='description'], meta[property='og:description']", 
            attr = "content, text" // Will try content attribute first (for meta), then text (for div)
        ),
        seriesIndicator = CssSelector(query = ".breadcrumb li:contains(مسلسلات)", attr = "text"),
        parentSeriesUrl = CssSelector(query = "div.video-info-line a[href*='view-serie.php']", attr = "href"),
    )

    override val episodeConfig = EpisodeConfig(
        container = "ul.pm-ul-browse-videos li", // Keep as extreme fallback just in case
        title = CssSelector(query = "h3 a", attr = "text"),
        url = CssSelector(query = "h3 a", attr = "href"),
        episode = CssSelector(query = "h3 a", attr = "text", regex = "(\\d+)")
    )

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null

        var plot = data.plot
        if (!plot.isNullOrBlank()) {
            val parts = plot.split(":")
            if (parts.size > 1 && parts.first().contains("تحميل")) {
                // Drop the first part containing 'تحميل' and rejoin the rest
                plot = parts.drop(1).joinToString(":").trim()
            }
        }

        return data.copy(plot = plot)
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
                val tabId = tabBlock.attr("id") // e.g., "Season1"
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

        // 3. Fallback to the old layout (ul/li structure) if neither new structure exists
        episodes.addAll(super.parseEpisodes(doc, seasonNum))
        return episodes
    }



    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "ul.WatchList li[data-embed-url]", attr = "data-embed-url"),
        id = CssSelector(query = "ul.WatchList li[data-embed-url]", attr = "data-embed-id"),
        title = CssSelector(query = "ul.WatchList li[data-embed-url].strong", attr = "text"),
        iframe = CssSelector(query = ".brooks_player iframe", attr = "src")
    )

    // Unified Series Detection Logic
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        // 0. Explicit Movie Checks (Fail-fast)
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false

        // 1. Element Context Checks (Priority)
        if (element != null) {
            if (element is Document) {
                // Parse Load Page Context
                // Check if episodes exist
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                
                // Check Breadcrumb indicator
                if (element.extract(loadPageConfig.seriesIndicator) != null) return true
            } else {
                // Parse Item Context (Main Page/Search)
                // Check category tag if available
                val categoryText = element.select("span.label").text()
                if (categoryText.contains("مسلسلات")) return true
            }
        }
        
        // 2. Keyword check (Fallback)
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("series") || url.contains("ramadan")) return true
        
        return false
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        // Look for play page link - class name changes randomly, use multiple selectors
        val playLink = doc.selectFirst("a[href*='play.php?vid=']")?.attr("href")
            ?: doc.selectFirst("#BiBplayer a[href*='play.php']")?.attr("href")
            ?: doc.selectFirst("#video-wrapper a[href*='play.php']")?.attr("href")

        Log.d("LarozaParser", "getPlayerPageUrl: found='$playLink'")
        return playLink
    }
}