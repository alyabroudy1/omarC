package com.cloudstream.shared.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards master-vs-variant playlist classification.
 *
 * The regression that motivated this: scdns.io names its variants `<id>_<quality>_playlist.m3u8`,
 * and [VideoUrlClassifier.isMasterM3u8] matched "playlist.m3u8" before it looked for a quality
 * marker — so every FaselHD variant was reported as a master. SnifferExtractor builds its
 * `capturedMap` by excluding masters, so the map came out empty, the WebView-authorized links were
 * never merged in, and the player got re-minted URLs that the CDN answered with 403.
 *
 * URLs below are real, from a failing run (log3.txt, 2026-07-28).
 */
class VideoUrlClassifierTest {

    private val master =
        "https://master.c.scdns.io/stream/v2/mcATPkx4tvggDhi8GuJuyQ/1785270141/normal/0/" +
            "194.213.108.11/yes/7410bc0e883cb017fe92d10db9e999d2/www.fasel-hd.cam/master.m3u8"

    private fun variant(name: String) =
        "https://r467--5z6nc80t.c.scdns.io/stream/v1/hls/nwm-rrNJwgzM7mDUGtw3TQ/1785270141/" +
            "www.fasel-hd.cam/all/194.213.108.11/yes/DE/0/05-02/4/" +
            "7410bc0e883cb017fe92d10db9e999d2/$name"

    @Test
    fun `master playlist is a master`() {
        assertTrue(VideoUrlClassifier.isMasterM3u8(master))
    }

    @Test
    fun `quality-suffixed playlists are variants, not masters`() {
        assertFalse(VideoUrlClassifier.isMasterM3u8(variant("160_hd1080b_playlist.m3u8")))
        assertFalse(VideoUrlClassifier.isMasterM3u8(variant("155_hd720b_playlist.m3u8")))
        assertFalse(VideoUrlClassifier.isMasterM3u8(variant("140_sd360b_playlist.m3u8")))
    }

    @Test
    fun `quality in the parent directory still marks a variant`() {
        assertFalse(VideoUrlClassifier.isMasterM3u8("https://cdn.example/hls/720p/index.m3u8"))
    }

    @Test
    fun `unqualified playlist and manifest names remain masters`() {
        assertTrue(VideoUrlClassifier.isMasterM3u8("https://cdn.example/hls/playlist.m3u8"))
        assertTrue(VideoUrlClassifier.isMasterM3u8("https://cdn.example/hls/manifest.m3u8"))
    }

    @Test
    fun `a token full of digits does not turn a master into a variant`() {
        // 1785270141 and the md5 both carry digit runs that a whole-URL search would trip over.
        assertTrue(
            VideoUrlClassifier.isMasterM3u8(
                "https://master.c.scdns.io/stream/v2/x/1785014807/normal/0/1.2.3.4/yes/" +
                    "aa72014801080aa/www.fasel-hd.cam/master.m3u8"
            )
        )
    }

    @Test
    fun `media segments are not masters`() {
        assertFalse(VideoUrlClassifier.isMasterM3u8(variant("seg-1-v1-a1.ts")))
    }
}
