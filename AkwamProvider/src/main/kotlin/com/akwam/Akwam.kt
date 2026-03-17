package com.akwam

import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.provider.BaseProvider
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Akwam : BaseProvider() {

    init {
        Log.i("Akwam", "Initializing Akwam provider")
    }

    override val baseDomain get() = "ak.sv"
    override val providerName get() = "Akwam"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/akwam.json"

    override val mainPage = mainPageOf(
        "/movies" to "أحدث الأفلام",
        "/series" to "أحدث المسلسلات",
        "/shows" to "العروض",
        "/series?section=29&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "مسلسلات عربي",
        "/series?section=32&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "مسلسلات تركي",
        "/series?section=33&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "مسلسلات اسيوية",
        "/series?section=30&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "مسلسلات اجنبي",
        "/series?section=31&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "مسلسلات هندي",
        "/movies?section=29&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "أفلام عربي",
        "/movies?section=32&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "أفلام تركي",
        "/movies?section=33&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "أفلام اسيوية",
        "/movies?section=30&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "أفلام اجنبي",
        "/movies?section=31&category=0&rating=0&year=0&language=0&formats=0&quality=0" to "أفلام هندي"
    )

    override fun getParser(): NewBaseParser {
        Log.d("Akwam", "getParser called")
        return AkwamParser()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[$providerName] [loadLinks]"
        Log.i(methodTag, "START data='$data'")

        try {
            httpService.ensureInitialized()

            val detailDoc = httpService.getDocument(data)
            if (detailDoc == null) {
                Log.e(methodTag, "Failed to fetch detail page")
                return false
            }

            val watchPageUrl = getParser().getPlayerPageUrl(detailDoc)
            if (watchPageUrl == null) {
                Log.e(methodTag, "Failed to get watch page URL from detail page")
                return false
            }

            val actualWatchUrl = if (watchPageUrl.startsWith("http")) {
                watchPageUrl
            } else {
                "$mainUrl/$watchPageUrl".replace("//", "/").replace("https:/", "https://")
            }

            Log.d(methodTag, "Fetching watch page: $actualWatchUrl")
            var watchDoc = httpService.getDocument(actualWatchUrl)

            // Fallback with Referer as seen in decompiled code
            if (watchDoc == null) {
                Log.d(methodTag, "Retrying watch page with Referer...")
                watchDoc = httpService.getDocument(actualWatchUrl, mapOf("Referer" to data))
            }

            if (watchDoc == null) {
                Log.e(methodTag, "Failed to fetch watch page document")
                return false
            }

            val sources = watchDoc.select("source[src]")
            Log.d(methodTag, "Found ${sources.size} direct video sources")

            sources.forEach { src ->
                var videoUrl = src.attr("abs:src").ifBlank { src.attr("src") }
                if (videoUrl.isNotBlank()) {
                    // Normalize URL
                    videoUrl = videoUrl.replace("https://", "http://").replace(" ", "%20")

                    val qualityStr = src.attr("size").ifBlank { src.attr("label") }.ifBlank { "direct" }
                    val quality = getQualityFromName(qualityStr)

                    Log.d(methodTag, "Delivering link: quality='$qualityStr', url='${videoUrl.take(100)}'")

                    callback(
                        newExtractorLink(
                            source = providerName,
                            name = providerName,
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = quality
                        }
                    )
                }
            }

            return sources.isNotEmpty()
        } catch (e: Exception) {
            Log.e(methodTag, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("#")
        val realUrl = parts[0]
        val posterFromUrl = parts.getOrNull(1)?.takeIf { it.isNotBlank() }

        val response = super.load(realUrl) ?: return null

        // Inject poster from URL fragment if provided (Search -> Load metadata bridge)
        if (posterFromUrl != null) {
            Log.d("Akwam", "Injecting poster from URL fragment: $posterFromUrl")
            when (response) {
                is MovieLoadResponse -> response.posterUrl = posterFromUrl
                is TvSeriesLoadResponse -> response.posterUrl = posterFromUrl
            }
        }
        return response
    }

    override suspend fun fetchExtraEpisodes(
        doc: Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val methodTag = "[$providerName] [fetchExtraEpisodes]"
        
        // Akwam series pages usually only show episodes for the CURRENT season.
        // We need to find other seasons, fetch their pages, and collect all episodes.
        val seasonsLinks = doc.select("div.widget-body > a.btn[href*='/series/']")
        if (seasonsLinks.isEmpty()) {
            return data.episodes ?: emptyList()
        }

        Log.i(methodTag, "Found ${seasonsLinks.size} season links. Aggregating episodes...")
        
        val allEpisodes = mutableListOf<com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode>()
        allEpisodes.addAll(data.episodes ?: emptyList())

        val currentDomain = baseDomain
        
        seasonsLinks.forEach { link ->
            val seasonUrl = link.attr("abs:href").ifBlank { link.attr("href") }
            val absoluteSeasonUrl = if (seasonUrl.startsWith("http")) seasonUrl else "https://$currentDomain$seasonUrl"
            
            // Skip current page
            if (absoluteSeasonUrl.contains(url.substringBefore("#").trimEnd('/'))) {
                return@forEach
            }

            try {
                Log.d(methodTag, "Fetching season: $absoluteSeasonUrl")
                val seasonDoc = httpService.getDocument(absoluteSeasonUrl)
                if (seasonDoc != null) {
                    val seasonName = link.text()
                    val seasonNum = getSeasonNumber(seasonName) ?: 1
                    val episodes = getParser().parseEpisodes(seasonDoc, seasonNum)
                    Log.d(methodTag, "Parsed ${episodes.size} episodes from season $seasonNum")
                    allEpisodes.addAll(episodes)
                }
            } catch (e: Exception) {
                Log.e(methodTag, "Error fetching season $absoluteSeasonUrl: ${e.message}")
            }
        }

        return allEpisodes.distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun getSeasonNumber(text: String): Int? {
        val map = mapOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10
        )
        for ((key, value) in map) {
            if (text.contains(key)) return value
        }
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun getQualityFromName(qualityName: String): Int {
        return when {
            qualityName.contains("4k", true) || qualityName.contains("2160") -> Qualities.P2160.value
            qualityName.contains("1080") -> Qualities.P1080.value
            qualityName.contains("720") -> Qualities.P720.value
            qualityName.contains("480") -> Qualities.P480.value
            qualityName.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
