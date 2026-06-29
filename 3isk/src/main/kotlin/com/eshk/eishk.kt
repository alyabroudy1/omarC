package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

        val r0 = httpService.getDocument(data, checkDomainChange = true, rewriteDomain = true)
        if (r0 == null) { Log.w("EshkLinks", "r0 getDocument returned null"); return false }

        if (tryDirectExtract(r0, data, subtitleCallback, callback)) return true

        Log.w("EshkLinks", "direct extraction failed, trying POST workflow fallback")
        return tryPostWorkflow(r0, data, subtitleCallback, callback)
    }

    private suspend fun tryPostWorkflow(
        r0: Document, data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var watchForm = r0.selectFirst("button.single-watch-btn")?.parent()
        if (watchForm == null) {
            for (f in r0.select("form")) {
                val act = f.attr("action")
                if (act.contains("3isk") || act.contains("aa.3isk") || act.contains("watch")) {
                    watchForm = f; break
                }
            }
        }
        if (watchForm == null) { Log.w("EshkLinks", "no watchForm found"); return false }
        Log.w("EshkLinks", "watchForm action='${watchForm.attr("action")}'")

        val firstPostUrl = resolveUrlFromForm(watchForm)
        val firstFormData = watchForm.select("input[type=hidden]")
            .associate { it.attr("name") to it.attr("value") }.toMutableMap()
        val watchBtn = r0.selectFirst("button.single-watch-btn")
        if (watchBtn != null) {
            val btnName = watchBtn.attr("name")
            if (btnName.isNotBlank()) firstFormData[btnName] = watchBtn.attr("value")
        }

        val r1Text = httpService.postText(firstPostUrl, firstFormData, referer = data)
        if (r1Text == null) { Log.w("EshkLinks", "first POST returned null"); return false }

        val myUrlMatch = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1Text)
        val newsMatch = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1Text)
        if (myUrlMatch == null || newsMatch == null) { Log.w("EshkLinks", "myUrl/news regex not found"); return false }

        val nextPost = myUrlMatch.groupValues[1]
        val newsVal = newsMatch.groupValues[1]

        val r2Text = httpService.postText(nextPost, mapOf("news" to newsVal, "u" to "", "submit" to "submit"), referer = nextPost)
        if (r2Text == null) { Log.w("EshkLinks", "second POST returned null"); return false }

        val r2Doc = org.jsoup.Jsoup.parse(r2Text)
        val iframeSrc = r2Doc.select("iframe").firstNotNullOfOrNull { it.attr("src").ifBlank { null } }
        if (iframeSrc == null) { Log.w("EshkLinks", "no iframe in second POST response"); return false }
        Log.w("EshkLinks", "iframeSrc=$iframeSrc")

        var foundAny = false
        loadExtractor(iframeSrc, nextPost, subtitleCallback) { link ->
            callback(link); foundAny = true
        }
        return foundAny
    }

    private suspend fun tryDirectExtract(
        r0: Document, data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (iframe in r0.select("iframe")) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                Log.w("EshkLinks", "fallback iframe=$src")
                var found = false
                loadExtractor(src, data, subtitleCallback) { link ->
                    callback(link); found = true
                }
                if (found) return true
            }
        }
        val mediaRegex = Regex("""(?i)(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""")
        for (match in mediaRegex.findAll(r0.html())) {
            val url = match.groupValues[1]
            Log.w("EshkLinks", "fallback direct media=$url")
            var found = false
            loadExtractor(url, data, subtitleCallback) { link ->
                callback(link); found = true
            }
            if (found) return true
        }
        Log.w("EshkLinks", "fallback found nothing")
        return false
    }

    private fun resolveUrlFromForm(form: Element): String {
        val action = form.attr("action")
        if (action.startsWith("http")) return action
        return "$mainUrl/$action".replace("//", "/").replace("https:/", "https://")
    }
}
