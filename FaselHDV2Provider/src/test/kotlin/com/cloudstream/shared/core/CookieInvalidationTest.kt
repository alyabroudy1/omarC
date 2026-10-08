package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 2 acceptance 4: a Cloudflare re-solve expires the solved host's cookies and nothing else.
 * `removeAllCookies` would take every other provider's session with it.
 */
class CookieInvalidationTest {

    @Test
    fun expiresOnlyNamedCookiesForOneUrl() {
        val x = "https://x.example/"
        val y = "https://y.example/"
        val storage = FakeCookieStorage()
        storage.seed(x, "a=1; b=2")
        storage.seed(y, "c=3")

        expireCookiesFor(x, storage)

        assertEquals(2, storage.writes.size)
        assertEquals(listOf(x, x), storage.writes.map { it.first })
        val a = storage.writes[0].second
        val b = storage.writes[1].second
        assertTrue(a, a.startsWith("a=;") && a.contains("Max-Age=0"))
        assertTrue(b, b.startsWith("b=;") && b.contains("Max-Age=0"))
    }

    @Test
    fun skipsEmptySegments() {
        // A trailing `;` and a stray `; ;` are legal in a Cookie header; neither is a cookie.
        val x = "https://x.example/"
        val storage = FakeCookieStorage()
        storage.seed(x, "a=1; ; b=2;")

        expireCookiesFor(x, storage)

        assertEquals(2, storage.writes.size)
        assertEquals(listOf("a=; Max-Age=0; Path=/", "b=; Max-Age=0; Path=/"),
            storage.writes.map { it.second })
    }

    @Test
    fun expiresNothingWhenTheStoreHoldsNothing() {
        val storage = FakeCookieStorage()
        expireCookiesFor("https://x.example/", storage)
        assertEquals(emptyList<Pair<String, String>>(), storage.writes)
    }
}
