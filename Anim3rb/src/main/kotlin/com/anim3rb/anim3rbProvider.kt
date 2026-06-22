package com.anime3rb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.provider.BaseProvider
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Anim3rbProvider : BaseProvider() {
    override val providerName = "Anim3rb"
    override var name: String
        get() = providerName
        set(value) {}
    override val baseDomain = "anime3rb.com"
    override val githubConfigUrl = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/anim3rb.json"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val hasMainPage = true

    private val context: android.content.Context? get() = PluginContext.context

    companion object {
        private var savedCookies: String = ""
        private const val TAG = "Anime3rb_Log"
        private val NON_DIGITS = Regex("[^0-9]")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+")
    }

    override fun getParser(): com.cloudstream.shared.parsing.NewBaseParser {
        return object : com.cloudstream.shared.parsing.NewBaseParser() {
            override val mainPageConfig = com.cloudstream.shared.parsing.MainPageConfig(
                container = "a.video-card",
                title = com.cloudstream.shared.parsing.CssSelector(query = "h3.title-name", attr = "text"),
                url = com.cloudstream.shared.parsing.CssSelector(query = "", attr = "href"),
                poster = com.cloudstream.shared.parsing.CssSelector(query = "img", attr = "src")
            )
            override val loadPageConfig = com.cloudstream.shared.parsing.LoadPageConfig(
                title = com.cloudstream.shared.parsing.CssSelector(query = "h1", attr = "text"),
                poster = com.cloudstream.shared.parsing.CssSelector(query = "img[alt*='بوستر']", attr = "src"),
                plot = com.cloudstream.shared.parsing.CssSelector(query = "div.py-4.flex.flex-col.gap-2 p", attr = "text")
            )
            override val episodeConfig = com.cloudstream.shared.parsing.EpisodeConfig(
                container = ".video-list a, .episodes-list a",
                title = com.cloudstream.shared.parsing.CssSelector(query = ".video-data p", attr = "text", regex = "(.+)"),
                url = com.cloudstream.shared.parsing.CssSelector(query = "", attr = "href"),
                episode = com.cloudstream.shared.parsing.CssSelector(query = ".video-data span", attr = "text", regex = "(\\d+)")
            )
            override val watchServersSelectors = com.cloudstream.shared.parsing.WatchServerSelector()
        }
    }

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private suspend fun getDocumentSmart(url: String): Document? {
        val ctx = context ?: return httpService.getDocument(url)
        val result = loadVisibleWebViewCheck(url, ctx)
        return when (result) {
            is SmartResult.Success -> result.document
            is SmartResult.NeedsCaptcha -> CloudflareSolver.solve(ctx as? Activity, url, USER_AGENT)
            else -> httpService.getDocument(url)
        }
    }

    sealed class SmartResult {
        data class Success(val document: Document) : SmartResult()
        object NeedsCaptcha : SmartResult()
        object Error : SmartResult()
    }

    private suspend fun loadVisibleWebViewCheck(url: String, ctx: android.content.Context): SmartResult {
        return suspendCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val activity = ctx as? Activity
                if (activity == null || activity.isFinishing) {
                    continuation.resume(SmartResult.Error); return@post
                }
                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setCancelable(false)
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.window?.setDimAmount(0f)
                val params = WindowManager.LayoutParams().apply {
                    copyFrom(dialog.window?.attributes)
                    width = 1; height = 1
                    gravity = Gravity.TOP or Gravity.START
                    x = -10; y = -10
                }
                dialog.window?.attributes = params
                val webView = WebView(activity)
                dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                try {
                    webView.settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                        userAgentString = USER_AGENT; blockNetworkImage = true
                    }
                } catch (_: Exception) {}
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true); cookieManager.setAcceptThirdPartyCookies(webView, true)
                var isFinished = false
                val handler = Handler(Looper.getMainLooper())
                fun finish(result: SmartResult) {
                    if (isFinished) return; isFinished = true
                    handler.removeCallbacksAndMessages(null)
                    try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                    try { webView.destroy() } catch (_: Exception) {}
                    if (result is SmartResult.Success) { cookieManager.flush(); savedCookies = cookieManager.getCookie(url) ?: "" }
                    continuation.resume(result)
                }
                val poller = object : Runnable {
                    override fun run() {
                        if (isFinished) return
                        webView.evaluateJavascript("""
                            (function() {
                                const html = document.documentElement.innerHTML;
                                if (html.includes('challenge-platform') || html.includes('cf-turnstile') || document.getElementById('cf-wrapper')) return 'CAPTCHA';
                                if (document.querySelector('.video-card, .main-content')) return 'SUCCESS::' + html;
                                return 'POLLING';
                            })();
                        """) { result ->
                            if (isFinished) return@evaluateJavascript
                            when (result?.removeSurrounding("\"")) {
                                "CAPTCHA" -> finish(SmartResult.NeedsCaptcha)
                                else -> {
                                    val cleanResult = result?.removeSurrounding("\"")
                                    if (cleanResult?.startsWith("SUCCESS::") == true) {
                                        val html = cleanResult.substringAfter("SUCCESS::").replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"").replace("\\\\", "\\")
                                        finish(SmartResult.Success(Jsoup.parse(html)))
                                    } else handler.postDelayed(this, 1000)
                                }
                            }
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { handler?.proceed() }
                }
                try { dialog.show(); webView.loadUrl(url); handler.postDelayed(poller, 1000); handler.postDelayed({ if (!isFinished) finish(SmartResult.Error) }, 20000) } catch (_: Exception) { finish(SmartResult.Error) }
            }
        }
    }

    override val mainPage by lazy { mainPageOf("$mainUrl/" to "الرئيسية") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()
        try {
            doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()?.let { header ->
                header.parent()?.parent()?.parent()?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull { toSearchResult(it) }
                    ?.let { if (it.isNotEmpty()) homeSets.add(HomePageList("الأنميات المثبتة", it)) }
            }
            doc.select("#videos a.video-card").mapNotNull { toSearchResult(it) }.let { if (it.isNotEmpty()) homeSets.add(HomePageList("أحدث الحلقات", it)) }
            doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()?.let { header ->
                header.parent()?.parent()?.parent()?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull { toSearchResult(it) }
                    ?.let { if (it.isNotEmpty()) homeSets.add(HomePageList("آخر الأنميات المضافة", it)) }
            }
        } catch (_: Exception) {}
        return newHomePageResponse(homeSets)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val rawTitle = element.select("h3.title-name").text()
            val title = cleanTitleText(rawTitle)
            val href = toAbsoluteUrl(element.attr("href"))
            val posterUrl = element.select("img").attr("src")
            val episodeNum = cleanTitleText(element.select("p.number").text()).filter { it.isDigit() }.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl; addDubStatus(false, episodeNum) }
        } catch (_: Exception) { null }
    }

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        return searchImpl(query, useNoFallback = false)
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchImpl(query, useNoFallback = true)
    }

    private suspend fun searchImpl(query: String, useNoFallback: Boolean): List<SearchResponse> {
        val mainDoc = if (useNoFallback) httpService.getDocumentNoFallback(mainUrl) else getDocumentSmart(mainUrl)
        mainDoc ?: return emptyList()
        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]") ?: return emptyList()
        val csrfToken = scriptTag.attr("data-csrf").ifBlank { return emptyList() }
        val form = mainDoc.selectFirst("form[wire:id]") ?: return emptyList()
        val snapshotRaw = form.attr("wire:snapshot").ifBlank { return emptyList() }
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)
        val updateUrl = "$mainUrl/livewire/update"
        val payload = mapOf("_token" to csrfToken, "components" to listOf(mapOf("snapshot" to snapshotStr, "updates" to mapOf("query" to query), "calls" to emptyList<Any>())))
        val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*", "Content-Type" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/", "Cookie" to savedCookies)
        val postRes = app.post(updateUrl, headers = headers, json = payload)
        val responseJson = AppUtils.parseJson<Map<String, Any>>(postRes.text)
        val components = responseJson["components"] as? List<Map<String, Any>> ?: return emptyList()
        val htmlContent = (components.firstOrNull()?.get("effects") as? Map<String, Any>)?.get("html") as? String ?: return emptyList()
        return Jsoup.parse(htmlContent).select("a.simple-title-card").mapNotNull { item ->
            val rawTitle = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val link = toAbsoluteUrl(item.attr("href"))
            val rating = item.selectFirst(".badge")?.text()?.trim() ?: "N/A"
            val type = if (rating.contains("Movie") || rating.contains("Film") || rawTitle.contains("فيلم")) TvType.AnimeMovie else TvType.Anime
            newAnimeSearchResponse(cleanTitleText(rawTitle), link, type) { posterUrl = item.selectFirst("img")?.attr("src") }
        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ").replace("\n", " ").replace(Regex("بترجمة.*"), "").replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun forceLoadAllEpisodes(url: String, timeoutMs: Long = 20000L): Document? {
        val ctx = context ?: return httpService.getDocument(url)
        return suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(ctx)
                webView.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = false
                    userAgentString = USER_AGENT; blockNetworkImage = true; loadsImagesAutomatically = false
                    mediaPlaybackRequiresUserGesture = true; javaScriptCanOpenWindowsAutomatically = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true); cookieManager.setAcceptThirdPartyCookies(webView, true)
                var finished = false
                fun finish(doc: Document?) {
                    if (finished) return; finished = true
                    try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                    try { cookieManager.flush(); val newCookies = cookieManager.getCookie(url); if (!newCookies.isNullOrEmpty()) savedCookies = newCookies } catch (_: Exception) {}
                    cont.resume(doc)
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        var attempts = 0
                        val handler = Handler(Looper.getMainLooper())
                        val checkRunnable = object : Runnable {
                            override fun run() {
                                if (finished) return
                                view?.evaluateJavascript("(function() { var count = document.querySelectorAll('.video-list a, .episodes-list a').length; if (count > 0) return document.documentElement.outerHTML; return null; })()") { html ->
                                    if (html != null && html != "null" && html.length > 100) {
                                        var cleanHtml = html
                                        if (cleanHtml.startsWith("\"") && cleanHtml.endsWith("\"")) cleanHtml = cleanHtml.substring(1, cleanHtml.length - 1)
                                        cleanHtml = cleanHtml.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"").replace("\\\\", "\\")
                                        finish(Jsoup.parse(cleanHtml))
                                    } else { attempts++; if (attempts < 40) handler.postDelayed(this, 250) else finish(null) }
                                }
                            }
                        }
                        checkRunnable.run()
                    }
                }
                webView.loadUrl(url)
                Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, timeoutMs)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = forceLoadAllEpisodes(fullUrl) ?: return null
        return try {
            var rawTitle = doc.selectFirst("h1")?.text() ?: ""
            rawTitle = cleanTitleText(rawTitle)
            val title = TITLE_EP_REGEX.replace(rawTitle, "").replace("( مسلسل )", "").replace("( فيلم )", "").trim()
            val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""
            var episodes = doc.select(".video-list a, .episodes-list a").mapNotNull { element ->
                val href = toAbsoluteUrl(element.attr("href").ifBlank { return@mapNotNull null })
                val videoData = element.selectFirst(".video-data")
                val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: videoData?.children()?.getOrNull(0)?.text() ?: "")
                val epNum = NON_DIGITS.replace(epText, "").toIntOrNull()
                val epName = cleanTitleText(videoData?.selectFirst("p")?.text() ?: videoData?.children()?.getOrNull(1)?.text() ?: "")
                newEpisode(href) { name = if (epName.isNotBlank()) epName else epText; episode = epNum; posterUrl = element.selectFirst("img")?.attr("src").orEmpty() }
            }
            if (episodes.size > 1) {
                val firstEpNum = episodes.first().episode ?: 0; val lastEpNum = episodes.last().episode ?: 0
                if (firstEpNum > lastEpNum && lastEpNum != 0) episodes = episodes.reversed()
            }
            var desc = ""
            if (episodes.isNotEmpty()) {
                try {
                    val epDoc = httpService.getDocument(episodes.first().data)
                    desc = epDoc?.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis")?.joinToString("\n") { it.text().trim() } ?: ""
                    if (desc.isBlank()) desc = epDoc?.select("meta[name=description]")?.attr("content")?.trim() ?: ""
                } catch (_: Exception) {}
            }
            if (desc.isBlank()) desc = doc.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
            newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) { this.posterUrl = poster; this.plot = desc }
        } catch (_: Exception) { null }
    }

    private suspend fun hijackAndExtractRaw(url: String, timeoutMs: Long = 60000L): List<Pair<String, String>> {
        val ctx = context ?: return emptyList()
        return suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(ctx)
                webView.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; userAgentString = USER_AGENT
                    blockNetworkImage = false; mediaPlaybackRequiresUserGesture = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                val extractedRaw = mutableListOf<Pair<String, String>>()
                var isDone = false
                val handler = Handler(Looper.getMainLooper())
                fun finish() {
                    if (isDone) return; isDone = true
                    try { handler.removeCallbacksAndMessages(null); (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy() } catch (_: Exception) {}
                    cont.resume(extractedRaw.distinctBy { it.first })
                }
                handler.postDelayed({ finish() }, timeoutMs)
                webView.webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) = h!!.proceed()
                    override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                        if (reqUrl.contains("/player/") && !reqUrl.contains("cf_token=")) {
                            Thread {
                                try {
                                    val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                    connection.requestMethod = "GET"
                                    request.requestHeaders?.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v) }
                                    CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                                    connection.setRequestProperty("Referer", url)
                                    val playerHtml = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                                    Regex("""var\s+video_sources\s*=\s*(\[[^;]+]);""").find(playerHtml)?.let { m ->
                                        AppUtils.parseJson<List<Map<String, Any?>>>(m.groupValues[1]).forEach { item ->
                                            val src = item["src"]?.toString() ?: item["file"]?.toString()
                                            val label = item["label"]?.toString() ?: "Default"
                                            if (!src.isNullOrBlank()) extractedRaw.add(src to label)
                                        }
                                        if (extractedRaw.isNotEmpty()) handler.post { finish() }
                                    }
                                } catch (_: Exception) {}
                            }.start()
                            return super.shouldInterceptRequest(view, request)
                        }
                        if (reqUrl.contains("/sources") && reqUrl.contains("cf_token=")) {
                            try {
                                val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                request.requestHeaders?.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v) }
                                CookieManager.getInstance().getCookie(reqUrl)?.let { connection.setRequestProperty("Cookie", it) }
                                val jsonString = String((if (connection.responseCode < 400) connection.inputStream else connection.errorStream).readBytes(), Charsets.UTF_8)
                                AppUtils.parseJson<List<Map<String, Any?>>>(jsonString).forEach { item ->
                                    val src = item["src"]?.toString() ?: item["file"]?.toString()
                                    val label = item["label"]?.toString() ?: "Default"
                                    if (!src.isNullOrBlank()) extractedRaw.add(src to label)
                                }
                                if (extractedRaw.isNotEmpty()) handler.post { finish() }
                                return android.webkit.WebResourceResponse(connection.contentType?.split(";")?.get(0) ?: "application/json", "UTF-8", ByteArrayInputStream(jsonString.toByteArray())).apply {
                                    responseHeaders = mutableMapOf("Access-Control-Allow-Origin" to "*")
                                }
                            } catch (_: Exception) {}
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webView.loadUrl(url)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val rawLinks = hijackAndExtractRaw(data)
        if (rawLinks.isEmpty()) return false
        for ((src, label) in rawLinks) {
            try {
                callback(newExtractorLink(source = this.name, name = "$name $label", url = src, type = ExtractorLinkType.VIDEO) { referer = "https://video.vid3rb.com/" })
            } catch (_: Exception) {}
        }
        return true
    }
}
