package com.cloudstream.shared.webview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-only tests for the one surviving [parseCookieString] — it used to exist verbatim in
 * `NavigationEngine`, `VideoSnifferEngine` and `CfBypassEngine`. Top-level, so this test loads no
 * Android classes.
 */
class CookieStringParserTest {

    @Test
    fun parsesNameValuePairs() {
        val out = parseCookieString("a=1; b=2; c=")
        assertEquals("1", out["a"])
        assertEquals("2", out["b"])
        // The surviving implementation KEEPS an empty value: only a blank *name* is dropped.
        assertEquals(3, out.size)
        assertEquals(true, out.containsKey("c"))
        assertEquals("", out["c"])
    }

    @Test
    fun handlesValueContainingEquals() {
        assertEquals("aa=bb", parseCookieString("jwt=aa=bb")["jwt"])
    }

    @Test
    fun nullAndBlankGiveEmptyMap() {
        assertEquals(emptyMap<String, String>(), parseCookieString(null))
        assertEquals(emptyMap<String, String>(), parseCookieString(""))
        assertEquals(emptyMap<String, String>(), parseCookieString("   "))
    }

    @Test
    fun dropsBlankNamesAndTrims() {
        val out = parseCookieString("  a = 1 ; ; =orphan; b=2")
        assertEquals(mapOf("a" to "1", "b" to "2"), out)
    }
}
