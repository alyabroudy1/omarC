package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class eishk : BaseProvider() {
    override val providerName get() = "قصة عشق"
    override val baseDomain get() = "3esk.onl"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/eishk.json"

    override fun getParser(): NewBaseParser = EshkParser()

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportsLazySearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        httpService.ensureInitialized()
        val doc = httpService.getDocument(mainUrl, checkDomainChange = true, rewriteDomain = true) ?: return null
        val all = ArrayList<HomePageList>()

        doc.select("section.home-items-sec").forEach { section ->
            val sectionTitle = section.selectFirst(".sec-title")?.text() ?: return@forEach
            val sectionDoc = org.jsoup.Jsoup.parse(section.html())
            val items = getParser().parseMainPage(sectionDoc).mapNotNull { item ->
                newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = item.posterUrl
                }
            }
            if (items.isNotEmpty()) all.add(HomePageList(sectionTitle, items))
        }
        return newHomePageResponse(all)
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = getParser().getSearchUrl(mainUrl, encoded)
        val doc = httpService.getDocumentNoFallback(url, checkDomainChange = true, rewriteDomain = true)
            ?: throw com.cloudstream.shared.service.CloudflareBlockedSearchException(name, baseDomain)
        val items = getParser().parseSearch(doc)
        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
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
        Log.w("EshkLinks", "loadLinks called | data=$data")

        val r0 = httpService.getDocument(data, checkDomainChange = true, rewriteDomain = true)
        if (r0 == null) {
            Log.w("EshkLinks", "getDocument returned null for data=$data")
            return false
        }
        Log.w("EshkLinks", "getDocument OK | title=${r0.title()} | forms=${r0.select("form").size} | iframes=${r0.select("iframe").size}")

        // Primary: POST handshake → iframe → loadExtractor (EshkEmbedExtractor handles server enum + JS unpack)
        val iframeUrl = resolveIframeViaPostHandshake(r0, data)
        if (iframeUrl != null) {
            Log.w("EshkLinks", "resolveIframeViaPostHandshake returned iframe=$iframeUrl")
            var foundAny = false
            loadExtractor(iframeUrl, data, subtitleCallback) { link ->
                callback(link); foundAny = true
            }
            if (foundAny) {
                Log.w("EshkLinks", "loadExtractor from iframe succeeded")
                return true
            }
            Log.w("EshkLinks", "loadExtractor from iframe found nothing")
        }

        // Fallback: direct iframe/media extraction from the episode page itself
        Log.w("EshkLinks", "trying direct extract fallback")
        return tryDirectExtract(r0, data, subtitleCallback, callback)
    }

    private suspend fun resolveIframeViaPostHandshake(r0: Document, data: String): String? {
        // =============================================
        // PATH 1: Direct <a> tag approach (current site uses this)
        // The page has <a class="single-watch-btn" href="https://aa.3isk.icu/..."> 
        // Following this href redirects to the actual embed page
        // =============================================
        val directLink = r0.selectFirst("a.single-watch-btn")
        if (directLink != null) {
            val href = directLink.attr("href")
            if (href.isNotBlank()) {
                Log.w("EshkLinks", "Found a.single-watch-btn href=$href")
                try {
                    // Follow redirect to get the embed page
                    val redirectDoc = httpService.getDocument(href, headers = mapOf("Referer" to data))
                    if (redirectDoc != null) {
                        val finalUrl = redirectDoc.location()
                        Log.w("EshkLinks", "Redirect landed at: $finalUrl")
                        // Final URL is likely the embed page itself (e.g., https://3esk.onl/embed/N/xxx)
                        if (finalUrl.isNotBlank() && finalUrl != href) {
                            return finalUrl
                        }
                        // Or the page has an iframe leading to the embed
                        val iframeSrc = redirectDoc.select("iframe").firstNotNullOfOrNull {
                            it.attr("src").ifBlank { null }
                        }
                        if (iframeSrc != null) {
                            Log.w("EshkLinks", "Found iframe in redirect page: $iframeSrc")
                            return iframeSrc
                        }
                    }
                } catch (e: Exception) {
                    Log.w("EshkLinks", "Failed to follow redirect: ${e.message}")
                }
            }
        }

        // =============================================
        // PATH 2: <button> inside <form> (old POST handshake)
        // =============================================
        Log.w("EshkLinks", "a.single-watch-btn not found, trying form-based approach")
        var watchForm = r0.selectFirst("button.single-watch-btn")?.let { it.parent() }
        if (watchForm == null) {
            for (f in r0.select("form")) {
                val act = f.attr("action")
                if (act.contains("3isk") || act.contains("aa.3isk") || act.contains("watch")) {
                    watchForm = f; break
                }
            }
        }
        if (watchForm == null) {
            Log.w("EshkLinks", "NO watch form found on page")
            return null
        }
        Log.w("EshkLinks", "watchForm action='${watchForm.attr("action")}'")

        val firstPostUrl = watchForm.attr("action")
        val firstFormData = watchForm.select("input[type=hidden]")
            .associate { it.attr("name") to it.attr("value") }.toMutableMap()
        r0.selectFirst("button.single-watch-btn")?.let { btn ->
            val btnName = btn.attr("name")
            if (btnName.isNotBlank()) firstFormData[btnName] = btn.attr("value")
        }
        Log.w("EshkLinks", "POST1 url=$firstPostUrl data=$firstFormData")

        val r1Text = httpService.postText(firstPostUrl, firstFormData, referer = data) ?: return null

        val myUrlMatch = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1Text)
        val newsMatch = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1Text)
        if (myUrlMatch == null || newsMatch == null) {
            Log.w("EshkLinks", "myUrl=${myUrlMatch?.groupValues?.getOrNull(1)} news=${newsMatch?.groupValues?.getOrNull(1)}")
            return null
        }
        val nextPost = myUrlMatch.groupValues[1]
        val newsVal = newsMatch.groupValues[1]
        Log.w("EshkLinks", "myUrl=$nextPost news=$newsVal")

        val r2Text = httpService.postText(
            nextPost,
            mapOf("news" to newsVal, "u" to "", "submit" to "submit"),
            referer = nextPost
        ) ?: return null

        val r2Doc = org.jsoup.Jsoup.parse(r2Text)
        val iframeSrc = r2Doc.select("iframe").firstNotNullOfOrNull { it.attr("src").ifBlank { null } }
        Log.w("EshkLinks", "iframe from post2: $iframeSrc")
        return iframeSrc
    }

    private suspend fun tryDirectExtract(
        r0: Document, data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframes = r0.select("iframe")
        Log.w("EshkLinks", "tryDirectExtract | iframes=${iframes.size} | mediaRegex=${Regex("""(?i)(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""").findAll(r0.html()).toList().size}")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                Log.w("EshkLinks", "tryDirectExtract iframe src=$src")
                var found = false
                loadExtractor(src, data, subtitleCallback) { link -> callback(link); found = true }
                if (found) return true
            }
        }
        val mediaRegex = Regex("""(?i)(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""")
        for (match in mediaRegex.findAll(r0.html())) {
            val url = match.groupValues[1]
            Log.w("EshkLinks", "tryDirectExtract media url=$url")
            var found = false
            loadExtractor(url, data, subtitleCallback) { link -> callback(link); found = true }
            if (found) return true
        }
        Log.w("EshkLinks", "tryDirectExtract - nothing found")
        return false
    }
}
