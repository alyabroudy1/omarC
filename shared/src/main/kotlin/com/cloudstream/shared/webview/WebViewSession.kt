package com.cloudstream.shared.webview

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_WEBVIEW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the WebView engines ([CfBypassEngine], [VideoSnifferEngine],
 * [com.cloudstream.shared.network.ChromiumFetcher]) all do the same way.
 *
 * Three things here are structural guarantees, not conveniences:
 *
 * 1. **One fingerprint.** Every WebView is born in [createWebView], i.e. in [WebViewFactory], so the
 *    Wave 1 UA + `X-Requested-With` invariant holds by construction instead of by convention.
 * 2. **One session at a time per engine instance.** [withSession] takes a per-instance [Mutex], so
 *    two concurrent `runSession`/`fetch` calls queue instead of trampling each other's
 *    [resultDelivered] flag and WebView reference.
 * 3. **No orphaned coroutines.** Everything an engine launches goes through [launchInSession], whose
 *    scope is created at session start and cancelled when the session ends. A poller or a timeout
 *    that fires after teardown lands on a cancelled scope and does nothing.
 *
 * Anything used by only one engine — dialog chrome, exit-condition logic, the ad-skip overlay —
 * stays in that engine.
 */
abstract class WebViewSession(
    protected val activityProvider: () -> Activity?
) {

    /** Serialises [withSession] bodies for this instance. */
    private val sessionMutex = Mutex()

    /**
     * Whether this session already handed a result to its caller.
     *
     * `@Volatile` because it is written from the session scope (main thread) and read from WebView
     * callbacks and, on cancellation, from the caller's own thread. Reset by [withSession].
     */
    @Volatile
    protected var resultDelivered: Boolean = false

    /**
     * Scope for every coroutine this session launches. Replaced per session, cancelled by [cleanup];
     * a [SupervisorJob] so one failing child does not tear down its siblings.
     */
    @Volatile
    private var sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Runs [block] as the one session of this engine instance: waits for any session in flight,
     * resets [resultDelivered], installs a fresh session scope, and calls [cleanup] on the way out
     * (normal return, exception, or cancellation).
     *
     * [block] must not be run *in* [sessionScope] — [cleanup] cancels that scope, which would
     * cancel the caller before it could deliver its own result.
     */
    protected suspend fun <T> withSession(block: suspend () -> T): T {
        if (sessionMutex.isLocked) {
            ProviderLogger.i(TAG_WEBVIEW, "WebViewSession.withSession", "session busy, waiting",
                "engine" to (this::class.simpleName ?: "?"))
        }
        return sessionMutex.withLock {
            resultDelivered = false
            sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                block()
            } finally {
                cleanup()
            }
        }
    }

    /** Launches [block] in the current session scope. After [cleanup] this is a no-op. */
    protected fun launchInSession(block: suspend CoroutineScope.() -> Unit): Job =
        sessionScope.launch(block = block)

    /** The only WebView constructor in the engines. */
    protected fun createWebView(activity: Activity): WebView = WebViewFactory.create(activity)

    /**
     * The page's current serialised DOM, or `""` if the WebView cannot produce one.
     *
     * `evaluateJavascript` hands back a JSON string literal, so the result is unwrapped with
     * `JSONTokener` rather than by stripping quotes — the DOM is full of `\"` and `\n`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    protected suspend fun extractHtml(webView: WebView): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                "(function() { return document.documentElement.outerHTML; })();"
            ) { result ->
                val html = try {
                    if (result == null || result == "null") ""
                    else org.json.JSONTokener(result).nextValue().toString()
                } catch (e: Exception) {
                    ProviderLogger.e(TAG_WEBVIEW, "WebViewSession.extractHtml", "HTML unwrap failed", e)
                    ""
                }
                if (cont.isActive) cont.resume(html) {}
            }
        }
    }

    /**
     * Cookies for [url] as the page sees them: the system [CookieManager] merged with
     * `document.cookie`, JS winning on conflict.
     *
     * Both halves are needed. `document.cookie` is the source of truth for what Cloudflare's own JS
     * set, and the CookieManager contributes the `HttpOnly` cookies JS cannot see.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    protected suspend fun extractCookies(webView: WebView, url: String): Map<String, String> =
        suspendCancellableCoroutine { cont ->
            try {
                val cmMap = parseCookieString(CookieManager.getInstance().getCookie(url))

                webView.evaluateJavascript("(function() { return document.cookie; })();") { result ->
                    try {
                        val jsCookieString = if (result != null && result != "null") {
                            result.removeSurrounding("\"")
                        } else ""
                        val jsMap = parseCookieString(jsCookieString)

                        val merged = HashMap<String, String>(cmMap)
                        merged.putAll(jsMap)

                        ProviderLogger.d(TAG_WEBVIEW, "WebViewSession.extractCookies", "Cookie extraction complete",
                            "cmCount" to cmMap.size,
                            "jsCount" to jsMap.size,
                            "total" to merged.size,
                            "hasClearance" to merged.containsKey("cf_clearance"))

                        if (cont.isActive) cont.resume(merged) {}
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG_WEBVIEW, "WebViewSession.extractCookies", "JS parse failed", e)
                        if (cont.isActive) cont.resume(cmMap) {}
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.e(TAG_WEBVIEW, "WebViewSession.extractCookies", "Extraction failed", e)
                if (cont.isActive) cont.resume(emptyMap()) {}
            }
        }

    /**
     * Tears down one WebView and its host dialog: dismiss, then stop, blank, detach and destroy on
     * the main thread (a `destroy()` off the main looper throws, and destroying a still-loading
     * WebView leaves its renderer running).
     */
    protected fun cleanupWebView(webView: WebView?, dialog: Dialog? = null) {
        try {
            dialog?.dismiss()
            webView?.let { view ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        view.clearHistory()
                        view.removeAllViews()
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG_WEBVIEW, "WebViewSession.cleanupWebView", "Error", "error" to e.message)
                    }
                }
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG_WEBVIEW, "WebViewSession.cleanupWebView", "Error", "error" to e.message)
        }
    }

    /**
     * Ends the session scope. Called by [withSession] once the session's caller has its result, so
     * cancelling here cannot cancel that caller.
     *
     * Subclasses override to release their own per-session state and must call `super.cleanup()`.
     */
    protected open fun cleanup() {
        sessionScope.cancel()
    }
}
