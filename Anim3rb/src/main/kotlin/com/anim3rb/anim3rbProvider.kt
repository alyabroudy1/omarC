package com.anime3rb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Anime3rb(val context: Context) : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anim3rbtest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private var savedCookies: String = ""
        private const val TAG = "Anime3rb_Log"
        private val NON_DIGITS = Regex("[^0-9]")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+")
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
        val result = loadVisibleWebViewCheck(url)

        return when (result) {
            is SmartResult.Success -> result.document
            is SmartResult.NeedsCaptcha -> {
               val activity = this.context as? Activity
                CloudflareSolver.solve(activity, url, USER_AGENT)
            }
            else -> null
        }
    }

    sealed class SmartResult {
        data class Success(val document: Document) : SmartResult()
        object NeedsCaptcha : SmartResult()
        object Error : SmartResult()
    }

    private suspend fun loadVisibleWebViewCheck(url: String): SmartResult {
        return suspendCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val activity = this.context as? Activity
                if (activity == null || activity.isFinishing) {
                    continuation.resume(SmartResult.Error)
                    return@post
                }

                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setCancelable(false)

                dialog.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.window?.setDimAmount(0f)

                val params = WindowManager.LayoutParams()
                params.copyFrom(dialog.window?.attributes)
                params.width = 1
                params.height = 1
                params.gravity = Gravity.TOP or Gravity.START
                params.x = -10 // خارج الشاشة
                params.y = -10 // خارج الشاشة
                dialog.window?.attributes = params

                val webView = WebView(activity)
                dialog.setContentView(
                    webView,
                    ViewGroup.LayoutParams(1, 1) // حجم 1x1 بكسل
                )

                try {
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = USER_AGENT
                        blockNetworkImage = true // لا نحتاج الصور في الفحص المخفي
                    }
                } catch (_: Exception) {}

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isFinished = false
                val handler = Handler(Looper.getMainLooper())

                fun finish(result: SmartResult) {
                    if (isFinished) return
                    isFinished = true
                    handler.removeCallbacksAndMessages(null)
                    try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                    try { webView.destroy() } catch (_: Exception) {}

                    if (result is SmartResult.Success) {
                        cookieManager.flush()
                        savedCookies = cookieManager.getCookie(url) ?: ""
                    }
                    continuation.resume(result)
                }

                val poller = object : Runnable {
                    override fun run() {
                        if (isFinished) return
                        val jsCheck = """
                        (function() {
                            const html = document.documentElement.innerHTML;
                            if (html.includes('challenge-platform') || html.includes('cf-turnstile') || document.getElementById('cf-wrapper')) return 'CAPTCHA';
                            if (document.querySelector('.video-card, .main-content')) return 'SUCCESS::' + html;
                            return 'POLLING';
                        })();
                        """
                        webView.evaluateJavascript(jsCheck) { result ->
                            if (isFinished) return@evaluateJavascript
                            val cleanResult = result?.removeSurrounding("\"")
                            when {
                                cleanResult == "CAPTCHA" -> finish(SmartResult.NeedsCaptcha)
                                cleanResult?.startsWith("SUCCESS::") == true -> {
                                    val html = cleanResult.substringAfter("SUCCESS::")
                                    val cleanHtml = html.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"").replace("\\\\", "\\")
                                    finish(SmartResult.Success(Jsoup.parse(cleanHtml)))
                                }
                                else -> handler.postDelayed(this, 1000)
                            }
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }

                try {
                    dialog.show()
                    webView.loadUrl(url)
                    handler.postDelayed(poller, 1000)
                    handler.postDelayed({ if (!isFinished) finish(SmartResult.Error) }, 20000)
                } catch (e: Exception) {
                    finish(SmartResult.Error)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()
        try {
            doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()?.let { header ->
                val list = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!list.isNullOrEmpty()) homeSets.add(HomePageList("الأنميات المثبتة", list))
            }
            val latest = doc.select("#videos a.video-card").mapNotNull { toSearchResult(it) }
            if (latest.isNotEmpty()) homeSets.add(HomePageList("أحدث الحلقات", latest))

            doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()?.let { header ->
                val list = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!list.isNullOrEmpty()) homeSets.add(HomePageList("آخر الأنميات المضافة", list))
            }
        } catch (e: Exception) { Log.e(TAG, "MainPage Error: ${e.message}") }
        return newHomePageResponse(homeSets)
    }

    override val mainPage = mainPageOf("$mainUrl/" to "الرئيسية")

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val rawTitle = element.select("h3.title-name").text()
            val title = cleanTitleText(rawTitle)
            val href = toAbsoluteUrl(element.attr("href"))
            val posterUrl = element.select("img").attr("src")
            val episodeText = cleanTitleText(element.select("p.number").text())
            val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val mainDoc = getDocumentSmart(mainUrl) ?: return emptyList()

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = scriptTag?.attr("data-csrf") ?: return emptyList()

        val form = mainDoc.selectFirst("form[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: return emptyList()
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Cookie" to savedCookies // 👈 إرسال الـ Session هنا
        )

        val updateUrl = "$mainUrl/livewire/update"
        val payload = mapOf(
            "_token" to csrfToken,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshotStr,
                    "updates" to mapOf("query" to query),
                    "calls" to emptyList<Any>()
                )
            )
        )

        val postRes = app.post(updateUrl, headers = headers, json = payload)

        if (postRes.code != 200) return emptyList()

        val responseJson = AppUtils.parseJson<Map<String, Any>>(postRes.text)
        val components = responseJson["components"] as? List<Map<String, Any>> ?: return emptyList()
        val effects = components.firstOrNull()?.get("effects") as? Map<String, Any> ?: return emptyList()
        val htmlContent = effects["html"] as? String ?: return emptyList()

        val soupResults = Jsoup.parse(htmlContent)

        return soupResults.select("a.simple-title-card").mapNotNull { item ->

            val rawTitle = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val title = cleanTitleText(rawTitle)

            val link = item.attr("href")
            val absoluteLink = toAbsoluteUrl(link)

            val img = item.selectFirst("img")
            val image = img?.attr("src")

            val ratingTag = item.selectFirst(".badge")
            val rating = ratingTag?.text()?.trim() ?: "N/A"

            val type = if (rating.contains("Movie") || rating.contains("Film") || title.contains("فيلم")) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            newAnimeSearchResponse(title, absoluteLink, type) {
                this.posterUrl = image
            }
        }
    }

















    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun forceLoadAllEpisodes(url: String, timeoutMs: Long = 20000L): org.jsoup.nodes.Document? =
        suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(this.context)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    userAgentString = USER_AGENT
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                var finished = false

                fun finish(doc: org.jsoup.nodes.Document?) {
                    if (finished) return
                    finished = true
                    try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                    try { cookieManager.flush(); val newCookies = cookieManager.getCookie(url); if (!newCookies.isNullOrEmpty()) savedCookies = newCookies } catch (_: Exception) {}
                    cont.resume(doc)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        var attempts = 0
                        val maxAttempts = 40
                        val handler = Handler(Looper.getMainLooper())
                        val checkRunnable = object : Runnable {
                            override fun run() {
                                if (finished) return
                                val jsCheck = """
                                    (function() {
                                        var count = document.querySelectorAll('.video-list a, .episodes-list a').length;
                                        if (count > 0) return document.documentElement.outerHTML;
                                        return null;
                                    })();
                                """
                                view?.evaluateJavascript(jsCheck) { html ->
                                    if (html != null && html != "null" && html.length > 100) {
                                        var cleanHtml = html
                                        if (cleanHtml.startsWith("\"") && cleanHtml.endsWith("\"")) cleanHtml = cleanHtml.substring(1, cleanHtml.length - 1)
                                        cleanHtml = cleanHtml.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"").replace("\\\\", "\\")
                                        finish(Jsoup.parse(cleanHtml))
                                    } else {
                                        attempts++
                                        if (attempts < maxAttempts) handler.postDelayed(this, 250)
                                        else finish(null)
                                    }
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

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = forceLoadAllEpisodes(fullUrl) ?: return null
        return try {
            var rawTitle = doc.selectFirst("h1")?.text() ?: ""
            rawTitle = cleanTitleText(rawTitle)

            val title = TITLE_EP_REGEX.replace(rawTitle, "")
                .replace("( مسلسل )", "")
                .replace("( فيلم )", "")
                .trim()

            val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""

            val elements = doc.select(".video-list a, .episodes-list a")
            var episodes = elements.mapNotNull { element ->
                val rawHref = element.attr("href")
                if (rawHref.isNullOrBlank()) return@mapNotNull null
                val href = toAbsoluteUrl(rawHref)

                val videoData = element.selectFirst(".video-data")

                val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: videoData?.children()?.getOrNull(0)?.text() ?: "")
                val epNum = NON_DIGITS.replace(epText, "").toIntOrNull()

                val epName = cleanTitleText(videoData?.selectFirst("p")?.text() ?: videoData?.children()?.getOrNull(1)?.text() ?: "")

                val imgAttr = element.selectFirst("img")?.attr("src").orEmpty()

                newEpisode(href) {
                    name = if (epName.isNotBlank()) epName else epText
                    episode = epNum
                    posterUrl = imgAttr
                }
            }

            if (episodes.size > 1) {
                val firstEpNum = episodes.first().episode ?: 0
                val lastEpNum = episodes.last().episode ?: 0
                if (firstEpNum > lastEpNum && lastEpNum != 0) {
                    episodes = episodes.reversed()
                }
            }

            var desc = ""
            if (episodes.isNotEmpty()) {
                try {
                    val sampleEpisodeUrl = episodes.first().data
                    val epDoc = app.get(sampleEpisodeUrl).document
                    desc = epDoc.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
                    if (desc.isBlank()) {
                        desc = epDoc.select("meta[name=description]").attr("content").trim()
                    }
                } catch (e: Exception) {

                }
            }

            if (desc.isBlank()) {
                desc = doc.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
            }

            newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = desc
            }
        } catch (e: Exception) {

            null
        }
    }

    private suspend fun hijackAndExtractRaw(
        url: String,
        timeoutMs: Long = 60_000L
    ): List<Pair<String, String>> = suspendCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(this.context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
                blockNetworkImage = false
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            val extractedRaw = mutableListOf<Pair<String, String>>()
            var isDone = false
            val handler = Handler(Looper.getMainLooper())

            fun finish() {
                if (isDone) return
                isDone = true
                try {
                    handler.removeCallbacksAndMessages(null)
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                } catch (e: Exception) {

                }
                cont.resume(extractedRaw.distinctBy { it.first })
            }

            handler.postDelayed({ finish() }, timeoutMs)

            webView.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) = h!!.proceed()

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    if (reqUrl.contains("/player/") && !reqUrl.contains("cf_token=")) {
                        Thread {
                            try {
                                val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                                }
                                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                                connection.setRequestProperty("Referer", url)

                                val playerHtml = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                                val jsonPattern =
                                    """var\s+video_sources\s*=\s*(\[[^;]+]);""".toRegex()
                                val jsonMatch = jsonPattern.find(playerHtml)

                                if (jsonMatch != null) {
                                    val jsonStr = jsonMatch.groupValues[1]
                                    val linksFromJson = AppUtils.parseJson<List<Map<String, Any?>>>(jsonStr)
                                    linksFromJson.forEach { item ->
                                        val src = item["src"]?.toString() ?: item["file"]?.toString()
                                        val label = item["label"]?.toString() ?: "Default"
                                        if (!src.isNullOrBlank()) extractedRaw.add(src to label)
                                    }

                                    if (extractedRaw.isNotEmpty()) {
                                        handler.post { finish() }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }.start()
                        return super.shouldInterceptRequest(view, request)
                    }

                    if (reqUrl.contains("/sources") && reqUrl.contains("cf_token=")) {
                        try {
                            val connection = URL(reqUrl).openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            request.requestHeaders?.forEach { (k, v) ->
                                if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                            }
                            CookieManager.getInstance().getCookie(reqUrl)?.let { connection.setRequestProperty("Cookie", it) }

                            val responseBytes = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).readBytes()
                            val jsonString = String(responseBytes, Charsets.UTF_8)

                            val linksFromJson = AppUtils.parseJson<List<Map<String, Any?>>>(jsonString)
                            linksFromJson.forEach { item ->
                                val src = item["src"]?.toString() ?: item["file"]?.toString()
                                val label = item["label"]?.toString() ?: "Default"
                                if (!src.isNullOrBlank()) extractedRaw.add(src to label)
                            }

                            if (extractedRaw.isNotEmpty()) {
                                handler.post { finish() }
                            }

                            val contentType = connection.contentType?.split(";")?.get(0) ?: "application/json"
                            return android.webkit.WebResourceResponse(contentType, "UTF-8", ByteArrayInputStream(responseBytes)).apply {
                                responseHeaders = mutableMapOf("Access-Control-Allow-Origin" to "*")
                            }

                        } catch (e: Exception) {

                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView.loadUrl(url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = hijackAndExtractRaw(data)

        if (rawLinks.isEmpty()) {

            return false
        }

        rawLinks.forEach { (src, label) ->
            try {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} $label",
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = "https://video.vid3rb.com/"
                    }
                )
            } catch (e: Exception) {

            }
        }
        return true
    }

    private fun extractQuality(label: String): Int {
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(label)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}