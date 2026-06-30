package com.eseek

import com.lagradost.api.Log
import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.parsing.WatchServerSelector
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GessehParser : NewBaseParser() {

    companion object {
        private const val TAG = "GessehParser"
    }

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/?s=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = "article.post, article.postEp",
        title = CssSelector(query = "div.title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        poster = CssSelector(
            query = "div.imgBg, div.imgSer, img",
            attr = "style, data-src, data-lazy-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        ),
        isMovie = { _, url, _ -> url.contains("/movies/") }
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "div.info h1", attr = "text"),
        poster = CssSelector(
            query = "div.cover div.img, div.cover img",
            attr = "style, data-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        ),
        plot = CssSelector(query = "div.story", attr = "text"),
        seriesIndicator = CssSelector(query = "article.postEp", attr = "text"),
        parentSeriesUrl = CssSelector(query = "div.singleSeries div.info h1 a", attr = "href")
    )

    override val episodeConfig = EpisodeConfig(
        container = "article.postEp",
        title = CssSelector(query = "div.title", attr = "text"),
        url = CssSelector(query = "a", attr = "href"),
        episode = CssSelector(query = "div.episodeNum span:last-child", attr = "text"),
        poster = CssSelector(
            query = "div.imgSer, img",
            attr = "style, data-src, src",
            regex = """url\(['"]?([^'")]+)['"]?\)"""
        )
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "a.fullscreen-clickable", attr = "href")
    )

    override fun isSeries(title: String, url: String, element: Element?): Boolean {
        return !url.contains("/movies/")
    }

    override fun getPlayerPageUrl(doc: Document): String? {
        return null
    }

    // ── Debug overrides ──

    override fun parseLoadPage(doc: Document, url: String): ParserInterface.ParsedLoadData? {
        val config = loadPageConfig

        Log.d(TAG, "=== parseLoadPage ===")
        Log.d(TAG, "url=$url")
        Log.d(TAG, "poster selector query=\"${config.poster?.query}\" attr=\"${config.poster?.attr}\" regex=\"${config.poster?.regex}\"")

        if (config.poster != null) {
            for (q in config.poster.query.split(",").map { it.trim() }) {
                val el = doc.selectFirst(q)
                if (el != null) {
                    Log.d(TAG, "  poster query \"$q\" MATCHED -> outerHtml: ${el.outerHtml().take(1500)}")
                } else {
                    Log.w(TAG, "  poster query \"$q\" NO MATCH")
                }
            }
        }

        val data = super.parseLoadPage(doc, url)

        Log.d(TAG, "  parsed posterUrl=\"${data?.posterUrl}\"")
        if (data?.posterUrl.isNullOrBlank()) {
            Log.w(TAG, "  posterUrl is blank! Dumping <div.cover> area:")
            val cover = doc.selectFirst("div.cover")
            if (cover != null) {
                Log.d(TAG, "  div.cover outerHtml: ${cover.outerHtml().take(2000)}")
            } else {
                Log.w(TAG, "  no div.cover found")
                Log.d(TAG, "  body snippet: ${doc.body()?.html()?.take(2000)}")
            }
        }

        return data
    }

    override fun parseEpisodes(doc: Document, seasonNum: Int?): List<ParserInterface.ParsedEpisode> {
        val config = episodeConfig

        Log.d(TAG, "=== parseEpisodes ===")
        Log.d(TAG, "episode container=\"${config.container}\"")
        Log.d(TAG, "poster selector query=\"${config.poster?.query}\" attr=\"${config.poster?.attr}\" regex=\"${config.poster?.regex}\"")

        val containers = doc.select(config.container)
        Log.d(TAG, "found ${containers.size} episode containers")

        containers.forEachIndexed { i, el ->
            val epTitle = el.extract(config.title) ?: "?"
            val epUrl = el.extract(config.url)
            Log.d(TAG, "  episode[$i] title=\"$epTitle\" url=\"$epUrl\"")
            Log.d(TAG, "  episode[$i] outerHtml: ${el.outerHtml().take(1000)}")

            if (config.poster != null) {
                for (q in config.poster.query.split(",").map { it.trim() }) {
                    val img = el.selectFirst(q)
                    if (img != null) {
                        Log.d(TAG, "  episode[$i] poster query \"$q\" MATCHED -> outerHtml: ${img.outerHtml().take(800)}")
                    } else {
                        Log.w(TAG, "  episode[$i] poster query \"$q\" NO MATCH")
                    }
                }
            }
        }

        val episodes = super.parseEpisodes(doc, seasonNum)

        episodes.forEachIndexed { i, ep ->
            Log.d(TAG, "  parsed episode[$i] name=\"${ep.name}\" url=\"${ep.url}\" posterUrl=\"${ep.posterUrl}\"")
        }

        return episodes
    }
}
