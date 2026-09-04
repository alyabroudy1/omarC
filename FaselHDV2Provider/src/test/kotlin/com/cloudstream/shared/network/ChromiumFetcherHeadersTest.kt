package com.cloudstream.shared.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-only tests for [filterLoadUrlHeaders] — the `WebView.loadUrl` extra-header narrowing.
 * The function is top-level (not on [ChromiumFetcher]) so this test loads no Android classes.
 */
class ChromiumFetcherHeadersTest {

    @Test
    fun keepsRefererAndCustomHeaders() {
        val out = filterLoadUrlHeaders(
            mapOf(
                "Referer" to "https://example.com/",
                "X-Foo" to "bar",
                "User-Agent" to "Mozilla/5.0"
            )
        )
        assertEquals(
            mapOf("Referer" to "https://example.com/", "X-Foo" to "bar"),
            out
        )
    }

    @Test
    fun dropsIdentityHeadersCaseInsensitively() {
        val out = filterLoadUrlHeaders(
            mapOf(
                "User-Agent" to "Mozilla/5.0",
                "COOKIE" to "a=b",
                "accept" to "application/json",
                "Sec-CH-UA" to "\"Chromium\";v=\"120\"",
                "x-requested-with" to "",
                "Referer" to "https://example.com/"
            )
        )
        assertEquals(mapOf("Referer" to "https://example.com/"), out)
    }
}
