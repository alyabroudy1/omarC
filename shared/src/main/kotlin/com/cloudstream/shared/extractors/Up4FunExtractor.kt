package com.cloudstream.shared.extractors

import com.cloudstream.shared.logging.ProviderLogger
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay

/**
 * Fixed Up4Fun/Up4Stream extractor that properly passes referer in HTTP requests.
 * 
 * Original CloudStream extractor has a bug:
 * - It receives referer parameter but never uses it in app.get()/app.post()
 * - This causes 403 Forbidden errors
 * 
 * This version fixes that by including Referer header in all requests.
 */
class Up4FunExtractor : ExtractorApi() {
    override val name = "Up4Fun"
    override val mainUrl = "https://up4fun.top"
    override val requiresReferer = true
    
    companion object {
        private const val TAG = "Up4FunExtractor"
    }
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ProviderLogger.d(TAG, "getUrl", "Processing", "url" to url.take(60), "referer" to (referer?.take(40) ?: "null"))
        
        val movieId = url.substringAfterLast("/").substringBefore(".html")
        
        try {
            // FIXED: Include Referer header in GET request
            val redirectResponse = app.get(
                url,
                cookies = mapOf("id" to movieId),
                headers = mapOf(
                    "Referer" to (referer ?: mainUrl),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
            
            val redirectForm = redirectResponse.document.selectFirst("form[method=POST]")
            if (redirectForm == null) {
                ProviderLogger.w(TAG, "getUrl", "No redirect form found on page")
                return
            }
            
            val redirectUrl = fixUrl(redirectForm.attr("action"))
            val redirectParams = redirectForm.select("input[type=hidden]").associate { input ->
                input.attr("name") to input.attr("value")
            }
            
            ProviderLogger.d(TAG, "getUrl", "Waiting 5s for redirect...")
            
            // Wait for 5 seconds (required by up4fun)
            delay(5000)
            
            // FIXED: Include Referer header in POST request
            val response = app.post(
                redirectUrl, 
                data = redirectParams,
                headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl
                )
            ).document
            
            val extractedPack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            if (extractedPack == null) {
                ProviderLogger.e(TAG, "getUrl", "Packed script not found - delay may be too short")
                return
            }
            
            val unpacked = JsUnpacker(extractedPack).unpack()
            if (unpacked == null) {
                ProviderLogger.e(TAG, "getUrl", "Failed to unpack script")
                return
            }
            
            val videoUrl = Regex("""sources:\[\{file:"(.*?)"""").find(unpacked)?.groupValues?.get(1)
            if (videoUrl == null) {
                ProviderLogger.e(TAG, "getUrl", "No video URL in unpacked script")
                return
            }
            
            ProviderLogger.d(TAG, "getUrl", "Found video URL", "url" to videoUrl.take(80))
            
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url  // Use the embed URL as referer for video playback
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl
                    )
                }
            )
            
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Error extracting video", e)
        }
    }
}
