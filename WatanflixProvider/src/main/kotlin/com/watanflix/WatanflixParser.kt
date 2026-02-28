package com.watanflix

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class WatanflixParser : NewBaseParser() {
    
    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/ar/search?q=$query"
    }
    
    override val mainPageConfig = MainPageConfig(
        container = "div.video-grid1",
        title = CssSelector(query = "div.video_img img", attr = "alt"),
        url = CssSelector(query = "a.v-link", attr = "href"),
        poster = CssSelector(query = "div.video_img img", attr = "src")
    )

    override val searchConfig = MainPageConfig(
        container = "div.video-grid1",
        title = CssSelector(query = "div.video_img img", attr = "alt"),
        url = CssSelector(query = "a.v-link", attr = "href"),
        poster = CssSelector(query = "div.video_img img", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1.details_title", attr = "text"),
        poster = CssSelector(query = "meta[property='og:image']", attr = "content"),
        plot = CssSelector(query = "div.description p", attr = "text"),
        seriesIndicator = CssSelector(query = "div#slidingSeries", attr = "text"),
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.item:has(a.linkPlay)",
        title = CssSelector(query = "p b", attr = "text"),
        url = CssSelector(query = "a.linkPlay", attr = "href"),
        episode = CssSelector(query = "p", attr = "text", regex = "Episode (\\d+)")
    )

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        
        val episodeBlocks = doc.select("div.item:has(a.linkPlay)")
        
        if (episodeBlocks.isEmpty()) {
            return super.parseEpisodes(doc, seasonNum)
        }
        
        episodeBlocks.forEach { block ->
            val link = block.selectFirst("a.linkPlay")?.attr("href") ?: return@forEach
            val title = block.selectFirst("p")?.text() ?: ""
            val epNum = Regex("Episode (\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (link.isNotBlank()) {
                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = title,
                        url = link,
                        season = seasonNum ?: 1,
                        episode = epNum
                    )
                )
            }
        }
        
        return episodes
    }

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "iframe", attr = "src"),
    )

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (title.contains("فيلم") || title.contains("film", true) || title.contains("movie", true)) return false
        if (title.contains("مسلسل") || title.contains("حلقة") || title.contains("موسم")) return true
        if (url.contains("series")) return true
        return false
    }
}
