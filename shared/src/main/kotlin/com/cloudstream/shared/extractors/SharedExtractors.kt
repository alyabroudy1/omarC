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
    registerExtractorAPI(EarnVidsExtractor())
    
    val sniffer = SnifferExtractor()
    sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { com.cloudstream.shared.android.ActivityProvider.currentActivity }
    registerExtractorAPI(sniffer)
}
