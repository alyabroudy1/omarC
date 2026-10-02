package com.cloudstream.shared.webview

import android.content.Context
import android.webkit.WebView
import com.cloudstream.shared.core.Fingerprint
import com.cloudstream.shared.logging.ProviderLogger

/**
 * The one place a [WebView] is born, so all four WebView tiers present the same identity.
 *
 * Sets the User-Agent and applies [RequestedWithHeaderControl.suppress] — which fixes both
 * `X-Requested-With` and the `sec-ch-ua: "Android WebView"` brand list for every origin — and
 * **nothing else**. JavaScript, DOM storage, media playback and the rest stay with the caller,
 * because those differ per tier.
 */
object WebViewFactory {

    private const val TAG = "WebViewFactory"

    /**
     * @param userAgentOverride a deliberate, per-session deviation from the fingerprint (today only
     *   one provider's TV UA, which that site's own `isTv()` reads to skip the popunder flow). When set,
     *   every WebView of that one session must be created with the same override so the session
     *   presents one UA. Null — the normal case — means the device fingerprint.
     */
    fun create(
        context: Context,
        fingerprint: Fingerprint = Fingerprint.current(),
        userAgentOverride: String? = null
    ): WebView {
        val webView = WebView(context)
        val effectiveUa = userAgentOverride?.takeIf { it.isNotBlank() && it != fingerprint.userAgent }
        webView.settings.userAgentString = effectiveUa ?: fingerprint.userAgent
        if (effectiveUa != null) {
            ProviderLogger.w(TAG, "create",
                "Fingerprint deviation: WebView UA overridden by caller",
                "override" to effectiveUa)
        }
        // suppress() builds its client-hint metadata with chromeBrandMetadata(fp), which reads the
        // same Fingerprint this call was given, so that brand list and Fingerprint.secChUa — spelled
        // by Fingerprint.brandListFor(major) — agree by construction.
        val suppressed = RequestedWithHeaderControl.suppress(webView, fingerprint)
        ProviderLogger.i(TAG, "create", "WebView created",
            "ua" to (effectiveUa ?: fingerprint.userAgent),
            "chrome" to fingerprint.chromeFullVersion,
            "suppressed" to suppressed.toString(),
            "headersControlledGlobally" to
                RequestedWithHeaderControl.headersControlledGlobally.toString())
        return webView
    }
}
