package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder

class EgyDead : BaseProvider() {

    override val providerName get() = "EgyDead"
    override val baseDomain get() = "tv10.egydead.live"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/egydead.json"

    override val mainPage = mainPageOf(
        "/page/movies/" to "افلام اجنبي",
        "/category/افلام-عربي/" to "افلام عربي",
        "/type/comedy/" to "كوميديا",
        "/Series/" to "مسلسلات",
        "/category/افلام-كرتون/افلام-كرتون-ديزني-باللهجة-المصرية/" to "افلام كرتون",
        "/series-category/tv-shows/" to "برامج تلفزيونية"
    )

    override fun getParser(): NewBaseParser {
        return EgyDeadParser()
    }

    /**
     * Arabic season names matching the site labels.
     */
    override fun getSeasonName(seasonNum: Int): String = "الموسم $seasonNum"

    /**
     * The site now has three layers:
     *  - `/Series/<slug>/` is the parent series page; its `.seasons-list` contains the
     *    real seasons (not related titles).
     *  - `/seasons/<slug>/` is one season and carries the episodes in `.EpsList`.
     *  - `/episodes/<slug>/` is one episode.
     *
     * The old implementation treated `.seasons-list` on every page as the child-season
     * list. On a season page that list is actually "related seasons", so it mixed other
     * shows/seasons into the current one. Branch by URL instead.
     */
    override suspend fun fetchExtraEpisodes(
        doc: Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        if (!data.episodes.isNullOrEmpty()) return data.episodes

        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("/series/") -> fetchSeriesSeasons(doc)
            lowerUrl.contains("/seasons/") -> {
                val season = EgyDeadSeries.seasonNumber(url, doc.title()) ?: 1
                getParser().parseEpisodes(doc, season)
            }
            lowerUrl.contains("/episodes/") -> fetchEpisodeSeason(doc, url)
            else -> emptyList()
        }
    }

    /**
     * Parent `/Series/` page: fetch every real season page and collect its episodes.
     * The season cards are rendered newest-first (s05..s01), so fall back to a reverse
     * index only when neither the slug nor the title exposes a season number.
     */
    private suspend fun fetchSeriesSeasons(doc: Document): List<ParserInterface.ParsedEpisode> {
        val seasonLinks = doc.select("div.seasons-list ul > li > a")
        if (seasonLinks.isEmpty()) return emptyList()

        val allEpisodes = mutableListOf<ParserInterface.ParsedEpisode>()
        for ((index, link) in seasonLinks.withIndex()) {
            val seasonUrl = link.absUrl("href").ifBlank { link.attr("href") }.trim()
            if (seasonUrl.isBlank()) continue

            val seasonTitle = link.attr("title")
                .ifBlank { link.selectFirst("h1.BottomTitle")?.text().orEmpty() }
                .trim()
            val seasonNumber = EgyDeadSeries.seasonNumber(seasonUrl, seasonTitle)
                ?: (seasonLinks.size - index)

            try {
                val seasonDoc = httpService.getDocument(seasonUrl, rewriteDomain = true) ?: continue
                val episodes = getParser().parseEpisodes(seasonDoc, seasonNumber)
                Log.d("EgyDead", "Season $seasonNumber ($seasonUrl): ${episodes.size} episodes")
                allEpisodes.addAll(episodes.map { it.copy(season = seasonNumber) })
            } catch (e: Exception) {
                Log.e("EgyDead", "Failed to fetch season $seasonNumber: ${e.message}")
            }
        }

        return allEpisodes
            .distinctBy { it.url }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * Episode page fallback: resolve its season from the breadcrumb and load that
     * season's episode list. BaseProvider normally does this through
     * `parentSeriesUrl`; this keeps the provider usable if that swap is skipped.
     */
    private suspend fun fetchEpisodeSeason(
        doc: Document,
        url: String
    ): List<ParserInterface.ParsedEpisode> {
        val seasonUrl = EgyDeadSeries.seasonUrlFromBreadcrumb(doc)
        if (!seasonUrl.isNullOrBlank()) {
            try {
                val seasonDoc = httpService.getDocument(seasonUrl, rewriteDomain = true)
                if (seasonDoc != null) {
                    val season = EgyDeadSeries.seasonNumber(seasonUrl, seasonDoc.title()) ?: 1
                    return getParser().parseEpisodes(seasonDoc, season)
                }
            } catch (e: Exception) {
                Log.e("EgyDead", "Failed to fetch season from episode page: ${e.message}")
            }
        }

        // Last resort: expose the current episode as a single-item season.
        val season = EgyDeadSeries.seasonNumber(url, doc.title()) ?: 1
        val episode = EgyDeadSeries.episodeNumber(url, doc.selectFirst("div.singleTitle")?.text().orEmpty())
            ?: 1
        val title = doc.selectFirst("div.singleTitle em")?.text()?.trim()
            .orEmpty()
            .ifBlank { "Episode $episode" }
        return listOf(
            ParserInterface.ParsedEpisode(
                name = title,
                url = url,
                season = season,
                episode = episode
            )
        )
    }

    /**
     * MegaMax-style `/iframe/<hash>` players fan out into per-quality mirror pages.
     * Returning all of them lets BaseProvider run each mirror through the shared
     * extractors instead of treating the iframe URL itself as a playable source.
     */
    override suspend fun resolveServerUrls(url: String, referer: String): List<String> {
        if (!isMegamaxIframe(url)) return listOf(url)
        val mirrors = resolveMegamaxMirrors(url, referer)
        Log.d("EgyDead", "MegaMax ${url.take(60)} -> ${mirrors.size} mirrors")
        return mirrors.map { it.first }.ifEmpty { listOf(url) }
    }

    /**
     * MegaMax-style mirror player: https://<mirror-host>/iframe/<hash>
     * (legacy file hosts like vidhide/doodstream go through their own shared extractors).
     */
    private fun isMegamaxIframe(url: String): Boolean {
        if (!url.contains("/iframe/")) return false
        val lower = url.lowercase()
        val legacy = listOf(
            "vidhide", "dood", "streamtape", "vidguard", "uptostream", "trgsfjll",
            "ok.ru", "vk.com", "mail.ru", "mp4upload", "filemoon", "voe", "mixdrop",
            "streamwish", "uqload", "doodstream"
        )
        if (legacy.any { lower.contains(it) }) return false
        return true
    }

    /**
     * Resolves a MegaMax mirror iframe to per-quality embed mirror links.
     *
     * The player is an Inertia.js app: the iframe HTML only carries a version + video
     * hash, the actual mirrors come from a partial-reload GET
     * (props.streams.data[].mirrors[].link). Same backend as TukTukcima/Animerco.
     */
    private suspend fun resolveMegamaxMirrors(
        iframeUrl: String,
        referer: String
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val reqHeaders = mapOf(
                "User-Agent" to httpService.userAgent,
                "Referer" to referer
            )

            // 1. Initial page: Inertia version + mirror cookies (XSRF)
            val initialRes = httpService.getRaw(iframeUrl, headers = reqHeaders)
            val setCookies = initialRes.headers("Set-Cookie")
            val xsrf = setCookies.find { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            val cookieHeader = setCookies.joinToString("; ") { it.substringBefore(";") }
            val initialText = try {
                initialRes.body?.string() ?: ""
            } catch (_: Exception) {
                ""
            } finally {
                try { initialRes.close() } catch (_: Exception) {}
            }

            val version = Regex("\"version\":\"([^\"]+)\"").find(initialText)?.groupValues?.get(1)
            if (version.isNullOrBlank()) {
                Log.w("EgyDead", "MegaMax: no Inertia version in $iframeUrl")
                return emptyList()
            }

            // 2. Partial reload asking only for the streams prop
            val inertiaHeaders = mutableMapOf(
                "X-Inertia" to "true",
                "X-Inertia-Version" to version,
                "X-Inertia-Partial-Component" to "files/mirror/video",
                "X-Inertia-Partial-Data" to "streams",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to iframeUrl,
                "Accept" to "application/json"
            )
            if (!xsrf.isNullOrBlank()) inertiaHeaders["X-XSRF-TOKEN"] = xsrf
            if (cookieHeader.isNotBlank()) inertiaHeaders["Cookie"] = cookieHeader

            val jsonRes = httpService.getRaw(iframeUrl, headers = inertiaHeaders)
            val jsonText = try {
                jsonRes.body?.string() ?: ""
            } catch (_: Exception) {
                ""
            } finally {
                try { jsonRes.close() } catch (_: Exception) {}
            }
            if (jsonText.isBlank() || !jsonText.trimStart().startsWith("{")) {
                Log.w("EgyDead", "MegaMax: unexpected streams response (${jsonText.take(80)})")
                return emptyList()
            }

            val data = JSONObject(jsonText).optJSONObject("props")
                ?.optJSONObject("streams")?.optJSONArray("data")
            if (data == null) {
                Log.w("EgyDead", "MegaMax: no streams.data in response")
                return emptyList()
            }

            for (i in 0 until data.length()) {
                val quality = data.optJSONObject(i) ?: continue
                val label = quality.optString("label", "")
                val mirrors = quality.optJSONArray("mirrors") ?: continue
                for (j in 0 until mirrors.length()) {
                    val link = mirrors.optJSONObject(j)?.optString("link")?.trim().orEmpty()
                    if (link.isBlank()) continue
                    val finalLink = if (link.startsWith("//")) "https:$link" else link
                    if (!finalLink.startsWith("http")) continue
                    out.add(finalLink to label)
                }
            }
        } catch (e: Exception) {
            Log.e("EgyDead", "resolveMegamaxMirrors failed: ${e.message}")
        }
        return out.distinctBy { it.first }
    }
}
