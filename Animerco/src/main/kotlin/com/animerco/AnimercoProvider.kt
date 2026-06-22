package com.animerco

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.shared.extractors.MegaboxResolver
import com.cloudstream.shared.extractors.MailruExtractor
import com.cloudstream.shared.extractors.VideaExtractor
import com.cloudstream.shared.provider.BaseProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup

class AnimercoProvider : BaseProvider() {
    override val providerName = "Animerco"
    override var name: String
        get() = providerName
        set(value) {}
    override val baseDomain = "animerco.org"
    override val githubConfigUrl = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/animerco.json"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override fun getParser() = AnimercoParser()

    private fun posterUrl(el: org.jsoup.nodes.Element?, selector: String): String? {
        val img = el?.selectFirst(selector)
        return img?.attr("data-src")?.ifBlank { img.attr("src") }?.ifBlank { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = httpService.getDocument(mainUrl) ?: return null
        val items = ArrayList<HomePageList>()

        fun toSearchResponse(element: org.jsoup.nodes.Element): AnimeSearchResponse? {
            val link = element.selectFirst("a")?.attr("href") ?: return null
            val title = element.selectFirst("div.info a h3")?.text() ?: return null
            return newAnimeSearchResponse(title, link) {
                posterUrl = posterUrl(element, "a.image")
                year = element.selectFirst("span.anime-aired")?.text()?.toIntOrNull()
            }
        }

        document.select("div.featured-slider div.anime-card").mapNotNull { toSearchResponse(it) }
            .let { if (it.isNotEmpty()) items.add(HomePageList("أنميات مميزة", it)) }
        document.select("div.media-section:contains(آخر الحلقات المضافة) div.episode-card").mapNotNull {
            val animeTitle = it.selectFirst("div.info a h3")?.text() ?: return@mapNotNull null
            newAnimeSearchResponse("$animeTitle - ${it.selectFirst("a.episode span")?.text()}", it.selectFirst("a.image")?.attr("href") ?: return@mapNotNull null) {
                posterUrl = posterUrl(it, "a.image"); type = TvType.Anime
            }
        }.let { if (it.isNotEmpty()) items.add(HomePageList("آخر الحلقات المضافة", it)) }
        document.select("div.media-section:contains(آخر الأنميات المضافة) div.anime-card").mapNotNull { toSearchResponse(it) }
            .let { if (it.isNotEmpty()) items.add(HomePageList("آخر الأنميات المضافة", it)) }
        document.select("div.media-section:contains(آخر الأفلام المضافة) div.anime-card").mapNotNull { toSearchResponse(it) }
            .let { if (it.isNotEmpty()) items.add(HomePageList("آخر الأفلام المضافة", it)) }

        return newHomePageResponse(items)
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        return searchImpl(query, useNoFallback = false)
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchImpl(query, useNoFallback = true)
    }

    private suspend fun searchImpl(query: String, useNoFallback: Boolean): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenHrefs = mutableSetOf<String>()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
            try {
                val doc = if (useNoFallback) httpService.getDocumentNoFallback(url) else httpService.getDocument(url)
                doc ?: break
                val cards = doc.select("div.search-card")
                if (cards.isEmpty()) break
                cards.forEach { item ->
                    val href = item.selectFirst("a.image")?.attr("href") ?: return@forEach
                    if (href.isBlank() || seenHrefs.contains(href)) return@forEach
                    val poster = posterUrl(item, "a.image")
                    results.add(newAnimeSearchResponse(
                        item.selectFirst("div.info h3")?.text()?.trim() ?: return@forEach, href
                    ) { posterUrl = poster?.ifBlank { null } })
                    seenHrefs.add(href)
                }
            } catch (e: Exception) { if (useNoFallback) throw e else continue }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        fun normalizeUrl(u: String?): String? {
            if (u.isNullOrBlank()) return null
            return when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("http://") -> u.replaceFirst("http://", "https://")
                u.startsWith("http") -> u
                u.startsWith("/") -> mainUrl.trimEnd('/') + u
                else -> if (u.startsWith("www.")) "https:$u" else mainUrl.trimEnd('/') + "/" + u.trimStart('/')
            }
        }

        suspend fun fetchEpisodesFromDoc(doc: org.jsoup.nodes.Document): List<Episode> {
            return doc.select("ul.episodes-lists#filter li").mapNotNull { el ->
                val link = el.selectFirst("a.title") ?: return@mapNotNull null
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                val epTitle = link.selectFirst("h3")?.text()?.trim() ?: link.text().trim()
                newEpisode(epUrl) {
                    name = epTitle
                    posterUrl = posterUrl(el, "a.image")
                    episode = el.attr("data-number").toIntOrNull() ?: Regex("""\d+""").find(epTitle)?.value?.toIntOrNull() ?: 1
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        try {
            val doc = httpService.getDocument(url) ?: return null
            val title = doc.selectFirst("div.media-title h1")?.text()?.trim().orEmpty()
            val poster = posterUrl(doc, "div.anime-card div.image")
            val plot = doc.selectFirst("div.media-story div.content p")?.text()
            val tags = doc.select("div.genres a").map { it.text() }
            val year = doc.select("ul.media-info li:contains(بداية العرض) a").text().toIntOrNull()
            val rating = doc.selectFirst("div.votes span.score")?.text()?.trim()?.toFloatOrNull()

            val typeText = doc.select("div.media-info li:contains(النوع) span").text()
            val isMovie = typeText.contains("Movie", true) || typeText.contains("film", true) ||
                    url.contains("/movies/", true) || url.contains("/movie/", true)

            if (isMovie) {
                return newTvSeriesLoadResponse(title, url, TvType.AnimeMovie, listOf(
                    newEpisode(url) { name = title; posterUrl = poster; episode = 1; season = 1 }
                )) { this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year; score = rating?.let { Score.from10(it) } }
            }

            val seasonNodes = doc.select("div.media-seasons ul.episodes-lists li")
            if (seasonNodes.isEmpty()) {
                val parentHref = listOf(
                    "a.btn.seasons", "a.seasons", "a:contains(المواسم)",
                    "div.page-controls a.btn.seasons", "div.page-controls a.seasons",
                    "a[href*='/animes/']", "a[href*='/anime/']", "a[href*='season']",
                    "div.breadcrumb a", "a[title*='المواسم']"
                ).firstNotNullOfOrNull { sel -> try { doc.selectFirst(sel)?.attr("href")?.takeIf { it.isNotBlank() } } catch (_: Exception) { null } }

                val parentUrl = normalizeUrl(parentHref)
                if (!parentUrl.isNullOrBlank() && parentUrl != url) return load(parentUrl)

                val epTitle = doc.selectFirst("div.media-title h1")?.text()?.trim() ?: title
                val epNumber = doc.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                    ?: doc.selectFirst("span.episode-number")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                return newTvSeriesLoadResponse(title, url, TvType.Anime, listOf(
                    newEpisode(url) { name = epTitle; posterUrl = poster; episode = epNumber ?: 1; season = 1 }
                )) { this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year; score = rating?.let { Score.from10(it) } }
            }

            val episodes = mutableListOf<Episode>()
            seasonNodes.forEach { season ->
                val seasonUrl = normalizeUrl(season.selectFirst("a.title")?.attr("href") ?: return@forEach) ?: return@forEach
                try {
                    val seasonDoc = httpService.getDocument(seasonUrl)
                    val seasonNum = seasonDoc?.selectFirst("div.media-title h1")?.text()?.trim()
                        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                    fetchEpisodesFromDoc(seasonDoc ?: return@forEach).forEach { ep -> ep.season = seasonNum ?: 1; episodes.add(ep) }
                } catch (_: Exception) {}
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode ?: 0 }) {
                this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year; score = rating?.let { Score.from10(it) }
            }
        } catch (_: Exception) { return null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        suspend fun ensureHttpsRaw(link: String?, base: String? = null): String? {
            if (link.isNullOrBlank()) return null
            val s = link.trim()
            return when {
                s.startsWith("https://") -> s
                s.startsWith("//") -> "https:$s"
                s.startsWith("http://") -> "https://" + s.removePrefix("http://")
                base != null && s.startsWith("/") -> mainUrl.trimEnd('/') + s
                Regex("""^[\w\-]+\.[\w\-.]+""").containsMatchIn(s) -> "https://$s"
                else -> s
            }
        }

        fun extractIframeSrc(text: String?): String? {
            if (text.isNullOrBlank()) return null
            return Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        }

        suspend fun fetchPlayerPageAndExtract(sessionReferer: String?, url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                val html = httpService.getText(url, headers = mapOf("Referer" to (sessionReferer ?: data)))
                val iframe = extractIframeSrc(html)
                if (!iframe.isNullOrBlank()) ensureHttpsRaw(iframe, url)
                else Regex("""https?://[^\s"']+\.(m3u8|mp4)(?:\?[^\s"']*)?""", RegexOption.IGNORE_CASE).find(html ?: "")?.value
            } catch (_: Exception) { null }
        }

        try {
            val rawHtml = httpService.getText(data) ?: return false
            val doc = Jsoup.parse(rawHtml)
            val scriptData = doc.selectFirst("script#dt_main_ajax-js-extra")?.data() ?: rawHtml
            val globalNonce = Regex(""""nonce"\s*:\s*"([a-f0-9]+)"""", RegexOption.IGNORE_CASE).find(scriptData)?.groupValues?.get(1) ?: ""

            val serverButtons = doc.select("ul.server-list li a.option")
            if (serverButtons.isEmpty()) return true

            data class Btn(val name: String, val post: String, val nume: String, val type: String, val security: String)
            val btns = serverButtons.mapNotNull { b ->
                val postId = b.attr("data-post").ifBlank { return@mapNotNull null }
                val nume = b.attr("data-nume").ifBlank { return@mapNotNull null }
                val dtype = b.attr("data-type").ifBlank { return@mapNotNull null }
                val sname = b.selectFirst("span.server")?.text()?.trim() ?: b.text().trim().ifBlank { return@mapNotNull null }
                Btn(sname, postId, nume, dtype, b.attr("data-nonce").ifBlank { globalNonce })
            }

            val baseHtml = httpService.getText("$mainUrl/") ?: mainUrl
            val baseMatch = Regex("""<base[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(baseHtml)?.groupValues?.get(1)
            val ajaxUrl = "${baseMatch ?: mainUrl}/wp-admin/admin-ajax.php"

            val linkChannel = Channel<ExtractorLink>(Channel.UNLIMITED)
            val consumer = GlobalScope.launch(Dispatchers.Default) {
                for (link in linkChannel) { try { callback(link) } catch (_: Exception) {} }
            }

            suspend fun sendLinkSafe(link: ExtractorLink) {
                try { linkChannel.send(link) } catch (_: Exception) {}
            }

            val maxConcurrent = kotlin.math.min(12, kotlin.math.max(4, btns.size))
            val sem = Semaphore(maxConcurrent)

            val jobs = btns.map { btn ->
                GlobalScope.async(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            val payload = mutableMapOf(
                                "action" to "player_ajax",
                                "post" to btn.post,
                                "nume" to btn.nume,
                                "type" to btn.type
                            )
                            if (btn.security.isNotBlank()) payload["security"] = btn.security
                            if (globalNonce.isNotBlank()) payload["nonce"] = globalNonce

                            val txt = httpService.postText(ajaxUrl, data = payload, headers = mapOf(
                                "Referer" to data, "X-Requested-With" to "XMLHttpRequest"
                            ))

                            var embedRaw: String? = null
                            try { embedRaw = JSONObject(txt).optString("embed_url").ifEmpty { null } } catch (_: Exception) {}
                            if (embedRaw.isNullOrBlank()) {
                                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(txt ?: "")
                                embedRaw = iframeMatch?.groupValues?.get(1) ?: txt?.trim()
                            }

                            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(embedRaw ?: "")?.groupValues?.get(1)
                            var abs = ensureHttpsRaw(iframeSrc?.trim() ?: embedRaw?.replace(Regex("""<iframe[^>]+src=["']|["'].*"""), "")?.trim(), data) ?: ""

                            if (abs.isNotBlank() && (abs.contains("jwplayer", true) || abs.contains("jw.", true) || abs.contains(".php", true) || abs.contains("player", true))) {
                                val extracted = fetchPlayerPageAndExtract(data, abs)
                                if (!extracted.isNullOrBlank()) abs = ensureHttpsRaw(extracted, abs) ?: abs
                            }

                            when {
                                abs.contains("yonaplay.net", true) -> {
                                    decodeYonaplayAndLoad(abs, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                }
                                abs.contains("videa.hu", true) -> {
                                    VideaExtractor().getUrl(abs, null, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                    loadExtractor(abs, data, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                }
                                abs.contains("my.mail.ru", true) || abs.contains("/video/embed/", true) -> {
                                    MailruExtractor().getUrl(abs, null, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                    loadExtractor(abs, data, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                }
                                abs.contains("drive.google.com", true) -> {
                                    val fileId = Regex(""".*/file/d/([0-9A-Za-z_-]{10,})""").find(abs)?.groupValues?.get(1)
                                    val direct = if (fileId != null) "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t" else abs
                                    loadExtractor(direct, data, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                }
                                abs.contains("streamhg", true) || abs.contains("earnvids", true) -> {
                                    val extracted = try { ExternalEarnVidsExtractor.extract(abs, data) } catch (_: Throwable) { null }
                                    if (extracted != null) {
                                        sendLinkSafe(newExtractorLink("EarnVids", "ExternalEarnVids", ensureHttpsRaw(extracted, data) ?: extracted, ExtractorLinkType.VIDEO) {
                                            referer = data; quality = Qualities.Unknown.value
                                        })
                                    } else {
                                        loadExtractor(abs, data, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                    }
                                }
                                else -> {
                                    loadExtractor(abs, data, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                }
                            }

                            val lowerServer = btn.name.lowercase()
                            if (lowerServer.contains("megabox") || lowerServer.contains("megamax") || abs.lowercase().contains("megabox") || abs.lowercase().contains("megamax")) {
                                try {
                                    val extras = MegaboxResolver.process(abs, data)
                                    for (mb in extras) {
                                        val mbUrl = ensureHttpsRaw(mb, abs) ?: continue
                                        if (mbUrl.contains("streamhg", true) || mbUrl.contains("earnvids", true)) {
                                            val extracted = try { ExternalEarnVidsExtractor.extract(mbUrl, data) } catch (_: Throwable) { null }
                                            if (extracted != null) {
                                                sendLinkSafe(newExtractorLink("EarnVids", "ExternalEarnVids", ensureHttpsRaw(extracted, data) ?: extracted, ExtractorLinkType.VIDEO) {
                                                    referer = data; quality = Qualities.Unknown.value
                                                })
                                                continue
                                            }
                                        }
                                        loadExtractor(mbUrl, data, subtitleCallback) { l -> runBlocking { sendLinkSafe(l) } }
                                    }
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            jobs.forEach { runCatching { it.await() } }
            linkChannel.close()
            consumer.join()
            return true
        } catch (_: Exception) { return false }
    }

    private suspend fun decodeYonaplayAndLoad(
        yonaplayUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = httpService.getText(yonaplayUrl) ?: return
            val tokenRegex = Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""")
            val tokens = tokenRegex.findAll(html).map { it.groupValues[1] }.toList()

            if (tokens.isNotEmpty()) {
                for (token in tokens) {
                    try {
                        var fixed = token
                        val padding = token.length % 4
                        if (padding != 0) fixed += "=".repeat(4 - padding)
                        val decoded = String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))

                        if (decoded.contains("drive.google.com/file/d/")) {
                            val fileId = Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)?.groupValues?.get(1)
                            if (fileId != null) {
                                callback(newExtractorLink("Google Drive", "Yonaplay",
                                    "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                                    ExtractorLinkType.VIDEO) { referer = "https://drive.google.com/"; quality = Qualities.Unknown.value })
                            }
                            continue
                        }

                        val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.get(1)
                        if (!iframeMatch.isNullOrBlank()) {
                            loadExtractor(if (iframeMatch.startsWith("//")) "https:$iframeMatch" else iframeMatch, yonaplayUrl, subtitleCallback, callback)
                            continue
                        }

                        if (decoded.startsWith("http")) {
                            loadExtractor(decoded, yonaplayUrl, subtitleCallback, callback)
                            continue
                        }

                        val candidate = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(decoded)?.value
                        if (!candidate.isNullOrBlank()) { loadExtractor(candidate, yonaplayUrl, subtitleCallback, callback); continue }
                    } catch (_: Exception) {}
                }
                return
            }

            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank()) {
                val final = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                if (final.contains("drive.google.com", true)) {
                    val fileId = Regex(""".*/file/d/([0-9A-Za-z_-]{10,})""").find(final)?.groupValues?.get(1)
                    if (fileId != null) {
                        callback(newExtractorLink("Google Drive", "Yonaplay",
                            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                            ExtractorLinkType.VIDEO) { referer = "https://drive.google.com/"; quality = Qualities.Unknown.value })
                        return
                    }
                }
                loadExtractor(final, yonaplayUrl, subtitleCallback, callback)
                return
            }

            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val candidate = m.value
                if (candidate.contains("yonaplay.net", true)) return@forEach
                loadExtractor(candidate, yonaplayUrl, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }
}
