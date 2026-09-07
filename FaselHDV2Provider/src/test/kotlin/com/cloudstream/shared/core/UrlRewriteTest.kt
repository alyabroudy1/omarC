package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two pure URL rules the gateway runs on every request: `buildUrl` (path → absolute URL on the
 * session domain) and `rewriteHost` (point a URL at the current domain after a domain change).
 *
 * These were private methods on the gateway when the `document(rewrite = false)` regression of
 * Wave 4b-2 slipped through review, which is why they are top-level and pinned here.
 */
class UrlRewriteTest {

    @Test
    fun rewritesTheHostComponent() {
        assertEquals(
            "https://new.example/watch/1?id=7",
            rewriteHost("https://old.example/watch/1?id=7", "old.example", "new.example")
        )
    }

    /**
     * Documents CURRENT behaviour, not desired behaviour: `rewriteHost` is a plain
     * `String.replace` of the host token (verbatim from `ProviderHttpService.rewriteUrlIfNeeded`
     * at HEAD), so an old host repeated inside the query string is rewritten too. Narrowing this
     * to the host component only is deliberately deferred — Wave 6's
     * `HostHistoryRewriteTest.rewritesHostComponentNotQueryString`.
     */
    @Test
    fun alsoRewritesTheOldHostInsideTheQueryString_currentBehaviour() {
        assertEquals(
            "https://new.x/p?u=https://new.x/q",
            rewriteHost("https://old.x/p?u=https://old.x/q", "old.x", "new.x")
        )
    }

    @Test
    fun leavesUrlWhenHostsEqual() {
        val url = "https://same.example/a/b?c=d"
        assertEquals(url, rewriteHost(url, "same.example", "same.example"))
    }

    @Test
    fun leavesUrlWhenFromHostIsNullOrBlank() {
        val url = "https://some.example/a"
        assertEquals(url, rewriteHost(url, null, "new.example"))
        assertEquals(url, rewriteHost(url, "", "new.example"))
    }

    @Test
    fun preservesSchemePortPathAndQuery() {
        assertEquals(
            "http://new.example:8080/a/b?q=1&r=2#frag",
            rewriteHost("http://old.example:8080/a/b?q=1&r=2#frag", "old.example", "new.example")
        )
    }

    @Test
    fun buildUrlKeepsAbsolute() {
        assertEquals(
            "https://embed.host/e/abc",
            buildUrl("https://embed.host/e/abc", "provider.example")
        )
        assertEquals(
            "http://embed.host/e/abc",
            buildUrl("http://embed.host/e/abc", "provider.example")
        )
    }

    @Test
    fun buildUrlJoinsPath() {
        assertEquals("https://provider.example/movies", buildUrl("/movies", "provider.example"))
        assertEquals("https://provider.example/movies", buildUrl("movies", "provider.example"))
    }
}
