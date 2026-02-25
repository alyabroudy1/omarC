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

    private fun decodeObfuscatedHtml(doc: Document): Document {
        val scriptData = doc.select("script").firstOrNull()?.data() ?: return doc
        if (!scriptData.contains("hide_my_HTML_")) {
            return doc
        }

        val hideMyHtmlContent = Regex("['+\\n\" ]")
            .replace(
                scriptData.substringAfter("var hide_my_HTML_").substring(3)
                    .substringAfter(" =").substringBeforeLast("';").trim(),
                ""
            )

        val lastNumber = Regex("-\\d+").findAll(scriptData)
            .lastOrNull()?.value?.toIntOrNull() ?: 0

        val decodedHtml1 = decodeObfuscatedString(hideMyHtmlContent, lastNumber)
        val encodedHtml = String(decodedHtml1.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return Jsoup.parse(encodedHtml)
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

    private suspend fun handleCima(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = httpService.getDocument(url) ?: return
            val decodedDoc = decodeObfuscatedHtml(doc)
            val urls = getParser().extractWatchServersUrls(decodedDoc)
            
            for (playerUrl in urls) {
                val playerUrlLower = playerUrl.lowercase()
                when {
                    playerUrlLower.contains("vidpro") -> handleVidPro(playerUrl, url, callback)
                    playerUrlLower.contains("govid") -> handleGovid(playerUrl, url, callback)
                    playerUrlLower.contains("vidlook") -> handleVidlook(playerUrl, url, callback)
                    playerUrlLower.contains("streamwish") -> handleStreamwish(playerUrl, url, callback)
                    playerUrlLower.contains("streamfile") -> handleStreamfile(playerUrl, url, callback)
                    playerUrlLower.contains("luluvid") -> handleLuluvid(playerUrl, url, callback)
                    playerUrlLower.contains("vadbam") -> handleVadbam(playerUrl, url, callback)
                    playerUrlLower.contains("viidshare") -> handleViidshare(playerUrl, url, callback)
                    playerUrlLower.contains("ok ru") || playerUrlLower.contains("okru") -> {
                        loadExtractor(playerUrl, url, {}, callback)
                    }
                    else -> {
                        loadExtractor(playerUrl, url, {}, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "handleCima: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // First try: Decode and extract using custom handlers
        try {
            httpService.ensureInitialized()
            
            val detailDoc = httpService.getDocument(data)
            if (detailDoc == null) {
                Log.e("Cimanow", "Failed to fetch document")
                return false
            }
            
            val decodedDoc = decodeObfuscatedHtml(detailDoc)
            val urls = getParser().extractWatchServersUrls(decodedDoc)
            Log.d("Cimanow", "Extracted ${urls.size} player URLs")
            
            if (urls.isNotEmpty()) {
                for (url in urls) {
                    val urlLower = url.lowercase()
                    val referer = "https://$baseDomain/"
                    
                    when {
                        urlLower.contains("vidpro") -> handleVidPro(url, referer, callback)
                        urlLower.contains("govid") -> handleGovid(url, referer, callback)
                        urlLower.contains("vidlook") -> handleVidlook(url, referer, callback)
                        urlLower.contains("streamwish") -> handleStreamwish(url, referer, callback)
                        urlLower.contains("streamfile") -> handleStreamfile(url, referer, callback)
                        urlLower.contains("luluvid") -> handleLuluvid(url, referer, callback)
                        urlLower.contains("vadbam") -> handleVadbam(url, referer, callback)
                        urlLower.contains("viidshare") -> handleViidshare(url, referer, callback)
                        urlLower.contains("/watching/") -> handleNet(url, referer, callback)
                        else -> {
                            try {
                                loadExtractor(url, referer, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.d("Cimanow", "loadExtractor failed for $url: ${e.message}")
                            }
                        }
                    }
                }
                return true
            }
        } catch (e: Exception) {
            Log.e("Cimanow", "loadLinks error: ${e.message}")
        }

        // Fallback to parent's extraction (with DRM handling)
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
