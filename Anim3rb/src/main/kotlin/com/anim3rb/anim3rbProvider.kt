package com.anime3rb

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.NavigationStep
import com.cloudstream.shared.service.CloudflareBlockedSearchException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class Anim3rbProvider : BaseProvider() {

    override val providerName get() = "Anim3rb"
    override val baseDomain get() = "anime3rb.com"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/anim3rb.json"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override fun getParser(): NewBaseParser = Anim3rbParser()

    companion object {
        private const val TAG = "Anim3rb"
    }

    // ==================== MAIN PAGE ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.i(TAG, "getMainPage: START page=$page, data=${request.data}, name=${request.name}")
        if (page > 1) return null

        httpService.ensureInitialized()

        val doc = httpService.getDocument(request.data, checkDomainChange = true) ?: run {
            Log.e(TAG, "getMainPage: doc is null")
            return null
        }
        Log.i(TAG, "getMainPage: title='${doc.title()}', body.length=${doc.body()?.html()?.length ?: 0}")

        val homeSets = mutableListOf<HomePageList>()

        // Section 1 — pinned anime
        try {
            val pinnedHeader = doc.selectFirst("h2:contains(الأنميات المثبتة)")
            if (pinnedHeader != null) {
                val parentContainer = pinnedHeader.parent()?.parent()?.parent()
                val items = parentContainer
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!items.isNullOrEmpty()) {
                    Log.i(TAG, "getMainPage: section 'الأنميات المثبتة' → ${items.size} items")
                    homeSets.add(HomePageList("الأنميات المثبتة", items))
                } else {
                    Log.w(TAG, "getMainPage: pinned section selectors matched 0 items")
                }
            } else {
                Log.w(TAG, "getMainPage: pinned header 'الأنميات المثبتة' not found")
                Log.w(TAG, "getMainPage: all h2 text: ${doc.select("h2").eachText()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: pinned section error: ${e.message}")
        }

        // Section 2 — latest episodes
        try {
            val latestItems = doc.select("#videos a.video-card").mapNotNull { toSearchResult(it) }
            if (latestItems.isNotEmpty()) {
                Log.i(TAG, "getMainPage: section 'أحدث الحلقات' → ${latestItems.size} items")
                homeSets.add(HomePageList("أحدث الحلقات", latestItems))
            } else {
                Log.w(TAG, "getMainPage: '#videos a.video-card' matched 0 elements")
                val videoCards = doc.select("a.video-card")
                Log.w(TAG, "getMainPage: total a.video-card on page: ${videoCards.size}")
                videoCards.firstOrNull()?.let { el ->
                    Log.w(TAG, "getMainPage: first card html: ${el.html().take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: latest episodes error: ${e.message}")
        }

        // Section 3 — latest added anime
        try {
            val addedHeader = doc.selectFirst("h3:contains(آخر الأنميات المضافة)")
            if (addedHeader != null) {
                val parentContainer = addedHeader.parent()?.parent()?.parent()
                val items = parentContainer
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!items.isNullOrEmpty()) {
                    Log.i(TAG, "getMainPage: section 'آخر الأنميات المضافة' → ${items.size} items")
                    homeSets.add(HomePageList("آخر الأنميات المضافة", items))
                } else {
                    Log.w(TAG, "getMainPage: added section selectors matched 0 items")
                }
            } else {
                Log.w(TAG, "getMainPage: added header 'آخر الأنميات المضافة' not found")
                Log.w(TAG, "getMainPage: all h3 text: ${doc.select("h3").eachText()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: latest added error: ${e.message}")
        }

        if (homeSets.isEmpty()) {
            Log.e(TAG, "getMainPage: NO sections found — dumping <body> HTML:\n${doc.body()?.html()?.take(3000)}")
        }

        Log.i(TAG, "getMainPage: returning ${homeSets.size} sections")
        return newHomePageResponse(homeSets)
    }

    // ==================== SEARCH ====================

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        Log.i(TAG, "searchNormal: START query='$query'")
        val standard = standardSearch(query, useNoFallback = false)
        if (standard.isNotEmpty()) {
            Log.i(TAG, "searchNormal: standard returned ${standard.size} results")
            return standard
        }
        Log.w(TAG, "searchNormal: standard empty, falling back to Livewire")
        return livewireSearch(query, useNoFallback = false)
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        Log.i(TAG, "searchLazy: START query='$query'")
        return standardSearch(query, useNoFallback = true)
    }

    private suspend fun standardSearch(query: String, useNoFallback: Boolean): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = getParser().getSearchUrl(mainUrl, encoded)
        Log.i(TAG, "standardSearch: url=$url, useNoFallback=$useNoFallback")

        val doc = try {
            if (useNoFallback) {
                httpService.getDocumentNoFallback(url, checkDomainChange = true)
            } else {
                httpService.getDocument(url, checkDomainChange = true)
            }
        } catch (e: CloudflareBlockedSearchException) {
            Log.w(TAG, "standardSearch: CF blocked in lazy mode — rethrowing")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "standardSearch: error: ${e.message}")
            return emptyList()
        }

        if (doc == null) {
            Log.w(TAG, "standardSearch: doc is null")
            return emptyList()
        }

        Log.i(TAG, "standardSearch: title='${doc.title()}', body.length=${doc.body()?.html()?.length ?: 0}")

        val items = getParser().parseSearch(doc)
        Log.i(TAG, "standardSearch: parser returned ${items.size} items")

        if (items.isEmpty()) {
            val cardCount = doc.select("a.simple-title-card, a.video-card").size
            Log.w(TAG, "standardSearch: 0 items parsed. card-like elements found: $cardCount")
        }

        return items.map { item ->
            val type = if (item.isMovie) TvType.AnimeMovie else TvType.Anime
            newAnimeSearchResponse(item.title, item.url, type) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
    }

    private suspend fun livewireSearch(query: String, useNoFallback: Boolean): List<SearchResponse> {
        Log.i(TAG, "livewireSearch: START query='$query', useNoFallback=$useNoFallback")

        val mainDoc = try {
            if (useNoFallback) {
                httpService.getDocumentNoFallback(mainUrl)
            } else {
                httpService.getDocument(mainUrl)
            }
        } catch (e: CloudflareBlockedSearchException) {
            Log.w(TAG, "livewireSearch: CF blocked in lazy mode — rethrowing")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "livewireSearch: error fetching main page: ${e.message}")
            return emptyList()
        }

        if (mainDoc == null) {
            Log.e(TAG, "livewireSearch: failed to fetch main page")
            return emptyList()
        }
        Log.i(TAG, "livewireSearch: main page title='${mainDoc.title()}'")

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        if (scriptTag == null) {
            Log.e(TAG, "livewireSearch: <script src=*livewire.min.js> not found")
            Log.w(TAG, "livewireSearch: all external scripts: ${mainDoc.select("script[src]").eachAttr("src")}")
            return emptyList()
        }
        val csrfToken = scriptTag.attr("data-csrf")
        if (csrfToken.isBlank()) {
            Log.e(TAG, "livewireSearch: data-csrf blank on livewire script")
            Log.w(TAG, "livewireSearch: script attrs: ${scriptTag.attributes()}")
            return emptyList()
        }
        Log.d(TAG, "livewireSearch: csrf=$csrfToken")

        val form = mainDoc.selectFirst("form[wire\\\\:id]")
        if (form == null) {
            Log.e(TAG, "livewireSearch: form[wire:id] not found")
            Log.w(TAG, "livewireSearch: forms on page: ${mainDoc.select("form").size}")
            val wireElements = mainDoc.select("[wire\\\\:id]")
            Log.w(TAG, "livewireSearch: elements with wire:id: ${wireElements.size}, first: ${wireElements.firstOrNull()?.html()?.take(200)}")
            return emptyList()
        }

        val snapshotRaw = form.attr("wire:snapshot")
        if (snapshotRaw.isBlank()) {
            Log.e(TAG, "livewireSearch: wire:snapshot blank")
            return emptyList()
        }
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)
        Log.d(TAG, "livewireSearch: snapshot (first 300): ${snapshotStr.take(300)}")

        val updateUrl = "$mainUrl/livewire/update"
        val safeQuery = query.replace("\"", "\\\"")
        val jsonBody = """{"_token":"$csrfToken","components":[{"snapshot":$snapshotStr,"updates":{"query":"$safeQuery"},"calls":[]}]}"""
        Log.d(TAG, "livewireSearch: POST $updateUrl body.length=${jsonBody.length}")

        return try {
            val cookies = httpService.cookies
            val cookieStr = if (cookies.isNotEmpty()) {
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else ""
            Log.d(TAG, "livewireSearch: cookies=${cookies.size}")

            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = okhttp3.Request.Builder()
                .url(updateUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", "$mainUrl/")
                .apply { if (cookieStr.isNotBlank()) header("Cookie", cookieStr) }
                .post(body)
                .build()

            val client = app.baseClient.newBuilder().build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "livewireSearch: HTTP ${response.code}, body.length=${responseBody.length}")

            if (response.code != 200) {
                Log.e(TAG, "livewireSearch: HTTP ${response.code}, body=${responseBody.take(800)}")
                return emptyList()
            }

            val json = try {
                AppUtils.parseJson<Map<String, Any>>(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "livewireSearch: JSON parse error: ${e.message}, body=${responseBody.take(500)}")
                return emptyList()
            }

            val components = json["components"] as? List<Map<String, Any>>
            if (components.isNullOrEmpty()) {
                Log.e(TAG, "livewireSearch: no components in response, top-level keys=${json.keys}")
                return emptyList()
            }

            val effects = components.firstOrNull()?.get("effects") as? Map<String, Any>
            val htmlContent = effects?.get("html") as? String
            if (htmlContent.isNullOrBlank()) {
                Log.e(TAG, "livewireSearch: effects.html is blank/null, component keys=${components.firstOrNull()?.keys}")
                return emptyList()
            }
            Log.d(TAG, "livewireSearch: effects.html length=${htmlContent.length}")

            val htmlDoc = org.jsoup.Jsoup.parse(htmlContent)
            val items = getParser().parseSearch(htmlDoc)
            Log.i(TAG, "livewireSearch: parser returned ${items.size} items")

            items.map { item ->
                val type = if (item.isMovie) TvType.AnimeMovie else TvType.Anime
                newAnimeSearchResponse(item.title, item.url, type) {
                    this.posterUrl = item.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "livewireSearch: exception: ${e.message}")
            emptyList()
        }
    }

    // ==================== LOAD (DETAIL PAGE) ====================

    override suspend fun load(url: String): LoadResponse? {
        val methodTag = "[$name] [load]"
        Log.i(methodTag, "START url='$url'")

        try {
            httpService.ensureInitialized()

            // Use getRaw + Cookie header to bypass domain rewriting in RequestQueue.
            // The session domain may be video.vid3rb.com (persisted from a previous embed redirect),
            // causing getDocument() to rewrite anime3rb.com URLs to video.vid3rb.com (→ "Not Found").
            val cookieStr = httpService.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val headers = mutableMapOf("Referer" to mainUrl)
            if (cookieStr.isNotBlank()) headers["Cookie"] = cookieStr

            val resp = httpService.getRaw(url, headers = headers)
            val body = resp?.body?.string()
            resp?.close()
            if (body == null) {
                Log.e(methodTag, "getRaw returned null")
                return null
            }
            Log.i(methodTag, "Fetched via getRaw: ${body.length} bytes, title=${body.substringAfter("<title>").substringBefore("</title>")}")

            var actualDoc = Jsoup.parse(body, url)
            var actualUrl = url

            // Resolve Meta-Refresh (rare, but handle using getRaw)
            val refreshMeta = actualDoc.selectFirst("meta[http-equiv=Refresh]")
            if (refreshMeta != null) {
                val newUrlMatch = Regex("URL=(.+)", RegexOption.IGNORE_CASE).find(refreshMeta.attr("content"))
                val newUrl = newUrlMatch?.groupValues?.get(1)
                if (!newUrl.isNullOrBlank()) {
                    Log.d(methodTag, "Meta-Refresh to: $newUrl")
                    val refreshResp = httpService.getRaw(newUrl, headers = headers)
                    val refreshBody = refreshResp?.body?.string()
                    refreshResp?.close()
                    if (refreshBody != null) {
                        actualDoc = Jsoup.parse(refreshBody, newUrl)
                        actualUrl = newUrl
                        Log.d(methodTag, "Meta-Refresh resolved")
                    }
                }
            }

            var data = getParser().parseLoadPageData(actualDoc, actualUrl)

            if (data == null) {
                Log.e(methodTag, "Failed to parse load data")
                return null
            }

            // Resolve parent series if needed (episode URL → fetch parent anime page)
            if (!data.isMovie && data.episodes.isNullOrEmpty() && !data.parentSeriesUrl.isNullOrBlank()) {
                val parentUrl = data.parentSeriesUrl!!
                try {
                    val parentResp = httpService.getRaw(parentUrl, headers = headers)
                    val parentBody = parentResp?.body?.string()
                    parentResp?.close()
                    if (parentBody != null) {
                        val parentDoc = Jsoup.parse(parentBody, parentUrl)
                        val parentData = getParser().parseLoadPageData(parentDoc, parentUrl)
                        if (parentData != null) {
                            Log.d(methodTag, "Swapped to parent series: $parentUrl")
                            data = parentData
                            actualDoc = parentDoc
                            actualUrl = parentUrl
                        }
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "Failed fetching parent series: ${e.message}")
                }
            }

            val allEpisodes = fetchExtraEpisodes(actualDoc, actualUrl, data)
                .distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            val finalType = if (data.type == TvType.TvSeries && allEpisodes.isEmpty()) {
                Log.d(methodTag, "Fallback: 0 episodes → treating as Movie")
                TvType.Movie
            } else {
                data.type
            }

            Log.d(methodTag, "title='${data.title}', type=$finalType, episodes=${allEpisodes.size}")

            return if (finalType == TvType.Movie) {
                val movieDataUrl = data.watchUrl ?: data.url
                newMovieLoadResponse(data.title, data.url, TvType.Movie, movieDataUrl) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                }
            } else {
                newTvSeriesLoadResponse(data.title, data.url, TvType.TvSeries, allEpisodes.map { ep ->
                    newEpisode(ep.url) {
                        this.name = ep.name
                        this.season = ep.season
                        this.episode = ep.episode
                    }
                }) {
                    this.posterUrl = data.posterUrl
                    this.posterHeaders = httpService.getImageHeaders()
                    this.plot = data.plot
                    this.tags = data.tags
                    this.year = data.year
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    override suspend fun fetchExtraEpisodes(
        doc: Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val existing = data.episodes ?: emptyList()
        if (existing.isNotEmpty()) {
            Log.i(TAG, "fetchExtraEpisodes: ${existing.size} episodes from static HTML — using them")
            return existing
        }

        Log.w(TAG, "fetchExtraEpisodes: 0 episodes in static HTML, launching WebView render for $url")

        return try {
            val result = httpService.navigateWithSteps(
                steps = listOf(
                    NavigationStep.LoadUrl(url, referer = mainUrl),
                    NavigationStep.WaitForSelector(
                        selector = ".video-list a, .episodes-list a",
                        timeoutMs = 20000L,
                        abortOnFailure = false
                    ),
                    NavigationStep.ExtractHtml(key = "rendered_episodes")
                ),
                mode = Mode.HEADLESS,
                overallTimeoutMs = 30000L
            )

            Log.i(TAG, "fetchExtraEpisodes: navigateWithSteps success=${result.success}, completedSteps=${result.completedSteps}, finalUrl=${result.finalUrl}")

            val renderedDoc = result.extractedHtml["rendered_episodes"]?.let { html ->
                Log.d(TAG, "fetchExtraEpisodes: rendered HTML length=${html.length}")
                org.jsoup.Jsoup.parse(html)
            }

            if (renderedDoc != null) {
                val episodes = getParser().parseEpisodes(renderedDoc, null)
                if (episodes.isNotEmpty()) {
                    Log.i(TAG, "fetchExtraEpisodes: WebView parsed ${episodes.size} episodes")
                    return episodes
                }
                Log.w(TAG, "fetchExtraEpisodes: WebView returned 0 episodes")
                Log.w(TAG, "fetchExtraEpisodes: rendered doc selector match count: ${renderedDoc.select(".video-list a, .episodes-list a").size}")
            } else {
                Log.w(TAG, "fetchExtraEpisodes: extractedHtml keys=${result.extractedHtml.keys}")
                if (!result.success) Log.e(TAG, "fetchExtraEpisodes: navigation failed at step ${result.failedAtStep}: ${result.error}")
            }

            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "fetchExtraEpisodes: exception: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "loadLinks: START data='$data'")

        // Phase 1: Try standard flow (BaseProvider handles parser selectors → sniffers → extractors)
        try {
            if (super.loadLinks(data, isCasting, subtitleCallback, callback)) {
                Log.i(TAG, "loadLinks: standard flow succeeded")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadLinks: standard flow exception: ${e.message}")
        }

        // Phase 2: Old pattern — bypass request queue (which rewrites URLs after domain change).
        // Use getRaw() + Jsoup + manual Cookie header to avoid URL rewriting and domain redirect handling.
        Log.w(TAG, "loadLinks: standard failed, using httpService-based player extraction")

        val cookieStr = httpService.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val reqHeaders = mutableMapOf("Referer" to mainUrl)
        if (cookieStr.isNotBlank()) reqHeaders["Cookie"] = cookieStr

        // Fetch episode page via getRaw to bypass request queue URL rewriting
        val episodeResp = httpService.getRaw(data, headers = reqHeaders)
        val episodeBody = episodeResp?.body?.string() ?: run { episodeResp?.close(); return false }
        episodeResp.close()
        val episodeDoc = Jsoup.parse(episodeBody, data)
        Log.i(TAG, "loadLinks: episode page fetched via getRaw, html.length=${episodeBody.length}")

        val playerUrls = getParser().extractWatchServersUrls(episodeDoc).toMutableList()
        Log.i(TAG, "loadLinks: parser found ${playerUrls.size} player URLs")

        // Extract Livewire video_url directly from the episode page HTML
        try {
            val snapshot = episodeDoc.select("[wire\\\\:snapshot]").joinToString(" ") { it.attr("wire:snapshot") }
            if (snapshot.isNotBlank()) {
                Regex(""""video_url"\s*:\s*"([^"]+)"""").findAll(snapshot).forEach { m ->
                    val raw = m.groupValues[1]
                    val cleaned = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                    if (cleaned.startsWith("http") && !playerUrls.contains(cleaned)) {
                        Log.i(TAG, "loadLinks: found Livewire video_url='${cleaned.take(100)}'")
                        playerUrls.add(cleaned)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadLinks: Livewire extraction error: ${e.message}")
        }

        // Fallback: construct /player/{slug}/{ep} on the ORIGINAL domain (anime3rb.com)
        val allUrls = if (playerUrls.isNotEmpty()) playerUrls else {
            val constructed = data.replace("/episode/", "/player/")
            Log.w(TAG, "loadLinks: no URLs from parser, trying constructed: $constructed")
            listOf(constructed)
        }

        for (playerUrl in allUrls) {
            Log.i(TAG, "loadLinks: trying player URL: $playerUrl")
            try {
                val playerResp = httpService.getRaw(playerUrl, headers = reqHeaders)
                val playerBody = playerResp?.body?.string()
                playerResp?.close()
                if (playerBody == null) continue
                Log.i(TAG, "loadLinks: player page fetched via getRaw, ${playerBody.length} bytes, title=${playerBody.substringAfter("<title>").substringBefore("</title>")}")

                // Parse video_sources from script tags (old pattern)
                val scriptMatch = Regex("""var\s+video_sources\s*=\s*(\[[^;]+]);""").find(playerBody)
                if (scriptMatch != null) {
                    try {
                        val sources = AppUtils.parseJson<List<Map<String, Any>>>(scriptMatch.groupValues[1])
                        Log.i(TAG, "loadLinks: found ${sources.size} video sources via video_sources")
                        for (item in sources) {
                            val src = item["src"]?.toString() ?: item["file"]?.toString()
                            val label = item["label"]?.toString() ?: "Default"
                            if (!src.isNullOrBlank()) {
                                callback(newExtractorLink(this.name, "$name $label", src, ExtractorLinkType.VIDEO) { referer = "https://anime3rb.com/" })
                            }
                        }
                        if (sources.isNotEmpty()) return true
                    } catch (e: Exception) {
                        Log.w(TAG, "loadLinks: failed to parse video_sources JSON: ${e.message}")
                    }
                }

                // Search for /sources?cf_token= in player page
                val sourcesUrl = Regex("""/sources\?[^"'\s\\<>]+""").find(playerBody)?.value
                if (sourcesUrl != null) {
                    val fullSourcesUrl = "https://anime3rb.com$sourcesUrl"
                    Log.i(TAG, "loadLinks: found sources URL: $fullSourcesUrl")
                    val jsonResp = httpService.getRaw(fullSourcesUrl, headers = reqHeaders)
                    val rawJson = jsonResp?.body?.string()
                    jsonResp?.close()
                    if (rawJson != null) {
                        try {
                            val sources = AppUtils.parseJson<List<Map<String, Any>>>(rawJson)
                            Log.i(TAG, "loadLinks: found ${sources.size} video sources via JSON endpoint")
                            for (item in sources) {
                                val src = item["src"]?.toString() ?: item["file"]?.toString()
                                val label = item["label"]?.toString() ?: "Default"
                                if (!src.isNullOrBlank()) {
                                    callback(newExtractorLink(this.name, "$name $label", src, ExtractorLinkType.VIDEO) { referer = "https://anime3rb.com/" })
                                }
                            }
                            if (sources.isNotEmpty()) return true
                        } catch (e: Exception) {
                            Log.w(TAG, "loadLinks: failed to parse sources JSON: ${e.message}")
                        }
                    }
                }

                // Try loading through extractors for player URL
                Log.d(TAG, "loadLinks: trying extractors for player URL: $playerUrl")
                var found = false
                loadExtractor(playerUrl, "https://anime3rb.com/", subtitleCallback) { link ->
                    callback(link)
                    found = true
                }
                if (found) return true

            } catch (e: Exception) {
                Log.w(TAG, "loadLinks: player URL failed: ${e.message}")
            }
        }

        Log.w(TAG, "loadLinks: all methods exhausted — no video sources found")
        return false
    }

    override suspend fun resolveServerUrl(url: String, referer: String): String? {
        Log.d(TAG, "resolveServerUrl: url=${url.take(100)}, referer=$referer")
        return super.resolveServerUrl(url, referer)
    }

    // ==================== HELPERS ====================

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val rawTitle = element.selectFirst("h3.title-name")?.text()?.trim()
            if (rawTitle.isNullOrBlank()) {
                Log.w(TAG, "toSearchResult: no title, html=${element.html().take(150)}")
                return null
            }
            val title = rawTitle
                .replace("\\n", " ")
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val href = element.attr("href").let { url ->
                when {
                    url.startsWith("http") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/") -> "$mainUrl$url"
                    else -> "$mainUrl/$url"
                }
            }
            if (href.isBlank() || href == mainUrl) return null

            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifBlank { img.attr("src") }.orEmpty()

            val episodeNum = element.selectFirst("p.number")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                this.posterHeaders = httpService.getImageHeaders()
                if (episodeNum != null) addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) {
            Log.w(TAG, "toSearchResult: error: ${e.message}")
            null
        }
    }
}
