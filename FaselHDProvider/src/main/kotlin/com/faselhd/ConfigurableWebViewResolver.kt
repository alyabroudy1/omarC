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
import com.lagradost.cloudstream3.AcraApplication

// ==========================================
// Configurable Cloudflare Killer
// ==========================================

class ConfigurableCloudflareKiller(
    private val userAgent: String? = null,
    private val blockNonHttp: Boolean = true,
    private val allowThirdPartyCookies: Boolean = true,
    /** Callback when domain change is detected (from WebView redirects) */
    private val onDomainChanged: ((oldDomain: String, newDomain: String) -> Unit)? = null,
    /** Callback when cookies are extracted (for ProviderState sync) */
    private val onCookiesExtracted: ((domain: String, cookies: Map<String, String>) -> Unit)? = null
) : Interceptor {
    companion object {
        const val TAG = "ConfigurableCFKiller"
        const val CF_FLOW_TAG = "CF_FLOW"
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
    
    /** 
     * Hosts that have been recently cleared. Prevents re-reading stale cookies from CookieManager.
     * This is crucial because CookieManager.flush() is async and may not complete immediately.
     */
    private val recentlyClearedHosts: MutableSet<String> = mutableSetOf()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()
        Log.d(TAG, "[getCookieHeaders] WebViewResolver.webViewUserAgent: ${WebViewResolver.webViewUserAgent}")
        Log.d(TAG, "[getCookieHeaders] savedCookies for ${URI(url).host}: ${savedCookies[URI(url).host]}")
        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    fun clearCookies(url: String) {
        val host = URI(url).host
        
        // 1. Clear from in-memory map
        savedCookies.remove(host)
        
        // 2. Mark host as recently cleared - prevents re-reading stale cookies
        recentlyClearedHosts.add(host)
        
        // 3. Clear from System CookieManager (more aggressive approach)
        safe {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies != null) {
                // Expire ALL cookies for this URL, not just cf_* ones
                cookies.split(";").forEach { cookie ->
                    val name = cookie.split("=").firstOrNull()?.trim()
                    if (!name.isNullOrBlank()) {
                        // Expire the cookie by setting Max-Age=0
                        cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
                    }
                }
                // Force sync flush
                cookieManager.flush()
            }
        }
        Log.d(TAG, "Cleared cookies for $host (added to recentlyCleared)")
    }
    
    /**
     * Called after a successful WebView solve to allow future reads from CookieManager.
     */
    fun markHostSolved(host: String) {
        recentlyClearedHosts.remove(host)
        Log.d(TAG, "Marked $host as solved (removed from recentlyCleared)")
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
        // 0. Filter noise (images, css, etc.)
        val path = request.url.encodedPath
        val noiseExtensions = listOf(".png", ".jpg", ".jpeg", ".ico", ".css", ".js", ".woff", ".woff2", ".ttf")
        if (noiseExtensions.any { path.endsWith(it, ignoreCase = true) }) {
            return false
        }

        val host = request.url.host
        
        // 1. FIRST: Check in-memory saved cookies (these are FRESH from JS callback)
        val savedHostCookies = savedCookies[host]
        if (savedHostCookies != null && savedHostCookies.isNotEmpty()) {
            Log.i(CF_FLOW_TAG, ">>> [trySolve] ✓ Found ${savedHostCookies.size} cookies in savedCookies for $host")
            return true
        }

        // 2. If host was recently cleared, do NOT read from System CookieManager (stale)
        if (recentlyClearedHosts.contains(host)) {
            Log.d(CF_FLOW_TAG, ">>> [trySolve] Host $host recently cleared, skipping CookieManager")
            return false
        }

        // 3. Fallback to System CookieManager (only if not recently cleared)
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            val cookieCount = cookie.split(";").filter { it.contains("=") }.size
            Log.d(CF_FLOW_TAG, ">>> [trySolve] CookieManager check | host=$host | cookieCount=$cookieCount") 
            
            if (cookie.isNotBlank() && cookieCount > 0) {
                savedCookies[host] = parseCookieMap(cookie)
                Log.i(CF_FLOW_TAG, ">>> [trySolve] ✓ Saved $cookieCount cookies from CookieManager for $host")
                true
            } else {
                false
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        // Use local userAgent if available, otherwise global fallback
        val effectiveUserAgent = userAgent ?: WebViewResolver.webViewUserAgent
        
        val userAgentMap = effectiveUserAgent?.let {
            mapOf("User-Agent" to it)
        } ?: emptyMap()

        // Filter out existing UA from request to avoid duplicates/conflicts
        val requestHeaders = request.headers.toMap().filterKeys { 
            !it.equals("User-Agent", ignoreCase = true) 
        }

        val headers = getHeaders(requestHeaders + userAgentMap, cookies + request.cookies)
        Log.i(CF_FLOW_TAG, ">>> [proceed] Making request with cookies | url=${request.url.toString().take(60)} | cookieCount=${cookies.size} | UA=${effectiveUserAgent?.take(40)}")
        return app.baseClient.newCall(
             request.newBuilder()
                 .headers(headers)
                 .build()
        ).await()
    }
    
    private val Request.cookies: Map<String, String>
        get() = this.header("Cookie")?.let { parseCookieMap(it) } ?: emptyMap()

    /**
     * Bypass Cloudflare using WebView.
     * 
     * ## Flow:
     * 1. Load target URL in WebView
     * 2. Detect domain changes (redirects) FIRST
     * 3. Wait for page content to load (not Cloudflare challenge)
     * 4. Extract ALL cookies (not just cf_clearance)
     * 5. Return response with cookies
     * 
     * NO RETRIES - single clean pass.
     */
    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()
        val originalHost = request.url.host
        
        Log.i(CF_FLOW_TAG, "========================================")
        Log.i(CF_FLOW_TAG, ">>> [bypassCloudflare] START")
        Log.i(CF_FLOW_TAG, ">>> URL: $url")
        Log.i(CF_FLOW_TAG, ">>> OriginalHost: $originalHost")
        Log.i(CF_FLOW_TAG, ">>> UA: ${userAgent?.take(50)}")
        Log.i(CF_FLOW_TAG, "========================================")

        // Check if we already have valid cookies
        val existingCookies = savedCookies[originalHost]
        if (existingCookies != null && existingCookies.isNotEmpty()) {
            Log.i(CF_FLOW_TAG, ">>> Already have cookies for $originalHost: ${existingCookies.keys}")
            return proceed(request, existingCookies)
        }

        // Track state during WebView navigation
        var finalDomain = originalHost
        var extractedCookies: Map<String, String> = emptyMap()
        var contentLoaded = false
        
        Log.i(CF_FLOW_TAG, ">>> Loading WebView to solve Cloudflare...")
        
        ConfigurableWebViewResolver(
            interceptUrl = Regex("match_nothing"), // Don't intercept for immediate exit
            userAgent = userAgent,
            useOkhttp = false,
            additionalUrls = listOf(Regex(".*")),
            blockNonHttp = blockNonHttp,
            allowThirdPartyCookies = allowThirdPartyCookies,
            timeout = 60_000L,
            script = """
                (function() {
                    try {
                        // 1. Check if this is a Cloudflare challenge page
                        var title = (document.title || "").toLowerCase();
                        var isChallenge = 
                            title.includes("just a moment") || 
                            title.includes("cloudflare") ||
                            title.includes("checking your browser") ||
                            title.includes("تحقق") ||
                            document.getElementById('cf-wrapper') != null ||
                            document.getElementById('challenge-form') != null ||
                            document.querySelector('[data-ray]') != null;
                        
                        if (isChallenge) {
                            return "CLOUDFLARE_CHALLENGE";
                        }
                        
                        // 2. Page is real content - extract ALL cookies
                        var cookies = document.cookie;
                        if (cookies && cookies.length > 0) {
                            return cookies;
                        }
                        return "CONTENT_LOADED_NO_COOKIES";
                    } catch(e) {
                        return "ERROR:" + e.message;
                    }
                })();
            """.trimIndent(),
            scriptCallback = { result ->
                Log.i(CF_FLOW_TAG, ">>> [JS_CALLBACK] Result: ${result.take(100)}")
                
                when {
                    result == "\"CLOUDFLARE_CHALLENGE\"" || result == "CLOUDFLARE_CHALLENGE" -> {
                        Log.d(CF_FLOW_TAG, ">>> [JS_CALLBACK] Still on challenge page, waiting...")
                    }
                    result.startsWith("\"ERROR:") || result.startsWith("ERROR:") -> {
                        Log.w(CF_FLOW_TAG, ">>> [JS_CALLBACK] JS error: $result")
                    }
                    result == "\"CONTENT_LOADED_NO_COOKIES\"" || result == "CONTENT_LOADED_NO_COOKIES" -> {
                        Log.i(CF_FLOW_TAG, ">>> [JS_CALLBACK] Content loaded but no cookies from JS")
                        contentLoaded = true
                    }
                    result.isNotBlank() && result != "null" && result != "\"\"" -> {
                        Log.i(CF_FLOW_TAG, ">>> [JS_CALLBACK] Content loaded with cookies!")
                        contentLoaded = true
                        
                        // Parse ALL cookies (not filtered)
                        val cleanResult = result.removeSurrounding("\"")
                        extractedCookies = parseCookieMap(cleanResult)
                        Log.i(CF_FLOW_TAG, ">>> [JS_CALLBACK] Parsed ${extractedCookies.size} cookies: ${extractedCookies.keys}")
                    }
                }
            }
        ).resolveUsingWebView(url) { webViewRequest ->
            val reqUrl = webViewRequest.url.toString()
            val reqHost = webViewRequest.url.host
            
            // DOMAIN CHANGE DETECTION (before cookie extraction)
            if (reqHost != null && 
                reqHost != originalHost && 
                !reqHost.contains("cloudflare") &&
                !reqHost.contains("cdn-cgi") &&
                !reqHost.contains("challenges.cloudflare.com") &&
                reqHost.contains("fasel", ignoreCase = true)) {
                
                Log.i(CF_FLOW_TAG, ">>> [DOMAIN_CHANGE] $originalHost -> $reqHost")
                finalDomain = reqHost
            }
            
            // Exit if content loaded
            if (contentLoaded && extractedCookies.isNotEmpty()) {
                Log.i(CF_FLOW_TAG, ">>> [INTERCEPT] Content loaded with cookies, exiting WebView")
                return@resolveUsingWebView true
            }
            
            false // Keep WebView running
        }
        
        Log.i(CF_FLOW_TAG, ">>> [POST_WEBVIEW] WebView completed")
        Log.i(CF_FLOW_TAG, ">>> [POST_WEBVIEW] FinalDomain: $finalDomain")
        Log.i(CF_FLOW_TAG, ">>> [POST_WEBVIEW] ContentLoaded: $contentLoaded")
        Log.i(CF_FLOW_TAG, ">>> [POST_WEBVIEW] ExtractedCookies: ${extractedCookies.keys}")
        
        // If no cookies from JS, try CookieManager with FINAL domain
        if (extractedCookies.isEmpty()) {
            val cookieUrl = "https://$finalDomain"
            val cmCookies = getWebViewCookie(cookieUrl)
            Log.i(CF_FLOW_TAG, ">>> [COOKIE_MANAGER] URL: $cookieUrl")
            Log.i(CF_FLOW_TAG, ">>> [COOKIE_MANAGER] Cookies: ${cmCookies?.take(100)}")
            
            if (!cmCookies.isNullOrBlank()) {
                extractedCookies = parseCookieMap(cmCookies)
                Log.i(CF_FLOW_TAG, ">>> [COOKIE_MANAGER] Parsed ${extractedCookies.size} cookies: ${extractedCookies.keys}")
            }
            
            // Also try original host if different from final
            if (extractedCookies.isEmpty() && finalDomain != originalHost) {
                val originalUrl = "https://$originalHost"
                val originalCookies = getWebViewCookie(originalUrl)
                Log.i(CF_FLOW_TAG, ">>> [COOKIE_MANAGER] Original URL: $originalUrl")
                Log.i(CF_FLOW_TAG, ">>> [COOKIE_MANAGER] Original Cookies: ${originalCookies?.take(100)}")
                
                if (!originalCookies.isNullOrBlank()) {
                    extractedCookies = parseCookieMap(originalCookies)
                }
            }
        }
        
        // Store cookies for the FINAL domain
        if (extractedCookies.isNotEmpty()) {
            savedCookies[finalDomain] = extractedCookies
            markHostSolved(finalDomain)
            Log.i(CF_FLOW_TAG, ">>> [SAVED] Cookies saved for $finalDomain")
            
            // Invoke domain change callback if domain changed
            if (finalDomain != originalHost) {
                Log.i(CF_FLOW_TAG, ">>> [CALLBACK] Notifying domain change: $originalHost -> $finalDomain")
                onDomainChanged?.invoke(originalHost, finalDomain)
            }
            
            // Invoke cookie extraction callback
            Log.i(CF_FLOW_TAG, ">>> [CALLBACK] Notifying cookies extracted for $finalDomain")
            onCookiesExtracted?.invoke(finalDomain, extractedCookies)
            
            Log.i(CF_FLOW_TAG, "========================================")
            return proceed(request, extractedCookies)
        }
        
        Log.w(CF_FLOW_TAG, ">>> [FAIL] No cookies extracted after WebView")
        Log.i(CF_FLOW_TAG, "========================================")
        return null
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
                    AcraApplication.context
                        ?: throw RuntimeException("No base context in WebViewResolver")
                ).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // Explicitly set database path for older WebViews or specific implementations
                    AcraApplication.context?.getDir("database", Context.MODE_PRIVATE)?.path?.let {
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
