package com.cimawbas

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink

class Cimawbas : BaseProvider() {
    override val baseDomain get() = "cimawbas.org"
    override val providerName get() = "Cimawbas"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/refs/heads/main/CimawbasProvider/cimawbasConfig.json"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "/movies/page/" to "أفلام",
        "/series/page/" to "مسلسلات",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/page/" to "أفلام أنمي",
        "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/page/" to "مسلسلات أنمي",
        "/last/page/" to "أضيف حديثاً"
    )

    override fun getParser(): NewBaseParser = CimawbasParser()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val doc = httpService.getDocument(data) ?: return false
        
        // 1. Check if watch button exists and points to embed index
        val watchButtonUrl = doc.selectFirst("a.watch")?.attr("href")
        
        val targetDoc = if (!watchButtonUrl.isNullOrBlank()) {
            httpService.getDocument(watchButtonUrl) ?: return false
        } else {
            doc
        }

        // 2. Look for ul#watch li and grab data-watch embed URLs
        targetDoc.select("ul#watch li").forEach { server ->
            val embedUrl = server.attr("data-watch")
            if (!embedUrl.isNullOrBlank()) {
                linksFound = true
                com.lagradost.cloudstream3.utils.loadExtractor(
                    embedUrl,
                    subtitleCallback,
                    callback
                )
            }
        }

        return linksFound
    }
}
