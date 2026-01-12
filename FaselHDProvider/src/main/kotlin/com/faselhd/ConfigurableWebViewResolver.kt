package com.faselhd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.Coroutines.threadSafeListOf
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import com.faselhd.utils.PluginContext

// ==========================================
// Configurable Cloudflare Killer
// ==========================================

class ConfigurableCloudflareKiller(
    private val blockNonHttp: Boolean = true,
    private val allowThirdPartyCookies: Boolean = true
) : Interceptor {
    companion object {
        const val TAG = "ConfigurableCFKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        safe {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

     val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()
        Log.d(TAG, "[getCookieHeaders] WebViewResolver.webViewUserAgent: ${WebViewResolver.webViewUserAgent}")
        Log.d(TAG, "[getCookieHeaders] savedCookies for ${URI(url).host}: ${savedCookies[URI(url).host]}")
        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }
    
    // Helper since we can't access CloudflareKiller.getHeaders (private)
    private fun getHeaders(headers: Map<String, String>, cookies: Map<String, String>): Headers {
        val finalHeaders = headers.toMutableMap()
        if (cookies.isNotEmpty()) {
            finalHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        return Headers.Builder().apply {
            finalHeaders.forEach { (k, v) -> add(k, v) }
        }.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if(!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }
            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? {
        return safe {
            CookieManager.getInstance()?.getCookie(url)
        }
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean {
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            Log.d(TAG, "[trySolveWithSavedCookies] Cookies for ${request.url}: $cookie")
            cookie.contains("cf_clearance").also { solved ->
                if (solved) {
                    savedCookies[request.url.host] = parseCookieMap(cookie)
                    Log.d(TAG, "[trySolveWithSavedCookies] SAVED COOKIES for ${request.url.host}")
                }
            }
        } ?: false.also { Log.d(TAG, "[trySolveWithSavedCookies] No cookies found for ${request.url}") }
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers = getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        return app.baseClient.newCall(
             request.newBuilder()
                 .headers(headers)
                 .build()
        ).await()
    }
    
    private val Request.cookies: Map<String, String>
        get() = this.header("Cookie")?.let { parseCookieMap(it) } ?: emptyMap()

    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading ConfigurableWebViewResolver to solve cloudflare for ${request.url}")
            ConfigurableWebViewResolver(
                Regex("match_nothing"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex(".*")),
                blockNonHttp = blockNonHttp,
                allowThirdPartyCookies = allowThirdPartyCookies
            ).resolveUsingWebView(
                url
            ) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}

// ==========================================
// Configurable WebView Resolver
// ==========================================

class ConfigurableWebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex>,
    val userAgent: String?,
    val useOkhttp: Boolean,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = 60_000L,
    val blockNonHttp: Boolean = true,
    val allowThirdPartyCookies: Boolean = true
) {

    companion object {
        private const val TAG = "ConfigurableResolver"
    }

    fun toWebResourceResponse(response: Response): WebResourceResponse {
        val contentTypeValue = response.header("Content-Type")
        val typeRegex = Regex("""(.*);(?:.*charset=(.*)(?:|;)|)""")
        return if (contentTypeValue != null) {
            val found = typeRegex.find(contentTypeValue)
            val contentType = found?.groupValues?.getOrNull(1)?.ifBlank { null } ?: contentTypeValue
            val charset = found?.groupValues?.getOrNull(2)?.ifBlank { null }
            WebResourceResponse(contentType, charset, response.body.byteStream())
        } else {
             WebResourceResponse("application/octet-stream", null, response.body.byteStream())
        }
    }
    
    fun webResourceRequestToRequest(request: WebResourceRequest): Request? {
        val webViewUrl = request.url.toString()
        return safe {
            requestCreator(
                request.method,
                webViewUrl,
                request.requestHeaders,
            )
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveUsingWebView(
        url: String,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        val headers = mapOf<String,String>() // Initial headers default to empty
        Log.i(TAG, "Initial web-view request: $url")
        var webView: WebView? = null
        var shouldExit = false

        fun destroyWebView() {
            main {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                shouldExit = true
                Log.i(TAG, "Destroyed webview")
            }
        }

        var fixedRequest: Request? = null
        val extraRequestList = threadSafeListOf<Request>()

        main {
            WebView.setWebContentsDebuggingEnabled(true)
            try {
                webView = WebView(
                    PluginContext.context
                        ?: throw RuntimeException("No base context in WebViewResolver")
                ).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // Explicitly set database path for older WebViews or specific implementations
                    PluginContext.context?.getDir("database", Context.MODE_PRIVATE)?.path?.let {
                        settings.databasePath = it
                    }

                    // CONFIGURABLE OPTION
                    Log.d(TAG, "Setting CookieManager thirdPartyCookies to $allowThirdPartyCookies")
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, allowThirdPartyCookies)

                    WebViewResolver.webViewUserAgent = settings.userAgentString
                    if (userAgent != null) {
                        settings.userAgentString = userAgent
                    }
                    Log.d(TAG, "WebView Settings: JS=${settings.javaScriptEnabled}, Dom=${settings.domStorageEnabled}, DB=${settings.databaseEnabled}, UA=${settings.userAgentString}")
                }

                // Use ConfigurableSafeWebViewClient
                webView?.webViewClient = ConfigurableSafeWebViewClient(
                    blockNonHttp = blockNonHttp, // CONFIGURABLE OPTION
                    delegate = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = runBlocking {
                        val webViewUrl = request.url.toString()
                        Log.i(TAG, "Loading WebView URL: $webViewUrl")

                        if (script != null) {
                            val handler = Handler(Looper.getMainLooper())
                            handler.post {
                                view.evaluateJavascript(script)
                                { scriptCallback?.invoke(it) }
                            }
                        }

                        if (interceptUrl.containsMatchIn(webViewUrl)) {
                            fixedRequest = webResourceRequestToRequest(request)?.also {
                                requestCallBack(it)
                            }
                            Log.i(TAG, "Web-view request finished: $webViewUrl")
                            destroyWebView()
                            return@runBlocking null
                        }

                        if (additionalUrls.any { it.containsMatchIn(webViewUrl) }) {
                            webResourceRequestToRequest(request)?.also {
                                if (requestCallBack(it)) destroyWebView()
                            }?.let(extraRequestList::add)
                        }

                         val blacklistedFiles = listOf(
                            ".jpg", ".png", ".webp", ".mpg", ".mpeg", ".jpeg", ".webm", ".mp4", ".mp3",
                            ".gifv", ".flv", ".asf", ".mov", ".mng", ".mkv", ".ogg", ".avi", ".wav",
                            ".woff2", ".woff", ".ttf", ".css", ".vtt", ".srt", ".ts", ".gif", "wss://"
                        )

                        return@runBlocking try {
                            when {
                                blacklistedFiles.any { URI(webViewUrl).path.contains(it) } || webViewUrl.endsWith("/favicon.ico") -> 
                                    WebResourceResponse("image/png", null, null)

                                webViewUrl.contains("recaptcha") || webViewUrl.contains("/cdn-cgi/") -> 
                                    super.shouldInterceptRequest(view, request)

                                useOkhttp && request.method == "GET" -> 
                                    app.get(webViewUrl, headers = request.requestHeaders).okhttpResponse.let { toWebResourceResponse(it) }

                                useOkhttp && request.method == "POST" -> 
                                    app.post(webViewUrl, headers = request.requestHeaders).okhttpResponse.let { toWebResourceResponse(it) }

                                else -> super.shouldInterceptRequest(view, request)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.proceed()
                    }
                })
                webView?.loadUrl(url, headers)
            } catch (e: Exception) {
                logError(e)
            }
        }

        var loop = 0
        val totalTime = timeout
        val delayTime = 100L

        while (loop < totalTime / delayTime && !shouldExit) {
            if (fixedRequest != null) return fixedRequest to extraRequestList
            delay(delayTime)
            loop += 1
        }

        Log.i(TAG, "Web-view timeout after ${totalTime / 1000}s")
        destroyWebView()
        return fixedRequest to extraRequestList
    }
}

// ==========================================
// Configurable Safe WebView Client
// ==========================================

open class ConfigurableSafeWebViewClient(
    private val blockNonHttp: Boolean,
    private val delegate: WebViewClient? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "ConfigurableSafeClient"
        private val ALLOWED_SCHEMES = setOf("http", "https", "about", "blob", "data", "javascript")
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme?.lowercase()

        if (blockNonHttp && scheme != null && scheme !in ALLOWED_SCHEMES) {
            Log.w(TAG, "Blocked redirect to non-HTTP URL: $url (scheme: $scheme)")
            return true 
        }

        return delegate?.shouldOverrideUrlLoading(view, request) ?: false
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (blockNonHttp && url != null) {
            val scheme = url.substringBefore("://").lowercase()
            if (scheme !in ALLOWED_SCHEMES) {
                Log.w(TAG, "Blocked redirect to non-HTTP URL: $url (scheme: $scheme)")
                return true
            }
        }
        @Suppress("DEPRECATION")
        return delegate?.shouldOverrideUrlLoading(view, url) ?: false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon) ?: super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        delegate?.onPageFinished(view, url) ?: super.onPageFinished(view, url)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        delegate?.onLoadResource(view, url) ?: super.onLoadResource(view, url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        return delegate?.shouldInterceptRequest(view, request) ?: super.shouldInterceptRequest(view, request)
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        delegate?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        delegate?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error)
    }
}
