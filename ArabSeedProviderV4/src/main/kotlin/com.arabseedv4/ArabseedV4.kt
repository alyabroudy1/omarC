package com.arabseedv4

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.android.PluginContext
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import com.cloudstream.shared.provider.BaseProvider

class ArabseedV4 : BaseProvider() {

    override val baseDomain get() = "arabseed.show"
    override val providerName get() = "ArabseedV4"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseed.json"

    override val mainPage = mainPageOf(
        "/movies-1/" to "أفلام",
        "/series-1/" to "مسلسلات"
    )

    override fun getParser(): NewBaseParser {
        return ArabseedV4Parser()
    }

    // ================= LOAD LOGIC (AJAX SEASONS) =================

    override suspend fun load(url: String): LoadResponse? {
        val doc = httpService.getDocument(url) ?: return null
        // 1. Basic parsing via NewBaseParser config
        val data = (getParser() as ArabseedV4Parser).parseLoadPage(doc, url) ?: return null

        if (data.type == TvType.Movie) {
             val watchUrl = (getParser() as ArabseedV4Parser).getWatchUrl(doc)
             return newMovieLoadResponse(data.title, url, TvType.Movie, watchUrl.ifBlank { url }) {
                 this.posterUrl = data.posterUrl
                 this.year = data.year
                 this.plot = data.plot
                 this.tags = data.tags
             }
        } else {
            // SERIES: Handle AJAX Seasons
            val episodes = mutableListOf<ParsedEpisode>()
            if (data.episodes != null) episodes.addAll(data.episodes!!)

            val parser = getParser() as ArabseedV4Parser
            val seasonDataList = parser.parseSeasonsWithPostId(doc)
            val csrfToken = parser.extractCsrfToken(doc)
            val currentSeason = episodes.firstOrNull()?.season ?: 1

            if (seasonDataList.isNotEmpty() && !csrfToken.isNullOrBlank()) {
                coroutineScope {
                    val otherEpisodes = seasonDataList.map { (seasonNum, postId) ->
                        async {
                            try {
                                if (seasonNum == currentSeason) return@async emptyList<ParsedEpisode>()
                                
                                val payload = mapOf(
                                    "season_id" to postId,
                                    "csrf_token" to csrfToken
                                )
                                
                                val result = httpService.postDebug(
                                    url = "/season__episodes/",
                                    data = payload,
                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                    referer = url
                                )
                                
                                if (result.success && result.html != null) {
                                    parser.parseEpisodesFromAjax(result.html, seasonNum)
                                } else {
                                    emptyList<ParsedEpisode>()
                                }
                            } catch (e: Exception) {
                                emptyList<ParsedEpisode>()
                            }
                        }
                    }.awaitAll().flatten()
                    episodes.addAll(otherEpisodes)
                }
            }
            
            val sortedEpisodes = episodes.distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            
            val mappedEpisodes = sortedEpisodes.map { ep ->
                newEpisode(ep.url) {
                    this.name = ep.name
                    this.season = ep.season
                    this.episode = ep.episode
                }
            }

            return newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, mappedEpisodes) {
                this.posterUrl = data.posterUrl
                this.year = data.year
                this.plot = data.plot
                this.tags = data.tags
            }
        }
    }

    // ================= LOAD LINKS LOGIC (LAZY & SNIFFER) =================

    private data class ArabseedLazySource(
        val postId: String, 
        val quality: String, 
        val serverId: String, 
        val csrfToken: String, 
        val baseUrl: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parser = getParser() as ArabseedV4Parser
        
        // 1. Resolve Watch Page
        val doc = httpService.getDocument(data) ?: return false
        val watchDoc = if (parser.isWatchPage(doc)) doc else {
            val watchUrl = parser.getWatchUrl(doc)
            if (watchUrl.isNotBlank()) httpService.getDocument(watchUrl) ?: return false else return false
        }

        // 2. Extract Metadata for Sources
        val availableQualities = parser.extractQualities(watchDoc)
        val defaultQuality = parser.extractDefaultQuality(watchDoc, availableQualities)
        val visibleServers = parser.extractVisibleServers(watchDoc)
        val directEmbeds = parser.extractDirectEmbeds(watchDoc)
        val globalPostId = parser.extractPostId(watchDoc) ?: ""
        val csrfToken = parser.extractCsrfToken(doc) ?: ""

        val currentBaseUrl = try {
            val uri = java.net.URI(watchDoc.location())
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) { "https://${httpService.currentDomain}" }

        // 3. Collect Sources (Lazy + Visible)
        val sources = mutableListOf<Any>() // Can be ArabseedParser.ServerData or specific Lazy object matching V2 logic
        
        // A. Qualities (Lazy)
        availableQualities.forEach { qData ->
            if (qData.quality != defaultQuality) {
                 val anyPostId = visibleServers.firstOrNull()?.postId?.ifBlank { globalPostId } ?: globalPostId
                 if (anyPostId.isNotBlank() && csrfToken.isNotBlank()) {
                     for (serverId in 1..5) {
                         sources.add(ArabseedLazySource(anyPostId, qData.quality.toString(), serverId.toString(), csrfToken, currentBaseUrl))
                     }
                 }
            } else {
                 // Default quality types
                 visibleServers.forEach { server ->
                     if (server.dataLink.isNotBlank()) {
                         sources.add(server.dataLink) // Direct link treated as visible source
                     } else if (server.postId.isNotBlank() && csrfToken.isNotBlank()) {
                         sources.add(ArabseedLazySource(server.postId, defaultQuality.toString(), server.serverId, csrfToken, currentBaseUrl))
                     }
                 }
            }
        }
        
        // B. Direct Embeds
        sources.addAll(directEmbeds)

        Log.d(providerName, "Collected ${sources.size} sources")

        // 4. Resolve & Extract Loop
        var anyFound = false
        
        sources.forEach { source ->
             try {
                 val sourceUrl = when (source) {
                     is ArabseedLazySource -> resolveLazyUrl(source)
                     is String -> source
                     else -> null
                 }
                 
                 if (!sourceUrl.isNullOrBlank()) {
                     // Try standard extractors first
                     val extracted = loadExtractor(sourceUrl, "$currentBaseUrl/", subtitleCallback, callback)
                     if (extracted) {
                         anyFound = true
                     } else {
                         // Fallback to Sniffer if standard failed
                         // We can use the visible sniffer logic if it's a private server
                         if (sourceUrl.contains("arabseed") || sourceUrl.contains("asd")) {
                              val sniffed = httpService.sniffVideosVisible(sourceUrl)
                              sniffed.forEach { vid ->
                                  callback(newExtractorLink(
                                      providerName,
                                      providerName,
                                      vid.url,
                                      ExtractorLinkType.VIDEO
                                  ) {
                                      this.headers = vid.headers
                                      this.quality = getQualityFromName(vid.quality)
                                  })
                                  anyFound = true
                              }
                         }
                     }
                 }
             } catch (e: Exception) {
                 Log.e(providerName, "Error processing source: ${e.message}")
             }
        }

        return anyFound
    }

    private suspend fun resolveLazyUrl(source: ArabseedLazySource): String? {
        val lazyUrl = "https://arabseed-lazy.com/?post_id=${source.postId}&quality=${source.quality}&server=${source.serverId}&csrf_token=${source.csrfToken}&base=${source.baseUrl}"
        val uri = java.net.URI(lazyUrl)
        val queryParams = uri.query.split("&").associate { 
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else it to ""
        }
        
        val baseUrlForAjax = queryParams["base"] ?: "https://asd.pics"
        
        val result = httpService.postDebug(
            "$baseUrlForAjax/get__watch__server/",
            data = mapOf(
                "post_id" to source.postId,
                "quality" to source.quality,
                "server" to source.serverId,
                "csrf_token" to source.csrfToken
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to baseUrlForAjax,
                "Referer" to "$baseUrlForAjax/"
            )
        )
        
        if (!result.success || result.html == null) return null
        
        val serverResponse = result.html
        var embedUrl = ""
        
        if (serverResponse.trim().startsWith("{")) {
            val serverMatch = Regex("\"server\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
            if (serverMatch != null) {
                embedUrl = serverMatch.groupValues[1].replace("\\/", "/")
            } else if (serverResponse.contains("\"html\"")) {
                val htmlMatch = Regex("\"html\"\\s*:\\s*\"([^\"]+)\"").find(serverResponse)
                val escapedHtml = htmlMatch?.groupValues?.get(1)
                if (escapedHtml != null) {
                    val unescaped = escapedHtml.replace("\\/", "/").replace("\\\"", "\"")
                    embedUrl = org.jsoup.Jsoup.parse(unescaped).select("iframe").attr("src")
                }
            }
        } else {
            val serverDoc = org.jsoup.Jsoup.parse(serverResponse, "$baseUrlForAjax/")
            embedUrl = serverDoc.select("iframe").attr("src")
        }
        
        return if (embedUrl.isNotBlank()) embedUrl else null
    }
}