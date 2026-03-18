package com.tuktukhd

import android.util.Base64
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.provider.ProviderConfig
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
import org.jsoup.nodes.Document

data class MirrorItem(@JsonProperty("link") val link: String?)
data class StreamItem(@JsonProperty("mirrors") val mirrors: List<MirrorItem>?)
data class StreamsData(@JsonProperty("data") val data: List<StreamItem>?)
data class InertiaProps(@JsonProperty("streams") val streams: StreamsData?)
data class InertiaResponse(@JsonProperty("props") val props: InertiaProps?)

class TukTukcima : BaseProvider() {
    override val baseDomain get() = "tuktukhd.com"
    override val providerName get() = "TukTukcima"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/tuktukhd.json"
    override val hasMainPage = true
    
    override var mainUrl = "https://tuktukhd.com"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "/recent/page/" to "المضاف حديثاً",
        "/category/movies-2/page/" to "أحدث الأفلام",
        "/category/series-1/page/" to "أحدث الحلقات",
        "/category/movies-2/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/page/" to "أفلام مدبلجة"
    )



    override fun getParser(): NewBaseParser {
        return TukTukcimaParser()
    }

    override suspend fun fetchExtraEpisodes(
        doc: Document,
        url: String,
        data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val listSelect = doc.select(".allseasonss .Block--Item a")
        if (listSelect.isEmpty()) {
            return data.episodes ?: emptyList()
        }

        val extraEpisodes = coroutineScope {
            listSelect.map { seasonEl ->
                async {
                    val seasonUrl = fixUrlLocally(seasonEl.attr("href"))
                    val seasonName = seasonEl.select("h3").text()
                    val seasonNum = seasonName.filter { it.isDigit() }.toIntOrNull() ?: 1

                    val seasonDoc = httpService.getDocument(seasonUrl) ?: return@async emptyList<ParserInterface.ParsedEpisode>()
                    
                    getParser().parseEpisodes(seasonDoc, seasonNum)
                }
            }.awaitAll().flatten()
        }
        
        return extraEpisodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        httpService.ensureInitialized()
        val doc = httpService.getDocument(data) ?: return false

        val iframeCrypt = doc.selectFirst("iframe#main-video-frame")?.attr("data-crypt")
        if (iframeCrypt.isNullOrBlank()) return false

        val iframeUrl = String(Base64.decode(iframeCrypt, Base64.DEFAULT), Charsets.UTF_8)
        
        val reqHeaders = mapOf(
            "User-Agent" to httpService.userAgent,
            "Referer" to mainUrl
        )
        
        val initialRes = httpService.getRaw(iframeUrl, headers = reqHeaders)
        val xsrfTokenDecoded = initialRes.headers("Set-Cookie").find { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            
        val initialText = initialRes.body?.string() ?: ""
        val version = Regex("\"version\":\"([^\"]+)\"").find(initialText)?.groupValues?.get(1)

        if (xsrfTokenDecoded == null || version == null) return false

        val cookieHeaderString = initialRes.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }

        val inertiaHeaders = mapOf(
            "X-XSRF-TOKEN" to xsrfTokenDecoded,
            "X-Inertia" to "true",
            "X-Inertia-Version" to version,
            "X-Inertia-Partial-Component" to "files/mirror/video",
            "X-Inertia-Partial-Data" to "streams",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to iframeUrl,
            "Content-Type" to "application/json",
            "Cookie" to cookieHeaderString
        )

        val jsonRes = httpService.getRaw(iframeUrl, headers = inertiaHeaders)
        val jsonText = jsonRes.body?.string() ?: ""
        
        var linksFound = false
        try {
            val inertia = parseJson<InertiaResponse>(jsonText)
            val streams = inertia.props?.streams?.data
            if (streams != null) {
                for (stream in streams) {
                    val mirrors = stream.mirrors
                    if (mirrors != null) {
                        for (mirror in mirrors) {
                            val link = mirror.link
                            if (!link.isNullOrBlank()) {
                                val finalLink = if (link.startsWith("//")) "https:$link" else link
                                loadExtractor(finalLink, data, subtitleCallback, callback)
                                linksFound = true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound
    }
    
    private fun fixUrlLocally(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl/$url".replace("//", "/").replace("https:/", "https://")
    }
}
