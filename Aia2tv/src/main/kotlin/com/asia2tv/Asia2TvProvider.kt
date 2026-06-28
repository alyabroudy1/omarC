package com.asia2tv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import org.jsoup.Jsoup
import org.json.JSONObject

class Asia2TvProvider : BaseProvider() {
    override val baseDomain get() = "asia2tv.com"
    override val providerName get() = "Asia2Tv"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/asia2tv.json"

    override val mainPage = mainPageOf(
        "$mainUrl/series?page=1" to "الرئيسية"
    )

    override fun getParser(): NewBaseParser {
        return Asia2TvParser()
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()
        val pageUrl = url.substringBefore("#")

        val doc = httpService.getDocument(pageUrl, rewriteDomain = true) ?: return null
        val title = doc.selectFirst("h1.mb-0")?.text()?.trim() ?: "Unknown"
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        val posterImg = doc.selectFirst("div.single-thumb-bg img")
        val poster = posterImg?.attr("data-src")?.ifBlank { posterImg.attr("src") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("p.mb-3")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tags = doc.select("div.post_tags a").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
        val year = doc.select("ul.mb-2 li:contains(سنة العرض) a").firstOrNull()?.text()?.toIntOrNull()

        val allEpisodesElements = doc.select("div.loop-episode a").toMutableList()
        val serieId = Regex("""serieid=(\d+)""").find(doc.html())?.groupValues?.get(1)

        if (!csrfToken.isNullOrBlank() && !serieId.isNullOrBlank() && doc.selectFirst("a.more-episode") != null) {
            val ajaxHeaders = mapOf(
                "X-CSRF-TOKEN" to csrfToken,
                "X-XSRF-TOKEN" to csrfToken,
                "X-Requested-With" to "XMLHttpRequest"
            )
            var page = 2
            var hasMore = true
            while (hasMore) {
                try {
                    val responseText = httpService.postText(
                        "$mainUrl/ajaxGetRequest",
                        data = mapOf("action" to "moreepisode", "page" to page.toString(), "serieid" to serieId),
                        referer = pageUrl,
                        headers = ajaxHeaders
                    )
                    if (!responseText.isNullOrBlank()) {
                        val json = JSONObject(responseText)
                        if (json.optBoolean("status", false)) {
                            val html = json.optString("html")
                            if (html.isNotBlank()) {
                                val newEpisodes = Jsoup.parseBodyFragment(html).select("a")
                                allEpisodesElements.addAll(newEpisodes)
                                page = json.optInt("newpage", page + 1)
                                hasMore = json.optBoolean("showmore", false)
                            } else {
                                hasMore = false
                            }
                        } else {
                            hasMore = false
                        }
                    } else {
                        hasMore = false
                    }
                } catch (e: Exception) {
                    Log.w("Asia2Tv", "AJAX episode pagination failed: ${e.message}")
                    hasMore = false
                }
            }
        }

        val isMovie = url.contains("/movie/")
        if (isMovie || allEpisodesElements.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = allEpisodesElements.mapNotNull { el ->
                val href = el.attr("href")
                val name = el.selectFirst("div.titlepisode")?.ownText()?.trim()
                if (name.isNullOrBlank()) return@mapNotNull null
                val epNum = name.filter { it.isDigit() }.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        httpService.ensureInitialized()

        val doc = httpService.getDocument(data, rewriteDomain = true) ?: return false
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false

        val serverElements = doc.select("li.getplay")
        if (serverElements.isEmpty()) return false

        val ajaxHeaders = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-XSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest"
        )

        for (serverLiElement in serverElements) {
            val serverElement = serverLiElement.selectFirst("a") ?: continue
            val serverCode = serverElement.attr("data-code")
            if (serverCode.isBlank()) continue

            try {
                val responseText = httpService.postText(
                    "$mainUrl/ajaxGetRequest",
                    data = mapOf("action" to "iframe_server", "code" to serverCode),
                    referer = data,
                    headers = ajaxHeaders
                )
                if (!responseText.isNullOrBlank()) {
                    val json = JSONObject(responseText)
                    if (json.optBoolean("status", false)) {
                        val codeplay = json.optString("codeplay")
                        if (codeplay.isNotBlank()) {
                            val iframeSrc = Jsoup.parseBodyFragment(codeplay).selectFirst("iframe")?.attr("src")
                            if (!iframeSrc.isNullOrBlank()) {
                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("Asia2Tv", "Server extraction failed: ${e.message}")
            }
        }

        return true
    }
}
