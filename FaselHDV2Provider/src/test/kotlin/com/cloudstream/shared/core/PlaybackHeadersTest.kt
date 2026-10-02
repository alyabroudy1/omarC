package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlaybackHeadersTest {

    private val fp = Fingerprint.fromUserAgent(
        "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/150.0.7100.5 Mobile Safari/537.36",
        Locale.US, "16", "Pixel 9"
    )

    @Test
    fun containsUaRefererOriginAndCookie() {
        val headers = fp.playbackHeaders(
            referer = "https://site.tld/watch",
            origin = "https://site.tld",
            cookieHeader = "cf_clearance=abc"
        )
        assertEquals(
            setOf(
                "User-Agent", "Referer", "Origin", "Accept", "Cookie",
                "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform"
            ),
            headers.keys
        )
        assertEquals(fp.userAgent, headers["User-Agent"])
        assertEquals("https://site.tld/watch", headers["Referer"])
        assertEquals("https://site.tld", headers["Origin"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("cf_clearance=abc", headers["Cookie"])
        assertEquals(fp.secChUa, headers["sec-ch-ua"])
        assertEquals("\"Android\"", headers["sec-ch-ua-platform"])
    }

    @Test
    fun omitsCookieWhenEmpty() {
        assertTrue("Cookie" !in fp.playbackHeaders("https://s.tld/", null, "").keys)
        assertTrue("Cookie" !in fp.playbackHeaders("https://s.tld/", null, null).keys)
        assertTrue("Origin" !in fp.playbackHeaders("https://s.tld/", null, null).keys)
    }
}
