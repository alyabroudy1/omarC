package com.faselHDV2

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class FaselHDV2 : BaseProvider() {

    override val baseDomain get() = "faselhds.biz"
    override val providerName get() = "FaselHDV2"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/faselhd.json"

    override val mainPage = mainPageOf(
        "/all-movies/page/" to "جميع الافلام",
        "/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "/dubbed-movies/page/" to "الأفلام المدبلجة",
        "/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "/series/page/" to "مسلسلات",
        "/recent_series/page/" to "المضاف حديثا",
        "/anime/page/" to "الأنمي",
    )

    override fun getParser(): NewBaseParser {
        return FaselHDV2Parser()
    }

    /**
     * Fetch extra episodes from inactive season tabs via AJAX.
     * FaselHD shows only the active season's episodes on the detail page.
     * Other seasons require fetching `$mainUrl/?p=ID` for each season tab.
     */
    override suspend fun fetchExtraEpisodes(
        doc: Document, url: String, data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val parser = getParser() as FaselHDV2Parser
        val currentEpisodes = data.episodes?.toMutableList() ?: mutableListOf()
        
        // Parse inactive season tabs
        val seasonTabs = parser.parseSeasonTabs(doc)
        
        if (seasonTabs.isEmpty()) {
            Log.d("[FaselHDV2]", "fetchExtraEpisodes: no extra season tabs found")
            return currentEpisodes
        }
        
        Log.i("[FaselHDV2]", "fetchExtraEpisodes: fetching ${seasonTabs.size} extra seasons in parallel")
        
        // Fetch all season pages in parallel
        val extraEpisodes = coroutineScope {
            seasonTabs.map { (seasonNum, pageUrl) ->
                async {
                    try {
                        val fullUrl = if (pageUrl.startsWith("http")) pageUrl else "$mainUrl$pageUrl"
                        Log.d("[FaselHDV2]", "fetchExtraEpisodes: fetching season $seasonNum from $fullUrl")
                        
                        val seasonDoc = httpService.getDocument(fullUrl) ?: return@async emptyList()
                        val episodes = parser.parseEpisodes(seasonDoc, seasonNum)
                        
                        Log.d("[FaselHDV2]", "fetchExtraEpisodes: season $seasonNum -> ${episodes.size} episodes")
                        episodes
                    } catch (e: Exception) {
                        Log.w("[FaselHDV2]", "fetchExtraEpisodes: failed for season $seasonNum: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        
        currentEpisodes.addAll(extraEpisodes)
        return currentEpisodes
    }
}