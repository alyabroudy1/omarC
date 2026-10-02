package com.cloudstream.shared.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 review F3: the four deleted `ExternalEarnVidsExtractor` copies scanned the raw HTML for a
 * bare `.m3u8` **first** and returned immediately; the fold runs the decoders first and the raw scan
 * last, so a decoy URL in the markup can no longer beat a decoded one. Nothing asserted that. This
 * does, on a fixture that carries both a decodable payload and a raw `.m3u8`.
 *
 * Also F9: the registered host list and [EARNVIDS_HOSTS] used to be hand-mirrored. They now come
 * from the single [EARNVIDS_REGISTRATIONS] list, which this pins against the expected host set.
 *
 * JVM-only: every strategy exercised here is pure (no `android.util.Log`, no network).
 */
class EarnVidsStrategyOrderTest {

    private val pageUrl = "https://govid.live/e/abc123"

    /** The URL the hex payload decodes to — must be >= 20 chars so the 40-hex-digit floor is met. */
    private val decodedUrl = "https://cdn.example.com/decoded/master.m3u8"

    /** A raw `.m3u8` sitting in the markup, exactly what the copies would have returned first. */
    private val rawDecoyUrl = "https://decoy.example.com/raw/decoy.m3u8"

    private fun toHex(s: String): String =
        s.toByteArray(Charsets.US_ASCII).joinToString("") { String.format("%02x", it) }

    /** Contains BOTH a decodable hex payload and a raw `.m3u8` string. */
    private fun fixtureWithBoth(): String = """
        <html><head><script>
          var setup = { "file": "$rawDecoyUrl" };
          const kx1 = "${toHex(decodedUrl)}";
        </script></head><body>player</body></html>
    """.trimIndent()

    private fun fixtureRawOnly(): String =
        """<html><body><source src="$rawDecoyUrl" type="application/x-mpegURL"></body></html>"""

    /** First non-null decode wins, exactly as both entry points loop. */
    private fun runPipeline(html: String): Pair<String, String>? {
        for (strategy in EARNVIDS_STRATEGIES) {
            val decoded = strategy.decode(html, pageUrl)
            if (!decoded.isNullOrBlank()) return strategy::class.java.simpleName to decoded
        }
        return null
    }

    @Test
    fun decodersRunBeforeTheRawM3u8Scan() {
        val names = EARNVIDS_STRATEGIES.map { it::class.java.simpleName }
        assertEquals(
            listOf("HexObfuscationStrategy", "PackerObfuscationStrategy", "RawM3u8Strategy"),
            names
        )
        // The raw scan is last, so it can only add a link where every decoder found nothing.
        assertEquals("RawM3u8Strategy", names.last())
    }

    @Test
    fun decodedPayloadBeatsARawM3u8InTheSameMarkup() {
        val html = fixtureWithBoth()
        // Sanity: the decoy really is present and really is matchable by the raw strategy.
        assertEquals(rawDecoyUrl, RawM3u8Strategy().decode(html, pageUrl))

        val winner = runPipeline(html)
        assertEquals("HexObfuscationStrategy", winner?.first)
        assertEquals(decodedUrl, winner?.second)
    }

    @Test
    fun rawStrategyStillReturnsTheOnlyM3u8WhenNothingDecodes() {
        val html = fixtureRawOnly()
        assertNull("no hex payload to decode", HexObfuscationStrategy().decode(html, pageUrl))
        assertEquals(rawDecoyUrl, RawM3u8Strategy().decode(html, pageUrl))
    }

    @Test
    fun rawStrategyUnescapesSlashesInsideThePath() {
        // The regex needs a literal `https://` scheme, so only escapes after the host are undone.
        val escaped = """{"file":"https://cdn.tld\/a\/b.m3u8"}"""
        assertEquals("https://cdn.tld/a/b.m3u8", RawM3u8Strategy().decode(escaped, pageUrl))
    }

    @Test
    fun registeredHostsAreExactlyEarnvidsHosts() {
        // registerSharedExtractors loops EARNVIDS_REGISTRATIONS, and EARNVIDS_HOSTS is derived from
        // it, so there is one list — this pins its contents (F9).
        assertEquals(
            setOf(
                "earnvids.com",
                "dingtezuni.com",
                "fsdcmo.sbs",
                "govid.live",
                "1vid1shar.space",
                "mycima.page",
                "fdewsdc.sbs"
            ),
            EARNVIDS_REGISTRATIONS.map { it.first }.toSet()
        )
        assertEquals(EARNVIDS_REGISTRATIONS.map { it.first }, EARNVIDS_HOSTS)
        assertEquals(EARNVIDS_REGISTRATIONS.size, EARNVIDS_REGISTRATIONS.map { it.first }.toSet().size)
        assertTrue(EARNVIDS_REGISTRATIONS.all { it.second.isNotBlank() })
    }
}
