package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SavefilesExtractor : ExtractorApi() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "SavefilesExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val safeUrl = url.replace("http://", "https://")
        ProviderLogger.d(TAG, "getUrl", "Processing", "url" to safeUrl.take(60), "referer" to (referer ?: "null"))

        try {
            // Use User-Agent and Cookies from SessionProvider if available
            val headers = mapOf(
                "User-Agent" to (com.cloudstream.shared.session.SessionProvider.getUserAgent() ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
                "Referer" to "https://savefiles.com/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )

            var response = app.get(safeUrl, headers = headers)
            var text = response.text
            
            ProviderLogger.d(TAG, "getUrl", "Response received", "code" to response.code, "length" to text.length)

            // CHECK FOR INTERSTITIAL FORM
            if (text.contains("action=\"/dl\"") || text.contains("action='/dl'")) {
                 ProviderLogger.d(TAG, "getUrl", "Interstitial detected, preparing POST request")
                 val doc = org.jsoup.Jsoup.parse(text)
                 val form = doc.selectFirst("form[action='/dl']") ?: doc.selectFirst("form[action=\"/dl\"]")
                 
                 if (form != null) {
                     val inputs = form.select("input[type=hidden]")
                     val postData = inputs.associate { it.attr("name") to it.attr("value") }.toMutableMap()
                     
                     // Ensure referer is set correctly for the form
                     if (postData["referer"].isNullOrBlank()) {
                         postData["referer"] = "https://savefiles.com/"
                     }
                     
                     ProviderLogger.d(TAG, "getUrl", "Posting to /dl", "data" to postData.toString())
                     
                     val postHeaders = headers.toMutableMap()
                     postHeaders["Referer"] = safeUrl
                     postHeaders["Content-Type"] = "application/x-www-form-urlencoded"
                     
                     val postResponse = app.post("https://savefiles.com/dl", headers = postHeaders, data = postData)
                     if (postResponse.code == 200) {
                         text = postResponse.text
                         ProviderLogger.d(TAG, "getUrl", "POST successful", "length" to text.length)
                     } else {
                         ProviderLogger.e(TAG, "getUrl", "POST failed", null, "code" to postResponse.code)
                     }
                 }
            }

            // OPTION 1: Direct Regex
            // ... (rest of logic)
            // Look for sources: [{file:"..."}]
            val sourcesRegex = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""")
            var match = sourcesRegex.find(text)
            var videoUrl = match?.groupValues?.get(1)

            // OPTION 2: Packed JS
            if (videoUrl == null) {
                ProviderLogger.d(TAG, "getUrl", "Direct regex failed, checking for packed JS")
                val packed = text.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
                if (packed.isNotBlank()) {
                    val fullPacked = "eval(function(p,a,c,k,e,d)$packed"
                    val unpacked = com.lagradost.cloudstream3.utils.JsUnpacker(fullPacked).unpack()
                    if (unpacked != null) {
                        ProviderLogger.d(TAG, "getUrl", "JS Unpacked successfully")
                        match = sourcesRegex.find(unpacked)
                        videoUrl = match?.groupValues?.get(1)
                    } else {
                        ProviderLogger.w(TAG, "getUrl", "Failed to unpack JS")
                    }
                }
            }

            if (videoUrl != null) {
                ProviderLogger.i(TAG, "getUrl", "Found video URL", "url" to videoUrl!!.take(80))
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl!!,
                        type = if (videoUrl!!.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = safeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                ProviderLogger.w(TAG, "getUrl", "No video URL found", "responsePreview" to text.take(500))
            }

        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting video", e)
        }
    }
}
