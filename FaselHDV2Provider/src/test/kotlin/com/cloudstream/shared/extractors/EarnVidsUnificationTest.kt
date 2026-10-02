package com.cloudstream.shared.extractors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 folded four per-provider `ExternalEarnVidsExtractor` copies (Animerco, Lodynet,
 * Replaymatch, Shahid4u) onto shared's [EarnVidsExtractor]. This asserts the surviving extractor
 * still recognises every host the deleted copies and the old registration list between them
 * reached — `fdewsdc.sbs` included, which the copies handled only via a referer special case.
 */
class EarnVidsUnificationTest {

    private val unionOfHosts = listOf(
        "earnvids.com",
        "dingtezuni.com",
        "fsdcmo.sbs",
        "govid.live",
        "1vid1shar.space",
        "mycima.page",
        "fdewsdc.sbs"
    )

    @Test
    fun sharedExtractorHandlesAllFourHostSets() {
        for (host in unionOfHosts) {
            assertTrue(
                "shared EarnVidsExtractor should accept $host",
                matchesEarnVidsHost("https://$host/e/abc123")
            )
            // Registration uses bare hosts, so the matcher must not depend on a scheme.
            assertTrue(host, matchesEarnVidsHost("//$host/e/abc123"))
            // Host matching is case-insensitive: these sites emit mixed-case embed URLs.
            assertTrue(host, matchesEarnVidsHost("https://${host.uppercase()}/E/ABC"))
        }
        assertFalse(matchesEarnVidsHost("https://example.com/e/abc123"))
    }

    @Test
    fun theRegisteredHostListIsTheUnion() {
        // registerSharedExtractors constructs one EarnVidsExtractor per host in EARNVIDS_HOSTS.
        assertTrue(EARNVIDS_HOSTS.containsAll(unionOfHosts))
        assertTrue(EARNVIDS_HOSTS.size == unionOfHosts.size)
    }

    @Test
    fun normalizesRelativeLinksAgainstTheEmbedUrl() {
        val page = "https://govid.live/e/abc123"
        assertTrue(
            normalizeVideoUrl("/hls/x.m3u8", page) == "https://govid.live/hls/x.m3u8"
        )
        assertTrue(
            normalizeVideoUrl("//cdn.govid.live/x.m3u8", page) ==
                "https://cdn.govid.live/x.m3u8"
        )
        assertTrue(
            normalizeVideoUrl("https://cdn.tld/x.m3u8", page) == "https://cdn.tld/x.m3u8"
        )
    }
}
