package com.cloudstream.shared.core

import java.util.Locale

/**
 * The one HTTP-layer identity, built once per process from the device's own WebView.
 *
 * Every browser-shaped request — OkHttp, all four WebViews, and the player — presents the same
 * `User-Agent`, the same `sec-ch-ua` triple and the same `Accept-Language`. There is **no fallback
 * UA**: the Pixel 7 / Chrome 131 literal that used to live in `WebConfig` was a fingerprint of this
 * app, not of the device, so [current] throws instead of inventing one.
 *
 * [fromUserAgent] is pure and is what the unit tests exercise; [current] is the only
 * Android-touching entry point.
 */
data class Fingerprint(
    /** Device UA with the `; wv` WebView marker stripped and a `Mobile` token guaranteed. */
    val userAgent: String,
    /** e.g. `150.0.7100.5` */
    val chromeFullVersion: String,
    /** e.g. `150` */
    val chromeMajor: String,
    /**
     * One spelling only, the one `RequestedWithHeaderControl.chromeBrandMetadata()` (:157-170) puts
     * on the wire via `setUserAgentMetadataFromMap`, so the OkHttp tier cannot disagree with the
     * WebView tier.
     */
    val secChUa: String,
    /** Always `Android`. */
    val platform: String,
    /** `Build.VERSION.RELEASE` + `.0.0`, matching the WebView's own client-hint metadata. */
    val platformVersion: String,
    /** `Build.MODEL`. */
    val model: String,
    /** From the default locale, e.g. `en-US,en;q=0.9`. */
    val acceptLanguage: String,
    /** `com.android.chrome` — see `RequestedWithHeaderControl.SAFE_VALUE` (:126). */
    val requestedWithValue: String
) {

    /**
     * Headers for an [com.lagradost.cloudstream3.utils.ExtractorLink] / the player.
     *
     * Keys: `User-Agent`, `Referer` (when non-null), `Origin` (when non-null), `Accept: * / *`,
     * `Cookie` (only when non-blank), `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`.
     */
    fun playbackHeaders(
        referer: String?,
        origin: String?,
        cookieHeader: String?
    ): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (referer != null) put("Referer", referer)
        if (origin != null) put("Origin", origin)
        put("Accept", "*/*")
        if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader)
        put("sec-ch-ua", secChUa)
        put("sec-ch-ua-mobile", "?1")
        put("sec-ch-ua-platform", "\"$platform\"")
    }

    /**
     * The one image-request shape, on every tier: `User-Agent`, `Referer` (when non-null), an
     * image `Accept`, and `Cookie` only when there is one. Pure, so the shape is unit-testable.
     */
    fun imageHeaders(referer: String?, cookieHeader: String?): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (referer != null) put("Referer", referer)
        put("Accept", "image/avif,image/webp,*/*")
        if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader)
    }

    companion object {

        private val CHROME_VERSION = Regex("""Chrome/(\d+(?:\.\d+)*)""")

        /** `Version/4.0` — a token real Chrome never sends, and Android WebView always does. */
        private val VERSION_TOKEN = Regex("""\s*Version/\S+""")

        /**
         * The one brand-list spelling, shared by [Fingerprint.secChUa] and the WebView's own
         * client-hint metadata (`RequestedWithHeaderControl.chromeBrandMetadata`), so the two agree
         * by construction rather than by both parsing the same string.
         */
        fun brandListFor(major: String): String =
            "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"$major\", \"Google Chrome\";v=\"$major\""

        /** Pure. Throws [IllegalArgumentException] when [ua] carries no `Chrome/…` token. */
        fun fromUserAgent(ua: String, locale: Locale, release: String, model: String): Fingerprint {
            val full = CHROME_VERSION.find(ua)?.groupValues?.get(1)
                ?: throw IllegalArgumentException(
                    "Fingerprint: no Chrome version in User-Agent — refusing to guess one: " +
                        ua.take(160)
                )
            val major = full.substringBefore('.')

            // "; wv)" is the marker that says "Android WebView, not Chrome".
            var cleaned = ua.replace("; wv)", ")")
            // "Version/4.0" is the second WebView tell: Chrome for Android never sends it.
            cleaned = VERSION_TOKEN.replace(cleaned, "")
            // Mobile-shaped challenges only; a desktop-shaped UA gets the token Chrome would send.
            if (!cleaned.contains("Mobile")) {
                cleaned = cleaned.replaceFirst("Safari/", "Mobile Safari/")
            }

            return Fingerprint(
                userAgent = cleaned,
                chromeFullVersion = full,
                chromeMajor = major,
                secChUa = brandListFor(major),
                platform = "Android",
                platformVersion = "$release.0.0",
                model = model,
                acceptLanguage = acceptLanguageFor(locale),
                requestedWithValue = "com.android.chrome"
            )
        }

        private fun acceptLanguageFor(locale: Locale): String {
            val lang = locale.language
            val country = locale.country
            return if (country.isNotEmpty()) "$lang-$country,$lang;q=0.9" else lang
        }

        /**
         * Pure half of [current]: everything except reading the device. Throws
         * [IllegalStateException] — never a fallback UA — when the WebView handed back nothing.
         */
        fun build(ua: String?, locale: Locale, release: String, model: String): Fingerprint {
            if (ua.isNullOrBlank()) {
                throw IllegalStateException(
                    "Fingerprint.current(): WebSettings.getDefaultUserAgent returned no " +
                        "User-Agent — no WebView on this device, and there is no fallback."
                )
            }
            return fromUserAgent(ua = ua, locale = locale, release = release, model = model)
        }

        @Volatile
        private var cached: Fingerprint? = null

        /**
         * Process-wide, built once from the device. Throws [IllegalStateException] — never a
         * fallback UA — when the plugin context is missing or WebView is unavailable.
         */
        fun current(): Fingerprint {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val context = com.cloudstream.shared.android.PluginContext.context
                    ?: throw IllegalStateException(
                        "Fingerprint.current(): PluginContext.context is null — call " +
                            "PluginContext.init(context) in Plugin.load() before any request."
                    )
                val ua = try {
                    android.webkit.WebSettings.getDefaultUserAgent(context)
                } catch (e: Throwable) {
                    throw IllegalStateException(
                        "Fingerprint.current(): WebSettings.getDefaultUserAgent failed — no " +
                            "WebView on this device, and there is no fallback User-Agent.",
                        e
                    )
                }
                val fingerprint = build(
                    ua = ua,
                    locale = Locale.getDefault(),
                    release = android.os.Build.VERSION.RELEASE ?: "",
                    model = android.os.Build.MODEL ?: ""
                )
                com.cloudstream.shared.logging.ProviderLogger.i(
                    "Fingerprint", "current", "Device fingerprint resolved",
                    "chrome" to fingerprint.chromeFullVersion,
                    "model" to fingerprint.model,
                    "acceptLanguage" to fingerprint.acceptLanguage
                )
                cached = fingerprint
                return fingerprint
            }
        }
    }
}
