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
    
    // Selectors for 3rab FaselHD site layout
    override val mainPageConfig = MainPageConfig(
        container = "div#postList div.postDiv, section#blockList .blockMovie, section#blockList .postDiv, section#blockList .epDivHome",
        title = CssSelector(query = "a div img", attr = "alt"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "a div img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "div#postList div.postDiv, div.postDiv, article",
        title = CssSelector(query = "a div img", attr = "alt"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(query = "a div img", attr = "data-src, src")
    )

    // Load page selectors matching 3rab FaselHD site
    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = ".singleInfo .title.h1", attr = "text"),
        poster = CssSelector(
            query = "meta[itemprop=image], .posterImg img.poster",
            attr = "content, src"
        ),
        plot = CssSelector(
            query = ".singleDesc p, .story p",
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
            query = "div.seasonDiv, div.epAll",
            attr = "text"
        )
    )

    override val episodeConfig = EpisodeConfig(
        container = "div#epAll a",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = "(\\d+)")
    )

    // Watch server selectors — supports iframe + onclick patterns
    override val watchServersSelectors = WatchServerSelector(
        iframe = CssSelector(query = "iframe[src]", attr = "src"),
        script = CssSelector(query = "script", attr = "text")
    )

    // ================= OVERRIDES =================

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null

        // Title cleaning (from 3rab FaselHD)
        var title = data.title
        title = title.replace("\\n", "").replace("\n", "").trim()
        if (title.isBlank()) return null

        // Plot cleaning
        var plot = data.plot
        if (!plot.isNullOrBlank()) {
            plot = plot.replace("\\n", " ").replace("\n", " ").trim()
        }

        // Poster fallback from background image
        var posterUrl: String? = data.posterUrl
        if (posterUrl.isNullOrBlank()) {
            posterUrl = doc.selectFirst("div.singlePage")?.attr("style")
                ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
        }

        return data.copy(
            title = title,
            plot = plot,
            posterUrl = posterUrl ?: ""
        )
    }

    /**
     * Extract iframe/player sources from document.
     * Ported from 3rab Faselhd.kt extractIframeSources().
     * Extracts from:
     * 1. iframe[src] elements
     * 2. player_iframe.location.href in onclick attributes
     * 3. Script URL regex for player/embed URLs
     * 4. Short link divs (div.shortLink, span#liskSh, a[data-src])
     */
    fun extractIframeSources(doc: Document): List<String> {
        val results = mutableSetOf<String>()

        val blockedKeywords = listOf(
            "google.com/recaptcha",
            "google.com/ads",
            "googlesyndication.com",
            "googletagmanager.com"
        )

        fun addResult(url: String) {
            val fixedUrl = if (url.startsWith("http")) url else url
            if (blockedKeywords.none { fixedUrl.contains(it) }) {
                results.add(fixedUrl)
            }
        }

        // 1. iframe[src] elements
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) addResult(src)
        }

        // 2. player_iframe.location.href in onclick attributes
        val onClickRegex = Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        doc.select("[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            onClickRegex.find(onclick)?.let { match ->
                addResult(match.groupValues[1])
            }
        }

        // 3. Script URL regex for player/embed URLs
        val scriptRegex = Regex("""https?://[^\s"'<>]+""")
        doc.select("script").forEach { s ->
            val data = s.data()
            if (data.isNotBlank()) {
                scriptRegex.findAll(data).forEach { m ->
                    val url = m.value
                    if (url.contains("player") || url.contains("embed")) {
                        addResult(url)
                    }
                }
            }
        }

        // 4. Short link divs
        doc.select("div.shortLink, span#liskSh, a[data-src]").forEach { el ->
            val text = el.text().trim()
            if (text.startsWith("http")) addResult(text)
        }

        Log.d("[FaselHDV2Parser]", "extractIframeSources: found ${results.size} URLs")
        return results.toList()
    }

    /**
     * Extract watch server URLs using the 3rab approach.
     * Delegates to extractIframeSources for the player URLs.
     */
    override fun extractWatchServersUrls(doc: Document): List<String> {
        return extractIframeSources(doc)
    }

    override fun buildServerSelectors(
        doc: Document,
        urls: List<String>
    ): List<com.cloudstream.shared.extractors.SnifferSelector?> {
        return urls.map { null }
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()

        // 1. Check for Mobile Structure (Dropdown Selects)
        val mobileEpisodeBlocks = doc.select("select.episodeoption, select[aria-label='Episodes']")

        if (mobileEpisodeBlocks.isNotEmpty()) {
            val seasonMap = mutableMapOf<String, Int>()
            for (option in doc.select("select#mobileselect option, select[aria-label='Seasons'] option")) {
                val seasonId = option.attr("value")
                val seasonText = option.text()
                val extractedSeasonNum = Regex("(\\d+)").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                if (seasonId.isNotBlank() && extractedSeasonNum != null) {
                    seasonMap[seasonId] = extractedSeasonNum
                }
            }

            for (block in mobileEpisodeBlocks) {
                val blockId = block.attr("id")
                val actualSeasonNum = seasonMap[blockId] 
                    ?: Regex("(\\d+)").find(blockId)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: seasonNum 
                    ?: 1

                for (option in block.select("option:not([value='select-ep'])")) {
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
            for (tabBlock in desktopSeasonTabs) {
                val tabId = tabBlock.attr("id")
                val actualSeasonNum = Regex("(\\d+)").find(tabId)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: seasonNum 
                    ?: 1
                    
                for (li in tabBlock.select("ul.pm-ul-browse-videos li")) {
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

        // 3. 3rab FaselHD layout: div#epAll a (with season from seasonDiv)
        val activeSeasonNum = extractActiveSeasonNum(doc) ?: seasonNum ?: 1
        for (ep in doc.select("div#epAll a")) {
            val epUrl = ep.attr("href")
            val epTitle = ep.ownText().ifBlank { ep.text() }.replace("\\n", "").replace("\n", "").trim()
            if (epUrl.isNotBlank() && !epTitle.contains("باقي الحلقات") && !epTitle.contains("المزيد")) {
                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: 0
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
        val playLink = doc.selectFirst("a[href*='play.php?vid=']")?.attr("href")
            ?: doc.selectFirst("#BiBplayer a[href*='play.php']")?.attr("href")
            ?: doc.selectFirst("#video-wrapper a[href*='play.php']")?.attr("href")

        Log.d("FaselHDV2Parser", "getPlayerPageUrl: found='$playLink'")
        return playLink
    }

    // Series Detection
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false
        if (element != null) {
            if (element is Document) {
                if (element.select("div.seasonDiv").isNotEmpty()) return true
                if (element.select("div#epAll").isNotEmpty()) return true
                if (element.select(episodeConfig.container).isNotEmpty()) return true
                if (element.extract(loadPageConfig.seriesIndicator) != null) return true
            } else {
                val categoryText = element.select("span.label").text()
                if (categoryText.contains("مسلسلات")) return true
            }
        }
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("/seasons/") || url.contains("/series/") || url.contains("ramadan")) return true
        return false
    }

    // ================= HELPERS =================

    fun extractActiveSeasonNum(doc: Document): Int? {
        val seasonTabs = doc.select("div.seasonDiv")
        if (seasonTabs.isNotEmpty()) {
            val activeTab = seasonTabs.find { it.hasClass("active") }
            if (activeTab != null) {
                return Regex("\\d+").find(activeTab.select(".title").text())?.value?.toIntOrNull()
            }
        }
        val text = doc.select("div.seasonDiv.active .title, div.seasonDiv.active div.title").text()
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    fun parseSeasonTabs(doc: Document): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val seasonTabs = doc.select("div.seasonDiv")
        
        for (tab in seasonTabs) {
            if (tab.hasClass("active")) continue
            val title = tab.select(".title").text()
            val seasonNum = Regex("\\d+").find(title)?.value?.toIntOrNull() ?: continue
            
            val onclick = tab.attr("onclick")
            
            var pageUrl = Regex("""href\s*=\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)
            
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