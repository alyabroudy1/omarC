package com.laroza

import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.parsing.ParserInterface.ParsedItem
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode

// --- Selector Data Structures ---
data class ConfigSelector(
    val query: String,
    val attr: String? = null,
    val regex: String? = null
)

data class ItemPageSelectors(
    val container: String,
    val title: ConfigSelector,
    val url: ConfigSelector,
    val poster: ConfigSelector,
    val tags: List<ConfigSelector> = emptyList()
)

class LarozaParser : NewBaseParser() {
    
    // removed PARSER_TAG to avoid shadowing

    // Selectors from analysis (Bootstrap grid + thumbnail)
    private val itemSelectors = listOf(
        "div.col-md-3 div.thumbnail",
        "div.col-sm-4 div.thumbnail",
        "div.thumbnail",
        "div.pm-li-category" // Legacy fallback
    )

    // --- Configuration ---
    private val mainPageConfig = ItemPageSelectors(
        container = "div.col-md-3 div.thumbnail, div.col-sm-4 div.thumbnail, div.thumbnail",
        title = ConfigSelector(query = ".caption h3 a, h3 a"),
        url = ConfigSelector(query = ".caption h3 a, h3 a", attr = "href"),
        poster = ConfigSelector(query = ".thumbnail.a img, img", attr = "data-echo"),
        tags = listOf(
            ConfigSelector(query = ".pm-label-duration") // Duration tag
        )
    )

    // --- Generic Parsing ---
    private fun extractValue(element: Element, selector: ConfigSelector): String? {
        val target = if (selector.query.isBlank()) element else (element.selectFirst(selector.query) ?: return null)
        
        var value = if (selector.attr != null) {
            target.attr(selector.attr).takeIf { it.isNotBlank() } ?: target.attr("data-echo") // Fallback for lazyload
        } else {
            target.text().trim()
        }
        
        // Apply regex if present
        if (!value.isNullOrBlank() && selector.regex != null) {
            // value = Regex(selector.regex).find(value)?.value // Simple implementation
        }
        
        return value
    }

    private fun parseGenericItems(doc: Document, config: ItemPageSelectors): List<ParsedItem> {
        Log.d("LarozaParser", "Parsing Generic Items with selector: ${config.container}")
        val elements = doc.select(config.container)
        Log.d("LarozaParser", "Found ${elements.size} items")
        
        return elements.mapNotNull { element ->
            val link = element.select("a").attr("href")
            val title = element.select("a").attr("title").ifBlank { element.select("h3").text() }
            
            if (title.isNullOrBlank() || link.isNullOrBlank()) return@mapNotNull null
            
            // Log the HTML of the image element as requested
            Log.d("LarozaParser", "Search Element FULL HTML: ${element.outerHtml()}")
            
            val posterUrl = extractImage(element) ?: extractBackgroundImage(element)
            
            val extractedTags = config.tags.mapNotNull { extractValue(element, it) }

            ParsedItem(
                title = title,
                url = link,
                posterUrl = posterUrl,
                isMovie = isMovie(title, link, element),
                tags = extractedTags
            )
        }
    }

    // --- Implementation ---

    // Main Page Selectors
    override fun parseMainPage(doc: Document): List<ParsedItem> {
        val title = doc.title()
        Log.d("LarozaParser", "parseMainPage START. Title: '$title'")
        
        val items = parseGenericItems(doc, mainPageConfig)
        if (items.isEmpty()) {
             Log.d("LarozaParser", "No items found in main page. Dumping 500 chars:")
             Log.d("LarozaParser", doc.html().take(500))
             
             // Try fallback to search parser logic if valid list found
             if (doc.select("ul.pm-ul-browse-videos li").isNotEmpty()) {
                 return parseSearch(doc)
             }
        }
        
        Log.d("LarozaParser", "parseMainPage END. Returning ${items.size} items")
        return items
    }
        


    override fun parseSearch(doc: Document): List<ParsedItem> {
        Log.d("LarozaParser", "parseSearch START")
        // Use same config for search as structure appears identical or fallback is built-in
        val items = parseGenericItems(doc, mainPageConfig)
        
        if (items.isEmpty()) {
            Log.d("LarozaParser", "Search empty. Trying main page parser fallback.")
            return parseMainPage(doc) // Fallback to main page parser if structure matches
        }
        
        Log.d("LarozaParser", "parseSearch END. Returning ${items.size} items")
        return items
    }

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        Log.d("LarozaParser", "parseLoadPage START. URL: $url")
        
        // Check if it is a Series Page (view-serie.php) or has SeasonsBoxUL
        val isSeriesPage = url.contains("view-serie") || doc.select(".SeasonsBoxUL").isNotEmpty()
        
        if (isSeriesPage) {
            return parseSeriesPage(doc, url)
        }

        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        
        // Plot
        val rawPlot = doc.selectFirst("div#video-description")?.text()?.trim()
            ?: doc.select("meta[name='description']").attr("content")
            ?: doc.select("meta[property='og:description']").attr("content")
        val plot = cleanPlot(rawPlot)
        
        // Tags
        val tags = doc.select("div.video-info-line.video-category-line a").map { it.text().trim() }
        
        // Poster
        val posterUrl = doc.select("meta[property='og:image']").attr("content")
        
        // Parent Series Link (from Episode Page)
        val seriesLinkElement = doc.selectFirst("div.video-info-line a[href*='view-serie']")
        val seriesUrl = seriesLinkElement?.attr("href")
        val seriesTitle = seriesLinkElement?.text()?.trim()
        
        Log.d("LarozaParser", "Episode Page. Parent Series: '$seriesTitle' -> '$seriesUrl'")
        
        // Type Detection & Episode Parsing
        // 1. Check for Episodes list (Generic selector for div or ul)
        var episodeElements = doc.select(".pm-ul-browse-videos a")
        
        // 2. Fallback: Check for Select-based seasons/episodes (Mobile/Alternative view)
        // If the main list is empty, try to parse the selects
        val selectEpisodes = if (episodeElements.isEmpty()) parseSelectEpisodes(doc) else emptyList()
        
        val hasEpisodes = episodeElements.isNotEmpty() || selectEpisodes.isNotEmpty()
        
        Log.d("LarozaParser", "Type detection: hasEpisodes=$hasEpisodes")

        // 2. Keyword check
        val isSeriesKeyword = title.contains("مسلسل") || title.contains("حلقة") || url.contains("series")
        
        // 3. Category/Breadcrumb check
        val categories = doc.select(".breadcrumb li").text()
        val isSeriesCategory = categories.contains("مسلسلات")
        
        val isMovie = !hasEpisodes && !isSeriesKeyword && !isSeriesCategory
        
        // Combine episodes
        val episodes = if (!isMovie) {
             if (selectEpisodes.isNotEmpty()) {
                 selectEpisodes
             } else {
                 episodeElements.mapNotNull { element ->
                    val epTitle = element.attr("title").trim()
                    val epUrl = element.attr("href")
                    
                    if (epUrl.isNotBlank()) {
                        // Extract number from title or inner text (e.g. <em>12</em>)
                        val emText = element.select("em").text().trim()
                        val epNum = emText.toIntOrNull() 
                            ?: Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                            ?: Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                            ?: 0
                            
                        ParsedEpisode(
                            name = epTitle,
                            url = epUrl,
                            season = 1, // Default to season 1 for now
                            episode = epNum
                        )
                    } else null
                }
             }
        } else emptyList()

        Log.d("LarozaParser", "parseLoadPage END. isMovie=$isMovie, episodes=${episodes.size}")

        // Force dump if no episodes found, regardless of type, to debug "19df723d6"
        if (episodes.isEmpty()) {
             Log.d("LarozaParser", "NO EPISODES FOUND. DUMPING HTML:")
             Log.d("LarozaParser", doc.html())
        }

        return ParsedLoadData(
            title = title,
            plot = plot,
            posterUrl = posterUrl,
            url = url,
            type = if (isMovie) TvType.Movie else TvType.TvSeries,
            year = null, // Could extract from title or meta
            episodes = episodes,
            tags = tags,
            parentSeriesUrl = seriesUrl
        )
    }

    private fun extractImage(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        Log.d("LarozaParser", "Image attributes: ${img.attributes()}")
        return img.attr("data-echo").ifBlank {
            img.attr("data-src").ifBlank {
                img.attr("data-original").ifBlank {
                    img.attr("data-image").ifBlank {
                        img.attr("src")
                    }
                }
            }
        }
    }

    private fun extractBackgroundImage(element: Element): String? {
        // Check for style="background-image: url(...)" on the element or children
        val styleElements = element.select("[style*='background-image']")
        for (el in styleElements) {
            val style = el.attr("style")
            val urlMatch = Regex("url\\(['\"]?([^'\")]+)['\"]?\\)", RegexOption.IGNORE_CASE).find(style)
            val url = urlMatch?.groupValues?.get(1)
            if (!url.isNullOrBlank()) {
                Log.d("LarozaParser", "Found background-image: $url")
                return url
            }
        }
        return null
    }

    private fun cleanPlot(rawPlot: String?): String? {
        if (rawPlot.isNullOrBlank()) return null
        
        // User Rule: Split by ':' and remove first part if it contains 'تحميل'
        val parts = rawPlot.split(":", limit = 2)
        if (parts.size > 1 && parts[0].contains("تحميل")) {
             return parts[1].trim()
        }
        return rawPlot
    }

    private fun parseSelectEpisodes(doc: Document): List<ParsedEpisode> {
        val episodes = mutableListOf<ParsedEpisode>()
        // seasons : div.TabS select#mobileselect option
        // episodes: div.TabE select.episodeoption option
        
        // Map Season IDs to Numbers
        val seasonSelect = doc.selectFirst("div.TabS select#mobileselect")
        val seasonMap = mutableMapOf<String, Int>() // MSeason1 -> 1
        
        if (seasonSelect != null) {
            for (opt in seasonSelect.select("option")) {
                val id = opt.attr("value") // MSeason1
                val text = opt.text() // موسم 1
                val num = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                seasonMap[id] = num
            }
        } else {
            // Default if no season select found but maybe episode select exists
             seasonMap["MSeason1"] = 1
        }
        
        // Parse Episodes
        val epSelects = doc.select("div.TabE select.episodeoption")
        for (select in epSelects) {
            val id = select.id() // MSeason1
            val seasonNum = seasonMap[id] ?: 1
            
            for (opt in select.select("option")) {
                val epUrl = opt.attr("value")
                val epText = opt.text() // الحلقة 1
                
                if (epUrl.contains("video.php")) {
                     val epNum = Regex("(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                     episodes.add(ParsedEpisode(
                         name = epText,
                         url = epUrl,
                         season = seasonNum,
                         episode = epNum
                     ))
                }
            }
        }
        return episodes
    }

    private fun parseSeriesPage(doc: Document, url: String): ParsedLoadData {
        Log.d("LarozaParser", "Parsing Series Page $url")
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        val plot = doc.selectFirst("div.well")?.text()?.trim() // Common description area in series
            ?: doc.select("meta[name='description']").attr("content")
        val posterUrl = doc.select("meta[property='og:image']").attr("content")
        
        val episodes = mutableListOf<ParsedEpisode>()
        
        // --- Strategy 1: SeasonsBoxUL (New Structure from User Script) ---
        // Script: $('.SeasonsBoxUL > ul > li').click... data-serie="..."
        // Tabs: .SeasonsBoxUL > ul > li[data-serie]
        // Content: .SeasonsEpisodes[data-serie] OR .SeasonsEpisodesMain > div[data-serie]
        
        val seasonTabsNew = doc.select(".SeasonsBoxUL > ul > li")
        if (seasonTabsNew.isNotEmpty()) {
             Log.d("LarozaParser", "Found New Series Structure (.SeasonsBoxUL)")
             for (tab in seasonTabsNew) {
                 val seasonNumStr = tab.attr("data-serie")
                 val seasonNum = seasonNumStr.toIntOrNull() ?: 1
                 val seasonName = tab.text().trim() // e.g., "Season 1"
                 
                 // Find content container. The script implies two possible locations or classes:
                 // 1. '.SeasonsEpisodes[data-serie="X"]'
                 // 2. '.SeasonsEpisodesMain > div[data-serie="X"]'
                 var contentDiv = doc.selectFirst(".SeasonsEpisodes[data-serie='$seasonNumStr']")
                 if (contentDiv == null) {
                     contentDiv = doc.selectFirst(".SeasonsEpisodesMain > div[data-serie='$seasonNumStr']")
                 }

                 if (contentDiv != null) {
                     val epElements = contentDiv.select("a") // Generic 'a' inside the container
                     for (element in epElements) {
                         val epUrl = element.attr("href")
                         if (epUrl.isNotBlank()) {
                             val epTitle = element.attr("title").trim().ifBlank { element.text().trim() }
                             val epNum = Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                                 ?: Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                                 ?: 0
                             
                             episodes.add(ParsedEpisode(
                                 name = epTitle,
                                 url = epUrl,
                                 season = seasonNum,
                                 episode = epNum
                             ))
                         }
                     }
                 }
             }
        }
        
        // --- Strategy 2: Old/Alternative Structure (Tab buttons + onclick) ---
        if (episodes.isEmpty()) {
            val seasonTabs = doc.select("div.Tab button.tablinks")
            if (seasonTabs.isNotEmpty()) {
                Log.d("LarozaParser", "Found Old Series Structure (Tab buttons)")
                val seasonMap = mutableMapOf<String, Int>() // ID -> SeasonNum
            
                for (tab in seasonTabs) {
                    val onclick = tab.attr("onclick")
                    val idMatch = Regex("'([^']+)'").find(onclick)
                    val id = idMatch?.groupValues?.get(1)
                    val seasonName = tab.text().trim()
                    val seasonNum = Regex("(\\d+)").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    if (id != null) {
                        seasonMap[id] = seasonNum
                    }
                }
                
                if (seasonMap.isEmpty()) seasonMap["Season1"] = 1
    
                val tabContents = doc.select("div.tabcontent")
                for (content in tabContents) {
                    val id = content.id()
                    val seasonNum = seasonMap[id] ?: 1
                    
                    val epElements = content.select(".pm-ul-browse-videos a")
                    for (element in epElements) {
                        val epUrl = element.attr("href")
                         if (epUrl.isNotBlank()) {
                             val epTitle = element.attr("title").trim()
                             val epNum = Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                                 ?: Regex("(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                                 ?: 0
                                 
                            episodes.add(ParsedEpisode(
                                name = epTitle,
                                url = epUrl,
                                season = seasonNum,
                                episode = epNum
                            ))
                         }
                    }
                }
            }
        }
        
        Log.d("LarozaParser", "Parsed ${episodes.size} episodes")

        return ParsedLoadData(
            title = title,
            plot = plot,
            posterUrl = posterUrl,
            url = url,
            type = TvType.TvSeries,
            year = null,
            episodes = episodes
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        // Handled in parseLoadPage for this structure, or can be called separately
        return emptyList()
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        // Look for play page link - class name changes randomly, use multiple selectors
        val playLink = doc.selectFirst("a[href*='play.php?vid=']")?.attr("href")
            ?: doc.selectFirst("#BiBplayer a[href*='play.php']")?.attr("href")
            ?: doc.selectFirst("#video-wrapper a[href*='play.php']")?.attr("href")
        
        Log.d("LarozaParser", "getPlayerPageUrl: found='$playLink'")
        return playLink
    }

    override fun extractPlayerUrls(doc: Document): List<String> {
        Log.d("LarozaParser", "extractPlayerUrls START")
        val urls = mutableListOf<String>()
        
        // 1. WatchList on play page - extract server URLs with their titles
        val listItems = doc.select("ul.WatchList li[data-embed-url]")
        Log.d("LarozaParser", "Found ${listItems.size} WatchList items")
        for (li in listItems) {
            val embedUrl = li.attr("data-embed-url")
            val serverTitle = li.selectFirst("strong")?.text()?.trim() ?: "سيرفر"
            val serverId = li.attr("data-embed-id")
            
            if (embedUrl.isNotBlank()) {
                Log.d("LarozaParser", "Server $serverId: $serverTitle -> $embedUrl")
                urls.add(embedUrl)
            }
        }
        
        // 2. Direct Iframe (Fallback for detail page without play link)
        if (urls.isEmpty()) {
            val iframeSrc = doc.select(".brooks_player iframe").attr("src")
            Log.d("LarozaParser", "Fallback iframe src: '$iframeSrc'")
            if (iframeSrc.isNotBlank()) {
                urls.add(iframeSrc)
            }
        }
        
        Log.d("LarozaParser", "extractPlayerUrls END. Found ${urls.size} urls")
        return urls.distinct()
    }

    // Helper for robust detection
    private fun isMovie(title: String, url: String, element: Element?): Boolean {
        // User Rules:
        // 1. Series if url contains 'serie'
        if (url.contains("serie", ignoreCase = true)) return false
        
        // 2. Episode if title contains 'حلقة' and url contains 'video'
        if (title.contains("حلقة") && url.contains("video", ignoreCase = true)) return false
        
        // 3. Movie if title contains 'فيلم' and url contains 'video'
        if (title.contains("فيلم") && url.contains("video", ignoreCase = true)) return true

        // Fallback for items that match none of the specific rules
        // Check category tag if available as a safety net
        val categoryText = element?.select("span.label")?.text() ?: ""
        if (categoryText.contains("مسلسلات")) return false
        if (title.contains("مسلسل")) return false

        // Default to Movie if likely a standalone video
        return true
    }
}