package com.anime3rb

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.webview.NavigationStep
import com.cloudstream.shared.service.CloudflareBlockedSearchException
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
            Log.i(TAG, "searchNormal: standard search returned ${standard.size} results")
            return standard
        }
        Log.w(TAG, "searchNormal: standard search empty, falling back to Livewire")
        return livewireSearch(query)
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
        Log.d(TAG, "standardSearch: URL after fetch: ${doc.location()}")

        val items = getParser().parseSearch(doc)
        Log.i(TAG, "standardSearch: parser returned ${items.size} items")

        if (items.isEmpty()) {
            val cardCount = doc.select("a.simple-title-card, a.video-card, div.item").size
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

    private suspend fun livewireSearch(query: String): List<SearchResponse> {
        Log.i(TAG, "livewireSearch: START query='$query'")

        val mainDoc = httpService.getDocument(mainUrl) ?: run {
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
