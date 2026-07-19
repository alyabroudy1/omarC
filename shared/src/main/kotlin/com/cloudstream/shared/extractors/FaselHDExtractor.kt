package com.cloudstream.shared.extractors

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.util.WebConfig
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * FaselHD stream URL extractor using hidden WebView with JS injection.
 *
 * Replaces the old CfBypassEngine + Rhino JS eval approach with
 * a hidden 1x1 WebView that auto-plays videos and captures m3u8 URLs
 * via shouldInterceptRequest and console message interception.
 * Full logic ported from re-3arabi/Faselhd/Faselhd.kt resolveWithWebView().
 */
class FaselHDExtractor : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl = "https://faselhdx.xyz"
    override val requiresReferer = true

    private var lastValidUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodName = "getUrl"
        val effectiveReferer = referer ?: "$mainUrl/"
        val activity = ActivityProvider.currentActivity
        val userAgent = if (activity != null) WebConfig.getUserAgent(activity) else WebConfig.getCachedUserAgent()

        ProviderLogger.i(TAG, methodName, "Starting WebView extraction for: ${url.take(100)}")

        val m3u8 = resolveWithWebView(url, effectiveReferer, userAgent, activity)

        if (!m3u8.isNullOrBlank()) {
            ProviderLogger.i(TAG, methodName, "Found m3u8: ${m3u8.take(100)}")
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8,
                referer = url,
                headers = mapOf(
                    "Referer" to url,
                    "User-Agent" to userAgent
                )
            ).forEach(callback)
        } else {
            ProviderLogger.w(TAG, methodName, "WebView extraction returned no m3u8")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        referer: String,
        userAgent: String,
        activity: android.app.Activity?
    ): String? = suspendCancellableCoroutine { cont ->

        if (activity == null || activity.isFinishing) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val finalUrl = iframeUrl.replace("&amp;", "&").trim()
        val originalHost = try { Uri.parse(finalUrl).host?.replace("www.", "") ?: "" } catch (e: Exception) { "" }

        activity.runOnUiThread {

            val dialog = Dialog(activity)

            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                attributes = attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
            }

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }

            try {
                dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                dialog.show()
            } catch (e: Exception) {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP))
                } catch (_: Exception) {}
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                this.userAgentString = userAgent
                blockNetworkImage = true
            }

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.flush()
            } catch (_: Exception) {}

            val client = app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .dns(com.cloudstream.shared.network.PreferIpv6Dns())
                .build()

            val foundM3u8 = linkedSetOf<String>()
            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())
            var finishRunnable: Runnable? = null

            var currentAttempt = 0
            val maxAttempts = 2
            val attemptTimeoutMs = 12_000L
            var attemptTimeoutRunnable: Runnable? = null
            var autoTouchRunnable: Runnable? = null

            fun cleanup() {
                try { attemptTimeoutRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { autoTouchRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { finishRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
                try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.stopLoading() } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
                try { cookieManager.flush() } catch (_: Exception) {}
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            }

            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try { if (cont.isActive) cont.resume(result) } catch (_: Exception) {}
                cleanup()
            }

            fun chooseAndFinish() {
                if (foundM3u8.isEmpty()) { safeFinish(null); return }
                val strict = foundM3u8.firstOrNull {
                    val clean = it.substringBefore("?")
                    clean.endsWith(".m3u8") && (clean.contains("master") || clean.contains("playlist") || clean.contains("index"))
                } ?: foundM3u8.firstOrNull { it.substringBefore("?").endsWith(".m3u8") }
                safeFinish(strict ?: foundM3u8.first())
            }

            fun handleFoundLink(url: String) {
                val clean = url.substringBefore("?")
                if (!clean.endsWith(".m3u8")) return
                synchronized(foundM3u8) {
                    if (!foundM3u8.contains(url)) {
                        foundM3u8.add(url)
                        finishRunnable?.let { handler.removeCallbacks(it) }
                        if (clean.contains("master") || clean.contains("playlist") || clean.contains("index")) {
                            finishRunnable = Runnable { chooseAndFinish() }
                            handler.postDelayed(finishRunnable!!, 300)
                        } else {
                            if (finishRunnable == null) {
                                finishRunnable = Runnable { chooseAndFinish() }
                                handler.postDelayed(finishRunnable!!, 1500)
                            }
                        }
                    }
                }
            }

            fun startNextAttempt() {
                synchronized(finishLock) { if (finished) return }

                if (currentAttempt >= maxAttempts) {
                    chooseAndFinish()
                    return
                }

                attemptTimeoutRunnable?.let { handler.removeCallbacks(it) }
                attemptTimeoutRunnable = Runnable {
                    synchronized(foundM3u8) {
                        if (foundM3u8.isEmpty()) {
                            currentAttempt++
                            startNextAttempt()
                        } else {
                            chooseAndFinish()
                        }
                    }
                }
                handler.postDelayed(attemptTimeoutRunnable!!, attemptTimeoutMs)

                activity.runOnUiThread {
                    try { webView.loadUrl(finalUrl, mapOf("Referer" to referer)) } catch (_: Exception) {}
                }
            }

            fun getStrategyJs(attempt: Int): String {
                return """
                (function() {
                    const strategy = $attempt;

                    Object.defineProperty(navigator, 'userActivation', { get: () => ({ hasBeenActive: true, isActive: true }) });

                    const Decryptor = {
                        key1: "V2@%YSU2B]G~", key2: "bv0fim4qf17",
                        ie: function(c) {
                            const x = c.charCodeAt(0);
                            if(x>=97 && x<=122) return x-97; if(x>=65 && x<=90) return x-65+26;
                            if(x>=48 && x<=57) return x-48+52; if(x===43) return 62; if(x===47) return 63; return 0;
                        },
                        bn: function(x) {
                            if(x<=25) return String.fromCharCode(x+97); if(x<=51) return String.fromCharCode(x-26+65);
                            if(x<=61) return String.fromCharCode(x-52+48); if(x===62) return '+'; if(x===63) return '/'; return ' ';
                        },
                        dec: function(e, k) {
                            let r=''; for(let i=0; i<e.length; i++) {
                                const kc=k[i%(k.length-1)]; const M=this.ie(e[i])-this.ie(kc); r+=this.bn(M<0?M+64:M);
                            } return r;
                        },
                        parse: function(url) {
                            if(!url || !url.startsWith('enc:')) return url;
                            try { return this.dec(this.dec(url.substring(4), this.key2), this.key1); } catch(e){return url;}
                        }
                    };

                    if ((strategy === 0 || strategy === 4) && !window.__isDecryptionHooked) {
                        window.__isDecryptionHooked = true;
                        let chk = setInterval(function() {
                            if (typeof window.jwplayer === 'function' && !window.jwplayer.__hooked) {
                                const orig = window.jwplayer;
                                window.jwplayer = function() {
                                    const p = orig.apply(this, arguments);
                                    if (!p.__sHook) {
                                        p.__sHook = true;
                                        const oSetup = p.setup;
                                        p.setup = function(cfg) {
                                            try {
                                                let s = cfg.sources || (cfg.playlist && cfg.playlist[0] ? cfg.playlist[0].sources : []);
                                                if (s) s.forEach(x => { if (x.file && x.file.startsWith('enc:')) x.file = Decryptor.parse(x.file); });
                                            } catch(e){}
                                            cfg.autostart = true; cfg.mute = true;
                                            return oSetup.call(this, cfg);
                                        };
                                    }
                                    return p;
                                };
                                Object.assign(window.jwplayer, orig);
                                window.jwplayer.prototype = orig.prototype;
                                window.jwplayer.__hooked = true;
                                clearInterval(chk);
                            }
                        }, 10);
                    }

                    try {
                        let p = typeof window.jwplayer === 'function' ? window.jwplayer("player") : null;
                        let isPlaying = p && (p.getState() === 'playing' || p.getState() === 'buffering');
                        if (isPlaying) return;

                        if (strategy === 0 || strategy === 1 || strategy === 7) {
                            var els = document.querySelectorAll('button, a, [onclick], video, [role="button"], .jw-icon, .vjs-control, .po-play-btn, .plyr__control');
                            els.forEach(el => {
                                if (el.href && (el.href.includes('google.com') || el.href.includes('recaptcha'))) return;
                                try { el.click(); } catch(e){}
                            });
                        }
                        if (strategy === 2 || strategy === 7) {
                            if (p && typeof p.play === 'function') { p.setMute(true); p.play(); }
                        }
                    } catch(e) {}
                })();
                """.trimIndent()
            }

            val fastSnifferJs = """
            (function() {
                try {
                    window.open = function() { return null; };
                    if (!window.__NET_HOOKED__) {
                        window.__NET_HOOKED__ = true;
                        const _fetch = window.fetch;
                        if (_fetch) {
                            window.fetch = function() {
                                return _fetch.apply(this, arguments).then(function(resp) {
                                    try {
                                        const u = resp && resp.url ? resp.url : '';
                                        if (u && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                    } catch(e){}
                                    return resp;
                                });
                            };
                        }
                        const _open = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, u) {
                            this.addEventListener('load', function() {
                                try {
                                    if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                } catch(e){}
                            });
                            return _open.apply(this, arguments);
                        };
                    }
                } catch(err){}
            })();
            """.trimIndent()

            lateinit var sharedWebViewClient: WebViewClient
            sharedWebViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url.toString()
                    val lowerUrl = url.lowercase()
                    if (!lowerUrl.startsWith("http")) return true
                    if (lowerUrl.contains("policies.google.com") || lowerUrl.contains("recaptcha") || lowerUrl.contains("mcaptcha") || lowerUrl.contains("melbet")) {
                        Handler(Looper.getMainLooper()).post { view?.loadUrl(finalUrl, mapOf("Referer" to referer)) }
                        return true
                    }
                    val currentHost = try { Uri.parse(url).host?.replace("www.", "") ?: "" } catch(e:Exception){""}
                    if (originalHost.isNotBlank() && currentHost.isNotBlank() && !currentHost.contains(originalHost)) {
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(fastSnifferJs, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(fastSnifferJs, null)

                    autoTouchRunnable?.let { handler.removeCallbacks(it) }
                    autoTouchRunnable = object : Runnable {
                        override fun run() {
                            if (finished) return
                            view?.evaluateJavascript(getStrategyJs(currentAttempt), null)
                            handler.postDelayed(this, 1000)
                        }
                    }
                    handler.postDelayed(autoTouchRunnable!!, 500)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    val method = request.method
                    val lower = url.lowercase()

                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".woff2") || lower.endsWith(".css")) {
                        return super.shouldInterceptRequest(view, request)
                    }

                    if (method.equals("GET", ignoreCase = true) && lower.contains(".m3u8") && lower.substringBefore("?").endsWith(".m3u8")) {
                        handleFoundLink(url)
                        try {
                            val reqBuilder = okhttp3.Request.Builder().url(url)
                                .header("User-Agent", userAgent)
                                .header("Referer", referer)
                                .header("Origin", mainUrl)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}

                            val response = client.newCall(reqBuilder.build()).execute()
                            if (!response.isSuccessful) return null

                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                            return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                        } catch (e: Exception) { return null }
                    }

                    if (method.equals("GET", ignoreCase = true) && (lower.contains("fasel") || lower.contains("jwplayer") || lower.contains("config") || lower.contains("player"))) {
                        try {
                            val reqBuilder = okhttp3.Request.Builder().url(url)
                                .header("User-Agent", userAgent)
                                .header("Referer", referer)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}

                            val response = client.newCall(reqBuilder.build()).execute()
                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "text/html"
                            return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                        } catch (e: Exception) { return super.shouldInterceptRequest(view, request) }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }
            }

            webView.webViewClient = sharedWebViewClient

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    val msg = cm?.message() ?: ""
                    if (msg.startsWith("NET_M3U8::")) {
                        handleFoundLink(msg.substringAfter("::").trim())
                    }
                    return true
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    try {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        val adWebView = WebView(activity).apply {
                            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP)
                            visibility = View.INVISIBLE
                        }
                        adWebView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            this.userAgentString = userAgent
                        }
                        try { (activity.window?.decorView as? ViewGroup)?.addView(adWebView) } catch (_: Exception) {}
                        adWebView.webViewClient = sharedWebViewClient
                        transport?.webView = adWebView
                        resultMsg?.sendToTarget()
                        handler.postDelayed({
                            try {
                                (adWebView.parent as? ViewGroup)?.removeView(adWebView)
                                adWebView.destroy()
                            } catch (e: Exception) {}
                        }, 1000)
                        return true
                    } catch (e: Exception) { return false }
                }
            }

            startNextAttempt()

            cont.invokeOnCancellation { handler.post { safeFinish(null) } }
        }
    }

    companion object {
        private const val TAG = "FaselHDExtractor"
    }
}
