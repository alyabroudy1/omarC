package com.tuktukhd

import android.util.Base64
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.provider.ProviderConfig
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
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

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "/recent/" to "المضاف حديثاً",
        "/category/movies-2/" to "أحدث الأفلام",
        "/sercat/مسلسلات-اجنبي/" to "مسلسلات اجنبي",
        "/channel/series-netflix-2/" to "مسلسلات نتفليكس",
        "/category/movies-2/افلام-مدبلجة/" to "أفلام مدبلجة"
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
                    val seasonName = seasonEl.select("h3").text().ifBlank { seasonEl.text() }
                    val seasonNum = Regex("(\\d+)").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    val seasonDoc = httpService.getDocument(seasonUrl, rewriteDomain = true) ?: return@async emptyList<ParserInterface.ParsedEpisode>()
                    
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
        val methodTag = "[$providerName] [loadLinks]"
        httpService.ensureInitialized()
        var linksFound = false
        fun countingCallback(link: ExtractorLink) {
            linksFound = true
            callback(link)
        }

        val doc = httpService.getDocument(data, rewriteDomain = true) ?: return false

        // 1. Mirror iframe from the encrypted holder (may point at a dead/rotated host)
        val iframeCrypt = doc.selectFirst("iframe#main-video-frame")?.attr("data-crypt")
        val iframeUrl = iframeCrypt?.takeIf { it.isNotBlank() }?.let { decodeCrypt(it) }
        if (iframeUrl != null) {
            com.lagradost.api.Log.d(methodTag, "Iframe: ${iframeUrl.take(80)}")
            for ((mirrorUrl, label) in resolveIframeStreams(iframeUrl, data)) {
                try {
                    com.lagradost.api.Log.d(methodTag, "Mirror [$label]: ${mirrorUrl.take(80)}")
                    loadExtractor(mirrorUrl, iframeUrl, subtitleCallback, ::countingCallback)
                } catch (e: Exception) {
                    com.lagradost.api.Log.d(methodTag, "Extractor failed for $mirrorUrl: ${e.message}")
                }
            }
        } else {
            com.lagradost.api.Log.w(methodTag, "No data-crypt iframe on page")
        }

        // 2. Download servers (go.php?u=<b64 share4max URL>) — independent of the
        // mirror host, works even when the iframe host is dead.
        val downloadUrls = doc.select(".downloads a.smart-external-link[href], .downloadBTN ~ .downloads a[href], div.downloads a[href]")
            .mapNotNull { decodeGoPhp(it.attr("href")) }
            .distinct()
        com.lagradost.api.Log.d(methodTag, "Download targets: $downloadUrls")
        for (dlUrl in downloadUrls) {
            for ((mirrorUrl, label) in resolveDownloadMirrors(dlUrl, data)) {
                if (isTrailerOrJunk(mirrorUrl)) continue
                try {
                    com.lagradost.api.Log.d(methodTag, "Download mirror [$label]: ${mirrorUrl.take(80)}")
                    loadExtractor(mirrorUrl, dlUrl, subtitleCallback, ::countingCallback)
                } catch (e: Exception) {
                    com.lagradost.api.Log.d(methodTag, "Extractor failed for $mirrorUrl: ${e.message}")
                }
            }
        }

        return linksFound
    }

    private fun isTrailerOrJunk(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun decodeCrypt(crypt: String): String? {
        val clean = crypt.trim()
        if (clean.isBlank()) return null
        // Standard base64 first, URL-safe variant as fallback
        val candidates = listOf(clean, clean.replace("-", "+").replace("_", "/"))
        for (c in candidates) {
            try {
                val padded = c.padEnd((c.length + 3) / 4 * 4, '=')
                val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
                if (decoded.startsWith("http")) return decoded
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Decodes theme go.php pills: .../go.php?u=<urlencoded base64 of target URL>.
     */
    private fun decodeGoPhp(href: String): String? {
        try {
            val query = href.substringAfter("?", "")
            if (query.isBlank()) return null
            val u = query.split("&").firstOrNull { it.startsWith("u=") }?.substringAfter("u=") ?: return null
            val b64 = URLDecoder.decode(u, "UTF-8").trim()
            if (b64.isBlank()) return null
            val padded = b64.padEnd((b64.length + 3) / 4 * 4, '=')
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.startsWith("http")) return decoded
        } catch (_: Exception) {}
        return null
    }

    /**
     * Watch-player flow: Inertia partial reload asking only for the streams prop.
     * Returns (embedUrl, qualityLabel). Tolerates missing XSRF (endpoint serves
     * the partial without it) but sends it when the landing page sets one.
     */
    private suspend fun resolveIframeStreams(iframeUrl: String, referer: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val reqHeaders = mapOf(
                "User-Agent" to httpService.userAgent,
                "Referer" to referer
            )

            val initialRes = httpService.getRaw(iframeUrl, headers = reqHeaders)
            val setCookies = initialRes.headers("Set-Cookie")
            val xsrfTokenDecoded = setCookies.find { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            val cookieHeaderString = setCookies.joinToString("; ") { it.substringBefore(";") }
            val initialText = try { initialRes.body?.string() ?: "" } catch (_: Exception) { "" } finally {
                try { initialRes.close() } catch (_: Exception) {}
            }

            val version = Regex("\"version\":\"([^\"]+)\"").find(initialText)?.groupValues?.get(1)
            if (version.isNullOrBlank()) {
                com.lagradost.api.Log.w(providerName, "No Inertia version (host dead?) $iframeUrl")
                return emptyList()
            }

            val inertiaHeaders = mutableMapOf(
                "X-Inertia" to "true",
                "X-Inertia-Version" to version,
                "X-Inertia-Partial-Component" to "files/mirror/video",
                "X-Inertia-Partial-Data" to "streams",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to iframeUrl,
                "Content-Type" to "application/json"
            )
            if (!xsrfTokenDecoded.isNullOrBlank()) inertiaHeaders["X-XSRF-TOKEN"] = xsrfTokenDecoded
            if (cookieHeaderString.isNotBlank()) inertiaHeaders["Cookie"] = cookieHeaderString

            val jsonRes = httpService.getRaw(iframeUrl, headers = inertiaHeaders)
            val jsonText = try { jsonRes.body?.string() ?: "" } catch (_: Exception) { "" } finally {
                try { jsonRes.close() } catch (_: Exception) {}
            }
            out.addAll(parseStreamsJson(jsonText))
        } catch (e: Exception) {
            com.lagradost.api.Log.e(providerName, "resolveIframeStreams failed: ${e.message}")
        }
        return out
    }

    /**
     * Download-page flow: GET {mirrorHost}/download/{hash} lists per-quality
     * attachments, then POST {attach} returns the mirrors for that quality.
     * Works even when the /iframe/ host embedded on tuktukhd is dead, because
     * the go.php pill points at a live front (share4max.org).
     */
    private suspend fun resolveDownloadMirrors(downloadPageUrl: String, referer: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val pageRes = httpService.getRaw(
                downloadPageUrl,
                headers = mapOf("User-Agent" to httpService.userAgent, "Referer" to referer)
            )
            val pageCookies = pageRes.headers("Set-Cookie")
            val xsrf = pageCookies.find { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            val cookieHeader = pageCookies.joinToString("; ") { it.substringBefore(";") }
            val pageText = try { pageRes.body?.string() ?: "" } catch (_: Exception) { "" } finally {
                try { pageRes.close() } catch (_: Exception) {}
            }

            val pageJson = Regex("<script data-page=\"app\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(pageText)?.groupValues?.get(1) ?: return emptyList()
            val version = Regex("\"version\":\"([^\"]+)\"").find(pageJson)?.groupValues?.get(1) ?: return emptyList()
            val attachments = try {
                org.json.JSONObject(pageJson).optJSONObject("props")
                    ?.optJSONArray("attachments")
            } catch (_: Exception) { null } ?: return emptyList()

            for (i in 0 until attachments.length()) {
                val attachHash = attachments.optJSONObject(i)?.optString("hashId")?.trim().orEmpty()
                val label = attachments.optJSONObject(i)?.optString("label").orEmpty()
                if (attachHash.isBlank()) continue

                try {
                    val body = org.json.JSONObject().put("attach", attachHash).toString()
                        .toRequestBody("application/json".toMediaType())
                    val request = okhttp3.Request.Builder()
                        .url(downloadPageUrl)
                        .header("X-Inertia", "true")
                        .header("X-Inertia-Version", version)
                        .header("X-Inertia-Partial-Component", "files/mirror/download")
                        .header("X-Inertia-Partial-Data", "download")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", httpService.userAgent)
                        .header("Referer", downloadPageUrl)
                        .apply {
                            if (!xsrf.isNullOrBlank()) header("X-XSRF-TOKEN", xsrf)
                            if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                        }
                        .post(body)
                        .build()
                    val jsonText = app.baseClient.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) "" else (res.body?.string() ?: "")
                    }
                    if (jsonText.isBlank()) continue
                    val mirrors = try {
                        org.json.JSONObject(jsonText).optJSONObject("props")
                            ?.optJSONObject("download")?.optJSONObject("data")
                            ?.optJSONArray("mirrors")
                    } catch (_: Exception) { null } ?: continue
                    for (j in 0 until mirrors.length()) {
                        val link = mirrors.optJSONObject(j)?.optString("link")?.trim().orEmpty()
                        if (link.isBlank()) continue
                        val finalLink = if (link.startsWith("//")) "https:$link" else link
                        if (!finalLink.startsWith("http")) continue
                        out.add(finalLink to label)
                    }
                } catch (e: Exception) {
                    com.lagradost.api.Log.d(providerName, "attach $label failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            com.lagradost.api.Log.e(providerName, "resolveDownloadMirrors failed: ${e.message}")
        }
        return out.distinctBy { it.first }
    }

    private fun parseStreamsJson(jsonText: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (jsonText.isBlank() || !jsonText.trimStart().startsWith("{")) return out
        try {
            val inertia = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(jsonText, InertiaResponse::class.java)
            val streams = inertia.props?.streams?.data
            if (streams != null) {
                for (stream in streams) {
                    val mirrors = stream.mirrors
                    if (mirrors != null) {
                        for (mirror in mirrors) {
                            val link = mirror.link
                            if (!link.isNullOrBlank()) {
                                val finalLink = if (link.startsWith("//")) "https:$link" else link
                                // label is not in the streams payload; keep empty
                                out.add(finalLink to "")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }
    
    private fun fixUrlLocally(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl/$url".replace("//", "/").replace("https:/", "https://")
    }
}
