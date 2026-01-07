package com.faselhd.utils // Changed package

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebResourceError
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.lagradost.cloudstream3.network.WebViewResolver
// Local imports
import com.faselhd.utils.GlobalHeaderStore
import com.faselhd.utils.HttpTraceLogger
import java.io.Serializable

class VideoSnifferActivity : AppCompatActivity() {

    companion object {
        const val TAG = "VideoSniffer"
        const val EXTRA_URL = "extra_url"
        const val RESULT_VIDEO_URL = "result_video_url"
        const val RESULT_HEADERS = "result_headers"
        
        private const val MIN_VIDEO_URL_LENGTH = 50 
        
        const val EXTRA_HEADERS = "extra_headers"
        
        fun createIntent(context: Context, url: String, headers: Map<String, String> = emptyMap()): Intent {
            return Intent(context, VideoSnifferActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HEADERS, HashMap(headers))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var resultDelivered = false
    private var targetUrl: String = ""
    private var allowedDomain: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        targetUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            Log.e(TAG, "No URL provided")
            finish()
            return
        }

        allowedDomain = extractBaseDomain(targetUrl)
        
        @Suppress("UNCHECKED_CAST")
        val extraHeaders = intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>
        
        Log.d(TAG, "Starting Video Sniffer for: $targetUrl (Allowed Domain: $allowedDomain)")
        HttpTraceLogger.logRequest("WEBVIEW_INTENT", targetUrl, "GET", extraHeaders?.toMap() ?: emptyMap())
        if (!extraHeaders.isNullOrEmpty()) {
             Log.d(TAG, "Injecting ${extraHeaders.size} headers")
        }
        
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        
        val host = Uri.parse(targetUrl).host
        if (host != null) {
            val globalCookies = GlobalHeaderStore.getCookiesForHost(host)
            if (globalCookies.isNotEmpty()) {
                Log.d(TAG, "Syncing ${globalCookies.size} global cookies for $host")
                globalCookies.forEach { (key, value) ->
                    cookieManager.setCookie(targetUrl, "$key=$value")
                }
            }
        }

        extraHeaders?.get("Cookie")?.let { cookieString ->
             Log.d(TAG, "Injecting Intent Cookies into WebView: $cookieString")
             cookieString.split(";").forEach { cookie ->
                 if (cookie.isNotBlank()) {
                    cookieManager.setCookie(targetUrl, cookie.trim())
                 }
             }
        }
        cookieManager.flush()
        
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK) // Changed to Black for better UI
        }
        
        val headerText = TextView(this).apply {
            text = "Video Link Extraction"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(32)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }
        
        statusText = TextView(this).apply {
            text = "Preparing WebView..."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = 140
            }
        }
        
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = 180
            }
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                
                val unifiedUA = GlobalHeaderStore.unifiedUserAgent 
                    ?: extraHeaders?.get("User-Agent")
                    ?: extraHeaders?.get("user-agent")
                    ?: WebViewResolver.webViewUserAgent 
                    ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                
                userAgentString = unifiedUA
                Log.d(TAG, "Using User-Agent: $unifiedUA")
            }
            
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            
            webChromeClient = object : WebChromeClient() {}
            
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        Log.w(TAG, "Blocking non-HTTP navigation: $url")
                        return true
                    }

                    val targetDomain = extractBaseDomain(url)
                    if (targetDomain != null && allowedDomain != null && targetDomain != allowedDomain) {
                        Log.w(TAG, "Blocking cross-domain redirect: $url (Target: $targetDomain, Base: $allowedDomain)")
                        return true
                    }

                    return false 
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    updateStatus("Page Loaded. Searching for video...")
                    injectSnifferScript()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "Unknown Error"
                        val code = error?.errorCode ?: 0
                        Log.e(TAG, "WebView error: $desc (Code: $code)")
                        runOnUiThread {
                            updateStatus("Error: $desc")
                        }
                    }
                }
                
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    
                    if (isVideoUrl(url)) {
                        if (url.length >= MIN_VIDEO_URL_LENGTH) {
                            Log.i(TAG, "🎯 VIDEO DETECTED (V2): $url")
                            HttpTraceLogger.logResponse("WEBVIEW_SNIFFER", url, 200, request.requestHeaders)
                            runOnUiThread {
                                deliverResult(url, request.requestHeaders)
                            }
                        } else {
                            Log.d(TAG, "Filtered probable ad-proxy video: $url")
                        }
                    }
                    HttpTraceLogger.logRequest("WEBVIEW_RESOURCE", url, request.method, request.requestHeaders)
                    
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        
        val progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 32
            }
            isIndeterminate = true
        }
        
        rootLayout.addView(webView)
        rootLayout.addView(headerText)
        rootLayout.addView(statusText)
        rootLayout.addView(progressBar)
        
        setContentView(rootLayout)
        
        updateStatus("Loading Video Player...")
        
        if (targetUrl.startsWith("http")) {
            if (extraHeaders != null && extraHeaders.isNotEmpty()) {
                val loadHeaders = extraHeaders.filterKeys { !it.equals("Cookie", ignoreCase = true) }
                webView.loadUrl(targetUrl, loadHeaders)
            } else {
                webView.loadUrl(targetUrl)
            }
        } else {
            Log.e(TAG, "Invalid URL scheme: $targetUrl")
            Toast.makeText(this, "Wait... Invalid URL: $targetUrl", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".ts") || lower.contains(".js") || lower.contains(".css") || 
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".gif")) {
            return false
        }
        return lower.contains(".m3u8") || lower.contains(".mp4") || 
               lower.contains(".mpd") || lower.contains("/hls/") || 
               lower.contains("/dash/") || lower.contains(".mkv")
    }

    private fun extractBaseDomain(url: String): String? {
        return try {
            val host = Uri.parse(url).host ?: return null
            val parts = host.split(".")
            if (parts.size >= 2) {
                parts[parts.size - 2] + "." + parts[parts.size - 1]
            } else {
                host
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun injectSnifferScript() {
        // Reduced script for brevity, but functional
        val script = """
            javascript:(function() {
                // Simplified Sniffer Hook
                function checkVideo(src) {
                    if (!src) return;
                    if (src.match(/\.(m3u8|mp4|mkv|webm|mpd)(\$|\?)/i)) {
                        console.log('[Sniffer] Found video via DOM: ' + src);
                    }
                }
                var videos = document.getElementsByTagName('video');
                for(var i=0; i<videos.length; i++) {
                    checkVideo(videos[i].src);
                    checkVideo(videos[i].currentSrc);
                }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }
    
    private fun updateStatus(message: String) {
        handler.post { statusText.text = message }
    }
    
    private fun deliverResult(videoUrl: String, headers: Map<String, String>) {
        if (resultDelivered) return
        resultDelivered = true
        
        Log.d(TAG, "=== DELIVERING VIDEO RESULT ===")
        
        val webViewUA = webView.settings.userAgentString
        GlobalHeaderStore.initUserAgent(this)

        val cookiesString = CookieManager.getInstance().getCookie(targetUrl) ?: ""
        try {
            val host = Uri.parse(targetUrl).host
            if (host != null) {
                val cookiesMap = cookiesString.split(";").associate {
                    val parts = it.split("=", limit = 2)
                    (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
                }.filter { it.key.isNotBlank() }
                
                GlobalHeaderStore.setCookiesForHost(host, cookiesMap)
            }
        } catch (e: Exception) {}

        val finalHeaders = HashMap<String, String>()
        finalHeaders.putAll(headers)
        finalHeaders["User-Agent"] = webViewUA
        finalHeaders["Cookie"] = cookiesString
        finalHeaders["Referer"] = webView.url ?: targetUrl
        
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {}
        
        val resultIntent = Intent().apply {
            putExtra(RESULT_VIDEO_URL, videoUrl)
            putExtra(RESULT_HEADERS, finalHeaders as Serializable)
        }
        
        VideoSniffer.onResult(Activity.RESULT_OK, resultIntent)
        
        setResult(Activity.RESULT_OK, resultIntent)
        handler.post { finish() }
    }
    
    override fun onBackPressed() {
        if (!resultDelivered) {
            VideoSniffer.onResult(Activity.RESULT_CANCELED, null)
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (!resultDelivered) {
            VideoSniffer.onResult(Activity.RESULT_CANCELED, null)
        }
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
