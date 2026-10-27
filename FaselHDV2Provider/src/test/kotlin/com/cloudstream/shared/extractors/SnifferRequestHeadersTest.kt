package com.cloudstream.shared.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SnifferRequestHeadersTest {

    @Test
    fun `transport headers from the WebView are dropped`() {
        val sanitized = SnifferRequestHeaders.sanitize(
            mapOf(
                "Range" to "bytes=0-",
                "Accept-Encoding" to "gzip, identity;q=1, *;q=0",
                "Host" to "r467--7umzmh0e.c.scdns.io",
                "Referer" to "https://www.fasel-hd.co/",
                "Cookie" to "cf_clearance=abc",
                "sec-ch-ua" to "\"Android WebView\";v=\"155\""
            )
        )

        assertFalse(sanitized.keys.any { it.equals("Range", ignoreCase = true) })
        assertFalse(sanitized.keys.any { it.equals("Accept-Encoding", ignoreCase = true) })
        assertFalse(sanitized.keys.any { it.equals("Host", ignoreCase = true) })
        assertEquals("https://www.fasel-hd.co/", sanitized["Referer"])
        assertEquals("cf_clearance=abc", sanitized["Cookie"])
        assertEquals("\"Android WebView\";v=\"155\"", sanitized["sec-ch-ua"])
    }
}
