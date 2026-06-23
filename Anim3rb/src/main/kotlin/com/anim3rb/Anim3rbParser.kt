package com.anime3rb

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class Anim3rbParser : NewBaseParser() {
    private val PARSER_TAG = "Anim3rbParser"

    override val mainPageConfig = MainPageConfig(
        container = "a.video-card",
        title = CssSelector(query = "h3.title-name", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val searchConfig = MainPageConfig(
        container = "a.simple-title-card",
        title = CssSelector(query = "h4", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "img", attr = "data-src, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1", attr = "text"),
        poster = CssSelector(query = "img[alt*='بوستر']", attr = "data-src, src"),
        plot = CssSelector(query = "div.py-4.flex.flex-col.gap-2 p, p.synopsis, meta[name=description]", attr = "content, text"),
        seriesIndicator = CssSelector(query = "span.badge:contains(Series), span.type:contains(مسلسل)", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = ".video-list a, .episodes-list a",
        title = CssSelector(query = ".video-data p", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = ".video-data span", attr = "text", regex = "(\\d+)")
    )

    override val watchServersSelectors = WatchServerSelector(
        iframe = CssSelector(query = "iframe[src*='player'], .brooks_player iframe, iframe[src]", attr = "src"),
        url = CssSelector(query = "ul.WatchList li[data-embed-url], div.server-item[data-url], a[href*='/player/']", attr = "data-embed-url, data-url, href"),
        script = CssSelector(query = "script:containsData(video_sources), script:containsData(file:)", attr = "text")
    )

    override fun getSearchUrl(domain: String, query: String): String {
        val url = "$domain/?s=$query"
        Log.i(PARSER_TAG, "getSearchUrl: $url")
        return url
    }

    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        Log.i(PARSER_TAG, "parseMainPage: doc.title=${doc.title()}, html.length=${doc.html().length}")
        val items = super.parseMainPage(doc)
        Log.i(PARSER_TAG, "parseMainPage: found ${items.size} items via container='${mainPageConfig.container}'")
        if (items.isEmpty()) {
            Log.w(PARSER_TAG, "parseMainPage: DUMP body (first 3000):\n${doc.body()?.html()?.take(3000)}")
            Log.w(PARSER_TAG, "parseMainPage: all a[href]: ${doc.select("a[href]").eachAttr("href").take(20)}")
        }
        return items
    }

    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        Log.i(PARSER_TAG, "parseSearch: title=${doc.title()}, html.length=${doc.html().length}")
        val items = super.parseSearch(doc)
        Log.i(PARSER_TAG, "parseSearch: found ${items.size} items via container='${searchConfig.container}'")
        if (items.isEmpty()) {
            Log.w(PARSER_TAG, "parseSearch: DUMP body (first 3000):\n${doc.body()?.html()?.take(3000)}")
            val matched = doc.select(searchConfig.container)
            Log.w(PARSER_TAG, "parseSearch: selector '${searchConfig.container}' matched ${matched.size} elements")
            matched.forEachIndexed { i, el -> Log.w(PARSER_TAG, "parseSearch: card[$i] html=${el.html().take(200)}") }
        }
        return items
    }

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        Log.i(PARSER_TAG, "parseLoadPage: url=$url, title=${doc.title()}, html.length=${doc.html().length}")
        val data = super.parseLoadPage(doc, url)
        if (data == null) { Log.e(PARSER_TAG, "parseLoadPage: super returned null"); return null }
        Log.i(PARSER_TAG, "parseLoadPage: title='${data.title}', poster='${data.posterUrl?.take(80)}', plot.len=${data.plot?.length ?: 0}, type=${data.type}, episodes=${data.episodes?.size ?: 0}")
        if (data.episodes.isNullOrEmpty()) {
            val matched = doc.select(episodeConfig.container)
            Log.w(PARSER_TAG, "parseLoadPage: episodes empty, selector '${episodeConfig.container}' matched ${matched.size}")
            matched.firstOrNull()?.let { Log.w(PARSER_TAG, "parseLoadPage: first ep html: ${it.html().take(300)}") }
            if (matched.isEmpty()) Log.w(PARSER_TAG, "parseLoadPage: DUMP body (first 3000):\n${doc.body()?.html()?.take(3000)}")
        }
        return data
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val raw = super.parseEpisodes(doc, seasonNum)
        Log.d(PARSER_TAG, "parseEpisodes: super returned ${raw.size} (season=$seasonNum)")
        if (raw.isEmpty()) return raw
        val resolved = raw.mapNotNull { ep ->
            val r = resolveUrl(ep.url, doc.location()) ?: return@mapNotNull null
            Log.d(PARSER_TAG, "parseEpisodes: ep=${ep.episode} url=$r name='${ep.name}'")
            ep.copy(url = r)
        }
        Log.i(PARSER_TAG, "parseEpisodes: resolved ${resolved.size}/${raw.size}")
        return resolved
    }

    override fun extractWatchServersUrls(doc: Document): List<String> {
        Log.i(PARSER_TAG, "extractWatchServersUrls: title=${doc.title()}, html.length=${doc.html().length}")
        val urls = super.extractWatchServersUrls(doc)
        Log.i(PARSER_TAG, "extractWatchServersUrls: found ${urls.size} URLs")
        if (urls.isEmpty()) {
            doc.select("iframe[src]").forEach { Log.w(PARSER_TAG, "extractWatchServersUrls: iframe src=${it.attr("src")}") }
            doc.select("a[href*='player' i]").forEach { Log.w(PARSER_TAG, "extractWatchServersUrls: player link href=${it.attr("href")} text=${it.text()}") }
            Log.w(PARSER_TAG, "extractWatchServersUrls: DUMP body (first 3000):\n${doc.body()?.html()?.take(3000)}")
        }
        return urls
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        val url = doc.selectFirst("a[href*='play.php'], a[href*='/player/'], a.play-button, a.watch-button")?.attr("href")
            ?: doc.selectFirst("iframe[src*='/player/']")?.attr("src")
        Log.i(PARSER_TAG, "getPlayerPageUrl: found='$url'")
        return url?.let { resolveUrl(it, doc.location()) }
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (title.contains("فيلم") || title.contains("film", true)) return false
        if (title.contains("مسلسل") || title.contains("anime", true)) return true
        if (url.contains("movie") || url.contains("film")) return false
        return true
    }

    override fun buildServerSelectors(doc: Document, urls: List<String>): List<com.cloudstream.shared.extractors.SnifferSelector?> {
        Log.i(PARSER_TAG, "buildServerSelectors: ${urls.size} URLs")
        return super.buildServerSelectors(doc, urls)
    }

    private fun resolveUrl(url: String, baseUrl: String?): String? {
        if (url.isBlank()) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        if (baseUrl == null) return url
        return try {
            val base = URI(baseUrl)
            URI("${base.scheme}://${base.host}").resolve(url).toString()
        } catch (_: Exception) { "$baseUrl$url" }
    }
}
