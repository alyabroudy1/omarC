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
        val response = app.get(url, headers = mapOf("Referer" to "https://laroza.cfd/"))
        val text = response.text
        
        // Check for packed JS
        val packed = text.substringAfter("eval(function(p,a,c,k,e,d)", "").substringBefore("</script>")
        val unpacked = if (packed.isNotBlank()) {
            JsUnpacker("eval(function(p,a,c,k,e,d)$packed").unpack()
        } else text

        // Extract sources
        // jwplayer("vplayer").setup({sources:[{file:"..."}]
        val sourcesRegex = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""")
        val match = sourcesRegex.find(unpacked ?: text)
        val videoUrl = match?.groupValues?.get(1)

        if (videoUrl != null) {
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
        }
    }
}
