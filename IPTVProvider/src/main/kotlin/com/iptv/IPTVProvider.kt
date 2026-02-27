package com.iptv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser

class IPTVProvider : BaseProvider() {

    override val providerName get() = "IPTV"
    override val baseDomain get() = "airtech35"
    override val githubConfigUrl get() = ""

    override val mainPage = mainPageOf(
        "/arach" to "All Channels"
    )

    override fun getParser(): NewBaseParser {
        return IPTVParser()
    }

    override val supportedTypes: Set<TvType> = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?,
        val headers: Map<String, String> = emptyMap()
    )

    private fun parseM3U(content: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        val lines = content.split("\n")
        
        var currentInfo: String? = null
        
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    currentInfo = line.removePrefix("#EXTINF:")
                }
                (line.startsWith("http") || line.startsWith("//")) && currentInfo != null -> {
                    val url = if (line.startsWith("//")) "https:$line" else line.trim()
                    val channel = parseExtInf(currentInfo, url)
                    if (channel != null) {
                        channels.add(channel)
                    }
                    currentInfo = null
                }
            }
        }
        
        return channels
    }

    private fun parseExtInf(info: String, url: String): M3UChannel? {
        try {
            val attributes = mutableMapOf<String, String>()
            
            // Parse all attributes: tvg-id="xxx" tvg-name="xxx" tvg-logo="xxx" group-title="xxx" etc.
            val attrRegex = Regex("""([a-zA-Z0-9-]+)="([^"]*)"|(\d+)""")
            val matches = attrRegex.findAll(info)
            
            for (match in matches) {
                val (key, value) = match.destructured
                if (key.isNotEmpty()) {
                    attributes[key] = value
                }
            }
            
            // Get channel name from after last comma
            val name = info.substringAfterLast(",")
                .replace("♛", "")
                .replace("❤️", "")
                .replace("🇶🇦", "")
                .replace("🎬", "")
                .trim()
            
            if (name.isBlank() || url.isBlank()) {
                return null
            }
            
            val group = attributes["group-title"] 
                ?: attributes["group"]
                ?: attributes["category"]
                ?: ""
            
            val tvgId = attributes["tvg-id"] 
                ?: attributes["tvgid"] 
                ?: attributes["CUID"]
                ?: ""
            
            val logo = attributes["tvg-logo"]
                ?: attributes["logo"]
                ?: attributes["tvglogo"]
                ?: ""
            
            return M3UChannel(
                name = name,
                url = cleanUrl(url),
                logo = logo.ifBlank { null },
                group = group.ifBlank { null },
                tvgId = tvgId.ifBlank { null },
                headers = emptyMap()
            )
        } catch (e: Exception) {
            Log.e("IPTV", "Error parsing EXTINF: info=$info, url=$url, error=${e.message}")
            return null
        }
    }

    private fun cleanUrl(url: String): String {
        return url.split("|").firstOrNull() ?: url
    }

    private fun getCategories(channels: List<M3UChannel>): Map<String, List<M3UChannel>> {
        return channels.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Other" }
    }

    private fun cleanGroupName(group: String): String {
        return group
            .replace("⚽", "")
            .replace("TR:", "")
            .replace("AR:", "")
            .replace("|", "")
            .trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        
        try {
            val url = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/IPTVProvider/src/main/kotlin/com/iptv/arach"
            
            val content = app.get(url).text
            val channels = parseM3U(content)
            
            Log.d("IPTV", "Total channels: ${channels.size}")
            
            val filteredChannels = channels.filter { chan ->
                val hasBadLogo = chan.logo == "https://bit.ly/3JQfa8u" || chan.logo == "https://bit.ly/jpgairmaxtv" || chan.logo.isNullOrBlank()
                val hasBadUrl = chan.url.contains("bit.ly") || chan.url.contains("cutt.ly")
                if (hasBadLogo || hasBadUrl) {
                    Log.d("IPTV", "Filtered out: name=${chan.name}, logo=${chan.logo}, url=${chan.url}")
                }
                !hasBadLogo && !hasBadUrl
            }
            
            Log.d("IPTV", "Filtered channels: ${filteredChannels.size}")
            
            val categories = getCategories(filteredChannels)
            val homePageLists = mutableListOf<HomePageList>()
            
            val sortedCategories = categories.toList().sortedBy { it.first }
            
            for ((category, categoryChannels) in sortedCategories) {
                val cleanCategory = cleanGroupName(category)
                if (cleanCategory == "Other" || cleanCategory.isBlank()) continue
                
                val searchResponses = categoryChannels.take(50).map { chan ->
                    newMovieSearchResponse(chan.name, chan.url, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
                
                if (searchResponses.isNotEmpty()) {
                    homePageLists.add(HomePageList(cleanCategory, searchResponses))
                }
            }
            
            // Add all channels
            val allChannels = filteredChannels.take(100).map { chan ->
                newMovieSearchResponse(chan.name, chan.url, TvType.Live) {
                    this.posterUrl = chan.logo
                }
            }
            
            if (allChannels.isNotEmpty()) {
                homePageLists.add(0, HomePageList("All Channels", allChannels))
            }
            
            return newHomePageResponse(homePageLists)
            
        } catch (e: Exception) {
            Log.e("IPTV", "Error in getMainPage: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val url = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/IPTVProvider/src/main/kotlin/com/iptv/arach"
            val content = app.get(url).text
            val channels = parseM3U(content)
            
            val filteredChannels = channels.filter { 
                it.logo != "https://bit.ly/3JQfa8u" && it.logo != "https://bit.ly/jpgairmaxtv" && !it.logo.isNullOrBlank() 
            }
            
            val queryLower = query.lowercase()
            return filteredChannels
                .filter { it.name.lowercase().contains(queryLower) }
                .take(50)
                .map { chan ->
                    newMovieSearchResponse(chan.name, chan.url, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
        } catch (e: Exception) {
            Log.e("IPTV", "Error in search: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (data.startsWith("http") || data.startsWith("//")) {
                val url = if (data.startsWith("//")) "https:$data" else data
                val cleanUrl = cleanUrl(url)
                
                val linkType = when {
                    cleanUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                    cleanUrl.contains(".mpd") -> ExtractorLinkType.DASH
                    cleanUrl.contains(".mp4") -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.M3U8
                }
                
                callback(
                    newExtractorLink(providerName, providerName, cleanUrl, type = linkType) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.e("IPTV", "Error in loadLinks: ${e.message}")
        }
        return false
    }
}
