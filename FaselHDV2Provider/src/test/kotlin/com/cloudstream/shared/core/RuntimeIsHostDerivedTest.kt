package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared extractors are constructed by one plugin and can serve a link raised by another
 * (docs/wave-4b-design.md section 5), which only works because an unqueued fetch derives its
 * default `Referer` from the *target* host and never from the provider's current domain.
 *
 * `text()` itself needs `app.baseClient` (Android), so the wire assertion is a device check; the
 * rule it depends on is this pure function.
 */
class RuntimeIsHostDerivedTest {

    @Test
    fun defaultRefererFollowsTargetHost() {
        assertEquals("https://embed.example.org/", defaultRefererFor("https://embed.example.org/e/abc?x=1"))
        assertEquals("https://vkvideo.ru/", defaultRefererFor("https://vkvideo.ru/video_ext.php?oid=1"))
        // A port is part of the origin, and one of the registered Laroza hosts carries one.
        assertEquals("https://vidspeed.org/", defaultRefererFor("https://vidspeed.org:2096/embed"))
    }

    @Test
    fun unparseableUrlLeavesTheFallbackToTheCaller() {
        assertEquals("", defaultRefererFor("not a url"))
    }
}
