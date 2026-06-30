package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.plugins.Plugin

/**
 * An extension function to easily register all shared extractors at once
 * inside the respective Provider's Plugin.kt file.
 */
fun Plugin.registerSharedExtractors() {
    registerExtractorAPI(ReviewRateExtractor())
    registerExtractorAPI(GameHubExtractor())
    registerExtractorAPI(SavefilesExtractor())
    registerExtractorAPI(OkPrimeExtractor())
    registerExtractorAPI(Up4FunExtractor())
    registerExtractorAPI(FaselHDExtractor())
    android.util.Log.d("SharedExtractors", "Registering VidobaExtractor...")
    registerExtractorAPI(VidobaExtractor())
    android.util.Log.d("SharedExtractors", "VidobaExtractor registered successfully")
    registerExtractorAPI(VertyuzExtractor())
    registerExtractorAPI(CswruExtractor())
    
    // Videa.hu extractor (moved from Animerco)
    registerExtractorAPI(VideaExtractor())
    
    // Mail.ru extractor (moved from Animerco)
    registerExtractorAPI(MailruExtractor())
    
    // Bysezejataos (API-based extraction with AES decryption)
    registerExtractorAPI(ByseExtractor("bysezejataos.com", "Bysezejataos"))
    registerExtractorAPI(ByseExtractor("bysezezj.com", "Bysezezj"))
    registerExtractorAPI(ByseExtractor("bysejetz.com", "Bysejetz"))
    registerExtractorAPI(ByseExtractor("bysezataos.com", "Bysezataos"))
    registerExtractorAPI(ByseExtractor("byseztajaos.com", "Byseztajaos"))
    registerExtractorAPI(ByseExtractor("byseztajos.com", "Byseztajos"))
    
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
    registerExtractorAPI(VidmolyExtractor("vidmoly.net", "VidmolyNet"))
    registerExtractorAPI(VidmolyExtractor("vidmoly.biz", "VidmolyBiz"))

    // Luluvid
    registerExtractorAPI(LuluvidExtractor())

    // Arab HD / estream (eval-packed JS extraction from eseek/qeseh)
    registerExtractorAPI(ArabHdExtractor())
    registerExtractorAPI(EstreamExtractor())

    // Laroza embed domains (CF-protected — uses httpService CF bypass)
    registerExtractorAPI(LarozaExtractor("https://mp4.okhd.site", "OkhdSite"))
    registerExtractorAPI(LarozaExtractor("https://rty1.film77.xyz", "Film77"))
}
