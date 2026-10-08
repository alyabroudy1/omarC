package com.cloudstream.shared.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the contract of the one [fixUrl] that replaced Replaymatch's, Tuniflix's and
 * TukTukcima's private copies (Wave 3).
 */
class FixUrlTest {

    @Test
    fun resolvesProtocolRelative() {
        assertEquals("https://cdn.x/y", fixUrl("//cdn.x/y", "https://a.b"))
        // The base is irrelevant to a protocol-relative URL.
        assertEquals("https://cdn.x/y", fixUrl("//cdn.x/y", "https://other.host/sub"))
    }

    @Test
    fun resolvesRootRelativeAndAbsolute() {
        assertEquals("https://a.b/y", fixUrl("/y", "https://a.b"))
        assertEquals("https://elsewhere.tld/z?q=1", fixUrl("https://elsewhere.tld/z?q=1", "https://a.b"))
        assertEquals("http://plain.tld/z", fixUrl("http://plain.tld/z", "https://a.b"))
        // A bare relative path resolves against the base's root.
        assertEquals("https://a.b/y", fixUrl("y", "https://a.b"))
        // Blank in, blank out.
        assertEquals("", fixUrl("", "https://a.b"))
        assertEquals("", fixUrl("   ", "https://a.b"))
    }

    @Test
    fun handlesBaseWithTrailingSlash() {
        assertEquals("https://a.b/y", fixUrl("/y", "https://a.b/"))
        assertEquals("https://a.b/y", fixUrl("y", "https://a.b/"))
        assertEquals("https://a.b/y", fixUrl("/y", "https://a.b///"))
        // Untouched cases stay untouched whatever the base looks like.
        assertEquals("https://cdn.x/y", fixUrl("//cdn.x/y", "https://a.b/"))
        assertEquals("https://z.tld/y", fixUrl("https://z.tld/y", "https://a.b/"))
    }
}
