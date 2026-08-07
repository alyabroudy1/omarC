package com.cloudstream.shared.webview

import android.webkit.WebSettings
import android.webkit.WebView
import com.cloudstream.shared.logging.ProviderLogger
import java.lang.reflect.InvocationHandler
import org.chromium.support_lib_boundary.WebSettingsBoundaryInterface
import org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface
import org.chromium.support_lib_boundary.WebkitToCompatConverterBoundaryInterface
import java.lang.reflect.Proxy

/**
 * Turns off WebView's `X-Requested-With: <package name>` header by talking to the WebView APK's own
 * support-library boundary directly, without androidx.webkit on the classpath.
 *
 * ## Why this file exists at all
 *
 * That header is the single most damaging thing this app puts on the wire. Measured 2026-08-06 against
 * freex's countdown page, changing nothing else:
 *
 * | `X-Requested-With` | `get-link.php` | requests | download button |
 * |---|---|---|---|
 * | absent | 200 at 11,773 ms | 29 | filled with the token |
 * | `com.lagradost.cloudstream3` | **never sent** | **4** | stays empty |
 * | `""` | 200 at 11,570 ms | 33 | filled with the token |
 *
 * And from a HAR of Chrome doing the same flow on-device: the header appears **once in 360 requests**,
 * as jQuery's ordinary `XMLHttpRequest`. Real browsers never send it. Google also refuses to serve
 * AdSense to a client that does — which is why our ad slots never fill, and an ad-gate that is never
 * paid has no reason to release a link.
 *
 * WebView adds it *below* the API surface: it is absent from `WebResourceRequest.getRequestHeaders()`,
 * so it cannot be seen, and `shouldInterceptRequest` can only avoid it by re-issuing a request
 * ourselves — which works for GETs, cannot work for POSTs (no body available) and breaks CORS-fetched
 * scripts (we forward no response headers; tried 2026-08-07, it took the freex page from 644 anchors
 * down to 2). The only place to remove it properly is where it is added.
 *
 * ## Why not just depend on androidx.webkit
 *
 * `CimaNowProviderV2/build.gradle.kts` documents the attempt: the CloudStream gradle plugin dexes
 * **project classes only**, and declared dependencies are compile-time stubs resolved against what the
 * host app already ships. CloudStream does not bundle androidx.webkit, so a `.cs3` built with the
 * dependency contains zero `androidx/webkit` classes and the device logs
 * `androidx.webkit not on the classpath`.
 *
 * ## How this works instead
 *
 * androidx.webkit is a thin reflective shim over interfaces the WebView APK already implements. The
 * chain it walks — and this file reproduces — is:
 *
 *  1. `org.chromium.support_lib_glue.SupportLibReflectionUtil.createWebViewProviderFactory()`, loaded
 *     from `WebView.getWebViewClassLoader()`, returns an [InvocationHandler].
 *  2. Proxy it as a factory, ask for `getSupportedFeatures()` and `getWebkitToCompatConverter()`.
 *  3. `convertSettings(WebSettings)` gives an [InvocationHandler] for that settings object.
 *  4. Proxy that and call `setRequestedWithHeaderOriginAllowList(emptySet())` — or, on older WebViews,
 *     `setRequestedWithHeaderMode(NO_HEADER)`.
 *
 * The interfaces we proxy **must be named `org.chromium.support_lib_boundary.*`** — see
 * `BoundaryInterfaces.kt`. Declaring them in this package was tried first, on the assumption that the
 * glue matched on method name and parameter types alone. It does not: `dupeMethod` re-loads the
 * *declaring class of the method* by name from the WebView's class loader, so an interface the WebView
 * APK has never heard of fails at `Class.forName` before the delegate is ever reached. On-device
 * 2026-08-07:
 *
 * ```
 * Reflection failed for method public abstract java.lang.String[]
 *   com.cloudstream.shared.webview.RequestedWithHeaderControl$FactoryBoundary.getSupportedFeatures()
 * ```
 *
 * Names and parameter types still have to match exactly; the package is an additional requirement, not
 * an alternative to them.
 *
 * Everything is best-effort and heavily logged. A WebView that does not expose the feature leaves the
 * header exactly as it is today, which is the current behaviour, so this cannot regress anything.
 */
object RequestedWithHeaderControl {

    private const val TAG = "XRWControl"

    /** androidx.webkit's `WebSettingsCompat.REQUESTED_WITH_HEADER_MODE_NO_HEADER`. */
    private const val MODE_NO_HEADER = 0

    private const val GLUE_CLASS = "org.chromium.support_lib_glue.SupportLibReflectionUtil"
    private const val GLUE_METHOD = "createWebViewProviderFactory"

    /** Feature names the WebView APK advertises for the two generations of this API. */
    private const val FEATURE_ALLOW_LIST = "REQUESTED_WITH_HEADER_ALLOW_LIST"
    private const val FEATURE_MODE = "REQUESTED_WITH_HEADER_CONTROL"

    /** Logged once — the feature list is a property of the WebView build, not of a call. */
    @Volatile
    private var featuresLogged = false

    // The boundary interfaces themselves live in `org.chromium.support_lib_boundary` — that exact
    // package is required, see BoundaryInterfaces.kt for the `dupeMethod` reason and the on-device
    // failure that proved it.

    private inline fun <reified T> cast(handler: InvocationHandler): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java), handler) as T

    /**
     * The class loader the WebView APK's glue lives in — and the reason this is not a one-liner.
     *
     * `WebView.getWebViewClassLoader()` is **API 28**, while this project's `minSdk` is **21**. Calling it
     * unguarded is a `NoSuchMethodError` on Android 5 through 9, i.e. on every Android 7 device, which is
     * exactly the population least likely to have anything else go right.
     *
     * Below 28 the loader comes from `WebViewFactory.getProvider()`, a hidden static. That is safe
     * *specifically because* it is only used there: hidden-API restrictions begin at API 28, which is the
     * same release that made the public method available, so the two paths cover the range between them
     * with no overlap and no blocklisted access.
     *
     * A device whose WebView predates the support-library glue (~WebView 66, 2018) simply fails to find
     * the class, logs, and leaves today's behaviour untouched. WebView is updatable from Android 5
     * onwards, so an old OS does not imply an old WebView — the device this was built for runs Android 16
     * with WebView 150, but an Android 7 phone on WebView 150 is equally normal and works the same way.
     */
    private fun webViewClassLoader(): ClassLoader? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return try {
                WebView.getWebViewClassLoader()
            } catch (e: Throwable) {
                ProviderLogger.w(TAG, "webViewClassLoader",
                    "WebView.getWebViewClassLoader() failed on API " +
                        "${android.os.Build.VERSION.SDK_INT}: ${e.message}")
                null
            }
        }
        return try {
            val provider = Class.forName("android.webkit.WebViewFactory")
                .getDeclaredMethod("getProvider")
                .apply { isAccessible = true }
                .invoke(null)
            provider?.javaClass?.classLoader.also {
                ProviderLogger.i(TAG, "webViewClassLoader",
                    "Resolved via WebViewFactory.getProvider() (pre-API-28 path)",
                    "sdk" to android.os.Build.VERSION.SDK_INT.toString())
            }
        } catch (e: Throwable) {
            ProviderLogger.w(TAG, "webViewClassLoader",
                "Pre-API-28 provider reflection failed — cannot reach the glue",
                "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
                "error" to (e.message ?: e.javaClass.simpleName))
            null
        }
    }

    /**
     * Best-effort. Returns true only if a header-suppressing call actually went through.
     *
     * Every failure mode is logged with what was attempted, because the cost of not knowing which step
     * failed is another day of guessing at 403s.
     */
    fun suppress(webView: WebView): Boolean {
        val factoryHandler = try {
            val loader = webViewClassLoader() ?: return false
            val glue = Class.forName(GLUE_CLASS, false, loader)
            val create = glue.getDeclaredMethod(GLUE_METHOD)
            create.isAccessible = true
            create.invoke(null) as? InvocationHandler
        } catch (e: Throwable) {
            ProviderLogger.w(TAG, "suppress",
                "Cannot reach the WebView support-library glue — leaving the header as it is",
                "error" to (e.message ?: e.javaClass.simpleName))
            null
        } ?: return false

        val factory = try { cast<WebViewProviderFactoryBoundaryInterface>(factoryHandler) } catch (e: Throwable) {
            ProviderLogger.w(TAG, "suppress", "Factory proxy failed: ${e.message}")
            return false
        }

        val features = try { factory.getSupportedFeatures() } catch (e: Throwable) {
            ProviderLogger.w(TAG, "suppress", "getSupportedFeatures failed: ${e.message}")
            emptyArray()
        }
        if (!featuresLogged) {
            featuresLogged = true
            val relevant = features.filter { it.contains("REQUESTED_WITH", ignoreCase = true) }
            ProviderLogger.i(TAG, "suppress", "WebView support-library features",
                "count" to features.size.toString(),
                "requestedWith" to (relevant.joinToString(",").ifEmpty { "NONE ADVERTISED" }))
        }
        val hasAllowList = features.any { it.startsWith(FEATURE_ALLOW_LIST) }
        val hasMode = features.any { it.startsWith(FEATURE_MODE) }

        val settingsHandler = try {
            cast<WebkitToCompatConverterBoundaryInterface>(factory.getWebkitToCompatConverter())
                .convertSettings(webView.settings)
        } catch (e: Throwable) {
            ProviderLogger.w(TAG, "suppress", "convertSettings failed: ${e.message}")
            return false
        }
        val settings = try { cast<WebSettingsBoundaryInterface>(settingsHandler) } catch (e: Throwable) {
            ProviderLogger.w(TAG, "suppress", "Settings proxy failed: ${e.message}")
            return false
        }

        // Allow-list first: it is the current API, and an empty list means "send it to nobody", which is
        // exactly what a browser does. `setRequestedWithHeaderMode` is its deprecated predecessor.
        if (hasAllowList || !hasMode) {
            try {
                settings.setRequestedWithHeaderOriginAllowList(emptySet())
                val readBack = try { settings.getRequestedWithHeaderOriginAllowList() } catch (_: Throwable) { null }
                ProviderLogger.i(TAG, "suppress",
                    "✅ X-Requested-With suppressed via the origin allow-list — no origin will receive it",
                    "advertised" to hasAllowList.toString(),
                    "readBack" to (readBack?.size?.toString() ?: "unreadable"))
                return true
            } catch (e: Throwable) {
                ProviderLogger.w(TAG, "suppress", "Allow-list call failed: ${e.message}")
            }
        }

        if (hasMode) {
            try {
                settings.setRequestedWithHeaderMode(MODE_NO_HEADER)
                val readBack = try { settings.getRequestedWithHeaderMode() } catch (_: Throwable) { -1 }
                val ok = readBack == MODE_NO_HEADER
                ProviderLogger.i(TAG, "suppress",
                    if (ok) "✅ X-Requested-With suppressed via header mode NO_HEADER"
                    else "⚠️ Header mode set but read back as $readBack, not $MODE_NO_HEADER",
                    "readBack" to readBack.toString())
                return ok
            } catch (e: Throwable) {
                ProviderLogger.w(TAG, "suppress", "Header-mode call failed: ${e.message}")
            }
        }

        ProviderLogger.w(TAG, "suppress",
            "This WebView exposes neither header API — the package name will keep going out, which " +
                "breaks freex's countdown and stops Google filling ad slots. See the class doc.")
        return false
    }
}
