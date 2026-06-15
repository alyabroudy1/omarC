package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.cloudstream.shared.logging.ProviderLogger
import org.jsoup.Jsoup

class CswruExtractor(
    private val host: String = "cswru.vid872.top",
    override val name: String = "Cswru"
) : ExtractorApi() {
    override val mainUrl get() = "https://$host"
    override val requiresReferer = true

    companion object {
        private const val TAG = "CswruExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val actualReferer = referer ?: "https://m.cimaleek.pw/"
        ProviderLogger.d(TAG, "getUrl", "Processing Cswru wrapper URL", "url" to url, "referer" to actualReferer)

        try {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to actualReferer
            )

            val response = app.get(url, headers = headers)
            val finalUrl = response.url
            
            // If it redirected to a different domain, delegate immediately
            if (finalUrl != url && !finalUrl.contains(host)) {
                ProviderLogger.d(TAG, "getUrl", "Redirected to a different domain, delegating to loadExtractor", "finalUrl" to finalUrl)
                loadExtractor(finalUrl, url, subtitleCallback, callback)
                return
            }

            val html = response.text
            if (html.isBlank()) {
                ProviderLogger.w(TAG, "getUrl", "Empty response from wrapper URL")
                return
            }

            val doc = Jsoup.parse(html, url)
            
            // Try to find the iframe source
            val iframeSrc = doc.selectFirst("iframe#embedr")?.let {
                val src = it.attr("src")
                if (src.startsWith("http")) src else it.absUrl("src")
            } ?: doc.selectFirst("iframe")?.let {
                val src = it.attr("src")
                if (src.startsWith("http")) src else it.absUrl("src")
            }

            if (!iframeSrc.isNullOrBlank()) {
                ProviderLogger.d(TAG, "getUrl", "Found iframe inside wrapper page, delegating to loadExtractor", "iframeSrc" to iframeSrc)
                loadExtractor(iframeSrc, url, subtitleCallback, callback)
                return
            }

            // Case 2: Check for AJAX redirect data block in javascript
            // var data = {"p_id":"...","link":"\/v2\/re.php?o=..."}
            val linkRegex = Regex(""""link"\s*:\s*"([^"]+)"""")
            val redirectLink = linkRegex.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!redirectLink.isNullOrBlank()) {
                val absoluteRedirectUrl = if (redirectLink.startsWith("http")) {
                    redirectLink
                } else {
                    val uri = java.net.URI(url)
                    "${uri.scheme}://${uri.host}$redirectLink"
                }
                ProviderLogger.d(TAG, "getUrl", "Found AJAX redirect link, delegating to loadExtractor", "redirectUrl" to absoluteRedirectUrl)
                loadExtractor(absoluteRedirectUrl, url, subtitleCallback, callback)
                return
            }

            ProviderLogger.w(TAG, "getUrl", "No iframe or redirect link found in wrapper HTML")
        } catch (e: Exception) {
            ProviderLogger.e(TAG, "getUrl", "Failed to extract from wrapper URL", e)
        }
    }
}
