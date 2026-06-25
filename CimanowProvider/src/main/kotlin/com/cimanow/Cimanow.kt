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
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import android.util.Base64
import kotlin.text.Regex
import com.cloudstream.shared.service.ProviderHttpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



class Cimanow : BaseProvider() {

    override var name = "سيما ناو"
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
        try {
            // Extract all inline script bodies to avoid breaking when obfuscation keys change every day
            val inlineScripts = doc.select("script:not([src])")
                .map { it.data() } // Jsoup `.data()` perfectly extracts the text content inside `<script>`
                .filter { it.isNotBlank() }
            
            if (inlineScripts.isEmpty()) return doc
            
            // Try/Catch each script block independently so one failing script doesn't crash the decoder
            val combinedJsCode = inlineScripts.joinToString("\n") { 
                "try { \n$it\n } catch(e) {}" 
            }

            val scriptToRun = """
                var __cimanow_written = "";
                document.write = function(str) { __cimanow_written += str; };
                document.open = function() {};
                document.close = function() {};
                
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                window.atob = function(input) {
                    var str = String(input).replace(/=+${'$'}/, '');
                    var output = '';
                    for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                        buffer = chars.indexOf(buffer);
                    }
                    return output;
                };
                
                // Prevent anti-bot redirects
                window.location.replace = function() {};
                window.location.assign = function() {};
                
                $combinedJsCode
                
                __cimanow_written;
            """.trimIndent()
            
            var evaluatedResult = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val activity = com.cloudstream.shared.android.ActivityProvider.currentActivity
                    if (activity != null) {
                        val webView = android.webkit.WebView(activity)
                        webView.settings.javaScriptEnabled = true
                        webView.evaluateJavascript(scriptToRun) { result ->
                            try {
                                evaluatedResult = if (result == null || result == "null") "" 
                                                  else org.json.JSONTokener(result).nextValue().toString()
                            } catch (e: Exception) {
                                Log.e("Cimanow", "JSON parse error: ${e.message}")
                            }
                            webView.destroy()
                            latch.countDown()
                        }
                    } else {
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    latch.countDown()
                }
            }
            
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)

            if (evaluatedResult.isNotBlank() && (evaluatedResult.contains("<ul") || evaluatedResult.contains("<li") || evaluatedResult.contains("<div"))) {
                Log.d("Cimanow", "Successfully decoded HTML using sandboxed WebView JS evaluator.")
                return Jsoup.parse(evaluatedResult)
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "JS decode format error: ${e.message}")
        }
        return doc
    }

    private fun evalJS(script: String): String? {
        val rhino = Context.enter()
        try {
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            val result = rhino.evaluateString(scope, script, "JavaScript", 1, null)
            return Context.toString(result)
        } catch (e: Exception) {
            Log.e("Cimanow", "evalJS Error: ${e.message}")
            return null
        } finally {
            Context.exit()
        }
    }

    private fun extractRandTokens(js: String): List<String> {
        val stringRegex = Regex("""["']([^"'\\]*(?:\\.[^"'\\]*)*)["']""")
        val matches = stringRegex.findAll(js)
        val tokens = mutableSetOf<String>()
        val md5Regex = Regex("""^[a-f0-9]{32}$""")

        for (match in matches) {
            val str = match.groupValues[1]
            if (str.length == 32) {
                for (key in 1..255) {
                    var xored = ""
                    for (i in str.indices) {
                        xored += (str[i].code xor key).toChar()
                    }
                    if (md5Regex.matches(xored)) {
                        tokens.add(xored)
                    }
                }
            }
        }
        return tokens.toList()
    }

    private fun decryptWatchHtml(html: String): String? {
        try {
            // 1. Extract all obfuscated string chunks matching the base64 format containing '~'
            val chunkRegex = Regex("""['"]([A-Za-z0-9+/=]+(?:~[A-Za-z0-9+/=]+)+)['"]""")
            val matches = chunkRegex.findAll(html)
            val sb = StringBuilder()
            for (match in matches) {
                sb.append(match.groupValues[1])
            }
            val obfuscatedData = sb.toString().replace(Regex("""[\r\n\t'+\s]"""), "")
            if (obfuscatedData.isBlank()) {
                Log.e("Cimanow", "obfuscatedData is blank")
                return null
            }

            // 2. Extract _r formula or literal subtraction
            var rValue: Int? = null
            
            val rMatch = Regex("""var\s+_r\s*=\s*([\d\s+\-*\/()]+);""").find(html)
            if (rMatch != null) {
                val formula = rMatch.groupValues[1]
                rValue = try {
                    evalJS(formula)?.toDoubleOrNull()?.toInt()
                } catch (e: Exception) {
                    null
                } ?: formula.split(Regex("""\D+""")).filter { it.isNotBlank() }.sumOf { it.trim().toInt() }
            } else {
                // Try to find the literal subtraction in the loop, e.g. - 87653);
                val literalMatch = Regex("""-\s*(\d+)\s*\)\s*;\s*\}\s*\)\s*;""").find(html)
                    ?: Regex("""-\s*(\d+)\s*\)\s*;\s*\}\s*\)\s*;\s*document\.open""").find(html)
                    ?: Regex("""-\s*(\d+)\s*\)\s*;""").find(html)
                if (literalMatch != null) {
                    rValue = literalMatch.groupValues[1].toIntOrNull()
                }
            }

            if (rValue == null) {
                Log.e("Cimanow", "Failed to resolve decryption offset value")
                return null
            }
            Log.d("Cimanow", "Resolved decryption offset: $rValue")

            // 3. Decrypt tokens
            val tokens = obfuscatedData.split("~")
            val outputStream = java.io.ByteArrayOutputStream()

            for (token in tokens) {
                if (token.isEmpty()) continue
                try {
                    var padded = token
                    while (padded.length % 4 != 0) {
                        padded += "="
                    }
                    val base64DecodedBytes = Base64.decode(padded, Base64.DEFAULT)
                    val decodedStr = String(base64DecodedBytes, Charsets.UTF_8)
                    val digitsOnly = decodedStr.replace(Regex("""\D"""), "")
                    if (digitsOnly.isEmpty()) continue

                    val num = digitsOnly.toInt() - rValue
                    outputStream.write(num)
                } catch (e: Exception) {
                    // Ignore malformed tokens
                }
            }

            return String(outputStream.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("Cimanow", "Decryption error: ${e.message}")
            return null
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
        val decoded = String(Base64.decode((t + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT), Charsets.UTF_8)
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
            for (element in doc.select("source")) {
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
        var linksCount = 0
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            linksCount++
            callback(link)
        }

        try {
            httpService.ensureInitialized()
            Log.d(methodTag, "[loadLinks] START data='$data'")

            // Step 1: Fetch episode page to capture Cloudflare cookies and find post_id
            val episodeHtml = httpService.getText(data) ?: ""
            val postIdMatch = Regex("""<link\s+rel=['"]shortlink['"]\s+href=['"][^'"]*\?p=(\d+)['"]""").find(episodeHtml)
            val postId = postIdMatch?.groupValues?.get(1) ?: Regex("post-(\\d+)").find(episodeHtml)?.groupValues?.get(1)
            Log.d(methodTag, "[loadLinks] Extracted post_id=$postId")

            // Step 2: Fetch the watch page
            val watchUrl = if (data.endsWith("/")) "${data}watching/" else "$data/watching/"
            Log.d(methodTag, "[loadLinks] Fetching watch URL: $watchUrl")
            
            val watchHtml = httpService.getText(watchUrl, headers = mapOf("Referer" to data))
            var decryptedJs = ""
            if (!watchHtml.isNullOrBlank()) {
                decryptedJs = decryptWatchHtml(watchHtml) ?: ""
            }

            var finalHtml = watchHtml ?: ""

            // Step 3: Extract rand tokens and call API if post_id is available
            if (postId != null && decryptedJs.isNotBlank()) {
                val randTokens = extractRandTokens(decryptedJs)
                Log.d(methodTag, "[loadLinks] Found ${randTokens.size} rand tokens")
                
                for (rand in randTokens) {
                    val apiUrl = "$mainUrl/wp-json/direct_download/v1/"
                    try {
                        val responseText = httpService.postText(
                            url = apiUrl,
                            headers = mapOf(
                                "Content-Type" to "application/x-www-form-urlencoded",
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest"
                            ),
                            referer = watchUrl,
                            data = mapOf("p" to postId, "rand" to rand)
                        )
                        Log.d(methodTag, "[loadLinks] direct_download response for rand=$rand length: ${responseText?.length ?: 0}")
                        if (!responseText.isNullOrBlank()) {
                            finalHtml = responseText
                            // Unescape JSON if needed
                            if (finalHtml.trim().startsWith("{")) {
                                try {
                                    val json = org.json.JSONObject(finalHtml)
                                    finalHtml = json.optString("html", finalHtml)
                                } catch (e: Exception) {}
                            }
                            Log.d(methodTag, "[loadLinks] direct_download returned valid payload")
                            break // Found the right rand token!
                        }
                    } catch (e: Exception) {
                        Log.e(methodTag, "[loadLinks] direct_download API error: ${e.message}")
                    }
                }
            } else {
                Log.e(methodTag, "[loadLinks] Fallback to WebView decryption (no post_id or JS)")
                if (decryptedJs.isNotBlank()) {
                    val decoded = decodeObfuscatedHtml(Jsoup.parse(decryptedJs))
                    finalHtml = decoded.outerHtml()
                }
            }

            if (finalHtml.isBlank()) {
                Log.e(methodTag, "[loadLinks] Final HTML is blank")
                return fallbackLoadLinks(data, isCasting, subtitleCallback, callback)
            }

            val watchDoc = Jsoup.parse(finalHtml)

            // Step 4: Extract iframes from watch document
            val watchSection = watchDoc.select("ul.tabcontent#watch li[aria-label='embed'] iframe, #watch li[aria-label='embed'] iframe, iframe")
            Log.d(methodTag, "[loadLinks] Decrypted watch section iframes=${watchSection.size}")
            for (iframe in watchSection) {
                try {
                    val iframeSrc = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                    if (iframeSrc.isBlank() || iframeSrc.contains("youtube.com")) continue
                    Log.d(methodTag, "[loadLinks] Found watch iframe: $iframeSrc")
                    val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                    try {
                        loadExtractor(fullIframeUrl, watchUrl, subtitleCallback, countingCallback)
                    } catch (e: Exception) {
                        // Fallback: sniff it
                        val snifferUrl = com.cloudstream.shared.extractors.SnifferExtractor.createSnifferUrl(fullIframeUrl, referer = watchUrl)
                        loadExtractor(snifferUrl, watchUrl, subtitleCallback, countingCallback)
                    }
                } catch (e: Exception) {
                    Log.e(methodTag, "[loadLinks] Error processing watch iframe: ${e.message}")
                }
            }

            // Step 4: Extract download links
            val downloadLinks = watchDoc.select("li[aria-label='quality'] a[href]")
            Log.d(methodTag, "[loadLinks] Found ${downloadLinks.size} download links")
            for (dl in downloadLinks) {
                try {
                    val dlUrl = dl.attr("href")
                    if (dlUrl.isBlank() || !dlUrl.startsWith("http")) continue
                    val dlText = dl.text().trim()
                    val quality = Regex("(\\d{3,4})p?").find(dlText)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                    Log.d(methodTag, "[loadLinks] Download: quality=$quality url=$dlUrl")
                    countingCallback(newExtractorLink(providerName, "Cimanow تحميل", dlUrl, type = getLinkType(dlUrl)) {
                        this.referer = watchUrl
                        this.quality = quality
                    })
                } catch (e: Exception) {
                    Log.e(methodTag, "[loadLinks] Error processing download link: ${e.message}")
                }
            }

            Log.d(methodTag, "[loadLinks] Direct links found: $linksCount")
            if (linksCount > 0) return true
        } catch (e: Exception) {
            Log.e(methodTag, "[loadLinks] error: ${e.message}")
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
