package com.cloudstream.shared.extractors

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the EarnVids/VidHide-family extractors (lolololu and
 * clones) using saved live embed pages from Sonic Prime.
 *
 * Case history:
 * - The first file: match on jwplayer setups grabbed the thumbnails track
 *   ("/dl?op=get_slides...jpg") and emitted it as VIDEO, which ExoPlayer
 *   cannot play. The extractor must skip non-media candidates.
 * - stream URLs hide behind JS vars (links.hls4||links.hls3||links.hls2);
 *   any hlsN key must resolve, highest first.
 * - hls3 playlists use a master.txt extension but carry HLS content.
 */
class EarnVidsDecodeTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun assertPlayable(url: String?) {
        assertNotNull("decode must produce a URL", url)
        val u = url!!
        assertTrue("absolute http(s): $u", u.startsWith("http://") || u.startsWith("https://"))
        assertFalse("never the slides track: $u", u.contains("get_slides"))
        assertFalse("never an image: $u", u.contains(".jpg"))
        assertFalse("never a logo asset: $u", u.contains("logo"))
    }

    @Test
    fun `sonic s3e1 embed resolves playable hls stream`() {
        val html = fixture("lolololu_sonic_s3e1.html")
        val url = PackerObfuscationStrategy().decode(html)
        assertPlayable(url)
        // This page only carries hls2+hls3; hls3 (best) must win.
        assertTrue("highest hlsN variant wins: $url", url!!.contains("hls3"))
    }

    @Test
    fun `sonic s1e6 embed skips slides track`() {
        // get_slides thumbnail is the FIRST file: entry in this page; the old
        // first-match regex emitted it as VIDEO (ExoPlayer: Source error).
        val html = fixture("lolololu_sonic_s1e6.html")
        val url = PackerObfuscationStrategy().decode(html)
        assertPlayable(url)
        assertTrue("real stream, not storyboard: $url", url!!.contains("m3u8") || url.contains("master.txt"))
    }

    @Test
    fun `hex strategy never emits thumbnails`() {
        for (name in listOf("lolololu_sonic_s3e1.html", "lolololu_sonic_s1e6.html")) {
            val url = HexObfuscationStrategy().decode(fixture(name))
            if (url != null) {
                assertFalse("hex must not emit slides: $url", url.contains("get_slides"))
                assertTrue("hex must emit absolute http: $url", url.startsWith("http"))
            }
        }
    }

    @Test
    fun `download landing yields nothing bogus`() {
        // /d/<id> pages carry no player. decode() must return null here
        // (getUrl retries as /v/<id>.html instead) rather than emitting a
        // download-button href as a stream.
        val html = fixture("lolololu_download_landing.html")
        assertNull(
            "no stream on a download landing",
            PackerObfuscationStrategy().decode(html)
        )
    }
}
