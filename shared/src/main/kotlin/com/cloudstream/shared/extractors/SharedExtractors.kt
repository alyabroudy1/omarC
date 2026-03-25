package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.plugins.Plugin

/**
 * An extension function to easily register all shared extractors at once
 * inside the respective Provider's Plugin.kt file.
 */
fun Plugin.registerSharedExtractors() {
    registerExtractorAPI(ReviewRateExtractor())
    registerExtractorAPI(SavefilesExtractor())
    registerExtractorAPI(OkPrimeExtractor())
    registerExtractorAPI(Up4FunExtractor())
    registerExtractorAPI(FaselHDExtractor())
    
    // EarnVids and its proxies
    registerExtractorAPI(EarnVidsExtractor())
    registerExtractorAPI(EarnVidsExtractor("dingtezuni.com"))
    registerExtractorAPI(EarnVidsExtractor("fsdcmo.sbs", "StreamGH"))
    registerExtractorAPI(EarnVidsExtractor("govid.live", "GoVid"))
    registerExtractorAPI(EarnVidsExtractor("1vid1shar.space", "Vid1Shar"))
    registerExtractorAPI(EarnVidsExtractor("mycima.page", "MyCima"))
    
    val sniffer = SnifferExtractor()
    sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { com.cloudstream.shared.android.ActivityProvider.currentActivity }
    registerExtractorAPI(sniffer)
    // Vidmoly Proxies
    registerExtractorAPI(VidmolyNet())
    registerExtractorAPI(VidmolyBiz())
}

class VidmolyNet : com.lagradost.cloudstream3.extractors.Vidmoly() {
    override val mainUrl = "https://vidmoly.net"
    override val name = "VidmolyNet"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ) {
        android.util.Log.d("VidmolyNet", "getUrl: Processing $url")
        try {
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            android.util.Log.e("VidmolyNet", "getUrl: Error processing $url: ${e.message}")
            throw e
        }
    }
}

class VidmolyBiz : com.lagradost.cloudstream3.extractors.Vidmoly() {
    override val mainUrl = "https://vidmoly.biz"
    override val name = "VidmolyBiz"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ) {
        android.util.Log.d("VidmolyBiz", "getUrl: Processing $url")
        try {
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            android.util.Log.e("VidmolyBiz", "getUrl: Error processing $url: ${e.message}")
            throw e
        }
    }
}
