package com.cloudstream.shared.core

import okhttp3.Interceptor
import okhttp3.Response

/**
 * What a request is, for header purposes. Attached as an OkHttp request tag; an untagged request is
 * a [Subresource], because that is the conservative shape (no `Upgrade-Insecure-Requests`, no HTML
 * `Accept`).
 */
enum class RequestKind { Document, Subresource }

/**
 * Puts the one [Fingerprint] on every OkHttp request that does not already carry an identity.
 *
 * Two rules, both inherited from the code this replaces:
 *
 * 1. **A *foreign* caller-supplied `User-Agent` suppresses everything.** Documented at
 *    `HttpGateway.executeDirectRequest`: a caller that sends its own UA (Krmzy sends a *desktop*
 *    Chrome) must not be handed mobile Android client hints, because that mismatch is itself the
 *    signal a bot check reads. Either the whole identity is ours, or none of it is. A caller that
 *    sets the *same* UA as the fingerprint is not foreign — it gets the matching hints, and its
 *    own header value is left in place (rule 2).
 * 2. **Never overwrite a header the caller set.** Content negotiation, `Referer` and `Cookie`
 *    belong to the caller.
 *
 * **`accept-encoding` is deliberately not set.** OkHttp's `BridgeInterceptor` (okhttp-jvm
 * 5.0.0-alpha.12, `BridgeInterceptor.kt:66-72`) only adds `Accept-Encoding: gzip` — and only then
 * takes responsibility for gunzipping the response — when the request carries no `Accept-Encoding`
 * of its own. Setting it here would leave every body gzipped for the caller to decode, and OkHttp
 * cannot decode `br` at all. Leaving it absent yields `accept-encoding: gzip` on the wire anyway.
 */
object FingerprintInterceptor : Interceptor {

    private const val DOCUMENT_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

    /**
     * Pure: the headers this interceptor would add. Unit-testable without OkHttp mocks.
     *
     * @param callerHeaders names of headers the caller already set (case-insensitive).
     * @param callerUserAgent the caller's own `User-Agent` value, or null when it set none. A value
     *   equal to [Fingerprint.userAgent] counts as no caller UA at all (rule 1).
     * @return an empty map when the caller set a *foreign* `User-Agent`.
     */
    fun headersFor(
        fingerprint: Fingerprint,
        kind: RequestKind,
        hasReferer: Boolean,
        refererHost: String?,
        targetHost: String,
        callerHeaders: Set<String>,
        callerUserAgent: String? = null
    ): Map<String, String> {
        val existing = callerHeaders.map { it.lowercase() }.toSet()
        if (callerUserAgent != null && callerUserAgent != fingerprint.userAgent) return emptyMap()
        if (callerUserAgent == null && "user-agent" in existing) return emptyMap()

        val secFetchSite = when {
            !hasReferer -> "none"
            refererHost == targetHost -> "same-origin"
            else -> "cross-site"
        }

        val headers = LinkedHashMap<String, String>()
        headers["user-agent"] = fingerprint.userAgent
        headers["sec-ch-ua"] = fingerprint.secChUa
        headers["sec-ch-ua-mobile"] = "?1"
        headers["sec-ch-ua-platform"] = "\"${fingerprint.platform}\""
        headers["accept-language"] = fingerprint.acceptLanguage
        when (kind) {
            RequestKind.Document -> {
                headers["accept"] = DOCUMENT_ACCEPT
                headers["upgrade-insecure-requests"] = "1"
                headers["sec-fetch-site"] = secFetchSite
                headers["sec-fetch-mode"] = "navigate"
                headers["sec-fetch-dest"] = "document"
                headers["sec-fetch-user"] = "?1"
            }
            RequestKind.Subresource -> {
                headers["accept"] = "*/*"
                headers["sec-fetch-site"] = secFetchSite
                headers["sec-fetch-mode"] = "cors"
                headers["sec-fetch-dest"] = "empty"
            }
        }
        headers.keys.removeAll(existing)
        return headers
    }

    /**
     * Test seam. Production never touches it: the default reads the one device fingerprint. A JVM
     * test injects a fixed [Fingerprint] so `intercept()` can run without Android.
     */
    @Volatile
    internal var fingerprintProvider: () -> Fingerprint = { Fingerprint.current() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val callerHeaders = request.headers.names()
        val fingerprint = fingerprintProvider()
        val callerUserAgent = request.header("User-Agent")
        if (callerUserAgent != null && callerUserAgent != fingerprint.userAgent) {
            return chain.proceed(request)
        }

        val referer = request.header("Referer") ?: request.header("referer")
        val refererHost = referer?.let {
            try { java.net.URI(it).host } catch (_: Exception) { null }
        }
        val added = headersFor(
            fingerprint = fingerprint,
            kind = request.tag(RequestKind::class.java) ?: RequestKind.Subresource,
            hasReferer = referer != null,
            refererHost = refererHost,
            targetHost = request.url.host,
            callerHeaders = callerHeaders,
            callerUserAgent = callerUserAgent
        )
        if (added.isEmpty()) return chain.proceed(request)

        val builder = request.newBuilder()
        for ((name, value) in added) builder.header(name, value)
        return chain.proceed(builder.build())
    }
}
