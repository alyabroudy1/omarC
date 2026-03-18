package com.syrialive

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document

class SyriaLiveParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = ".AY-PItem",
        title = CssSelector(query = ".AY-PostTitle a", attr = "text"),
        url = CssSelector(query = ".AY-PostTitle a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = ".AY-PItem",
        title = CssSelector(query = ".AY-PostTitle a", attr = "text"),
        url = CssSelector(query = ".AY-PostTitle a", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = ".EntryTitle", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image'], .teamlogo", attr = "content, data-src, src"),
        plot = CssSelector(query = ".entry-content p", attr = "text")
    )

    // Unused but required by NewBaseParser architecture
    override val episodeConfig = EpisodeConfig(
        container = "",
        title = CssSelector(query = "", attr = ""),
        url = CssSelector(query = "", attr = "")
    )
    
    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = ".video-serv a", attr = "href"),
        id = CssSelector(query = ".video-serv a", attr = "href"), // No unique ID available
        title = CssSelector(query = ".video-serv a", attr = "text"),
        iframe = CssSelector(query = ".entry-content iframe", attr = "src")
    )

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null
        
        val tableRows = doc.select(".AY-MatchInfo table tr")
        var plot = data.plot
        val tags = mutableListOf<String>()
        
        if (tableRows.isNotEmpty()) {
            val descBuilder = StringBuilder()
            tableRows.forEach { row ->
                val key = row.select("th").text()
                val value = row.select("td").text()
                if (key.isNotBlank() && value.isNotBlank()) {
                    descBuilder.append("$key: $value\n")
                }
                if (value.isNotBlank()) tags.add(value)
            }
            plot = descBuilder.toString().trim()
        }
        
        val isLive = url.contains("/matches/") && tableRows.isNotEmpty()
        
        return data.copy(
            plot = if (plot.isNullOrBlank()) null else plot,
            type = if (isLive) com.lagradost.cloudstream3.TvType.Live else com.lagradost.cloudstream3.TvType.Movie,
            tags = tags
        )
    }
}
