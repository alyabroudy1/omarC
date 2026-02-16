package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

open class OkPrimeExtractor : ExtractorApi() {
    override val name = "OkPrime"
    override val mainUrl = "https://okprime.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val service = com.cloudstream.shared.service.ProviderHttpServiceHolder.getInstance()
            ?: throw IllegalStateException("ProviderHttpService not initialized")

        val customHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        if (referer != null) {
            customHeaders["Referer"] = referer
        }
        
        com.cloudstream.shared.logging.ProviderLogger.d("OkPrime", "getUrl", "Requesting URL", "url" to url, "referer" to customHeaders["Referer"])

        // Use getDocument to handle Cloudflare automatically
        val doc = service.getDocument(url, customHeaders)
        val text = doc?.outerHtml() ?: ""
        
        com.cloudstream.shared.logging.ProviderLogger.d("OkPrime", "getUrl", "Response received", 
            "success" to (doc != null),
            "length" to text.length
        )

        // Check for packed JS
        val packed = text.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
        
        val unpacked = if (packed.isNotBlank()) {
            com.cloudstream.shared.logging.ProviderLogger.d("OkPrime", "getUrl", "Packed JS found", "length" to packed.length)
            val un = JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
            com.cloudstream.shared.logging.ProviderLogger.d("OkPrime", "getUrl", "Unpacked JS", "length" to (un?.length ?: 0))
            un
        } else {
            com.cloudstream.shared.logging.ProviderLogger.d("OkPrime", "getUrl", "No packed JS found")
            text
        }

        // Extract sources
        // jwplayer("vplayer").setup({sources:[{file:"..."}]
        val sourcesRegex = Regex(""""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']"""")
        val match = sourcesRegex.find(unpacked ?: text)
        val videoUrl = match?.groupValues?.get(1)

        if (videoUrl != null) {
            com.cloudstream.shared.logging.ProviderLogger.i("OkPrime", "getUrl", "Found video URL", "videoUrl" to videoUrl)
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                }
            )
        } else {
            com.cloudstream.shared.logging.ProviderLogger.e("OkPrime", "getUrl", "Video URL regex failed")
        }
    }
}
