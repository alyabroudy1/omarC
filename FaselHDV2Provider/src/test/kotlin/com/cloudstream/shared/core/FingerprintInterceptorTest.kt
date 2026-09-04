package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FingerprintInterceptorTest {

    private val fp = Fingerprint.fromUserAgent(
        "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/150.0.7100.5 Mobile Safari/537.36",
        Locale.US, "16", "Pixel 9"
    )

    @Test
    fun documentRequestHeaders() {
        val actual = FingerprintInterceptor.headersFor(
            fingerprint = fp,
            kind = RequestKind.Document,
            hasReferer = false,
            refererHost = null,
            targetHost = "example.com",
            callerHeaders = emptySet()
        )
        val expected = mapOf(
            "user-agent" to fp.userAgent,
            "sec-ch-ua" to fp.secChUa,
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "accept-language" to "en-US,en;q=0.9",
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,*/*;q=0.8",
            "upgrade-insecure-requests" to "1",
            "sec-fetch-site" to "none",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-dest" to "document",
            "sec-fetch-user" to "?1"
        )
        assertEquals(expected, actual)
    }

    @Test
    fun callerUaIsNotOverwritten() {
        val actual = FingerprintInterceptor.headersFor(
            fp, RequestKind.Document, false, null, "example.com", setOf("User-Agent")
        )
        assertEquals(emptyMap<String, String>(), actual)
    }

    @Test
    fun hintNamesAreLowerCase() {
        val actual = FingerprintInterceptor.headersFor(
            fp, RequestKind.Subresource, false, null, "example.com", emptySet()
        )
        assertTrue(actual.keys.all { it == it.lowercase() })
        assertEquals("*/*", actual["accept"])
        assertEquals("cors", actual["sec-fetch-mode"])
        assertEquals("empty", actual["sec-fetch-dest"])
    }

    @Test
    fun secFetchSiteSameOriginVsCrossSite() {
        fun site(hasReferer: Boolean, refererHost: String?) =
            FingerprintInterceptor.headersFor(
                fp, RequestKind.Subresource, hasReferer, refererHost, "example.com", emptySet()
            )["sec-fetch-site"]

        assertEquals("same-origin", site(true, "example.com"))
        assertEquals("cross-site", site(true, "cdn.other.com"))
        assertEquals("none", site(false, null))
    }

    /** F5: the same UA from the caller is not a foreign identity, so the hints still go on. */
    @Test
    fun callerUaEqualToFingerprintStillGetsHints() {
        val actual = FingerprintInterceptor.headersFor(
            fingerprint = fp,
            kind = RequestKind.Subresource,
            hasReferer = false,
            refererHost = null,
            targetHost = "example.com",
            callerHeaders = setOf("User-Agent"),
            callerUserAgent = fp.userAgent
        )
        assertEquals(fp.secChUa, actual["sec-ch-ua"])
        assertEquals(fp.acceptLanguage, actual["accept-language"])
        // Rule 2: the caller's own header value is left alone.
        assertTrue("user-agent" !in actual.keys)

        val foreign = FingerprintInterceptor.headersFor(
            fingerprint = fp,
            kind = RequestKind.Subresource,
            hasReferer = false,
            refererHost = null,
            targetHost = "example.com",
            callerHeaders = setOf("User-Agent"),
            callerUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0"
        )
        assertEquals(emptyMap<String, String>(), foreign)
    }

    @Test
    fun subresourceHeaderMapExact() {
        val actual = FingerprintInterceptor.headersFor(
            fingerprint = fp,
            kind = RequestKind.Subresource,
            hasReferer = true,
            refererHost = "example.com",
            targetHost = "example.com",
            callerHeaders = emptySet()
        )
        val expected = mapOf(
            "user-agent" to fp.userAgent,
            "sec-ch-ua" to fp.secChUa,
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "accept-language" to "en-US,en;q=0.9",
            "accept" to "*/*",
            "sec-fetch-site" to "same-origin",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty"
        )
        assertEquals(expected, actual)
    }

    @Test
    fun callerSuppliedHeaderIsNeverReplaced() {
        val actual = FingerprintInterceptor.headersFor(
            fp, RequestKind.Subresource, false, null, "example.com", setOf("Accept")
        )
        assertTrue("accept" !in actual.keys)
        assertEquals(fp.userAgent, actual["user-agent"])
    }
}
