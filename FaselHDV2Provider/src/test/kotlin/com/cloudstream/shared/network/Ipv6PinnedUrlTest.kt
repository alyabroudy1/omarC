package com.cloudstream.shared.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the detector for CDN links that are IP-pinned to an IPv6 address.
 *
 * scdns.io writes the requesting client's IP into the stream path and serves the media over IPv4
 * only, so a token minted over IPv6 can never be played — it 403s for every client. The sample
 * below is a real URL taken from a failing run (2026-07-25).
 */
class Ipv6PinnedUrlTest {

    private val ipv6Pinned =
        "https://r467--5z6nc80t.c.scdns.io/stream/v1/hls/027TsOkTUoiWXXKHO7rsiw/1785003764/" +
            "www.fasel-hd.cam/all/2001:16b8:5d9:700:87f5:db3:cc63:7efa/yes/DE/0/05-02/4/" +
            "7410bc0e883cb017fe92d10db9e999d2/140_sd360b_playlist.m3u8"

    private val ipv4Pinned =
        "https://r467--5z6nc80t.c.scdns.io/stream/v1/hls/027TsOkTUoiWXXKHO7rsiw/1785003764/" +
            "www.fasel-hd.cam/all/83.135.171.241/yes/DE/0/05-02/4/" +
            "7410bc0e883cb017fe92d10db9e999d2/140_sd360b_playlist.m3u8"

    @Test
    fun `detects the ipv6 address baked into a stream path`() {
        assertTrue(IpPinnedUrl.containsIpv6Literal(ipv6Pinned))
    }

    @Test
    fun `an ipv4-pinned url is fine`() {
        assertFalse(IpPinnedUrl.containsIpv6Literal(ipv4Pinned))
    }

    @Test
    fun `ordinary urls do not false-positive`() {
        listOf(
            "https://host.tld/hls/master.m3u8",
            "https://host.tld:8443/a/b/index.m3u8",              // port colon is in the authority
            "https://host.tld/path/with-dashes/file.m3u8?a=1:2",  // colon in the query
            "https://edge1-waw-sprintcdn.r66nv9ed.com/hls2/03/11680/x/master.m3u8?t=abc&s=1&e=2",
            "https://ok6-6.vkuser.net/?expires=1785063553065&srcIp=83.135.171.241&type=5"
        ).forEach { assertFalse(it, IpPinnedUrl.containsIpv6Literal(it)) }
    }

    @Test
    fun `abbreviated and full ipv6 forms are both caught`() {
        assertTrue(IpPinnedUrl.containsIpv6Literal("https://h/s/all/2001:db8::1/yes/x.m3u8"))
        assertTrue(
            IpPinnedUrl.containsIpv6Literal(
                "https://h/s/all/2001:0db8:85a3:0000:0000:8a2e:0370:7334/yes/x.m3u8"
            )
        )
    }
}
