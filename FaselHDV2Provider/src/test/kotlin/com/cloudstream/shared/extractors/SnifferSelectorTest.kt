package com.cloudstream.shared.extractors

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [SnifferSelector.toJson].
 *
 * It used to omit the closing quote on the query value, emitting
 * `{"query":"li.active,"attr":"onclick"}`. That is invalid JSON, so `fromJson` threw, returned
 * null, and the caller saw "no selector" with no error anywhere — the pre-sniff click silently
 * never ran for any provider. Observed in a device log as `hasSelector=true` at the provider and
 * `hasSelector=false` one call later, inside SnifferExtractor.
 *
 * Asserted on the serialised string rather than by round-tripping, because `fromJson` uses
 * `org.json`, which is a non-functional stub in JVM unit tests.
 */
class SnifferSelectorTest {

    @Test
    fun `query value is closed`() {
        assertEquals(
            """{"query":"li.active"}""",
            SnifferSelector(query = "li.active").toJson()
        )
    }

    @Test
    fun `query and attr both serialise as closed strings`() {
        assertEquals(
            """{"query":"li.active","attr":"onclick"}""",
            SnifferSelector(query = "li.active", attr = "onclick").toJson()
        )
    }

    @Test
    fun `the real FaselHD selector serialises intact`() {
        // The selector FaselHDV2 passes; the comma inside it is what made the old malformed output
        // parse as a truncated string followed by garbage instead of failing loudly.
        val query = "#watchareaa > div.signleWatch > ul.tabs-ul > li.active,"
        assertEquals(
            """{"query":"$query","attr":"onclick"}""",
            SnifferSelector(query = query, attr = "onclick").toJson()
        )
    }

    @Test
    fun `embedded quotes are escaped`() {
        assertEquals(
            """{"query":"li[data-id=\"1\"]"}""",
            SnifferSelector(query = "li[data-id=\"1\"]").toJson()
        )
    }

    @Test
    fun `non-default wait is emitted as a number`() {
        assertEquals(
            """{"query":"a","wait":500}""",
            SnifferSelector(query = "a", waitAfterClick = 500L).toJson()
        )
    }

    @Test
    fun `regex is included when set`() {
        assertEquals(
            """{"query":"a","attr":"href","regex":"\\d+"}""",
            SnifferSelector(query = "a", attr = "href", regex = """\d+""").toJson()
        )
    }
}
