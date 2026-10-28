package com.cloudstream.shared.extractors

import com.cloudstream.shared.webview.CapturedLinkData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the offline capture plan that replaced the old "fetch the master, then throw the capture
 * away if the fetch fails" flow.
 *
 * URLs are the real FaselHD failures from log3.txt: two scdns.io playlists, the detail page reported
 * by the DOM bridge, and a `.jpeg` reported by the same `[data-src]` scrape.
 */
class SnifferCapturePlannerTest {

    private val master =
        "https://master.c.scdns.io/stream/v2/W8OH3sf5yohEp1xfw3j-kA/1793151386/normal/0/" +
            "83.135.175.142/yes/5a904dd3bfefab8a38e19e08615d0f80/www.fasel-hd.co/master.m3u8"

    private fun variant(name: String) =
        "https://r467--7umzmh0e.c.scdns.io/stream/v1/hls/iyefhV_FADcDtlVau64zEA/1793151398/" +
            "www.fasel-hd.co/all/83.135.175.142/yes/DE/0/07-02/2/" +
            "5a904dd3bfefab8a38e19e08615d0f80/$name"

    private fun capture(url: String, label: String = "Network") =
        CapturedLinkData(url, label, emptyMap())

    @Test
    fun `a captured master is delivered directly`() {
        val plan = SnifferCapturePlanner.plan(listOf(capture(master)))

        assertEquals(1, plan.links.size)
        assertEquals(SnifferStreamKind.HLS_MASTER, plan.links.single().kind)
        assertEquals("Auto", plan.links.single().qualityLabel)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test
    fun `a single captured variant keeps its filename-derived quality`() {
        val plan = SnifferCapturePlanner.plan(listOf(capture(variant("155_hd720b_playlist.m3u8"))))

        assertEquals(1, plan.links.size)
        assertEquals(SnifferStreamKind.HLS_VARIANT, plan.links.single().kind)
        assertEquals(720, plan.links.single().quality)
        assertEquals("720p", plan.links.single().qualityLabel)
    }

    @Test
    fun `captured variants are ordered by quality without a master fetch`() {
        val plan = SnifferCapturePlanner.plan(
            listOf(
                capture(variant("140_sd360b_playlist.m3u8")),
                capture(variant("155_hd720b_playlist.m3u8")),
                capture(variant("160_hd1080b_playlist.m3u8"))
            )
        )

        assertEquals(listOf(1080, 720, 360), plan.links.map { it.quality })
        assertEquals(SnifferStreamKind.HLS_VARIANT, plan.links.first().kind)
    }

    @Test
    fun `a master wins when the captured variant ladder is incomplete`() {
        val plan = SnifferCapturePlanner.plan(
            listOf(
                capture(variant("155_hd720b_playlist.m3u8")),
                capture(master)
            )
        )

        assertEquals(1, plan.links.size)
        assertEquals(SnifferStreamKind.HLS_MASTER, plan.links.single().kind)
        assertEquals(master, plan.links.single().capture.url)
    }

    @Test
    fun `a master is delivered alone even when a complete captured ladder exists`() {
        val plan = SnifferCapturePlanner.plan(
            listOf(
                capture(variant("155_hd720b_playlist.m3u8")),
                capture(master),
                capture(variant("160_hd1080b_playlist.m3u8"))
            )
        )

        assertEquals(1, plan.links.size)
        assertEquals(SnifferStreamKind.HLS_MASTER, plan.links.single().kind)
        assertEquals(master, plan.links.single().capture.url)
    }

    @Test
    fun `player pages and thumbnails are rejected before reaching the player`() {
        val playerPage = "https://www.fasel-hd.co/video_player?player_token=abc123"
        val detailPage = "https://www.fasel-hd.co/?p=8672"
        val jpeg = "https://static.faselhdcdn.com/open-uri20150422-12561-z7nsh7_240f9deb-400x600.jpeg"

        val plan = SnifferCapturePlanner.plan(
            listOf(capture(playerPage, "Data Attr"), capture(detailPage, "Data Attr"), capture(jpeg, "Data Attr"))
        )

        assertTrue(plan.links.isEmpty())
        assertEquals(3, plan.rejected.size)
    }

    @Test
    fun `a no-extension network stream is still delivered as video`() {
        val vk = "https://vk6-3.vkuser.net/?expires=1790000000&type=1&sig=abc"

        val plan = SnifferCapturePlanner.plan(listOf(capture(vk)))

        assertEquals(1, plan.links.size)
        assertEquals(SnifferStreamKind.VIDEO, plan.links.single().kind)
        assertEquals(vk, plan.links.single().capture.url)
    }

    @Test
    fun `duplicate URLs are planned once`() {
        val plan = SnifferCapturePlanner.plan(listOf(capture(master), capture(master)))

        assertEquals(1, plan.links.size)
        assertEquals(1, plan.rejected.size)
    }
}
