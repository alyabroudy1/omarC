package com.laroza

import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.cloudstream.shared.extractors.LinkResolvers
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.cloudstream.shared.parsing.BaseParser
import com.cloudstream.shared.parsing.ParserInterface.ParsedItem
import com.cloudstream.shared.parsing.ParserInterface.ParsedLoadData
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode

class LarozaParser : NewBaseParser() {
    
    // Main Page Selectors
    override fun parseMainPage(doc: Document): List<ParsedItem> {
        val items = mutableListOf<ParsedItem>()
        // Select items from the grid (div.pm-li-category)
        val elements = doc.select("div.pm-li-category")
        for (element in elements) {
            val titleElement = element.selectFirst(".caption h3 a")
            val title = titleElement?.text()?.trim() ?: ""
            val url = titleElement?.attr("href") ?: ""
            val posterUrl = element.selectFirst(".pm-video-thumb img")?.attr("src") 
                ?: element.selectFirst(".pm-video-thumb img")?.attr("data-echo")

            if (title.isNotBlank() && url.isNotBlank()) {
                items.add(ParsedItem(
                    title = title,
                    url = url,
                    posterUrl = posterUrl,
                    isMovie = isMovie(title, url, element)
                ))
            }
        }
        return items
    }

    override fun parseSearch(doc: Document): List<ParsedItem> {
        // Search results usually use same structure or li inside ul.pm-ul-browse-videos
        val items = mutableListOf<ParsedItem>()
        
        // Try browse list first
        val elements = doc.select("ul.pm-ul-browse-videos li")
        for (element in elements) {
            val titleElement = element.selectFirst("h3 a")
            val title = titleElement?.text()?.trim() ?: ""
            val url = titleElement?.attr("href") ?: ""
            val posterUrl = element.selectFirst(".pm-video-thumb img")?.attr("src")
                 ?: element.selectFirst(".pm-video-thumb img")?.attr("data-echo")

            if (title.isNotBlank() && url.isNotBlank()) {
                items.add(ParsedItem(
                    title = title,
                    url = url,
                    posterUrl = posterUrl,
                    isMovie = isMovie(title, url, element)
                ))
            }
        }
        
        if (items.isEmpty()) {
            return parseMainPage(doc) // Fallback to main page parser if structure matches
        }
        
        return items
    }

    override fun parseLoadPage(doc: Document, url: String): ParsedLoadData? {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        
        // Plot
        val plot = doc.selectFirst("div#video-description")?.text()?.trim()
            ?: doc.select("meta[name='description']").attr("content")
            ?: doc.select("meta[property='og:description']").attr("content")
        
        // Poster
        val posterUrl = doc.select("meta[property='og:image']").attr("content")
        
        // Type Detection
        // 1. Check for Episodes list
        val episodeElements = doc.select("ul.pm-ul-browse-videos li")
        val hasEpisodes = episodeElements.isNotEmpty()
        
        // 2. Keyword check
        val isSeriesKeyword = title.contains("مسلسل") || title.contains("حلقة") || url.contains("series")
        
        // 3. Category/Breadcrumb check
        val categories = doc.select(".breadcrumb li").text()
        val isSeriesCategory = categories.contains("مسلسلات")
        
        val isMovie = !hasEpisodes && !isSeriesKeyword && !isSeriesCategory
        
        val episodes = if (!isMovie) {
             episodeElements.mapNotNull { element ->
                val epTitleElement = element.selectFirst("h3 a")
                val epTitle = epTitleElement?.text()?.trim() ?: ""
                val epUrl = epTitleElement?.attr("href") ?: ""
                
                if (epUrl.isNotBlank()) {
                    val epNum = Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
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
        } else emptyList()

        return ParsedLoadData(
            title = title,
            plot = plot,
            posterUrl = posterUrl,
            url = url,
            type = if (isMovie) TvType.Movie else TvType.TvSeries,
            year = null, // Could extract from title or meta
            episodes = episodes
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParsedEpisode> {
        // Handled in parseLoadPage for this structure, or can be called separately
        return emptyList()
    }

    override fun extractPlayerUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        
        // 1. WatchList (Most reliable for multiple Servers)
        val listItems = doc.select("ul.WatchList li[data-embed-url]")
        for (li in listItems) {
            val embedUrl = li.attr("data-embed-url")
            if (embedUrl.isNotBlank()) {
                urls.add(embedUrl)
            }
        }
        
        // 2. Direct Iframe (Fallback)
        if (urls.isEmpty()) {
            val iframeSrc = doc.select(".brooks_player iframe").attr("src")
            if (iframeSrc.isNotBlank()) {
                urls.add(iframeSrc)
            }
        }
        
        return urls.distinct()
    }
    
    // Helper for robust detection
    private fun isMovie(title: String, url: String, element: Element?): Boolean {
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return false
        if (url.contains("series") || url.contains("ramadan")) return false
        
        // Check category tag if available
        val categoryText = element?.select("span.label")?.text() ?: ""
        if (categoryText.contains("مسلسلات")) return false
        
        return true
    }
}