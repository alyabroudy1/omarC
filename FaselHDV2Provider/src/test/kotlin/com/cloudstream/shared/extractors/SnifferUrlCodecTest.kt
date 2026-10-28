package com.cloudstream.shared.extractors

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sniffer must preserve which MainAPI provider asked for the sniff.
 *
 * Core resolves an ExtractorLink's `source` back to a MainAPI provider. Hard-coding
 * "VideoSniffer" made that lookup return null, which silently skipped provider playback
 * hooks such as FaselHDV2.getVideoInterceptor (the IPv4 pin for scdns.io).
 */
class SnifferUrlCodecTest {

    @Test
    fun `provider source survives the sniffer url round trip`() {
        val url = SnifferUrlCodec.create(
            embedUrl = "https://www.fasel-hd.co/video_player?player_token=abc%3D",
            referer = "https://www.fasel-hd.co/",
            source = "فاصل نت"
        )

        val parsed = SnifferUrlCodec.parse(url)
        assertNotNull(parsed)
        assertEquals("https://www.fasel-hd.co/video_player?player_token=abc%3D", parsed!!.embedUrl)
        assertEquals("https://www.fasel-hd.co/", parsed.referer)
        assertEquals("فاصل نت", parsed.source)
        assertNull(parsed.selector)
    }

    @Test
    fun `legacy sniffer urls without source keep the old fallback`() {
        val legacy = "sniff://" +
            URLEncoder.encode("https://example.com/embed", "UTF-8") +
            "?referer=" +
            URLEncoder.encode("https://example.com/", "UTF-8")

        assertEquals(
            SnifferUrlCodec.DEFAULT_SOURCE,
            SnifferUrlCodec.parse(legacy)?.source
        )
    }
}
