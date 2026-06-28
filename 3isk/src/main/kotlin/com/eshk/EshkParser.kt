package com.eshk

import com.cloudstream.shared.parsing.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EshkParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/search/$query/"
    }

    override val mainPageConfig = MainPageConfig(
        container = "li.type_item_box a.type_item, li.type_item_wide_box a.type_item_wide",
        title = CssSelector(query = "", attr = "title"),
        url = CssSelector(query = "", attr = "data-clse, href"),
        poster = CssSelector(query = "img", attr = "data-image, src")
    )

    override val searchConfig = MainPageConfig(
        container = "ul.search-page li.type_item_box a.type_item",
        title = CssSelector(query = "", attr = "title"),
        url = CssSelector(query = "", attr = "data-clse, href"),
        poster = CssSelector(query = "img", attr = "data-image, src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.single_info h1.title", attr = "text"),
        poster = CssSelector(query = "div.poster-wrapper img", attr = "src"),
        plot = CssSelector(query = "div.description span[data-nosnippet]", attr = "text"),
        seriesIndicator = CssSelector(query = "div.single_info", attr = "text", regex = "مسلسل"),
        parentSeriesUrl = CssSelector(query = "a.single-serie-btn", attr = "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = "div.season-eps a.ep-num",
        title = CssSelector(query = "", attr = "title"),
        url = CssSelector(query = "", attr = "data-clse, href"),
        episode = CssSelector(query = "", attr = "data-ep-num")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "button.single-watch-btn", attr = "data-action, formaction, action"),
        id = CssSelector(query = "button.single-watch-btn", attr = "data-id, id"),
        title = CssSelector(query = "button.single-watch-btn", attr = "text")
    )

    override fun parseItem(element: Element, config: MainPageConfig): ParserInterface.ParsedItem? {
        val rawTitle = element.extract(config.title) ?: return null
        val rawUrl = element.extract(config.url) ?: return null
        val url = decodeBase64Url(rawUrl) ?: rawUrl
        val title = if (url.contains("/episodes/")) {
            rawTitle.substringBefore(" الحلقة").trim().ifBlank { rawTitle }
        } else rawTitle
        val posterUrl = element.extract(config.poster)
        return ParserInterface.ParsedItem(
            title = title, url = url, posterUrl = posterUrl,
            isMovie = config.isMovie?.invoke(title, url, element) ?: isMovie(title, url, element)
        )
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        doc.select("div.season-eps").forEach { seasonDiv ->
            val sNum = seasonDiv.attr("id").removePrefix("season-num-").toIntOrNull() ?: seasonNum ?: 1
            seasonDiv.select("a.ep-num").forEach { epA ->
                val rawUrl = epA.attr("data-clse").ifBlank { epA.attr("href") }
                if (rawUrl.isBlank()) return@forEach
                val url = if (rawUrl.startsWith("http")) rawUrl
                else decodeBase64Url(rawUrl) ?: epA.attr("href")
                if (url.isBlank()) return@forEach
                val epNum = epA.attr("data-ep-num").toIntOrNull()
                val epName = epA.attr("title").ifBlank { "الحلقة $epNum" }
                episodes.add(ParserInterface.ParsedEpisode(
                    name = epName,
                    url = url,
                    season = sNum,
                    episode = epNum ?: 0
                ))
            }
        }
        return episodes
    }

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val data = super.parseLoadPage(doc, url) ?: return null
        val cleanTitle = data.title.replace("مترجم", "").replace("مدبلج", "").trim()
        return data.copy(title = cleanTitle)
    }

    override fun isMovie(title: String, url: String, element: Element?): Boolean {
        if (isEpisode(title, url, element)) return false
        return url.contains("/movies/")
    }

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        if (isEpisode(title, url, element)) return false
        return !url.contains("/movies/")
    }

    override fun isEpisode(title: String, url: String, element: Element?): Boolean {
        return url.contains("/episodes/") || title.contains("الحلقة")
    }

    companion object {
        fun decodeBase64Url(encodedUrl: String): String? {
            val s = encodedUrl.trim().let {
                val mod = it.length % 4
                if (mod != 0) it + "=".repeat(4 - mod) else it
            }
            val flagsToTry = listOf(
                android.util.Base64.DEFAULT,
                android.util.Base64.NO_WRAP,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            for (flags in flagsToTry) {
                try {
                    val bytes = android.util.Base64.decode(s, flags)
                    return String(bytes, Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                }
            }
            return null
        }

        fun resolveUrl(element: Element): String? {
            val encoded = element.attr("data-clse")
            if (encoded.isNotBlank()) {
                decodeBase64Url(encoded)?.let { return it }
            }
            val href = element.attr("href")
            return href.takeIf { it.isNotBlank() }
        }
    }
}
