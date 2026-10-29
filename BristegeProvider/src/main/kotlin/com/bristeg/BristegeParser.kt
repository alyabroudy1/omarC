package com.bristeg

import com.cloudstream.shared.parsing.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.api.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BristegeParser : NewBaseParser() {

    // Prestige theme (v14, Oct 2026) layouts:
    //  - latest  (#pln-latest)   : article.pln-card
    //  - category(#pcg-category) : ul.pcg-episodes-grid > li (episodes),
    //                              ul.pcg-featured-grid > li, ul.pcg-series-grid > li (series)
    //  - home    (#pgh-home)     : ul.pgh-latest-grid / pgh-category-grid /
    //                              pgh-featured-grid / pgh-series-grid > li
    //  - search  (#prs-results)  : ul.prs-grid > li
    //  - series  (#pds-detail)   : article.pds-episode
    //  - movies  (#pmc-movies)   : .pmc-results-grid article.pmc-card
    //    NOTE: movie cards use RELATIVE img src ("/uploads/...") -> resolved via abs:src.
    override val mainPageConfig = MainPageConfig(
        container = "article.pln-card, ul.pcg-episodes-grid > li, ul.pcg-featured-grid > li, " +
                "ul.pcg-series-grid > li, ul.pgh-latest-grid > li, ul.pgh-category-grid > li, " +
                "ul.pgh-featured-grid > li, ul.pgh-series-grid > li, ul.prs-grid > li, " +
                "article.pds-episode, article.prs-card, " +
                ".pmc-results-grid article, article.pmc-card",
        title = CssSelector(
            "div.pln-caption h3 a, div.caption h2 a, div.caption h3 a, " +
                    "div.prs-caption h3 a, div.pds-caption h4 a, div.pmc-caption h3 a, " +
                    "h3.pgh-simple-title, " +
                    ".pgh-series-name, .pcg-series-name, a.pcg-series-card, a.pgh-series-card",
            "title, text"
        ),
        url = CssSelector(
            "div.pln-caption h3 a, div.caption h2 a, div.caption h3 a, " +
                    "div.prs-caption h3 a, div.pds-caption h4 a, div.pmc-caption h3 a, " +
                    "a.pcg-series-card, a.pgh-series-card, a.pgh-simple-card, " +
                    "a.prs-image, a.pln-picture, a.pds-thumb, a.pmc-photo, a.pmc-detail, " +
                    "a[href*=\"watch.php\"], a[href*=\"series1.php\"], a[href*=\"view-serie.php\"]",
            "abs:href, href"
        ),
        poster = CssSelector("img", "abs:src, abs:data-src, src, data-src, data-echo, data-original")
    )

    /**
     * Search results must be scoped to the results list. The rest of the page contains a
     * site-wide `.catfooter` with links to the same promoted series on every page; scanning
     * the whole document (the old fallback) made every empty search return those series.
     */
    override val searchConfig = MainPageConfig(
        container = "#prs-results ul.prs-grid > li, ul.pm-ul-browse-videos > li",
        title = CssSelector(
            "div.prs-caption h3 a, div.caption h3 a, h3 a",
            "title, text"
        ),
        url = CssSelector(
            "div.prs-caption h3 a, div.caption h3 a, h3 a, a.prs-image",
            "abs:href, href"
        ),
        poster = CssSelector("img", "abs:src, abs:data-src, src, data-src, data-echo, data-original")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(".pwr-heading h1, #pds-title, h1", "text"),
        plot = CssSelector(
            ".pds-description, .pwa-article p, .pwr-description p, " +
                    "meta[name=description], meta[property=og:description]",
            "text, content"
        ),
        poster = CssSelector(
            "meta[property=og:image], .pwu-poster-image, .pds-art img, .pva-poster-picture img",
            "content, src"
        ),
        tags = CssSelector(".pwr-meta dd a, .pds-category, a.pds-category"),
        seriesIndicator = CssSelector(
            ".pwr-seasons, .pds-season, .pwr-season-panel, ul.pwr-episode-grid",
            "text"
        )
    )

    override val episodeConfig = EpisodeConfig(
        container = ".pwr-season-panel ul.pwr-episode-grid li, " +
                "section.pds-season article.pds-episode, " +
                "div.SeasonsEpisodes[data-serie] a",
        url = CssSelector("a[href*=\"watch\"], a[href*=\"series\"]", "abs:href, href"),
        title = CssSelector(
            "div.pds-caption h4 a, a[title]",
            "title, text"
        ),
        episode = CssSelector("strong", "text")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector("div#WatchServers button.watchButton, div#WatchServers button.watchbutton", "data-embed-url, data-embed"),
        iframe = CssSelector("div#Playerholder iframe", "src")
    )

    // ---------- Main / search listing ----------

    override fun parseMainPage(doc: Document): List<ParserInterface.ParsedItem> {
        val viaConfig = super.parseMainPage(doc)
        if (viaConfig.isNotEmpty()) {
            return viaConfig.map {
                it.copy(
                    posterUrl = cleanPosterUrl(resolveUrl(doc.baseUri(), it.posterUrl)),
                    url = resolveUrl(doc.baseUri(), it.url) ?: it.url,
                    isMovie = !isSeries(it.title, it.url, null)
                )
            }.distinctBy { it.url }
        }
        // Fallback: generic link scan, scoped to the page content so footer links cannot leak in.
        return scanListingLinks(doc)
    }

    override fun parseSearch(doc: Document): List<ParserInterface.ParsedItem> {
        val items = super.parseSearch(doc)
        if (items.isEmpty()) {
            // Do not fall back to scanning the whole document: every search page carries a
            // site-wide `.catfooter` with the same promoted series links.
            return emptyList()
        }
        return items.map {
            it.copy(
                posterUrl = cleanPosterUrl(resolveUrl(doc.baseUri(), it.posterUrl)),
                url = resolveUrl(doc.baseUri(), it.url) ?: it.url,
                isMovie = !isSeries(it.title, it.url, null)
            )
        }.distinctBy { it.url }
    }

    override fun hasNextSearchPage(document: Document?): Boolean {
        val doc = document ?: return false
        // An empty result page must never keep the app paging forever.
        val hasResults = doc.select(
            "#prs-results ul.prs-grid > li, ul.pm-ul-browse-videos > li"
        ).isNotEmpty()
        if (!hasResults) return false
        val pagination = doc.selectFirst("#prs-results ul.pagination")
            ?: doc.selectFirst("ul.pagination")
            ?: return false
        val active = pagination.selectFirst("li.active") ?: return false
        val next = active.nextElementSibling() ?: return false
        return !next.hasClass("disabled")
    }

    /**
     * Generic fallback: collect every watch/series link on the page, resolve its
     * title from the closest card/caption, poster from the closest image.
     */
    private fun scanListingLinks(doc: Document): List<ParserInterface.ParsedItem> {
        val seen = linkedMapOf<String, ParserInterface.ParsedItem>()
        val root: Element = doc.selectFirst("main") ?: doc
        for (a in root.select("a[href*=\"watch.php?vid=\"], a[href*=\"series1.php?id=\"], a[href*=\"view-serie.php\"]")) {
            val rawHref = a.attr("abs:href").ifBlank { a.attr("href") }
            if (rawHref.isBlank() || rawHref.startsWith("#")) continue
            if (a.closest(".catfooter") != null) continue
            val url = rawHref.trim()
            if (seen.containsKey(url)) continue

            // Nearest card scope for title/poster lookup
            val scope: Element = a.closest(
                "article, li, div.thumbnail, div.pds-episode, div.pln-caption, div.caption, div.prs-caption, div.pds-caption, div.pmc-caption"
            ) ?: a.parent() ?: continue

            var title = scope.selectFirst(
                "div.pln-caption h3 a, div.caption h2 a, div.caption h3 a, " +
                        "div.prs-caption h3 a, div.pds-caption h4 a, div.pmc-caption h3 a, " +
                        "h3.pgh-simple-title, " +
                        ".pgh-series-name, .pcg-series-name"
            )?.let { it.attr("title").ifBlank { it.text() } }
                ?: a.attr("title").ifBlank { a.text() }
                ?: scope.text()
            title = title.trim()
            if (title.isBlank() || title.length < 2) continue
            // Skip chrome links (pagination / nav)
            if (scope.hasClass("pagination") || a.hasClass("pcg-view-all")) continue

            val poster = scope.selectFirst("img")?.let {
                it.attr("abs:src").ifBlank {
                    it.attr("src").ifBlank {
                        it.attr("data-src").ifBlank { it.attr("data-echo") }
                    }
                }
            }?.trim()

            seen[url] = ParserInterface.ParsedItem(
                title = title,
                url = url,
                posterUrl = cleanPosterUrl(resolveUrl(doc.baseUri(), poster)),
                isMovie = !isSeries(title, url, null)
            )
        }
        return seen.values.toList()
    }

    // ---------- Detail page ----------

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val title = doc.selectFirst(".pwr-heading h1")?.text()?.trim()
            ?: doc.selectFirst("#pds-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title()?.trim()
            ?: return null

        val posterRaw = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst(".pwu-poster-image")?.attr("abs:src")?.ifBlank {
                doc.selectFirst(".pwu-poster-image")?.attr("src")
            }?.trim()
            ?: doc.selectFirst(".pds-art img")?.attr("abs:src")?.ifBlank {
                doc.selectFirst(".pds-art img")?.attr("src")
            }?.trim()
            ?: doc.selectFirst(".pva-poster-picture img")?.attr("abs:src")?.ifBlank {
                doc.selectFirst(".pva-poster-picture img")?.attr("src")
            }?.trim()
            ?: ""
        val poster = resolveUrl(doc.baseUri(), posterRaw) ?: posterRaw

        var plot: String? = doc.selectFirst(".pds-description")?.text()?.trim()
            ?: doc.select(".pwa-article p, .pwr-description p").eachText()
                .map { it.trim() }.filter { it.isNotBlank() && it.length > 20 }
                .joinToString("\n").takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = doc.select(".pwr-meta dd a, a.pds-category, .pds-category")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val episodes = parseEpisodes(doc, null)
        val hasSeasons = doc.selectFirst(".pwr-seasons, .pds-season, ul.pwr-episode-grid") != null ||
                episodes.isNotEmpty()

        val isMovieByTag = tags.any { it.contains("افلام", true) || it.contains("فلم", true) }
        val isMovieByTitle = title.contains("فيلم") || title.contains("فلم")

        val type = when {
            isMovieByTag || isMovieByTitle -> TvType.Movie
            hasSeasons -> TvType.TvSeries
            // watch.php episode pages always carry حلقة/الحلقة in the title
            title.contains("حلقة") || title.contains("الحلقة") -> TvType.TvSeries
            url.contains("series1.php") || url.contains("view-serie.php") -> TvType.TvSeries
            else -> TvType.Movie
        }

        // Parent series link (episode -> series overview), used by BaseProvider fallback
        val parentSeriesUrl = doc.selectFirst(
            ".pwr-breadcrumb a[href*=\"series1.php\"], .pwr-breadcrumb a[href*=\"view-serie\"], " +
                    ".pwr-meta dd a[href*=\"series\"], .pds-category[href*=\"cat\"]"
        )?.let {
            val href = it.attr("abs:href").ifBlank { it.attr("href") }
            href.takeIf { h -> h.contains("series1.php") || h.contains("view-serie.php") }
        }

        return ParserInterface.ParsedLoadData(
            title = title,
            plot = plot,
            posterUrl = cleanPosterUrl(poster) ?: "",
            url = url,
            type = type,
            year = null,
            episodes = episodes.distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode })),
            tags = tags,
            parentSeriesUrl = parentSeriesUrl
        )
    }

    // ---------- Episodes ----------

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()

        // 1. Watch page: prestige seasons (.pwr-season-panel > ul.pwr-episode-grid)
        val panels = doc.select(".pwr-season-panel")
        panels.forEachIndexed { index, panel ->
            val season = extractSeasonNumber(
                panel.id().ifBlank { panel.selectFirst(".pwr-season-fallback")?.text() },
                doc.select(".pwr-season-tab").getOrNull(index)?.text()
            ) ?: (seasonNum ?: (index + 1))
            for (a in panel.select("ul.pwr-episode-grid li a")) {
                val epUrl = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (epUrl.isBlank()) continue
                val epName = a.attr("title").ifBlank { a.text().trim() }.trim()
                    .ifBlank { "الحلقة" }
                val epNum = a.selectFirst("strong")?.text()?.trim()?.toIntOrNull()
                    ?: Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = epName.ifBlank { "الحلقة $epNum" },
                        url = epUrl,
                        season = season,
                        episode = epNum
                    )
                )
            }
        }
        if (episodes.isNotEmpty()) return episodes

        // 2. Series overview page (#pds-detail > section.pds-season > article.pds-episode)
        val seasons = doc.select("section.pds-season")
        seasons.forEachIndexed { index, sec ->
            val season = extractSeasonNumber(sec.id(), sec.selectFirst(".pds-season-heading h3")?.text())
                ?: (seasonNum ?: (index + 1))
            for (ep in sec.select("article.pds-episode")) {
                val link = ep.selectFirst("div.pds-caption h4 a")
                    ?: ep.selectFirst("a[href*=\"watch.php\"]")
                    ?: continue
                val epUrl = link.attr("abs:href").ifBlank { link.attr("href") }.trim()
                if (epUrl.isBlank()) continue
                val epName = link.attr("title").ifBlank {
                    link.attr("aria-label").ifBlank { link.text() }
                }.trim()
                val epNum = Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = epName.ifBlank { "الحلقة $epNum" },
                        url = epUrl,
                        season = season,
                        episode = epNum
                    )
                )
            }
        }
        if (episodes.isNotEmpty()) return episodes

        // 3. LD+JSON embedded episode list (series pages carry a full TVSeries graph)
        episodes.addAll(parseLdJsonEpisodes(doc))
        if (episodes.isNotEmpty()) return episodes

        // 4. Legacy layout fallback
        for (seasonLi in doc.select("div.SeasonsBoxUL ul li")) {
            val seasonId = seasonLi.attr("data-serie")
            val season = seasonId.toIntOrNull() ?: (seasonNum ?: 1)
            for (epEl in doc.select("div.SeasonsEpisodes[data-serie='$seasonId'] a")) {
                val epUrl = epEl.attr("abs:href").ifBlank { epEl.attr("href") }
                if (epUrl.isBlank()) continue
                val epName = epEl.attr("title").ifBlank { epEl.text() }.trim()
                val epNum = epEl.selectFirst("em")?.text()?.toIntOrNull()
                    ?: Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                episodes.add(
                    ParserInterface.ParsedEpisode(
                        name = epName.ifBlank { "الحلقة $epNum" },
                        url = epUrl,
                        season = season,
                        episode = epNum
                    )
                )
            }
        }
        return episodes
    }

    private fun extractSeasonNumber(vararg candidates: String?): Int? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            // Arabic label "الموسم 1" or ids like pwr-season-0 / pds-season-1
            Regex("(\\d+)").find(c)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
                if (c.contains("pwr-season-") && !c.contains("pds-season-")) {
                    // pwr panels are 0-based (pwr-season-0 == season 1)
                    return n + 1
                }
                return n
            }
        }
        return null
    }

    private fun parseLdJsonEpisodes(doc: Document): List<ParserInterface.ParsedEpisode> {
        val out = mutableListOf<ParserInterface.ParsedEpisode>()
        try {
            for (script in doc.select("script[type=application/ld+json]")) {
                val json = script.data().ifBlank { script.html() }
                if (!json.contains("TVEpisode")) continue
                // Minimal regex parse (avoid adding a JSON dependency to the parser)
                val epRegex = Regex(
                    "\\{[^{}]*?\"@type\"\\s*:\\s*\"TVEpisode\"[^{}]*?\\}",
                    RegexOption.DOT_MATCHES_ALL
                )
                val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                val urlRegex = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                val numRegex = Regex("\"episodeNumber\"\\s*:\\s*\"?(\\d+)\"?")
                for (m in epRegex.findAll(json)) {
                    val chunk = m.value
                    val epUrl = urlRegex.find(chunk)?.groupValues?.get(1)?.trim() ?: continue
                    val epName = nameRegex.find(chunk)?.groupValues?.get(1)?.trim() ?: "الحلقة"
                    val epNum = numRegex.find(chunk)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                    out.add(
                        ParserInterface.ParsedEpisode(
                            name = epName,
                            url = epUrl,
                            season = 1,
                            episode = epNum
                        )
                    )
                }
                if (out.isNotEmpty()) break
            }
        } catch (e: Exception) {
            Log.w("BristegeParser", "LD+JSON parse failed: ${e.message}")
        }
        return out
    }

    override fun getSearchUrl(mainUrl: String, query: String): String {
        return "$mainUrl/search.php?keywords=$query"
    }

    // Same site engine as Laroza (search.php?keywords=): page links follow "&page=N".
    override val searchPaginationFormat: String get() = "&page=%d"

    override fun getPlayerPageUrl(doc: Document): String? {
        return doc.selectFirst("a.xtgo")?.attr("href")?.trim()
            ?: doc.selectFirst("a[href*=\"play.php?vid=\"]")?.attr("href")?.trim()
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (title.contains("فيلم") || title.contains("فلم")) return false
        if (url.contains("series1.php") || url.contains("view-serie.php") || url.contains("season")) return true
        return super.isSeries(title, url, element)
    }

    private fun cleanPosterUrl(url: String?): String? {
        if (url == null || url.isBlank()) return null
        var u = url.trim()

        val wpRegex = Regex("https?://i\\d+\\.wp\\.com/(.+)")
        wpRegex.find(u)?.let {
            u = it.groupValues[1]
            if (!u.startsWith("http")) u = "https://$u"
        }

        u = u.substringBefore("?")
        if (u.startsWith("//")) u = "https:$u"

        return u
    }

    /**
     * Resolves a possibly-relative URL against the document's base URI.
     * Movie cards (#pmc-movies) use root-relative posters ("/uploads/...").
     */
    private fun resolveUrl(baseUri: String?, url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        if (u.startsWith("http") || u.startsWith("//")) return u
        if (baseUri.isNullOrBlank()) return u
        return try {
            java.net.URL(java.net.URL(baseUri), u).toString()
        } catch (e: Exception) {
            u
        }
    }
}
