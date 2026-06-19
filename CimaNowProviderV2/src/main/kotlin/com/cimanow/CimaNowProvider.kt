package com.cimanow

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.webview.NavigationEngine
import com.cloudstream.shared.webview.WebViewFlowHelper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/// Main provider for CimaNow.cc — a multi-server movie/series streaming site.
///
/// Flow:
///   search/getMainPage/load → decodeHtml (unobfuscate page) → parse results
///   loadLinks → resolveFreex2line (JS challenge bypass) → decode watch page → extract servers
///
/// Server types handled: CimaNow native, VidPro, Govid, Vidlook, Streamwish,
/// Streamfile/Luluvid, Vadbam/Viidshare, Jetload, Forafile
class CimaNowProvider(private val context: Context) : MainAPI() {

    override var name = "Cimanow"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val usesWebView = false

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderHtmlInWebView(
        html: String,
        baseUrl: String,
        timeoutMs: Long = 12_000L
    ): String? = withContext(Dispatchers.Main) {
        val activity = ActivityProvider.currentActivity
        if (activity == null) {
            Log.w(TAG, "   renderHtmlInWebView: No Activity available")
            return@withContext null
        }

        ActivityProvider.initCompat(context)
        val deferred = CompletableDeferred<String?>()
        var delivered = false
        var webView: WebView? = null
        var interceptedCount = 0
        var proxySuccessCount = 0

        val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeoutMs)
            if (!delivered) {
                delivered = true
                Log.w(TAG, "   renderHtmlInWebView: Timeout after ${timeoutMs}ms (intercepted=$interceptedCount, proxied=$proxySuccessCount)")
                cleanupWebView(webView)
                deferred.complete(null)
            }
        }

        try {
            webView = WebView(activity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    @Suppress("DEPRECATION")
                    allowFileAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                }
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            // Sync app.get() session cookies into WebView CookieManager
            // so the page JS's document.cookie reads include them
            try {
                val initialCookies = CookieManager.getInstance().getCookie(baseUrl)
                Log.d(TAG, "   Initial CookieManager cookies for baseUrl: ${initialCookies?.take(80) ?: "none"}")
            } catch (_: Exception) {}

            webView.webViewClient = object : WebViewClient() {
                private fun syncCookiesToWebView(reqUrl: String, cookies: Map<String, String>) {
                    try {
                        if (cookies.isEmpty()) return
                        val domain = try { java.net.URI(reqUrl).host } catch (_: Exception) { null }
                        val cookieDomain = if (domain != null) "domain=$domain" else ""
                        for ((key, value) in cookies) {
                            if (key.isNotBlank() && value.isNotBlank()) {
                                CookieManager.getInstance().setCookie(reqUrl, "$key=$value; path=/; $cookieDomain; secure")
                            }
                        }
                        CookieManager.getInstance().flush()
                        Log.d(TAG, "   [WV] synced ${cookies.size} cookies from app.get to WebView CookieManager for ${reqUrl.take(60)}")
                    } catch (_: Exception) {}
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    val scheme = request.url?.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") return null
                    interceptedCount++

                    if (!reqUrl.contains("cimanow.cc", ignoreCase = true)) {
                        Log.d(TAG, "   [WV:${interceptedCount}] skip non-cimanow ${reqUrl.take(80)}")
                        return null
                    }

                    try {
                        val wvCookies = CookieManager.getInstance().getCookie(reqUrl)
                        val cookieHeaders = if (!wvCookies.isNullOrBlank()) {
                            mapOf("Cookie" to wvCookies)
                        } else emptyMap()
                        Log.d(TAG, "   [WV:${interceptedCount}] proxy ${reqUrl.take(80)} wvCookies=${wvCookies?.take(60) ?: "none"}")

                        val proxyResp = runBlocking {
                            app.get(reqUrl, referer = baseUrl, headers = cookieHeaders)
                        }
                        if (proxyResp.code !in 200..399) {
                            Log.w(TAG, "   [WV:${interceptedCount}] proxy ${reqUrl.take(80)} failed HTTP ${proxyResp.code}")
                            return null
                        }
                        proxySuccessCount++

                        // Sync cookies from app.get response back to WebView CookieManager
                        syncCookiesToWebView(reqUrl, proxyResp.cookies)

                        val mime = when {
                            reqUrl.contains(".js") -> "application/javascript"
                            reqUrl.contains(".css") -> "text/css"
                            reqUrl.contains(".png") -> "image/png"
                            reqUrl.contains(".jpg") || reqUrl.contains(".jpeg") -> "image/jpeg"
                            reqUrl.contains(".svg") -> "image/svg+xml"
                            reqUrl.contains(".woff2") || reqUrl.contains(".woff") -> "font/woff2"
                            reqUrl.contains(".json") -> "application/json"
                            else -> "text/html"
                        }
                        Log.i(TAG, "   [WV:${interceptedCount}] proxy OK ${reqUrl.take(80)} -> ${mime} ${proxyResp.text.length} chars, HTTP ${proxyResp.code}")
                        return WebResourceResponse(mime, "UTF-8", proxyResp.text.byteInputStream())
                    } catch (e: Exception) {
                        Log.w(TAG, "   [WV:${interceptedCount}] proxy EXCEPTION ${reqUrl.take(80)}: ${e.message}")
                        return null
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (delivered) return
                    Log.d(TAG, "   renderHtmlInWebView: onPageFinished url=${url?.take(80)}")

                    CoroutineScope(Dispatchers.Main).launch {
                        val attempt1 = extractHtmlFromWebView(view!!)
                        val doc1 = attempt1?.let { Jsoup.parse(it) }
                        val bodyLen1 = doc1?.body()?.html()?.length ?: 0
                        Log.d(TAG, "   renderHtmlInWebView: immediate extract body=${bodyLen1} chars, title='${doc1?.title()}'")

                        delay(3000L)
                        if (delivered) return@launch

                        val attempt2 = extractHtmlFromWebView(view!!)
                        val doc2 = attempt2?.let { Jsoup.parse(it) }
                        val bodyLen2 = doc2?.body()?.html()?.length ?: 0
                        val hasWatchUl = doc2?.select("ul#watch li[data-index], li[data-index]")?.isNotEmpty() == true
                        Log.d(TAG, "   renderHtmlInWebView: +3s extract body=${bodyLen2} chars, title='${doc2?.title()}', hasWatchUl=$hasWatchUl")

                        // Query the LIVE DOM inside WebView via JS to understand structure
                        try {
                            val jsResult = suspendCancellableCoroutine<String?> { c ->
                                Handler(Looper.getMainLooper()).post {
                                    view!!.evaluateJavascript("""
                                        (function(){
                                            var results = [];
                                            // Find elements with data-index
                                            var diEls = document.querySelectorAll('[data-index]');
                                            results.push('DATA-INDEX_ELS:' + diEls.length);
                                            for(var i=0;i<Math.min(diEls.length,10);i++){
                                                var e=diEls[i];
                                                results.push('  di['+i+']: tag='+e.tagName+' class='+e.className+' id='+e.id+' parent='+e.parentElement?.tagName+'#'+e.parentElement?.id+' data-index='+e.getAttribute('data-index')+' text='+e.textContent?.substring(0,60));
                                            }
                                            // Find elements with id=watch or class=watch
                                            var watchEls = document.querySelectorAll('#watch, .watch, [id*=watch], [class*=watch]');
                                            results.push('WATCH_ELS:' + watchEls.length);
                                            for(var i=0;i<Math.min(watchEls.length,10);i++){
                                                var e=watchEls[i];
                                                results.push('  w['+i+']: tag='+e.tagName+' class='+e.className+' id='+e.id+' children='+e.children.length+' html='+e.innerHTML?.substring(0,200));
                                            }
                                            // Find elements with data-id
                                            var didEls = document.querySelectorAll('[data-id]');
                                            results.push('DATA-ID_ELS:' + didEls.length);
                                            for(var i=0;i<Math.min(didEls.length,10);i++){
                                                var e=didEls[i];
                                                results.push('  did['+i+']: tag='+e.tagName+' class='+e.className+' id='+e.id+' data-id='+e.getAttribute('data-id')+' text='+e.textContent?.substring(0,60));
                                            }
                                            // Find all ul elements
                                            var uls = document.querySelectorAll('ul');
                                            results.push('UL_ELS:' + uls.length);
                                            for(var i=0;i<Math.min(uls.length,20);i++){
                                                var e=uls[i];
                                                results.push('  ul['+i+']: id='+e.id+' class='+e.className+' li_count='+e.querySelectorAll('li').length);
                                            }
                                            return results.join('\\n');
                                        })();
                                    """.trimIndent()) { r ->
                                        if (c.isActive) c.resume(r) {}
                                    }
                                }
                            }
                            if (jsResult != null && jsResult != "null") {
                                val cleaned = try { org.json.JSONTokener(jsResult).nextValue().toString() } catch (_: Exception) { jsResult }
                                Log.i(TAG, "   === LIVE DOM QUERY RESULTS ===\n$cleaned")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "   Live DOM query failed: ${e.message}")
                        }

                        // Log sections of HTML containing 'data-index' and 'watch'
                        if (attempt2 != null) {
                            val html2 = attempt2
                            val diIdx = html2.indexOf("data-index", ignoreCase = true)
                            if (diIdx >= 0) {
                                val start = maxOf(0, diIdx - 300)
                                val end = minOf(html2.length, diIdx + 500)
                                Log.i(TAG, "   === HTML AROUND data-index ===\n${html2.substring(start, end)}")
                            }
                            val watchIdx = html2.indexOf("watch", ignoreCase = true)
                            if (watchIdx >= 0) {
                                val start2 = maxOf(0, watchIdx - 300)
                                val end2 = minOf(html2.length, watchIdx + 500)
                                Log.i(TAG, "   === HTML AROUND 'watch' ===\n${html2.substring(start2, end2)}")
                            }
                            // Also search for server-related keywords
                            for (kw in listOf("server", "سيرفر", "watch-ul", "watchUl", "tab-")) {
                                val kwIdx = html2.indexOf(kw, ignoreCase = true)
                                if (kwIdx >= 0) {
                                    val s = maxOf(0, kwIdx - 200)
                                    val e2 = minOf(html2.length, kwIdx + 400)
                                    Log.i(TAG, "   === HTML AROUND '$kw' ===\n${html2.substring(s, e2)}")
                                }
                            }
                        }

                        if (attempt2 != null && bodyLen2 > 512 && hasWatchUl) {
                            delivered = true
                            timeoutJob.cancel()
                            Log.i(TAG, "   renderHtmlInWebView: Got watch page after 3s (${bodyLen2} chars body)")
                            cleanupWebView(webView)
                            deferred.complete(attempt2)
                            return@launch
                        }

                        delay(4000L)
                        if (delivered) return@launch

                        val attempt3 = extractHtmlFromWebView(view!!)
                        val doc3 = attempt3?.let { Jsoup.parse(it) }
                        val bodyLen3 = doc3?.body()?.html()?.length ?: 0
                        val hasWatchUl3 = doc3?.select("ul#watch li[data-index], li[data-index]")?.isNotEmpty() == true
                        Log.d(TAG, "   renderHtmlInWebView: +7s extract body=${bodyLen3} chars, title='${doc3?.title()}', hasWatchUl3=$hasWatchUl3")

                        delivered = true
                        timeoutJob.cancel()
                        if (attempt3 != null && bodyLen3 > bodyLen2) {
                            Log.i(TAG, "   renderHtmlInWebView: Got ${bodyLen3} chars body after 7s")
                            cleanupWebView(webView)
                            deferred.complete(attempt3)
                        } else if (attempt2 != null && bodyLen2 > 512) {
                            Log.w(TAG, "   renderHtmlInWebView: No improvement at 7s, using 3s result (${bodyLen2} chars)")
                            cleanupWebView(webView)
                            deferred.complete(attempt2)
                        } else {
                            Log.w(TAG, "   renderHtmlInWebView: All attempts produced no useful body")
                            cleanupWebView(webView)
                            deferred.complete(attempt3)
                        }
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true && !delivered) {
                        val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            error?.description?.toString()
                        } else error?.toString()
                        Log.w(TAG, "   renderHtmlInWebView: onReceivedError - $desc  url=${request.url?.toString()?.take(80)}")
                    } else if (!delivered) {
                        Log.d(TAG, "   renderHtmlInWebView: onReceivedError (sub) url=${request?.url?.toString()?.take(80)}")
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val nextUrl = request?.url?.toString() ?: return super.shouldOverrideUrlLoading(view, request)
                    val scheme = request.url?.scheme?.lowercase()
                    if (scheme != null && scheme != "http" && scheme != "https") return true
                    val currentHost = try { java.net.URI(nextUrl).host } catch (_: Exception) { null }
                    val baseHost = try { java.net.URI(baseUrl).host } catch (_: Exception) { null }
                    if (currentHost != null && baseHost != null && currentHost != baseHost) {
                        Log.d(TAG, "   renderHtmlInWebView: BLOCK cross-domain nav to ${nextUrl.take(80)}")
                        return true
                    }
                    if (!nextUrl.contains("/watching/", ignoreCase = true) && nextUrl.contains("cimanow.cc", ignoreCase = true)) {
                        Log.w(TAG, "   renderHtmlInWebView: BLOCK nav away from watch page -> ${nextUrl.take(80)}")
                        return true
                    }
                    Log.d(TAG, "   renderHtmlInWebView: ALLOW nav to ${nextUrl.take(80)}")
                    return false
                }
            }

            webView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    msg?.let {
                        val level = when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> "E"
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> "W"
                            else -> "D"
                        }
                        android.util.Log.println(android.util.Log.INFO, "CimaNowWV", "[$level] ${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
                    }
                    return true
                }
            }

            val spoofedHtml = html.replaceFirst(
                "<script",
                """<script>
(function(){
    try { Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } }); } catch(e) {}
    try {
        var od;
        Object.defineProperty(window, 'DisableDevtool', {
            get: function() {
                return function(o) { o = o || {}; o.ignore = function() { return true; }; o.url = ""; o.timeOutUrl = ""; o.ondevtoolopen = function() {}; if (od) try { return od(o); } catch(e) {} };
            },
            set: function(v) { od = v; },
            configurable: true
        });
    } catch(e) {}
    try { Object.defineProperty(navigator, 'plugins', { get: function() { return [1,2,3,4,5]; } }); } catch(e) {}
    try { Object.defineProperty(navigator, 'languages', { get: function() { return ['ar-SA','en-US','en']; } }); } catch(e) {}
})();
</script><script"""
            )
            Log.i(TAG, "   renderHtmlInWebView: Loading HTML (${spoofedHtml.length} chars, baseUrl=${baseUrl.take(80)})")
            webView.loadDataWithBaseURL(baseUrl, spoofedHtml, "text/html", "UTF-8", null)

        } catch (e: Exception) {
            delivered = true
            timeoutJob.cancel()
            Log.w(TAG, "   renderHtmlInWebView: Exception - ${e.message}")
            cleanupWebView(webView)
            deferred.complete(null)
        }

        deferred.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractHtmlFromWebView(webView: WebView): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                "(function() { return document.documentElement.outerHTML; })();"
            ) { result ->
                val html = try {
                    if (result == null || result == "null") null
                    else org.json.JSONTokener(result).nextValue().toString()
                } catch (e: Exception) { null }
                if (cont.isActive) cont.resume(html) {}
            }
        }
    }

    private fun cleanupWebView(webView: WebView?) {
        try {
            webView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.clearHistory()
                        wv.removeAllViews()
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private val navigationEngine by lazy {
        NavigationEngine { ActivityProvider.currentActivity }
    }

    private suspend fun routeServerByName(
        serverName: String,
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (iframeUrl.isBlank()) {
            Log.w(TAG, "routeServerByName: Empty iframe URL for server '$serverName'")
            return
        }
        Log.d(TAG, "routeServerByName: name='$serverName' iframe=${iframeUrl.take(80)}")
        when {
            serverName.contains("Cima Now", true) || serverName.contains("cima", true) ->
                handlecima(iframeUrl, serverName, callback)
            serverName.contains("VidPro", true) ->
                handleVidPro(iframeUrl, serverName, callback)
            serverName.contains("Govid", true) || serverName.contains("Goovid", true) ->
                handleGovid(iframeUrl, serverName, callback)
            serverName.contains("Vidlook", true) ->
                handleVidlook(iframeUrl, serverName, callback)
            serverName.contains("Streamwish", true) ->
                handleStreamwish(iframeUrl, serverName, callback)
            serverName.contains("Streamfile", true) || serverName.contains("Luluvid", true) ->
                handleStreamfileAndLuluvid(iframeUrl, serverName, callback)
            serverName.contains("Vadbam", true) || serverName.contains("Viidshare", true) ->
                handleVadbamAndViidshare(iframeUrl, serverName, callback)
            serverName.contains("Jetload", true) ->
                handleJetload(iframeUrl, 0, referer, callback)
            serverName.contains("Forafile", true) || iframeUrl.contains("forafile.com") ->
                handleForafile(iframeUrl, 0, referer, callback)
            else -> loadExtractor(iframeUrl, referer, { }, callback)
        }
    }

    private val TAG = "CimaNowDebug"
    private val TAG_LOAD = "CimaNowLoadLinks"

    override val mainPage = mainPageOf(
        mainUrl + "/الاحدث/" to "الاحدث",
        mainUrl + "/category/افلام-اجنبية/page/" to "افلام اجنبية",
        mainUrl + "/category/مسلسلات-اجنبية/page/" to "مسلسلات اجنبية",
        mainUrl + "/category/افلام-نتفليكس/page/" to "افلام نتفليكس",
        mainUrl + "/category/مسلسلات-نتفليكس/page/" to "مسلسلات نتفليكس",
        mainUrl + "/category/افلام-مارفل/page/" to "افلام مارفل",
        mainUrl + "/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        mainUrl + "/category/افلام-عربية/page/" to "افلام عربية",
        mainUrl + "/category/افلام-هندية/page/" to "أفلام هندية",
        mainUrl + "/category/افلام-تركية/page/" to "أفلام تركية",
        mainUrl + "/category/مسلسلات-تركية/page/" to "مسلسلات تركية"
    )

    private data class SvgObject(val stream: String, val hash: String)

    private fun getIntFromText(text: String): Int? {
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    // ==================== decodeHtml (Anti-bot deobfuscation) ====================
    //
    // CimaNow obfuscates HTML by embedding base64-encoded payloads in the page,
    // separated by '~'. A dynamic key `_r` is used to offset each decoded number.
    // This method reverses that: extracts _r, collects base64 chunks, decodes
    // each into numbers, subtracts _r, writes the resulting byte, then re-parses
    // the output as HTML.

    private fun decodeAndWriteFast(chunk: StringBuilder, key: Long, out: ByteArrayOutputStream): Int {
        val r = chunk.length % 4
        if (r > 0) {
            when (r) {
                2 -> chunk.append("==")
                3 -> chunk.append("=")
            }
        }
        return try {
            val bytes = Base64.decode(chunk.toString(), Base64.DEFAULT)
            var num = 0L
            for (b in bytes) {
                val bInt = b.toInt()
                if (bInt in 48..57) {
                    num = num * 10 + (bInt - 48)
                }
            }
            if (num > 0) {
                out.write((num - key).toInt())
                1
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeHtml(doc: Document, sourceLabel: String = "unknown", rawResponseText: String? = null): Document {
        val rawHtml = doc.outerHtml()
        val rawLen = rawHtml.length
        val rawTitle = doc.title()
        val bodyEmpty = doc.body()?.html()?.isBlank() != false

        Log.d(TAG, "decodeHtml[$sourceLabel]: doc.title=\"$rawTitle\", outerHtml.length=$rawLen, bodyEmpty=$bodyEmpty")

        // Try Strategy 1: _r key + base64
        var decoded = decodeWithRKey(rawHtml, sourceLabel)
        if (decoded != null) return Jsoup.parse(decoded)

        // Try Strategy 2: extract base64 data even without _r key, look for alternative keys
        Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy 1 failed, trying Strategy 2 (script extraction)...")
        val scriptDecoded = decodeWithRhino(rawHtml, sourceLabel)
        if (scriptDecoded != null) return Jsoup.parse(scriptDecoded)

        Log.w(TAG, "decodeHtml[$sourceLabel]: ALL strategies failed. rawHtml preview: ${rawHtml.take(500).replace('\n', ' ')}")
        return doc
    }

    /// Decode raw HTTP response body directly (bypasses Jsoup parsing).
    /// The watch page returns ~3MB of JS-obfuscated HTML that Jsoup misparses.
    /// This feeds the raw text directly to the decode strategies.
    private fun decodeHtmlRaw(rawText: String, sourceLabel: String = "raw"): Document? {
        Log.d(TAG, "decodeHtmlRaw[$sourceLabel]: raw text length=${rawText.length}")

        // Strategy 1: _r key + base64 on raw text
        val keyDecoded = decodeWithRKey(rawText, sourceLabel)
        if (keyDecoded != null) {
            val doc = Jsoup.parse(keyDecoded)
            Log.i(TAG, "decodeHtmlRaw[$sourceLabel]: Strategy1 succeeded, decoded ${keyDecoded.length} chars, body=${doc.body()?.html()?.length ?: 0} chars")
            return doc
        }

        // Strategy 2: Rhino on raw text
        Log.d(TAG, "decodeHtmlRaw[$sourceLabel]: Strategy1 failed, trying Rhino...")
        val rhinoDecoded = decodeWithRhino(rawText, sourceLabel)
        if (rhinoDecoded != null) {
            val doc = Jsoup.parse(rhinoDecoded)
            Log.i(TAG, "decodeHtmlRaw[$sourceLabel]: Rhino succeeded, decoded ${rhinoDecoded.length} chars, body=${doc.body()?.html()?.length ?: 0} chars")
            return doc
        }

        Log.w(TAG, "decodeHtmlRaw[$sourceLabel]: ALL strategies failed on raw text")
        return null
    }

    /// Strategy 1: extract _r key + base64 data (current working approach)
    private fun decodeWithRKey(rawHtml: String, sourceLabel: String): String? {
        try {
            val keyMatcher = Pattern.compile("var\\s+_r\\s*=\\s*(\\d+(?:\\+\\d+)*)").matcher(rawHtml)
            if (!keyMatcher.find()) {
                Log.w(TAG, "decodeHtml[$sourceLabel]: no _r key found")
                return null
            }
            val keyExpr = keyMatcher.group(1) ?: return null
            val dynamicKey = keyExpr.split("+").sumOf { it.toLong() }
            Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy1 _r expression='$keyExpr' => key=$dynamicKey")

            val dataMatcher = Pattern.compile("['\"]([A-Za-z0-9+/=~]{20,})['\"]").matcher(rawHtml)
            val extractedData = StringBuilder(100000)
            while (dataMatcher.find()) {
                extractedData.append(dataMatcher.group(1))
            }
            val extractedLen = extractedData.length
            if (extractedData.isEmpty()) {
                Log.w(TAG, "decodeHtml[$sourceLabel]: Strategy1 no base64 data found")
                return null
            }
            Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy1 extracted ${extractedLen} chars, ${extractedData.count { it == '~' }} chunks")

            val out = ByteArrayOutputStream(extractedLen / 4)
            val chunk = StringBuilder(64)
            var decodedCount = 0
            for (i in 0 until extractedLen) {
                val c = extractedData[i]
                when {
                    c == '~' -> {
                        if (chunk.isNotEmpty()) {
                            decodedCount += decodeAndWriteFast(chunk, dynamicKey, out)
                            chunk.setLength(0)
                        }
                    }
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' -> {
                        chunk.append(c)
                    }
                }
            }
            if (chunk.isNotEmpty()) decodedCount += decodeAndWriteFast(chunk, dynamicKey, out)
            Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy1 decoded $decodedCount chunks")

            val decoded = out.toString("UTF-8")
            if (decoded.isBlank()) {
                Log.w(TAG, "decodeHtml[$sourceLabel]: Strategy1 decoded output BLANK")
                return null
            }
            Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy1 decoded ${decoded.length} chars. Preview: ${decoded.take(300).replace('\n', ' ')}")
            return decoded
        } catch (e: Exception) {
            Log.e(TAG, "decodeHtml[$sourceLabel] Strategy1 error: ${e.message}")
        }
        return null
    }

    /// Strategy 2: extract <script> content and evaluate via Rhino with document.write polyfill
    private fun decodeWithRhino(rawHtml: String, sourceLabel: String): String? {
        try {
            // Extract script content - look for type="text/javascript" or language="Javascript"
            val scriptRegex = Regex(
                """<script(?:\s+[^>]*)?>\s*(function\s*\([\s\S]*?)\s*</script>""",
                RegexOption.IGNORE_CASE
            )
            val scriptMatch = scriptRegex.find(rawHtml)
            if (scriptMatch == null) {
                Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy2 no function-based script found")
                return null
            }

            var jsCode = scriptMatch.groupValues[1]
            Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy2 extracted script length: ${jsCode.length}")

            if (jsCode.length < 100) {
                Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy2 script too short, skipping")
                return null
            }

            // Set up Rhino with polyfills for atob and document.write
            val rhino = org.mozilla.javascript.Context.enter()
            try {
                rhino.optimizationLevel = -1
                val scope = rhino.initSafeStandardObjects()

                // Polyfill: document object with write/open/close
                val polyfill = """
                    var document = {
                        written: "",
                        open: function() {},
                        write: function(str) { this.written += str; },
                        close: function() {},
                        writeln: function(str) { this.written += str + "\n"; }
                    };
                    var window = this;
                    var navigator = { userAgent: "Mozilla/5.0" };
                    var location = { href: "", hostname: "" };
                    var _0x3f2a = '';
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    function atob(input) {
                        var str = String(input).replace(/=+$/, '');
                        var output = '';
                        for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                            buffer = chars.indexOf(buffer);
                        }
                        return output;
                    }
                """.trimIndent()

                jsCode = "$polyfill\n$jsCode"
                rhino.evaluateString(scope, jsCode, "JavaScript", 1, null)

                val documentObj = scope.get("document", scope)
                if (documentObj is org.mozilla.javascript.NativeObject) {
                    val written = documentObj.get("written", documentObj)?.toString() ?: ""
                    if (written.isNotBlank()) {
                        Log.d(TAG, "decodeHtml[$sourceLabel]: Strategy2 Rhino decoded ${written.length} chars. Preview: ${written.take(300).replace('\n', ' ')}")
                        return written
                    }
                    Log.w(TAG, "decodeHtml[$sourceLabel]: Strategy2 Rhino document.written is blank")
                } else {
                    Log.w(TAG, "decodeHtml[$sourceLabel]: Strategy2 document is not NativeObject: ${documentObj?.javaClass?.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "decodeHtml[$sourceLabel]: Strategy2 Rhino eval failed: ${e.message}")
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeHtml[$sourceLabel] Strategy2 error: ${e.message}")
        }
        return null
    }

    // ==================== search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query
        Log.d(TAG, "search: query=$q")
        val resp = app.get("$mainUrl/?s=$q", referer = mainUrl)
        Log.d(TAG, "   [search] HTTP ${resp.code} -> ${resp.url}, body=${resp.text.length} chars, cookies=${resp.cookies.size}")
        val doc = resp.document
        val decodedDoc = decodeHtml(doc, "search")
        val results = decodedDoc.select("article").mapNotNull { toSearchResponse(it) }
        Log.d(TAG, "search: found ${results.size} results")
        return results
    }

    // ==================== getMainPage ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + page
        Log.d(TAG, "getMainPage: url=$url")
        val doc = app.get(url, referer = mainUrl).document
        val decodedDoc = decodeHtml(doc)
        val elements = decodedDoc.select("article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, elements)
    }

    // ==================== toSearchResponse ====================

    /// Parses an <article> element from the listing page into a SearchResponse.
    /// Extracts: title, poster, year, quality, type (Movie/TvSeries).
    private fun toSearchResponse(element: Element): SearchResponse? {
        if (element.select("a").text().contains("الكل")) return null

        val urlElement = element.selectFirst("a") ?: return null
        val href = urlElement.attr("href")

        var posterUrl = element.select("img[data-src]").attr("data-src")
        if (posterUrl.isBlank()) {
            posterUrl = element.select("img[src]").attr("src")
        }
        if (posterUrl.isBlank()) {
            posterUrl = element.select("img").attr("src")
        }

        val category = element.select("ul.info li[aria-label=tab]").text()
        val titleElement = element.selectFirst("li[aria-label=title]")
        var title = ""
        if (titleElement != null) {
            titleElement.select("em").remove()
            title = titleElement.text().ifBlank { "" }
        }

        val year = element.select("li[aria-label=year]").text().toIntOrNull()

        val qualities = element.select("li[aria-label=ribbon]")
            .map { it.text() }
            .filter { Regex("\\d+").containsMatchIn(it) }
        val quality = getQualityFromString(qualities.joinToString(" "))

        val type = if (category.contains("مسلسلات", true) || category.contains("موسم", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val cleanTitle = Regex("$category|موسم 1|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\||")
            .replace(title, "")

        return newMovieSearchResponse(cleanTitle, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    // ==================== load (detail page) ====================

    /// Loads full metadata for a movie or series.
    /// For series: fetches each season's episode list concurrently.
    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")
        val detailResp = app.get(url, referer = mainUrl)
        Log.d(TAG, "   [load] HTTP ${detailResp.code} -> ${detailResp.url}, body=${detailResp.text.length} chars, cookies=${detailResp.cookies.size}")
        val doc = detailResp.document
        val decodedDoc = decodeHtml(doc, "load")

        val isMovie = decodedDoc.title().contains("فيلم")
        Log.d(TAG, "load: isMovie=$isMovie")

        val posterUrl = decodedDoc.select("figure img").attr("src")
            .ifBlank { decodedDoc.select("img[poster]").attr("poster") }
            .ifBlank { decodedDoc.select("img[src*='uploads']").attr("src") }
            .ifBlank { decodedDoc.select("img[src]").attr("src") }
        val year = decodedDoc.select("ul li a[href^='https://cimanow.cc/release-year/']").text().toIntOrNull()

        val titleRegex = Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\|${year}|Cima Now|-|سيما ناو|ج[0-9]|\\|")
        val title = titleRegex.replace(decodedDoc.title(), "")

        val tags = decodedDoc.select("article ul li")
            .filter { it.attr("aria-label") != "story" }
            .flatMap { it.text().split("،") }
            .map { it.trim() }

        val recommendations = decodedDoc.select("ul.related li").mapNotNull { toSearchResponse(it) }

        val synopsis = decodedDoc.select("li[aria-label=story] p").text()

        val actors = decodedDoc.select("ul li a[href^='https://cimanow.cc/actor/']")
            .map { it.text() }
            .filter { !it.isNullOrBlank() }
            .map { ActorData(Actor(it)) }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonElements = decodedDoc.select("section[aria-label=seasons] ul li a")

        if (seasonElements.isNotEmpty()) {
            Log.d(TAG, "load: fetching ${seasonElements.size} seasons concurrently")
            coroutineScope {
                val deferredEpisodes = seasonElements.map { seasonElement ->
                    async {
                        val (seasonResp, seasonDoc) = try {
                            val r = app.get(seasonElement.attr("href"), referer = url)
                            Log.d(TAG, "   [season] HTTP ${r.code} -> ${r.url}, body=${r.text.length} chars, cookies=${r.cookies.size}")
                            Pair(r, r.document)
                        } catch (e: Exception) {
                            Log.w(TAG, "load: failed fetching season: ${e.message}")
                            Pair(null, null)
                        }
                        if (seasonDoc != null) {
                            val decodedSeason = decodeHtml(seasonDoc, "season")
                            val seasonTitle = decodedSeason.selectFirst("span[aria-label=season-title]")
                            val seasonNum = getIntFromText(seasonTitle?.text() ?: "") ?: 1
                            decodedSeason.select("ul#eps li a").mapNotNull { epElement ->
                                newEpisode(epElement.attr("href")) {
                                    this.name = epElement.selectFirst("img")?.attr("alt")
                                    this.season = seasonNum
                                    this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                                    this.posterUrl = posterUrl
                                }
                            }
                        } else emptyList()
                    }
                }
                episodes.addAll(deferredEpisodes.awaitAll().flatten())
            }
        } else {
            val seasonTitle = decodedDoc.selectFirst("span[aria-label=season-title]")
            val seasonNum = getIntFromText(seasonTitle?.text() ?: "") ?: 1

            decodedDoc.select("ul#eps li a").mapNotNull { epElement ->
                newEpisode(epElement.attr("href")) {
                    this.name = epElement.selectFirst("img")?.attr("alt")
                    this.season = seasonNum
                    this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                    this.posterUrl = posterUrl
                }
            }.let { episodes.addAll(it) }
        }

        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        Log.d(TAG, "load: ${episodes.size} total episodes across ${seasonElements.size} seasons")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
            this.actors = actors
        }
    }

    // ==================== loadLinks (stream URL resolution) ====================
    //
    // Entry point for resolving playable links.
    //   1. Fetch movie page → find freex2line intermediate link
    //   2. resolveFreex2line → bypass JS challenge → get watch page URL
    //   3. Decode watch page → extract server elements (data-index/data-id)
    //   4. Route each server to its handler via AJAX iframe resolution
    //   5. Also extract direct download links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG_LOAD, "================ [START LOADLINKS] ================")
        Log.d(TAG_LOAD, "-> Data URL: $data")

        try {
            Log.i(TAG_LOAD, "[1/6] Fetching initial movie page...")
            val initialResp = app.get(data)
            Log.d(TAG_LOAD, "   [initial] HTTP ${initialResp.code} -> ${initialResp.url}, body=${initialResp.text.length} chars, cookies=${initialResp.cookies.size}")
            val moviePageDoc = initialResp.document

            // Log the page title and body length to check if we got a valid page
            Log.d(TAG_LOAD, "   Initial page title: ${moviePageDoc.title()}")
            Log.d(TAG_LOAD, "   Initial page body length: ${moviePageDoc.body()?.text()?.length ?: 0} chars")
            Log.d(TAG_LOAD, "   Has freex2line links: ${moviePageDoc.select("a[href*='freex2line']").size}")

            Log.i(TAG_LOAD, "[2/6] Searching for freex2line intermediate link...")
            var intermediateLink: String? = null

            // Try precise selector first: <a class="shine" href*="freex2line"> inside ul.btns
            val preciseLink = moviePageDoc.selectFirst("ul.btns li a.shine[href*='freex2line']")
            if (preciseLink != null) {
                intermediateLink = preciseLink.attr("href")
                Log.d(TAG_LOAD, "   Found via precise selector: $intermediateLink")
            }

            // Fallback: any <a href*="freex2line"> on the page
            if (intermediateLink.isNullOrBlank()) {
                Log.w(TAG, "   - Precise selector failed, trying general search...")
                intermediateLink = moviePageDoc.select("a[href*='freex2line']").firstOrNull()?.attr("href")
            }

            if (intermediateLink.isNullOrBlank()) {
                Log.e(TAG, "   - CRITICAL: Could not find any freex2line link.")
                throw ErrorLoadingException("Failed to find intermediate link.")
            }

            Log.d(TAG, "   Found intermediate link: $intermediateLink")
            Log.i(TAG, "[3/6] Attempting WebViewFlowHelper (freex2line → watching page)...")

            var watchPageHtml: String? = null
            var watchPageUrl = ""
            var flowFromNavEngine = false
            var preParsedServers = emptyList<WebViewFlowHelper.ServerInfo>()
            var preParsedDownloads = emptyList<WebViewFlowHelper.DownloadInfo>()

            ActivityProvider.initCompat(context)
            val flowHelper = WebViewFlowHelper(navigationEngine)
            val flowResult = flowHelper.navigateToWatchPage(
                intermediateUrl = intermediateLink,
                referer = data,
                config = WebViewFlowHelper.FlowConfig(
                    allowedDomains = setOf("cimanow.cc", "freex2line.online", "rm.freex2line.online", "href.li"),
                    destinationLockPatterns = listOf(Regex("/watching/"))
                )
            )

            if (flowResult.success && flowResult.finalUrl.contains("watching", ignoreCase = true)) {
                watchPageHtml = flowResult.watchPageHtml
                watchPageUrl = flowResult.finalUrl
                preParsedServers = flowResult.servers
                preParsedDownloads = flowResult.downloads
                flowFromNavEngine = true
                Log.i(TAG, "   WebViewFlowHelper SUCCEEDED! finalUrl=${watchPageUrl.take(80)}, " +
                    "servers=${preParsedServers.size}, downloads=${preParsedDownloads.size}, " +
                    "htmlLen=${watchPageHtml?.length ?: 0}")
                preParsedServers.forEach { s ->
                    Log.d(TAG, "     Server: '${s.name}' index=${s.index} iframe=${s.iframeUrl.take(60)}")
                }
                preParsedDownloads.forEach { d ->
                    Log.d(TAG, "     Download: '${d.name}' url=${d.url.take(60)}")
                }
            } else {
                Log.w(TAG, "   WebViewFlowHelper failed (${flowResult.error ?: "no watch page URL"}), falling back to resolveFreex2line...")
            }

            if (!flowFromNavEngine) {
                Log.i(TAG, "[3b/6] Falling back to resolveFreex2line + HTTP fetch + WebView render...")
                val finalCimaNowUrl = resolveFreex2line(intermediateLink, context)
                    ?: run {
                        Log.e(TAG, "   CRITICAL: resolveFreex2line returned null.")
                        throw ErrorLoadingException("Failed to bypass shortlink.")
                    }
                watchPageUrl = finalCimaNowUrl

                Log.i(TAG, "   Watch page URL obtained: $finalCimaNowUrl")
                Log.i(TAG, "[4/6] Fetching and decoding watch page...")

                val watchResp = app.get(finalCimaNowUrl, referer = data)
                Log.d(TAG, "   [watchPage] HTTP ${watchResp.code} -> ${watchResp.url}, body=${watchResp.text.length} chars")
                Log.d(TAG, "   [watchPage] Raw HTML preview: ${watchResp.text.take(300).replace('\n', ' ')}")
                val watchDoc = watchResp.document
                val bodyHtml = watchDoc.body()?.html() ?: ""
                val bodyEmpty = bodyHtml.isBlank()
                Log.d(TAG, "   Watch page body HTML length: ${bodyHtml.length} chars, empty=$bodyEmpty")

                // Check for block pages
                when {
                    bodyHtml.contains("ubiquiti", true) || bodyHtml.contains("UniFi", true) ->
                        Log.w(TAG, "   WARNING: UniFi block page!")
                    bodyHtml.contains("cloudflare", true) ->
                        Log.w(TAG, "   WARNING: Cloudflare challenge!")
                    bodyHtml.contains("captcha", true) ->
                        Log.w(TAG, "   WARNING: CAPTCHA detected!")
                }

                // Step 1: Try decoding the raw HTTP body directly (bypasses Jsoup)
                // The watch page returns ~3MB of JS-obfuscated HTML; feeding raw text
                // to the decode strategies often succeeds where Jsoup+WebView fails.
                val decodedDoc: Document
                Log.d(TAG, "   Trying decodeHtmlRaw on raw response body...")
                val rawDecoded = decodeHtmlRaw(watchResp.text, "watchPage")
                if (rawDecoded != null) {
                    val serverEls = rawDecoded.select("ul#watch li[data-index]")
                    val hasServers = serverEls.isNotEmpty()
                    Log.i(TAG, "   decodeHtmlRaw result: body=${rawDecoded.body()?.html()?.length ?: 0} chars, servers=$hasServers (${serverEls.size})")
                    if (hasServers) {
                        decodedDoc = rawDecoded
                        watchPageHtml = rawDecoded.body()?.html()
                        Log.i(TAG, "   decodeHtmlRaw SUCCESS — server elements found directly!")
                    } else {
                        // Raw decode produced HTML but no servers — try WebView render as fallback
                        Log.d(TAG, "   decodeHtmlRaw got body but no servers, trying WebView render...")
                        val rendered = renderHtmlInWebView(watchResp.text, finalCimaNowUrl)
                        if (rendered != null) {
                            val rDoc = Jsoup.parse(rendered)
                            val rServerEls = rDoc.select("ul#watch li[data-index]")
                            if (rServerEls.isNotEmpty()) {
                                decodedDoc = rDoc
                                watchPageHtml = rendered
                                Log.i(TAG, "   WebView render SUCCESS — server elements found!")
                            } else {
                                decodedDoc = rawDecoded
                                watchPageHtml = rawDecoded.body()?.html()
                                Log.w(TAG, "   WebView also has no servers, using raw decode result")
                            }
                        } else {
                            decodedDoc = rawDecoded
                            watchPageHtml = rawDecoded.body()?.html()
                            Log.w(TAG, "   WebView render returned null, using raw decode result")
                        }
                    }
                } else {
                    Log.d(TAG, "   decodeHtmlRaw returned null, trying WebView render...")
                    val rendered = renderHtmlInWebView(watchResp.text, finalCimaNowUrl)
                    if (rendered != null) {
                        decodedDoc = Jsoup.parse(rendered)
                        watchPageHtml = rendered
                        Log.i(TAG, "   WebView rendered ${rendered.length} chars, title='${decodedDoc.title()}'")
                    } else {
                        Log.e(TAG, "   Both decodeHtmlRaw and WebView render failed")
                        throw ErrorLoadingException("Failed to decode watch page")
                    }
                }
            }

            val decodedDoc = if (watchPageHtml != null) Jsoup.parse(watchPageHtml) else {
                Log.e(TAG, "   No watch page HTML available")
                throw ErrorLoadingException("Failed to decode watch page")
            }

            // Log the full body HTML of the decoded doc for server element debugging
            val decodedHtml = decodedDoc.body()?.html() ?: ""
            Log.d(TAG, "   Decoded document body HTML length: ${decodedHtml.length} chars")

            // Check for "ul#watch" or other expected structure patterns
            val hasUlWatch = decodedHtml.contains("ul#watch") || decodedHtml.contains("ul id=\"watch\"")
            val hasDataIndex = decodedHtml.contains("data-index")
            val hasLiTags = decodedHtml.contains("<li")
            Log.d(TAG, "   Decoded HTML has ul#watch=$hasUlWatch, data-index=$hasDataIndex, li tags=$hasLiTags")

            if (flowFromNavEngine && preParsedServers.isNotEmpty()) {
                // WebViewFlowHelper already parsed servers with iframe URLs — use them directly
                Log.i(TAG, "[5/6] Processing WATCH servers from WebViewFlowHelper (${preParsedServers.size})...")
                coroutineScope {
                    preParsedServers.map { server ->
                        async {
                            routeServerByName(
                                serverName = server.name,
                                iframeUrl = server.iframeUrl,
                                referer = watchPageUrl,
                                callback = callback
                            )
                        }
                    }.awaitAll()
                }
            } else {
                // Fallback: parse server elements from decoded document
                var serverElements = decodedDoc.select("ul#watch li[data-index]")
                Log.d(TAG, "   ul#watch li[data-index] count: ${serverElements.size}")
                if (serverElements.isEmpty()) {
                    serverElements = decodedDoc.select("li[data-index]")
                    Log.d(TAG, "   Fallback li[data-index] count: ${serverElements.size}")
                }
                if (serverElements.isEmpty()) {
                    serverElements = decodedDoc.select("li[class*='server'], li[class*='Server'], li[data-id]")
                    Log.d(TAG, "   Fallback li[server/data-id] count: ${serverElements.size}")
                }
                if (serverElements.isEmpty()) {
                    val allLis = decodedDoc.select("li")
                    Log.w(TAG, "   No watch server elements found. All <li> count: ${allLis.size}")
                    allLis.take(20).forEachIndexed { i, el ->
                        Log.d(TAG, "     li[$i]: class='${el.className()}' text='${el.text().take(80)}' html='${el.html().take(120)}'")
                    }
                    Log.w(TAG, "   Full decoded body HTML (first 1000 chars): ${decodedHtml.take(1000)}")
                } else {
                    Log.i(TAG, "   Found ${serverElements.size} watch server elements.")
                    serverElements.forEach { el ->
                        Log.d(TAG, "     Server: '${el.text()}' index=${el.attr("data-index")} id=${el.attr("data-id")} class=${el.className()}")
                    }
                }

                Log.i(TAG, "[5/6] Processing WATCH server elements (count=${serverElements.size})...")
                if (serverElements.isNotEmpty()) {
                    coroutineScope {
                        serverElements.map { serverElement ->
                            async {
                                processServerElement(serverElement, watchPageUrl, subtitleCallback, callback)
                            }
                        }.awaitAll()
                    }
                } else {
                    Log.w(TAG, "   No server elements to process — skipping watch server extraction.")
                }
            }

            Log.i(TAG, "[6/6] Processing DOWNLOAD links...")
            if (flowFromNavEngine && preParsedDownloads.isNotEmpty()) {
                // WebViewFlowHelper already extracted download links — use them directly
                Log.d(TAG, "   Using ${preParsedDownloads.size} download links from WebViewFlowHelper")
                preParsedDownloads.forEach { d ->
                    Log.d(TAG, "     Download: '${d.name}' url=${d.url.take(80)}")
                    if (d.url.startsWith("http")) {
                        val quality = Regex("\\d+p").find(d.name)?.value?.let { getQualityFromName(it) }
                            ?: Qualities.Unknown.value
                        callback(newExtractorLink("CimaNow", "CimaNow", d.url, type = getLinkType(d.url)) {
                            this.referer = watchPageUrl
                            this.quality = quality
                        })
                    }
                }
            } else {
                var downloadLinks = decodedDoc.select("ul#download li a[href]")
                Log.d(TAG, "   ul#download li a count: ${downloadLinks.size}")
                if (downloadLinks.isEmpty()) {
                    downloadLinks = decodedDoc.select("a[href*='download'], a[href*='dl'], .download-links a[href]")
                    Log.d(TAG, "   Fallback download link count: ${downloadLinks.size}")
                }
                if (downloadLinks.isEmpty()) {
                    downloadLinks = decodedDoc.select("ul li a[href*='.mp4'], ul li a[href*='.mkv']")
                    Log.d(TAG, "   Fallback mp4/mkv link count: ${downloadLinks.size}")
                }
                if (downloadLinks.isEmpty()) {
                    Log.d(TAG, "   No download links found. Decoded body #download section: ${decodedDoc.select("#download").html().take(300)}")
                }
                Log.d(TAG, "   Found ${downloadLinks.size} download links")
                downloadLinks.forEach { a ->
                    Log.d(TAG, "     Download: href='${a.attr("href").take(80)}' text='${a.text().take(60)}'")
                }

                coroutineScope {
                    downloadLinks.map { aTag ->
                        async {
                            processDownloadLink(aTag, watchPageUrl, subtitleCallback, callback)
                        }
                    }.awaitAll()
                }
            }

            Log.i(TAG, "================ [END LOADLINKS] =================")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in loadLinks: ${e.message}")
        }

        Log.i(TAG, "================ [END LOADLINKS] =================")
        return false
    }

    // ==================== processServerElement ====================
    //
    // Routes a watch server to its dedicated handler based on server name.
    // Each server type has its own iframe/AJAX resolution logic.

    private suspend fun processServerElement(
        serverElement: Element,
        finalCimaNowUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val dataIndex = serverElement.attr("data-index")
            val dataId = serverElement.attr("data-id")
            val serverName = serverElement.text().trim()

            Log.d(TAG, "Server: name='$serverName', index=$dataIndex, id=$dataId")

            // AJAX call to get the iframe URL for this server
            val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"
            Log.d(TAG, "   AJAX URL: $ajaxUrl")
            val playerResponse = try {
                val r = app.get(ajaxUrl, referer = finalCimaNowUrl)
                Log.d(TAG, "   AJAX HTTP ${r.code} -> ${r.url}, body length: ${r.text.length}")
                r
            } catch (e: Exception) {
                Log.w(TAG, "   AJAX failed for server '$serverName': ${e.message}")
                null
            }
            val playerDoc = playerResponse?.document
            if (playerDoc != null) {
                val iframes = playerDoc.select("iframe")
                Log.d(TAG, "   AJAX response body length: ${playerResponse.text.length}, iframes found: ${iframes.size}")
                if (iframes.isEmpty()) {
                    Log.d(TAG, "   AJAX response body (first 500): ${playerResponse.text.take(500)}")
                }
            } else {
                Log.w(TAG, "   playerDoc is null for server '$serverName'")
            }

            val iframeUrl = (playerDoc?.select("iframe")?.attr("src") ?: "").let { normalizeUrl(it, mainUrl) }
            Log.d(TAG, "   Extracted iframe URL: $iframeUrl")

            when {
                serverName.contains("Cima Now", true) || serverName.contains("cima", true) -> {
                    Log.d(TAG, "   -> Routing to handlecima")
                    handlecima(iframeUrl, serverName, callback)
                }
                serverName.contains("VidPro", true) -> {
                    Log.d(TAG, "   -> Routing to handleVidPro: $iframeUrl")
                    handleVidPro(iframeUrl, serverName, callback)
                }
                serverName.contains("Govid", true) || serverName.contains("Goovid", true) -> {
                    Log.d(TAG, "   -> Routing to handleGovid: $iframeUrl")
                    handleGovid(iframeUrl, serverName, callback)
                }
                serverName.contains("Vidlook", true) -> {
                    Log.d(TAG, "   -> Routing to handleVidlook: $iframeUrl")
                    handleVidlook(iframeUrl, serverName, callback)
                }
                serverName.contains("Streamwish", true) -> {
                    Log.d(TAG, "   -> Routing to handleStreamwish: $iframeUrl")
                    handleStreamwish(iframeUrl, serverName, callback)
                }
                serverName.contains("Streamfile", true) || serverName.contains("Luluvid", true) -> {
                    Log.d(TAG, "   -> Routing to handleStreamfileAndLuluvid: $iframeUrl")
                    handleStreamfileAndLuluvid(iframeUrl, serverName, callback)
                }
                serverName.contains("Vadbam", true) || serverName.contains("Viidshare", true) -> {
                    Log.d(TAG, "   -> Routing to handleVadbamAndViidshare: $iframeUrl")
                    handleVadbamAndViidshare(iframeUrl, serverName, callback)
                }
                serverName.contains("Jetload", true) -> {
                    Log.d(TAG, "   -> Routing to handleJetload: $iframeUrl")
                    handleJetload(iframeUrl, 0, finalCimaNowUrl, callback)
                }
                serverName.contains("Forafile", true) || iframeUrl.contains("forafile.com") -> {
                    Log.d(TAG, "   -> Routing to handleForafile: $iframeUrl")
                    handleForafile(iframeUrl, 0, finalCimaNowUrl, callback)
                }
                else -> {
                    Log.d(TAG, "   -> Unknown server type, trying direct links + loadExtractor")
                    for (link in serverElement.select("a")) {
                        val dlink = link.attr("href")
                        if (dlink.isNotBlank() && dlink.startsWith("http")) {
                            val quality = Regex("\\d+p").find(link.text())?.value?.let { getQualityFromName(it) }
                                ?: Qualities.Unknown.value
                            callback(newExtractorLink(serverName, serverName, dlink, type = getLinkType(dlink)) {
                                this.referer = finalCimaNowUrl
                                this.quality = quality
                            })
                        }
                    }
                    if (iframeUrl.isNotBlank()) {
                        loadExtractor(iframeUrl, finalCimaNowUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing server: ${e.message}")
        }
    }

    // ==================== processDownloadLink ====================

    private suspend fun processDownloadLink(
        aTag: Element,
        finalCimaNowUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val dlink = aTag.attr("href")
            val linkText = aTag.text()
            val quality = Regex("\\d+p").find(linkText)?.value?.let { getQualityFromName(it) }
                ?: Qualities.Unknown.value

            if (dlink.isNotBlank() && dlink.startsWith("http")) {
                Log.d(TAG, "Download link: quality=$quality url=${dlink.take(80)}")
                callback(newExtractorLink("CimaNow", "CimaNow", dlink, type = getLinkType(dlink)) {
                    this.referer = finalCimaNowUrl
                    this.quality = quality
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing download link: ${e.message}")
        }
    }

    // ==================== handlecima ====================
    //
    // Native CimaNow player: the iframe response contains inline
    // links like `[1080p] /uploads/path/to/video.mp4`. Extracts the
    // highest quality by parsing that format.

    private suspend fun handlecima(
        iframeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            val iframeResponse = app.get(finalUrl, referer = finalUrl).text

            // Pattern: [QUALITY] /uploads/...mp4
            val regex = Regex("\\[(\\d+p)]\\s+(/uploads/[^\"]+\\.mp4)")
            val baseUrlMatch = Regex("(https?://[^/]+)").find(finalUrl)
            val baseUrl = baseUrlMatch?.groupValues?.get(1) ?: ""

            val links = mutableListOf<ExtractorLink>()

            for (match in regex.findAll(iframeResponse)) {
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath

                val link = newExtractorLink("CimaNow", "CimaNow", videoUrl, type = getLinkType(videoUrl))
                link.quality = getQualityFromName(qualityStr)
                link.referer = finalUrl
                links.add(link)
            }

            Log.d(TAG, "handlecima: found ${links.size} quality levels")

            // Return the highest quality
            if (links.size > 1) {
                links.sortByDescending { it.quality }
            }

            links.firstOrNull()?.let {
                Log.d(TAG, "handlecima: selected quality ${it.quality} -> ${it.url.take(80)}")
                callback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handlecima error: ${e.message}")
        }
    }

    // ==================== Simple handle methods (loadExtractor based) ====================
    //
    // These servers just need their iframe URL passed to CS3's built-in
    // loadExtractor, which knows how to handle common extractors.

    private suspend fun handleVidPro(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleVidPro failed: ${e.message}")
        }
    }

    private suspend fun handleGovid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleGovid failed: ${e.message}")
        }
    }

    private suspend fun handleVidlook(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleVidlook failed: ${e.message}")
        }
    }

    private suspend fun handleStreamwish(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleStreamwish failed: ${e.message}")
        }
    }

    private suspend fun handleStreamfileAndLuluvid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleStreamfileAndLuluvid failed: ${e.message}")
        }
    }

    private suspend fun handleVadbamAndViidshare(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (e: Exception) {
            Log.w(TAG, "handleVadbamAndViidshare failed: ${e.message}")
        }
    }

    // ==================== handleJetload ====================
    //
    // Jetload uses a 3-step process:
    //   1. Load page → get session cookies
    //   2. Fetch Jetload4/ → extract extraToken and data-token
    //   3. Wait 10s → call get-link.php with tokens → get stream URL

    private suspend fun handleJetload(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_JL = "JetloadExtractor"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar-EG,ar;q=0.9"
        )

        try {
            val res1 = app.get(url, headers = headers)
            Log.d(TAG_JL, "[1/3] Initial page loaded, cookies=${res1.cookies.size}")
            val sessionCookies = res1.cookies.toMutableMap()

            val headers2 = headers + ("Referer" to url)
            val targetUrl = "https://jetload.pp.ua/Jetload4/"
            val res2 = app.get(targetUrl, headers = headers2, cookies = sessionCookies)
            val html = res2.text
            sessionCookies.putAll(res2.cookies)

            val extraToken = Regex("window\\.extraToken\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            val dataToken = Regex("data-token=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            Log.d(TAG_JL, "[2/3] extraToken=${extraToken?.take(20)}, dataToken=${dataToken?.take(20)}")

            if (extraToken == null || dataToken == null) {
                Log.e(TAG_JL, "[-] Failed to extract tokens.")
                return
            }

            Log.d(TAG_JL, "[3/3] Waiting 10s then fetching get-link.php...")
            delay(10000)

            val ajaxUrl = "https://jetload.pp.ua/Jetload4/get-link.php?token=$dataToken"
            val ajaxHeaders = headers2 + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to targetUrl
            )
            val finalResp = app.get(ajaxUrl, headers = ajaxHeaders, cookies = sessionCookies)
            val rawLink = finalResp.text.trim()
            Log.d(TAG_JL, "   Server response: ${rawLink.take(80)}")

            if (!rawLink.startsWith("http")) {
                Log.e(TAG_JL, "[-] Invalid server response: $rawLink")
                return
            }

            val intermediateLink = "$rawLink?t=$extraToken"
            Log.d(TAG_JL, "[+] Final Media Link: $intermediateLink")

            val link = newExtractorLink("Jetload", "Jetload", intermediateLink)
            link.referer = targetUrl
            link.quality = quality
            callback(link)

        } catch (e: Exception) {
            Log.e(TAG_JL, "[-] Error in Jetload: ${e.message}")
        }
    }

    // ==================== handleForafile ====================
    //
    // Forafile uses a POST form-based flow:
    //   1. POST to base URL with form data (op=download2, id=fileId)
    //   2. Follow redirect location header → get stream URL

    private suspend fun handleForafile(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_FF = "ForafileExtractor"
        try {
            val match = Regex("(https://forafile\\.com/([^/]+)/)").find(url) ?: run {
                Log.w(TAG_FF, "[-] Could not parse forafile URL: $url")
                return
            }
            val baseUrl = match.groupValues[1]
            val fileId = match.groupValues[2]
            Log.d(TAG_FF, "baseUrl=$baseUrl fileId=$fileId")

            val headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Linux; Android 13)",
                "referer" to url
            )
            val data = mapOf(
                "op" to "download2",
                "id" to fileId,
                "rand" to "",
                "referer" to "",
                "method_free" to "",
                "method_premium" to "",
                "adblock_detected" to "0"
            )

            val response = app.post(baseUrl, headers = headers, data = data)
            val location = response.headers["location"] ?: response.headers["Location"]
            Log.d(TAG_FF, "POST response location=$location")

            if (location.isNullOrBlank()) {
                Log.e(TAG_FF, "[-] No redirect location found.")
                return
            }

            val link = newExtractorLink("Forafile", "Forafile", location)
            link.referer = baseUrl
            link.quality = quality
            callback(link)
            Log.d(TAG_FF, "[+] Link emitted: ${location.take(80)}")

        } catch (e: Exception) {
            Log.e(TAG_FF, "[-] Error in Forafile: ${e.message}")
        }
    }

    // ==================== resolveFreex2line (JS challenge bypass) ====================
    //
    // Bypasses the freex2line.online JS challenge to resolve a short link
    // into the actual watch page URL.
    //
    // The challenge has two possible formats:
    //
    //   NEW format (preferred):
    //     window.ptr_XXXX = 'ctx_YYYY'                    → pointer to context object
    //     window['ctx_YYYY'] = { v_65b1: 'ch', ... }      → actual values
    //     window.map_XXXX = { ch: 'v_65b1', ri: '...' }   → key mapping
    //     Values are then: context[map['ch']], context[map['ri']], etc.
    //
    //   OLD format (fallback):
    //     window._0x_cfg = { c: 'chValue', r: 'reqId', k: 'encKeyB64', s: 'xorKey' }
    //
    // Steps:
    //   1. Load intermediate link → get session cookies
    //   2. Fetch challenge page (blog-post.html/)
    //   3. Parse challenge data (new format, fallback to old)
    //   4. XOR-decrypt the base64-encoded key using sXorKey
    //   5. HMAC-SHA256(requestId + ch + fp, secretKey)
    //   6. Wait 10s then POST to get-link.php → receive watch page URL

    private suspend fun resolveFreex2line(url: String, context: Context): String? {
        Log.i("Freex2lineResolver", "======= [STARTING RESOLVER v3 - DYNAMIC KEY] =======")

        try {
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "قد يستغرق 12 ثانية..", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}

        val sessionCookies = mutableMapOf<String, String>()

        try {
            Log.i("Freex2lineResolver", "[1/6] Initializing session...")
            val baseHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://rm.freex2line.online/"
            )

            // Step 1: Load the intermediate (loadon/?link=...) URL to establish session
            Log.d("Freex2lineResolver", "   [1/6] Loading: $url")
            val headResponse = app.get(url, headers = baseHeaders)
            sessionCookies.putAll(headResponse.cookies)
            val headHtml = headResponse.text
            Log.d("Freex2lineResolver", "   [1/6] HTTP ${headResponse.code} -> ${headResponse.url}")
            Log.d("Freex2lineResolver", "   [1/6] Response length: ${headHtml.length}, cookies: ${headResponse.cookies.size}")
            if (headHtml.length < 100) {
                Log.w("Freex2lineResolver", "   [1/6] WARNING: Head response is very short: '$headHtml'")
            }
            Log.d("Freex2lineResolver", "   [1/6] Has _0x_cfg: ${headHtml.contains("_0x_cfg")}")
            Log.d("Freex2lineResolver", "   [1/6] Has ptr_: ${headHtml.contains("ptr_")}")

            // Step 2: Fetch the challenge page that contains the JS variables
            Log.i("Freex2lineResolver", "[2/6] Fetching data page...")
            val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            Log.d("Freex2lineResolver", "   [2/6] URL: $pageUrl, cookies: ${sessionCookies.size}")
            val res = app.get(pageUrl, headers = baseHeaders, cookies = sessionCookies)
            var html = res.text
            sessionCookies.putAll(res.cookies)
            Log.d("Freex2lineResolver", "   [2/6] HTTP ${res.code} -> ${res.url}")
            Log.d("Freex2lineResolver", "   [2/6] Response length: ${html.length}")
            Log.d("Freex2lineResolver", "   [2/6] Has _0x_cfg: ${html.contains("_0x_cfg")}")
            Log.d("Freex2lineResolver", "   [2/6] Has ptr_: ${html.contains("ptr_")}")
            if (html.length < 200) {
                Log.w("Freex2lineResolver", "   [2/6] WARNING: Blog page is very short: '$html'")
            }

            // If the blog page doesn't have challenge data, try the head response instead
            if (!html.contains("_0x_cfg")) {
                Log.d(TAG, "   Blog page has no _0x_cfg, using head response page")
                html = headHtml
            }

            Log.i("Freex2lineResolver", "[3/6] Parsing challenge data...")

            // Try new format: window['ctx_XXXXX'] with ptr + map indirection
            val ptrMatch = reMatch(html, """window\.ptr_\w+\s*=\s*'([^']+)'""")
            val ctxName = ptrMatch
            var ch: String? = null
            var requestId: String? = null
            var encryptedKeyB64: String? = null
            var sXorKey: String? = null

            if (ctxName != null) {
                Log.d(TAG, "   Found ptr context name: $ctxName")
                // Extract the context object: window['ctx_XXXXX'] = { ... }
                val ctxJson = reMatch(html, """(?:window\[)?['\"]$ctxName['\"](?:\])?\s*=\s*\{([^}]+)\}""")
                val mapMatch = reMatch(html, """window\.map_\w+\s*=\s*\{([^}]+)\}""")

                if (ctxJson != null && mapMatch != null) {
                    val chKey = reMatch(mapMatch, """ch:\s*'([^']+)'""")
                    val riKey = reMatch(mapMatch, """ri:\s*'([^']+)'""")
                    val keKey = reMatch(mapMatch, """ke:\s*'([^']+)'""")
                    val seKey = reMatch(mapMatch, """se:\s*'([^']+)'""")

                    if (chKey != null) ch = reMatch(ctxJson, """'?$chKey'?\s*:\s*'([^']+)'""")
                    if (riKey != null) requestId = reMatch(ctxJson, """'?$riKey'?\s*:\s*'([^']+)'""")
                    if (keKey != null) encryptedKeyB64 = reMatch(ctxJson, """'?$keKey'?\s*:\s*'([^']+)'""")
                    if (seKey != null) sXorKey = reMatch(ctxJson, """'?$seKey'?\s*:\s*'([^']+)'""")

                    Log.d(TAG, "   New format: ch=$ch, ri=$requestId, ke=${encryptedKeyB64?.take(20)}..., se=$sXorKey")
                } else {
                    Log.w(TAG, "   New format: ctxJson=${ctxJson != null}, mapMatch=${mapMatch != null}")
                }
            }

            // Fallback: old _0x_cfg format (direct key-value pairs)
            if (ch == null || requestId == null || encryptedKeyB64 == null || sXorKey == null) {
                Log.d(TAG, "   New format incomplete, trying _0x_cfg fallback...")
                val cfgText = reMatch(html, "(?:var|let|const|window\\.)?\\s*_0x_cfg\\s*=\\s*\\{([^}]+)\\}")
                    ?: throw Exception("CFG object not found")
                Log.d(TAG, "   _0x_cfg content: $cfgText")

                if (ch == null) ch = reMatch(cfgText, "'?c'?:\\s*'([^']+)'")
                if (requestId == null) requestId = reMatch(cfgText, "'?r'?:\\s*'([^']+)'")
                if (encryptedKeyB64 == null) encryptedKeyB64 = reMatch(cfgText, "'?k'?:\\s*'([^']+)'")
                if (sXorKey == null) sXorKey = reMatch(cfgText, "'?s'?:\\s*'([^']+)'")

                ch = ch ?: throw Exception("ch value not found")
                requestId = requestId ?: throw Exception("requestId value not found")
                encryptedKeyB64 = encryptedKeyB64 ?: throw Exception("Encrypted key value not found")
                sXorKey = sXorKey ?: throw Exception("s (XOR key) not found")
                Log.d(TAG, "   _0x_cfg values: ch=$ch, ri=$requestId, se=$sXorKey")
            }

            Log.i("Freex2lineResolver", "[5/6] Decrypting secret key...")
            val encryptedBytes = Base64.decode(encryptedKeyB64, 0)
            val decryptedChars = encryptedBytes.mapIndexed { index, byte ->
                // XOR each byte with the corresponding sXorKey character
                (byte.toInt() xor sXorKey[index % sXorKey.length].code).toChar()
            }
            val secretKey = decryptedChars.joinToString("")
            Log.d("Freex2lineResolver", "   Secret key length: ${secretKey.length}, secretKey: $secretKey")

            Log.i("Freex2lineResolver", "[6/6] Generating HMAC signature...")
            val fpRaw = "Mozilla/5.10"
            val fpBase64 = Base64.encodeToString(fpRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val messageToSign = requestId + ch + fpBase64
            val hmacToken = calculateHmacSha256(messageToSign, secretKey)
            val hmacTokenEncoded = java.net.URLEncoder.encode(hmacToken, "UTF-8")
            Log.d("Freex2lineResolver", "   FP (base64): $fpBase64")
            Log.d("Freex2lineResolver", "   messageToSign length=${messageToSign.length}, hmac=$hmacToken")

            // Mandatory delay — the server enforces timing
            Log.d("Freex2lineResolver", "   Waiting 10s before API call...")
            delay(10000)
            Log.d("Freex2lineResolver", "   Wait complete, sending API request...")

            Log.i(TAG, "Sending final API request...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$requestId&hmac_token=$hmacTokenEncoded&ch=$ch&fp=$fpBase64"
            Log.d("Freex2lineResolver", "   API URL full: $apiUrl")
            Log.d("Freex2lineResolver", "   request_id=$requestId, ch=$ch")

            // Build cookie header string from session cookies
            val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val finalHeaders = mapOf(
                "User-Agent" to baseHeaders["User-Agent"]!!,
                "Referer" to "https://rm.freex2line.online/",
                "Cookie" to cookieHeader
            )
            Log.d("Freex2lineResolver", "   Cookie count: ${sessionCookies.size}")

            val finalRes = app.get(apiUrl, headers = finalHeaders)
            Log.d("Freex2lineResolver", "   Final API HTTP ${finalRes.code} -> ${finalRes.url}")
            Log.d("Freex2lineResolver", "   Final API response length: ${finalRes.text.length}")
            // Strip UTF-8 BOM (\uFEFF) that some responses include before the URL
            val finalResult = finalRes.text.trim().trim('\uFEFF')
            Log.d("Freex2lineResolver", "   Raw response (trimmed): $finalResult")

            if (finalResult.startsWith("http")) {
                Log.i("Freex2lineResolver", "[SUCCESS] Watch page URL obtained: $finalResult")
                return finalResult
            }

            Log.e("Freex2lineResolver", "[FAILURE] Server did not return a valid URL. Response: $finalResult")
        } catch (e: Exception) {
            Log.e("Freex2lineResolver", "[FATAL ERROR] An exception occurred during resolution: ${e.message}")
            e.printStackTrace()
        }

        Log.i(TAG, "======= [RESOLVER FINISHED - FAILED] =======")
        return null
    }

    // ==================== getLinkType ====================

    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    // ==================== Utilities ====================

    /// Normalizes a URL: protocol-relative (//example.com) → https://example.com,
    /// relative paths (/path) → https://mainUrl/path.
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = baseUrl.trimEnd('/')
                "$base$url"
            }
            else -> url
        }
    }

    /// Matches a regex pattern against html and returns the first capture group.
    /// Returns null if no match or if the pattern throws.
    private fun reMatch(html: String, regex: String): String? {
        return try {
            val matcher = Pattern.compile(regex).matcher(html)
            if (matcher.find()) matcher.group(1) else null
        } catch (e: Exception) {
            Log.w(TAG, "reMatch: invalid regex '$regex': ${e.message}")
            null
        }
    }

    /// Computes HMAC-SHA256 of `message` using `secret` as the key,
    /// returns the result as a Base64-encoded string (no wrapping).
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun calculateHmacSha256(message: String, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
