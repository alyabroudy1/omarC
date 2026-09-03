package com.cloudstream.shared.provider

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation

/**
 * Wave 0: the pageless `searchNormal(query)` / `searchLazy(query)` overloads were deleted from
 * [BaseProvider]; every provider override now takes `(query, page)`. These tests are the guard
 * against either half of that regressing.
 */
class SearchPagingTest {

    /** A parser that only exists to exercise the pure `getSearchUrl` logic. */
    private class PagingParser(
        override val searchPaginationFormat: String?
    ) : NewBaseParser() {
        private val sel = CssSelector(query = "a", attr = "href")
        override val mainPageConfig = MainPageConfig(container = "div", title = sel, url = sel, poster = sel)
        override val loadPageConfig = LoadPageConfig(title = sel, plot = sel, poster = sel)
        override val episodeConfig = EpisodeConfig(container = "div", url = sel)
        override val watchServersSelectors = WatchServerSelector()
    }

    /**
     * A suspend `fun f(String)` compiles to `f(String, Continuation)`; a suspend
     * `fun f(String, Int)` compiles to `f(String, int, Continuation)`. So the deleted pageless
     * overloads are exactly the 2-parameter (String, Continuation) shapes.
     */
    @Test
    fun pagelessOverloadIsGone() {
        val cls = BaseProvider::class.java
        val candidates = (cls.declaredMethods.toList() + cls.methods.toList())
            .filter { it.name == "searchNormal" || it.name == "searchLazy" }

        assertTrue(
            "expected the paged searchNormal/searchLazy to still exist",
            candidates.any { it.parameterCount == 3 }
        )

        val pageless = candidates.filter {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                Continuation::class.java.isAssignableFrom(it.parameterTypes[1])
        }
        assertEquals(
            "pageless search overloads must not exist: " + pageless.map { it.toString() },
            emptyList<String>(),
            pageless.map { it.toString() }
        )
    }

    /**
     * `ParserInterface.getSearchUrl(domain, query, page)` (ParserInterface.kt:72) is the only
     * decision point for the page URL: page 1 reuses the 2-arg URL verbatim, page > 1 appends
     * [ParserInterface.searchPaginationFormat] when the parser opted in.
     */
    @Test
    fun pageTwoUsesPaginationFormat() {
        val paged = PagingParser("&page=%d")
        val p1 = paged.getSearchUrl("https://example.com", "q", 1)
        val p2 = paged.getSearchUrl("https://example.com", "q", 2)

        assertEquals("page 1 must be byte-identical to the pageless URL", paged.getSearchUrl("https://example.com", "q"), p1)
        assertNotEquals("page 2 must differ from page 1 when a pagination format is set", p1, p2)
        assertEquals("https://example.com/?s=q&page=2", p2)
        assertTrue("a parser with a format opts into search paging", paged.supportsSearchPagination)

        // No format set: paging is a no-op and the parser says so.
        val unpaged = PagingParser(null)
        assertEquals(
            unpaged.getSearchUrl("https://example.com", "q", 1),
            unpaged.getSearchUrl("https://example.com", "q", 2)
        )
        assertTrue("a parser without a format must not claim search paging", !unpaged.supportsSearchPagination)
    }
}
