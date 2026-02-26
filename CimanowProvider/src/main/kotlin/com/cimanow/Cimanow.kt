package com.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.ParserInterface
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.ui.DrmPlayerDialog
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.util.Base64
import kotlin.text.Regex
import com.cloudstream.shared.service.ProviderHttpService

class Cimanow : BaseProvider() {

    override val providerName get() = "Cimanow"
    override val baseDomain get() = "cimanow.cc"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/cimanow.json"

    override val mainPage = mainPageOf(
        "/category/رمضان-2026" to "رمضان",
        "/احدث-الاضافات" to "احدث الاضافات",
        "/category/افلام-اجنبية" to "افلام اجنبية",
        "/category/مسلسلات-اجنبية" to "مسلسلات اجنبية",
        "/category/افلام-نتفليكس" to "افلام نتفليكس",
        "/category/مسلسلات-نتفليكس" to "مسلسلات نتفليكس",
        "/category/افلام-مارفل" to "افلام مارفل",
        "/category/مسلسلات-عربية" to "مسلسلات عربية",
        "/category/افلام-عربية" to "افلام عربية",
        "/category/برامج-تلفزيونية" to "برامج تلفزيونية"
    )

    override fun getParser(): NewBaseParser {
        return CimanowParser()
    }

    private data class SvgObject(val stream: String, val hash: String)

    override fun getSeasonName(seasonNum: Int): String = "الموسم $seasonNum"

    /**
     * Fetch seasons and episodes matching the original Braflix reference.
     * Seasons: section[aria-label=seasons] ul li a
     * Episodes per season: section[aria-label=details] ul#eps li (reversed)
     * Episode URL: episodeLink.select("a").attr("href")
     */
    override suspend fun fetchExtraEpisodes(
        doc: Document, url: String, data: ParserInterface.ParsedLoadData
    ): List<ParserInterface.ParsedEpisode> {
        val methodTag = "Cimanow"
        val episodes = mutableListOf<ParserInterface.ParsedEpisode>()
        
        try {
            // Decode the main series page
            val decodedDoc = decodeObfuscatedHtml(doc)
            
            // Get first season URL to start navigating seasons
            val firstSeasonUrl = decodedDoc.select("section[aria-label=seasons] ul li a").attr("href")
            Log.d(methodTag, "fetchExtraEpisodes: firstSeasonUrl=$firstSeasonUrl")
            
            if (firstSeasonUrl.isBlank()) {
                // No seasons section - maybe episodes are directly on the page
                val directEpisodes = decodedDoc.select("section[aria-label=details] ul#eps li")
                Log.d(methodTag, "fetchExtraEpisodes: direct episodes on page: ${directEpisodes.size}")
                directEpisodes.reversed().forEachIndexed { index, episodeLink ->
                    val epUrl = episodeLink.select("a").attr("href")
                    if (epUrl.isNotBlank()) {
                        episodes.add(ParserInterface.ParsedEpisode(
                            name = "الحلقة ${index + 1}",
                            url = epUrl,
                            season = 1,
                            episode = index + 1
                        ))
                    }
                }
                return episodes
            }
            
            // Fetch the first season page to get the season list
            val firstSeasonDoc = httpService.getDocument(firstSeasonUrl)
            if (firstSeasonDoc == null) {
                Log.e(methodTag, "fetchExtraEpisodes: Failed to fetch first season page")
                return episodes
            }
            
            val decodedFirstSeason = decodeObfuscatedHtml(firstSeasonDoc)
            val seasonElements = decodedFirstSeason.select("section[aria-label=seasons] ul li")
            Log.d(methodTag, "fetchExtraEpisodes: Found ${seasonElements.size} seasons")
            
            // Iterate through each season
            seasonElements.forEachIndexed { seasonIndex, season ->
                try {
                    val seasonUrl = season.select("a").attr("href")
                    val seasonNumber = seasonIndex + 1
                    Log.d(methodTag, "fetchExtraEpisodes: Season $seasonNumber URL=$seasonUrl")
                    
                    // Fetch season page (first season already fetched)
                    val seasonDoc = if (seasonIndex == 0) {
                        decodedFirstSeason
                    } else {
                        val fetchedDoc = httpService.getDocument(seasonUrl)
                        if (fetchedDoc != null) decodeObfuscatedHtml(fetchedDoc) else null
                    }
                    
                    if (seasonDoc == null) {
                        Log.e(methodTag, "fetchExtraEpisodes: Failed to fetch season $seasonNumber")
                        return@forEachIndexed
                    }
                    
                    // Extract episodes (reversed, matching original)
                    val episodeElements = seasonDoc.select("section[aria-label=details] ul#eps li").reversed()
                    Log.d(methodTag, "fetchExtraEpisodes: Season $seasonNumber has ${episodeElements.size} episodes")
                    
                    episodeElements.forEachIndexed { epIndex, episodeLink ->
                        val epUrl = episodeLink.select("a").attr("href")
                        val episodeNumber = epIndex + 1
                        if (epUrl.isNotBlank()) {
                            episodes.add(ParserInterface.ParsedEpisode(
                                name = "الحلقة $episodeNumber",
                                url = epUrl,
                                season = seasonNumber,
                                episode = episodeNumber
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "fetchExtraEpisodes: Error parsing season ${seasonIndex + 1}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(methodTag, "fetchExtraEpisodes error: ${e.message}")
        }
        
        Log.d(methodTag, "fetchExtraEpisodes: Total episodes found: ${episodes.size}")
        return episodes
    }

    private fun decodeObfuscatedHtml(doc: Document): Document {
        // Match reference: check doc.toString() for hide_my_HTML_ first
        val docString = doc.toString()
        if (!docString.contains("hide_my_HTML_")) {
            Log.d("Cimanow", "decodeObfuscatedHtml: No decoding needed")
            return doc
        }
        
        // Search ALL script elements for the one containing hide_my_HTML_
        // (reference uses doc.select("script").first() but the obfuscated script 
        // might not always be the first one)
        var scriptData: String? = null
        for (script in doc.select("script")) {
            val data = script.data()
            if (data.contains("hide_my_HTML_")) {
                scriptData = data
                break
            }
        }
        
        if (scriptData == null) {
            Log.d("Cimanow", "decodeObfuscatedHtml: hide_my_HTML_ in doc but not in any script data")
            return doc
        }
        
        Log.d("Cimanow", "decodeObfuscatedHtml: Found hide_my_HTML_ script, length=${scriptData.length}")

        try {
            val hideMyHtmlContent = Regex("['+\\n\" ]")
                .replace(
                    scriptData.substringAfter("var hide_my_HTML_").substring(3)
                        .substringAfter(" =").substringBeforeLast("';").trim(),
                    ""
                )

            val lastNumber = Regex("-\\d+").findAll(scriptData)
                .lastOrNull()?.value?.toIntOrNull() ?: 0
            
            Log.d("Cimanow", "decodeObfuscatedHtml: contentLength=${hideMyHtmlContent.length}, lastNumber=$lastNumber")

            val decodedHtml1 = decodeObfuscatedString(hideMyHtmlContent, lastNumber)
            val encodedHtml = String(decodedHtml1.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            val result = Jsoup.parse(encodedHtml)
            Log.d("Cimanow", "decodeObfuscatedHtml: Decoded HTML length=${encodedHtml.length}")
            return result
        } catch (e: Exception) {
            Log.e("Cimanow", "decodeObfuscatedHtml error: ${e.message}")
            return doc
        }
    }

    private fun decodeObfuscatedString(concatenated: String, lastNumber: Int): String {
        val output = StringBuilder()
        var start = 0
        val length = concatenated.length

        for (i in 0 until length) {
            if (concatenated[i] == '.') {
                val segment = concatenated.substring(start, i)
                decodeAndAppend(output, lastNumber, segment)
                start = i + 1
            }
        }
        val lastSegment = concatenated.substring(start)
        decodeAndAppend(output, lastNumber, lastSegment)
        return output.toString()
    }

    private fun decodeAndAppend(output: StringBuilder, lastNumber: Int, segment: String) {
        try {
            val decoded = String(Base64.getDecoder().decode(segment), Charsets.UTF_8)
            val digits = decoded.filter { it.isDigit() }
            if (digits.isNotEmpty()) {
                output.append((digits.toInt() + lastNumber).toChar())
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "Error decoding: ${e.message}")
        }
    }

    private fun runJS(script: String): String {
        val rhino = Context.enter()
        rhino.initSafeStandardObjects()
        rhino.optimizationLevel = -1
        val scope = rhino.initSafeStandardObjects()
        scope.put("window", scope, scope)

        var result = ""
        try {
            rhino.evaluateString(scope, script, "JavaScript", 1, null)
            val svgObject = scope.get("svg", scope)
            result = if (svgObject is NativeObject) {
                NativeJSON.stringify(Context.getCurrentContext(), scope, svgObject, null, null).toString()
            } else {
                Context.toString(svgObject)
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "JS Error: ${e.message}")
        } finally {
            Context.exit()
        }
        return result
    }

    private fun sigDecode(url: String): String {
        val sig = url.substringAfter("sig=").substringBefore("&")
        var t = ""
        for (v in sig.chunked(2)) {
            val byteValue = Integer.parseInt(v, 16) xor 2
            t += byteValue.toChar()
        }
        val padding = when (t.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        val decoded = String(Base64.getDecoder().decode((t + padding).toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        val t2 = decoded.dropLast(5).reversed()
        val charArray = t2.toCharArray()
        for (i in 0 until charArray.size - 1 step 2) {
            val temp = charArray[i]
            charArray[i] = charArray[i + 1]
            charArray[i + 1] = temp
        }
        val modifiedSig = String(charArray).dropLast(5)
        return url.replace(sig, modifiedSig)
    }

    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    private suspend fun handleVidPro(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val packed = html.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
            val unpacked = if (packed.isNotBlank()) {
                JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
            } else null

            val videoUrl = unpacked?.let {
                Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            } ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("VidPro", "VidPro", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleVidPro: ${e.message}")
        }
    }

    private suspend fun handleGovid(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Govid", "Govid", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleGovid: ${e.message}")
        }
    }

    private suspend fun handleVidlook(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val packed = html.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
            val unpacked = if (packed.isNotBlank()) {
                JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
            } else null

            val videoUrl = unpacked?.let {
                Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            } ?: Regex("""file:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Vidlook", "Vidlook", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleVidlook: ${e.message}")
        }
    }

    private suspend fun handleStreamwish(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Streamwish", "Streamwish", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleStreamwish: ${e.message}")
        }
    }

    private suspend fun handleStreamfile(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Streamfile", "Streamfile", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleStreamfile: ${e.message}")
        }
    }

    private suspend fun handleLuluvid(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Luluvid", "Luluvid", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleLuluvid: ${e.message}")
        }
    }

    private suspend fun handleVadbam(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val packed = html.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
            val unpacked = if (packed.isNotBlank()) {
                JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
            } else null

            val videoUrl = unpacked?.let {
                Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            } ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Vadbam", "Vadbam", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleVadbam: ${e.message}")
        }
    }

    private suspend fun handleViidshare(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val videoUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback(newExtractorLink("Viidshare", "Viidshare", videoUrl, type = getLinkType(videoUrl)) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleViidshare: ${e.message}")
        }
    }

    private suspend fun handleNet(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val html = doc.outerHtml()
            
            val scriptWithEval = html.substringAfter("eval(", "").substringBefore("</script>")
            if (scriptWithEval.isBlank()) return

            val jsResult = runJS("eval($scriptWithEval)")
            val mapper = jacksonObjectMapper()
            val jsonNode = mapper.readTree(jsResult)
            val streamUrl = jsonNode["stream"]?.asText() ?: return
            val watchLink = sigDecode(streamUrl)

            callback(newExtractorLink("Net", "Net", watchLink, type = ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            })
        } catch (e: Exception) {
            Log.e("Cimanow", "handleNet: ${e.message}")
        }
    }

    private suspend fun handleCima(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("Cimanow", "handleCima: $iframeUrl")
            val domain = "https://" + java.net.URL(iframeUrl).host
            val referer = "https://$baseDomain/"
            
            val doc = httpService.getDocument(iframeUrl, headers = mapOf("Referer" to referer)) ?: return
            Log.d("Cimanow", "handleCima doc fetched")
            
            // Original Braflix selects <source> elements for direct video links
            doc.select("source").forEach { element ->
                val fileLink = element.attr("src")
                val quality = element.attr("size").toIntOrNull() ?: Qualities.Unknown.value
                if (fileLink.isNotBlank()) {
                    val fullUrl = if (fileLink.startsWith("http")) fileLink else domain + fileLink
                    callback(newExtractorLink(name, name, fullUrl, type = getLinkType(fullUrl)) {
                        this.referer = iframeUrl
                        this.quality = quality
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleCima error: ${e.message}")
        }
    }

    // VidGuard-family domains that use the eval/sigDecode pattern
    private val vidGuardDomains = listOf(
        "vidguard", "vgfplay", "vgembed", "moflix-stream", 
        "v6embed", "vid-guard", "vembed", "embedv", "fslinks"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "Cimanow"
        try {
            httpService.ensureInitialized()
            
            // Step 1: Construct watch URL (original: data + "watching/")
            val watchUrl = if (data.endsWith("/")) "${data}watching/" else "$data/watching/"
            Log.d(methodTag, "loadLinks: watchUrl=$watchUrl")
            
            // Step 2: Fetch and decode the watching page
            val watchDoc = httpService.getDocument(watchUrl)
            if (watchDoc == null) {
                Log.e(methodTag, "Failed to fetch watching page")
                return fallbackLoadLinks(data, isCasting, subtitleCallback, callback)
            }
            
            val doc = decodeObfuscatedHtml(watchDoc)
            Log.d(methodTag, "Decoded watching doc")
            
            // Debug: log what's in the decoded page
            val allUls = doc.select("ul")
            Log.d(methodTag, "DEBUG: Found ${allUls.size} <ul> elements")
            for (ul in allUls.take(10)) {
                val className = ul.className()
                val childCount = ul.children().size
                Log.d(methodTag, "DEBUG: <ul class='$className'> children=$childCount")
            }
            val allLis = doc.select("li[data-index], li[data-id]")
            Log.d(methodTag, "DEBUG: Found ${allLis.size} <li> with data-index/data-id")
            for (li in allLis.take(5)) {
                Log.d(methodTag, "DEBUG: <li data-index='${li.attr("data-index")}' data-id='${li.attr("data-id")}'>${li.text().take(50)}")
            }
            val allIframes = doc.select("iframe")
            Log.d(methodTag, "DEBUG: Found ${allIframes.size} iframes")
            // Also log a snippet of the decoded HTML
            val htmlSnippet = doc.outerHtml().take(500)
            Log.d(methodTag, "DEBUG: HTML snippet: $htmlSnippet")
            
            // Step 3: Select server tabs (original: ul.tabcontent li)
            // Also try alternative selectors in case the class name differs
            var serverElements = doc.select("ul.tabcontent li")
            Log.d(methodTag, "Found ${serverElements.size} server elements (ul.tabcontent li)")
            
            if (serverElements.isEmpty()) {
                // Try broader selectors
                serverElements = doc.select("li[data-index][data-id]")
                Log.d(methodTag, "Trying li[data-index][data-id]: found ${serverElements.size}")
            }
            
            if (serverElements.isEmpty()) {
                // Fallback: try extracting servers directly from the page
                Log.d(methodTag, "No ul.tabcontent li found, trying direct extraction")
                return fallbackLoadLinks(data, isCasting, subtitleCallback, callback)
            }
            
            // Step 4: For each server, AJAX call to core.php and dispatch
            for (element in serverElements) {
                try {
                    val dataIndex = element.attr("data-index")
                    val dataId = element.attr("data-id")
                    val serverName = element.text().trim()
                    Log.d(methodTag, "Server: name='$serverName', index=$dataIndex, id=$dataId")
                    
                    // AJAX call to core.php (original: mainUrl + /wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=X&id=Y)
                    val ajaxUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"
                    val playerDoc = httpService.getDocument(ajaxUrl, headers = mapOf("Referer" to watchUrl))
                    
                    if (playerDoc == null) {
                        Log.w(methodTag, "Failed to fetch player for server '$serverName'")
                        continue
                    }
                    
                    // Get iframe URL from AJAX response
                    val iframeUrl = playerDoc.select("iframe").attr("src")
                    Log.d(methodTag, "Server '$serverName' -> iframe: $iframeUrl")
                    
                    // Check if element itself has an iframe (okframe fallback)
                    val elementIframe = element.select("iframe").first()
                    val okframe = if (elementIframe != null) {
                        "https:" + element.select("iframe").attr("src")
                    } else {
                        iframeUrl
                    }
                    
                    // Check if it's a VidGuard domain -> handleNet (eval/sigDecode)
                    val isVidGuard = vidGuardDomains.any { iframeUrl.contains(it, ignoreCase = true) }
                    
                    if (isVidGuard) {
                        // VidGuard domains use eval + sigDecode
                        try {
                            Log.d(methodTag, "VidGuard detected: $iframeUrl")
                            handleNet(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in VidGuard: ${e.message}")
                        }
                    } else if (okframe.isNotBlank() && okframe.contains("ok", ignoreCase = true)) {
                        // ok.ru -> loadExtractor
                        try {
                            loadExtractor(okframe, watchUrl, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in okframe: ${e.message}")
                        }
                    } else if (serverName.contains("Cima Now", ignoreCase = true)) {
                        try {
                            handleCima(iframeUrl, serverName, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in Cima Now: ${e.message}")
                        }
                    } else if (serverName.contains("VidPro", ignoreCase = true)) {
                        try {
                            handleVidPro(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in VidPro: ${e.message}")
                        }
                    } else if (serverName.contains("Govid", ignoreCase = true) || serverName.contains("Goovid", ignoreCase = true)) {
                        try {
                            handleGovid(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in Govid: ${e.message}")
                        }
                    } else if (serverName.contains("Vidlook", ignoreCase = true)) {
                        try {
                            handleVidlook(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in Vidlook: ${e.message}")
                        }
                    } else if (serverName.contains("Streamwish", ignoreCase = true)) {
                        try {
                            handleStreamwish(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in Streamwish: ${e.message}")
                        }
                    } else if (serverName.contains("Streamfile", ignoreCase = true) || serverName.contains("Luluvid", ignoreCase = true)) {
                        try {
                            handleStreamfile(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in Streamfile/Luluvid: ${e.message}")
                        }
                    } else if (serverName.contains("Vadbam", ignoreCase = true) || serverName.contains("Viidshare", ignoreCase = true)) {
                        try {
                            handleVadbam(iframeUrl, watchUrl, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in Vadbam/Viidshare: ${e.message}")
                        }
                    } else if (serverName.contains("upload", ignoreCase = true) || iframeUrl.contains("uqload.io", ignoreCase = true)) {
                        try {
                            loadExtractor(iframeUrl, watchUrl, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in upload: ${e.message}")
                        }
                    } else {
                        // Else: try download links from element + loadExtractor fallback
                        try {
                            element.select("a").forEach { link ->
                                val dlink = link.attr("href")
                                val quality = link.text().let { text ->
                                    Regex("\\d+").find(text)?.value?.toIntOrNull()
                                } ?: Qualities.Unknown.value
                                if (dlink.isNotBlank() && dlink.startsWith("http")) {
                                    Log.d(methodTag, "Download link: quality=$quality url=$dlink")
                                    callback(newExtractorLink(name, "Download Server", dlink, type = getLinkType(dlink)) {
                                        this.referer = mainUrl
                                        this.quality = quality
                                    })
                                }
                            }
                            loadExtractor(iframeUrl, watchUrl, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e(methodTag, "Error in else: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "Error processing server: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(methodTag, "loadLinks error: ${e.message}")
        }
        
        return fallbackLoadLinks(data, isCasting, subtitleCallback, callback)
    }
    
    /**
     * Fallback that delegates to BaseProvider.loadLinks with DRM handling.
     */
    private suspend fun fallbackLoadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var handledByDialog = false
        val interceptingCallback: (ExtractorLink) -> Unit = { link ->
            if (link.name.contains("(DRM Protected)") || (link.referer?.contains("shahid.net") == true && link.url.contains(".mpd"))) {
                handledByDialog = true
                ActivityProvider.currentActivity?.let { activity ->
                    activity.runOnUiThread {
                        val dialogUrl = link.referer ?: link.url
                        DrmPlayerDialog(activity, dialogUrl, link.referer).show()
                    }
                }
            } else {
                callback(link)
            }
        }
        
        return super.loadLinks(data, isCasting, subtitleCallback, interceptingCallback) || handledByDialog
    }
}
