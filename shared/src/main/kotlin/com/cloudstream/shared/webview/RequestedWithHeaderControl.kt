package com.cloudstream.shared.webview

import android.webkit.WebSettings
import android.webkit.WebView
import com.cloudstream.shared.logging.ProviderLogger
import java.lang.reflect.InvocationHandler
import org.chromium.support_lib_boundary.ProfileBoundaryInterface
import org.chromium.support_lib_boundary.ProfileStoreBoundaryInterface
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
 * This header is what breaks the freex countdown, and it is our package name specifically — not the
 * header's presence. Measured against the real chain, changing only the value (see [SAFE_VALUE] for the
 * full table): `com.android.chrome` mints at 11,710 ms, `XMLHttpRequest` at 10,653 ms, absent at
 * 11,508 ms, and `com.lagradost.cloudstream3` **never mints at all** — 4 requests and then a dead page.
 *
 * Confirmed on the wire, not inferred: a CDP capture of this app's own WebView (2026-08-07) showed
 * **15 of 15 requests carrying `X-Requested-With: com.lagradost.cloudstream3`**, plus
 * `sec-ch-ua: "Android WebView";v="150"`. `WebResourceRequest.getRequestHeaders()` never shows it, which
 * is why this went unnoticed for so long, and why two earlier theories about it were wrong in both
 * directions before the wire settled it.
 *
 * `shouldInterceptRequest` can only dodge it by re-issuing a request ourselves, which covers GETs, cannot
 * cover POSTs (the API hides the body) and breaks CORS-fetched scripts — that attempt took the freex page
 * from 644 anchors down to 2 and was reverted. The fix has to happen where WebView adds the header.
 *
 * Not a factor, for the record: AdSense. Ad slots do stay empty for us, but a plain browser mints with
 * **zero** requests to `googleads.g.doubleclick.net`, so the ad impression is not part of the gate.
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
 *     from the WebView's class loader, returns an [InvocationHandler].
 *  2. Proxy it as a factory and ask for `getSupportedFeatures()`.
 *  3. **Preferred:** `getProfileStore()` → `getOrCreateProfile("Default")` →
 *     `setOriginMatchedHeader("X-Requested-With", "com.android.chrome", setOf("*"))`. WebView 150's dex
 *     contains no `RequestedWith` method of any kind — that API was removed — but it advertises
 *     `SET_ORIGIN_MATCHED_HEADER`, so overriding the value is the lever that exists. It is also
 *     sufficient, because the block is on our package name.
 *  4. **Older WebViews:** `getWebkitToCompatConverter()` → `convertSettings(WebSettings)` →
 *     `setRequestedWithHeaderOriginAllowList(emptySet())`, or `setRequestedWithHeaderMode(NO_HEADER)`.
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
    private const val FEATURE_SET_ORIGIN_HEADER = "SET_ORIGIN_MATCHED_HEADER"

    private const val HEADER = "X-Requested-With"

    /**
     * What we send instead of our own package name.
     *
     * Not `""`. An empty value is a header no real Chrome ever emits, so the mask becomes the signature —
     * the handover records that exact mistake being made and reverted. Measured 2026-08-07 against the
     * real freex chain, changing only this value:
     *
     * | value | mint | requests | anchors |
     * |---|---|---|---|
     * | absent | 11,508 ms | 29 | 644 |
     * | `com.android.chrome` | **11,710 ms** | 33 | 644 |
     * | `XMLHttpRequest` | 10,653 ms | 34 | 644 |
     * | `com.lagradost.cloudstream3` | **never** | **4** | **1** |
     *
     * So the block is on *our package*, not on the header existing. `com.android.chrome` is a real
     * browser package, is plausible for a WebView-hosted page to send, and passes.
     */
    private const val SAFE_VALUE = "com.android.chrome"

    /** Every origin. Same wildcard grammar as `addWebMessageListener`'s allowed-origin rules. */
    private val ALL_ORIGINS = setOf("*")

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

        // Preferred on any modern WebView: override the header's value per origin.
        //
        // WebView 150's dex contains no `RequestedWith` method at all — that API was removed — but it does
        // advertise `SET_ORIGIN_MATCHED_HEADER`, and the CDP capture proves the header is still being sent
        // on every request (15 of 15, all `com.lagradost.cloudstream3`). So the value is what we can
        // change, and per [SAFE_VALUE] that is sufficient: the block is on our package name.
        try {
            val store = cast<ProfileStoreBoundaryInterface>(factory.getProfileStore())
            val profile = cast<ProfileBoundaryInterface>(store.getOrCreateProfile("Default"))
            profile.setOriginMatchedHeader(HEADER, SAFE_VALUE, ALL_ORIGINS)
            ProviderLogger.i(TAG, "suppress",
                "✅ $HEADER overridden for all origins",
                "value" to SAFE_VALUE,
                "advertised" to features.any { it.startsWith(FEATURE_SET_ORIGIN_HEADER) }.toString())
            return true
        } catch (e: Throwable) {
            ProviderLogger.w(TAG, "suppress",
                "Origin-matched header override failed — falling back to the older APIs",
                "error" to (e.message?.take(180) ?: e.javaClass.simpleName))
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
