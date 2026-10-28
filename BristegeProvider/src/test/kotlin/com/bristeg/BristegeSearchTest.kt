package com.bristeg

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BristegeSearchTest {

    private fun load(resource: String): Document {
        val html = javaClass.classLoader!!.getResource(resource)!!.readText()
        return Jsoup.parse(html, "https://brsteej.casa/search.php?keywords=test")
    }

    @Test
    fun `empty search does not fall back to site footer links`() {
        val items = BristegeParser().parseSearch(load("search-empty.html"))

        assertTrue("An empty search must return no results", items.isEmpty())
        assertFalse(
            "Footer promotions must never be returned as search results",
            items.any { it.url.contains("series1.php") }
        )
    }

    @Test
    fun `search results are scoped to the results grid and deduplicated`() {
        val items = BristegeParser().parseSearch(load("search-results.html"))

        assertEquals(2, items.size)
        assertEquals(
            listOf(
                "https://brsteej.casa/video.php?vid=aaa",
                "https://brsteej.casa/video.php?vid=bbb"
            ),
            items.map { it.url }
        )
        assertTrue(items.all { it.posterUrl == null || it.posterUrl!!.contains("/uploads/thumbs/") })
        assertFalse(items.any { it.url.contains("series1.php") })
    }

    @Test
    fun `empty search page stops pagination`() {
        val parser = BristegeParser()

        assertTrue(parser.hasNextSearchPage(load("search-results.html")))
        assertFalse(parser.hasNextSearchPage(load("search-empty.html")))
    }
}
