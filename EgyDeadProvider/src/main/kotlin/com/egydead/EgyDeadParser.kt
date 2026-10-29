package com.egydead

import com.lagradost.cloudstream3.TvType
import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.text.contains


class EgyDeadParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    /**
     * WordPress-style paged search: the page segment precedes the query string
     * (`<domain>/page/<N>/?s=<query>`), so a plain suffix format cannot express it.
     */
    override fun getSearchUrl(domain: String, query: String, page: Int): String {
        if (page <= 1) return getSearchUrl(domain, query)
        return "$domain/page/$page/?s=$query"
    }

    override val supportsSearchPagination: Boolean get() = true

    /**
     * Beyond the last result page the site redirects to the homepage, which still has
     * plenty of `li.movieItem` cards. Require both a search URL and at least one result
     * so that redirect cannot be mistaken for "another page exists".
     */
    override fun hasNextSearchPage(document: Document?): Boolean {
        val doc = document ?: return false
        val location = doc.location()
        if (!location.contains("?s=", ignoreCase = true) &&
            !location.contains("&s=", ignoreCase = true)
        ) {
            return false
        }
        return doc.select("li.movieItem").isNotEmpty()
    }

    // Reference: li.movieItem container, h1.BottomTitle title, img src, a href
    // Same selectors for both main page and search
    override val mainPageConfig = MainPageConfig(
        container = "li.movieItem",
        title = CssSelector(query = "h1.BottomTitle", attr = "text"),
        url = CssSelector(query = "a[href]", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    // Search uses exact same selectors as main page (from reference)
    override val searchConfig = MainPageConfig(
        container = "li.movieItem",
        title = CssSelector(query = "h1.BottomTitle", attr = "text"),
        url = CssSelector(query = "a[href]", attr = "href"),
        poster = CssSelector(query = "img", attr = "src")
    )

    // Reference: div.singleTitle em for title, div.single-thumbnail > img for poster
    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.singleTitle em", attr = "text"),
        poster = CssSelector(query = "div.single-thumbnail > img", attr = "src"),
        plot = CssSelector(
            query = "div.extra-content:contains(القصه) p",
            attr = "text"
        ),
        // Not used for type detection - we override isSeries instead
        seriesIndicator = null,
        parentSeriesUrl = null,
    )

    // Reference: div.EpsList > li > a for episodes
    override val episodeConfig = EpisodeConfig(
        container = "div.EpsList > li > a",
        title = CssSelector(query = "", attr = "title"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "text", regex = "(\\d+)")
    )

    /**
     * Collection pages (/assembly/...) list movies instead of carrying a player.
     * Expose the contained movies as episodes so the collection is browsable
     * and every entry resolves through the normal movie loadLinks flow.
     */
    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        if (url.contains("/assembly/")) {
            val title = doc.selectFirst("div.singleTitle em")?.text()?.trim()
                ?: doc.selectFirst("div.singleTitle")?.text()?.trim()
                ?: doc.title()?.trim()
                ?: return null
            val poster = doc.selectFirst("div.single-thumbnail > img")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: ""
            val plot = doc.selectFirst("div.extra-content:contains(القصه) p")?.text()?.trim()
            val episodes = doc.select("li.movieItem").mapIndexedNotNull { index, li ->
                val a = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                val epUrl = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (epUrl.isBlank()) return@mapIndexedNotNull null
                val epTitle = li.selectFirst("h1.BottomTitle")?.text()?.trim()
                    ?: a.attr("title").ifBlank { a.text().trim() }.trim()
                ParserInterface.ParsedEpisode(
                    name = epTitle.ifBlank { "Part ${index + 1}" },
                    url = epUrl,
                    season = 1,
                    episode = index + 1
                )
            }
            return ParserInterface.ParsedLoadData(
                title = title,
                plot = plot,
                posterUrl = poster,
                url = url,
                type = TvType.TvSeries,
                year = null,
                episodes = episodes,
                tags = emptyList()
            )
        }

        val data = super.parseLoadPage(doc, url) ?: return null
        if (url.contains("/episodes/", ignoreCase = true) && data.episodes.isNullOrEmpty()) {
            // The episode page itself has no episode grid; its breadcrumb links to the
            // season page that does. BaseProvider swaps to this parent when the document
            // is only one episode.
            val seasonUrl = EgyDeadSeries.seasonUrlFromBreadcrumb(doc)
            if (!seasonUrl.isNullOrBlank()) {
                return data.copy(parentSeriesUrl = seasonUrl)
            }
        }
        return data
    }

    /**
     * Parse episodes from a season document. The season number comes from the season
     * page URL/title when the caller does not already know it, and the episode number
     * comes from the link text or the `sXXeYY` slug.
     */
    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        val episodeLinks = doc.select("div.EpsList > li > a")

        val season = seasonNum
            ?: EgyDeadSeries.seasonNumber(doc.location(), doc.title())
            ?: episodeLinks.firstOrNull()?.let { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                EgyDeadSeries.seasonNumber(href)
            }
            ?: 1

        for (link in episodeLinks) {
            val epUrl = link.absUrl("href").ifBlank { link.attr("href") }.trim()
            if (epUrl.isBlank()) continue

            val epTitle = link.attr("title").trim().ifBlank { link.text().trim() }
            val epNum = EgyDeadSeries.episodeNumber(epUrl, link.text()) ?: 0

            episodes.add(
                ParserInterface.ParsedEpisode(
                    name = epTitle.ifBlank { "Episode $epNum" },
                    url = epUrl,
                    season = season,
                    episode = epNum
                )
            )
        }

        return episodes
            .distinctBy { it.url }
            .sortedBy { it.episode }
    }

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "li[data-link]", attr = "data-link"),
        id = CssSelector(query = "li[data-link]", attr = "class"),
        title = CssSelector(query = "li[data-link]", attr = "text"),
        iframe = CssSelector(query = "iframe[data-src]", attr = "data-src")
    )

    override fun extractWatchServersUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()

        // Current watch layout (desktop + mobile): each server is an <li data-link>.
        for (element in doc.select(
            "ul.serversList > li[data-link], .mob-servers li[data-link], " +
                ".watchAreaMaster li[data-link]"
        )) {
            addServerUrl(element.attr("data-link"), urls)
        }

        // Keep iframe fallbacks for older/mobile layouts, but skip the YouTube trailer
        // and any hidden tracking iframes.
        for (iframe in doc.select("iframe[data-src], iframe[src]")) {
            if (isHiddenIframe(iframe)) continue
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            addServerUrl(src, urls)
        }

        return urls.distinct()
    }

    private fun addServerUrl(rawUrl: String, output: MutableList<String>) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        val absolute = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> return
        }
        if (!isTrailerOrJunk(absolute)) output.add(absolute)
    }

    private fun isTrailerOrJunk(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun isHiddenIframe(iframe: Element): Boolean {
        val style = iframe.attr("style").lowercase()
        if (style.contains("display:none") || style.contains("display: none")) return true
        if (style.contains("width: 0") || style.contains("width:0")) return true
        if (style.contains("height: 0") || style.contains("height:0")) return true
        if (style.contains("-1000px") || style.contains("left:-1000")) return true
        if (iframe.attr("width") == "0" || iframe.attr("height") == "0") return true
        return false
    }

    /**
     * The current site uses three detail URL shapes. The old `/serie/` and `/season/`
     * checks no longer matched `/Series/`, `/seasons/` or `/episodes/`, so every series
     * and season page fell through as a movie.
     */
    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (EgyDeadSeries.isSeriesUrl(url)) return true
        return title.contains("مسلسل") ||
            title.contains("حلقة") ||
            title.contains("حلقه") ||
            title.contains("موسم")
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        val href = doc.selectFirst("a[href*=/watch/]")
            ?.attr("abs:href")
            ?.ifBlank { doc.selectFirst("a[href*=/watch/]")?.attr("href").orEmpty() }
            ?.trim()
        if (!href.isNullOrBlank()) {
            return if (href.startsWith("//")) "https:$href" else href
        }

        // Fallback for a detail page whose watch button is injected by JS.
        val location = doc.location().trim().trimEnd('/')
        if (location.isBlank() || location.contains("/watch", ignoreCase = true)) return null
        return "$location/watch/"
    }

    /**
     * Override parseSearch to filter out individual episode URLs from the legacy
     * `/episode/` layout. Current `/episodes/` results stay visible: the site's search
     * pages are mostly episode cards, and dropping them would make later search pages
     * look empty and stop pagination early.
     */
    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseSearch(doc)
        val filtered = items.filter { !it.url.contains("/episode/") }
        return if (filtered.isNotEmpty()) filtered else items
    }
}
