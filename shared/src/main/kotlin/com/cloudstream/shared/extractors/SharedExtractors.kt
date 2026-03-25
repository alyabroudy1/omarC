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
}

class VidmolyBiz : com.lagradost.cloudstream3.extractors.Vidmoly() {
    override val mainUrl = "https://vidmoly.biz"
}
