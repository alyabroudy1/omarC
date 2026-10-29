package com.egydead

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EgyDeadParserTest {

    private val parser = EgyDeadParser()

    private fun doc(html: String, url: String): Document = Jsoup.parse(html, url)

    @Test
    fun `current series season and episode URLs are not movies`() {
        assertTrue(parser.isSeries("مسلسل Breaking Bad 2008", "https://tv10.egydead.live/Series/breaking-bad-2008/", null))
        assertTrue(parser.isSeries("مسلسل Breaking Bad الموسم الخامس", "https://tv10.egydead.live/seasons/breaking-bad-s05/", null))
        assertTrue(parser.isSeries("مسلسل Breaking Bad الحلقة 16", "https://tv10.egydead.live/episodes/breaking-bad-s05e16/", null))
        assertFalse(parser.isSeries("فيلم Street Fighter 2026", "https://tv10.egydead.live/street-fighter-2026-1080p-hdcam/", null))
    }

    @Test
    fun `season page episodes get season and episode numbers from slug`() {
        val seasonDoc = doc(
            """
            <html><head><title>مسلسل Breaking Bad الموسم الخامس مترجم كامل</title></head><body>
              <div class="EpsList">
                <li><a href="https://tv10.egydead.live/episodes/breaking-bad-s05e16/" title="مسلسل Breaking Bad الموسم الخامس الحلقة 16 مترجمة">حلقه 16</a></li>
                <li><a href="https://tv10.egydead.live/episodes/breaking-bad-s05e15/" title="مسلسل Breaking Bad الموسم الخامس الحلقة 15 مترجمة">حلقه 15</a></li>
              </div>
            </body></html>
            """.trimIndent(),
            "https://tv10.egydead.live/seasons/breaking-bad-s05/"
        )

        val episodes = parser.parseEpisodes(seasonDoc, null)

        assertEquals(2, episodes.size)
        val episode16 = episodes.first { it.episode == 16 }
        assertEquals(5, episode16.season)
        assertEquals("https://tv10.egydead.live/episodes/breaking-bad-s05e16/", episode16.url)
        assertTrue(episode16.name.contains("الحلقة 16"))
    }

    @Test
    fun `episode page points BaseProvider at its season parent`() {
        val episodeDoc = doc(
            """
            <html><head><title>مسلسل Breaking Bad الموسم الخامس الحلقة 16 مترجمة</title></head><body>
              <div class="breadcrumbs-single">
                <a itemprop="url" href="https://tv10.egydead.live/Series/breaking-bad-2008/">المسلسل</a>
                <a itemprop="url" href="https://tv10.egydead.live/seasons/breaking-bad-s05/">الموسم</a>
              </div>
              <div class="singleTitle"><span><em>مسلسل Breaking Bad الموسم الخامس الحلقة 16 مترجمة</em></span></div>
              <a href="https://tv10.egydead.live/episodes/breaking-bad-s05e16/watch/">المشاهده والتحميل</a>
            </body></html>
            """.trimIndent(),
            "https://tv10.egydead.live/episodes/breaking-bad-s05e16/"
        )

        assertEquals(
            "https://tv10.egydead.live/seasons/breaking-bad-s05/",
            EgyDeadSeries.seasonUrlFromBreadcrumb(episodeDoc)
        )
        assertEquals("https://tv10.egydead.live/episodes/breaking-bad-s05e16/watch/", parser.getPlayerPageUrl(episodeDoc))
        assertFalse(parser.isSeries("فيلم Street Fighter 2026", "https://tv10.egydead.live/street-fighter-2026-1080p-hdcam/", null))
    }

    @Test
    fun `watch page server list wins over the youtube trailer iframe`() {
        val watchDoc = doc(
            """
            <html><body>
              <ul class="serversList">
                <li data-link="https://hgcloud.to/e/3d9hefp3ub76">StreamHG</li>
                <li data-link="https://rubyvidhub.com/embed-53uf88v3ggvn.html">StreamRuby</li>
              </ul>
              <iframe src="https://www.youtube.com/embed/-3hx6FVw038"></iframe>
            </body></html>
            """.trimIndent(),
            "https://tv10.egydead.live/episodes/breaking-bad-s05e16/watch/"
        )

        assertEquals(
            listOf(
                "https://hgcloud.to/e/3d9hefp3ub76",
                "https://rubyvidhub.com/embed-53uf88v3ggvn.html"
            ),
            parser.extractWatchServersUrls(watchDoc)
        )
    }

    @Test
    fun `search pagination uses the wordpress page path and stops off-search`() {
        val parser = EgyDeadParser()
        assertEquals(
            "https://tv10.egydead.live/page/2/?s=breaking",
            parser.getSearchUrl("https://tv10.egydead.live", "breaking", 2)
        )

        val search = doc(
            """<li class="movieItem"><a href="/seasons/breaking-bad-s05/"><h1 class="BottomTitle">x</h1></a></li>""",
            "https://tv10.egydead.live/page/3/?s=breaking"
        )
        val home = doc(
            """<li class="movieItem"><a href="/seasons/x/"><h1 class="BottomTitle">x</h1></a></li>""",
            "https://tv10.egydead.live/h3/"
        )
        assertTrue(parser.hasNextSearchPage(search))
        assertFalse(parser.hasNextSearchPage(home))
    }
}
