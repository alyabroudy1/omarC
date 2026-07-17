package com.cimawbas

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Cimawbas : BaseProvider() {
    override val baseDomain get() = "vid.mycima.cc"
    override val providerName get() = "Cimawbas"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/cimawbas.json"
    override val paginationFormat get() = "?&page=%d"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "/" to "الرئيسية",
        "/movies.php" to "أفلام",
        "/all-series.php" to "مسلسلات",
    )

    override fun getParser(): NewBaseParser = CimawbasParser()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedRegex = Regex("""src=['"]([^'"]+)['"]""")
        val vidRegex = Regex("""[?&]vid=([^&]+)""")

        suspend fun extractServers(doc: org.jsoup.nodes.Document): Boolean {
            var found = false

            val selectors = listOf(
                "ul.list_servers.list_embedded li[data-embed]",
                "ul.WatchServersList li[data-watch]",
                "ul#watch li[data-watch]",
                "ul.list_servers li[data-embed]",
                ".WatchServersList li",
                ".Links--Content ul li",
                ".serverList li"
            )

            for (selector in selectors) {
                val servers = doc.select(selector)
                if (servers.isEmpty()) continue
                for (server in servers) {
                    val embedRaw = server.attr("data-embed")
                        .ifEmpty { server.attr("data-watch") }
                        .ifEmpty { server.attr("data-url") }
                    val embedUrl = if (!embedRaw.isNullOrBlank()) {
                        val m = embedRegex.find(embedRaw)
                        if (m != null) m.groupValues[1] else embedRaw
                    } else {
                        server.selectFirst("a")?.attr("href") ?: ""
                    }
                    if (!embedUrl.isNullOrBlank()) {
                        found = true
                        loadExtractor(embedUrl, subtitleCallback, callback)
                    }
                }
                if (found) return true
            }
            return false
        }

        val doc = httpService.getDocument(data, rewriteDomain = true) ?: return false
        if (extractServers(doc)) return true

        val playLink = doc.selectFirst("a[href*='play.php'], a.play-video, a.btn-watch")
        if (playLink != null) {
            val href = playLink.attr("href")
            if (!href.isNullOrBlank()) {
                val playDoc = httpService.getDocument(href, rewriteDomain = true)
                if (playDoc != null && extractServers(playDoc)) return true
            }
        }

        val vidMatch = vidRegex.find(data)
        if (vidMatch != null) {
            val vid = vidMatch.groupValues[1]
            val playUrl = "https://${baseDomain}/play.php?vid=$vid"
            val playDoc = httpService.getDocument(playUrl, rewriteDomain = true)
            if (playDoc != null && extractServers(playDoc)) return true
        }

        return false
    }
}
