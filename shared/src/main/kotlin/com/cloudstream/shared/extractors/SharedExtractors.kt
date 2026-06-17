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
    android.util.Log.d("SharedExtractors", "Registering VidobaExtractor...")
    registerExtractorAPI(VidobaExtractor())
    android.util.Log.d("SharedExtractors", "VidobaExtractor registered successfully")
    registerExtractorAPI(VertyuzExtractor())
    registerExtractorAPI(CswruExtractor())
    
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

    // ──────────────────────────────────────────────────────────────────────────
    // Common embed host extractors (for sites that use cswru wrappers pointing
    // to mixdrop, ds2play, krakenfiles, luluvdo, filelions, etc.)
    // ──────────────────────────────────────────────────────────────────────────

    // MixDrop (extracts video URL from packed JS on /e/ pages)
    registerExtractorAPI(MixDropExtractor("https://mixdrop.ag", "MixDropAg"))
    registerExtractorAPI(MixDropExtractor("https://mixdrop.co", "MixDropCo"))
    registerExtractorAPI(MixDropExtractor("https://mixdrop.ch", "MixDropCh"))
    registerExtractorAPI(MixDropExtractor("https://mixdrop.to", "MixDropTo"))
    registerExtractorAPI(MixDropExtractor("https://mixdrop.bz", "MixDropBz"))

    // DoodStream (used by ds2play.com, ds2video.com)
    registerExtractorAPI(Ds2playExtractor("https://ds2play.com", "Ds2play"))
    registerExtractorAPI(Ds2playExtractor("https://ds2video.com", "Ds2video"))

    // Krakenfiles
    registerExtractorAPI(KrakenfilesExtractor("https://krakenfiles.com", "Krakenfiles"))

    // LuluStream (luluvdo.com, lulustream.com)
    registerExtractorAPI(LuluStreamExtractor("https://luluvdo.com", "LuluStream"))
    registerExtractorAPI(LuluStreamExtractor("https://lulustream.com", "Lulustream"))

    // Filelions / VidHidePro
    registerExtractorAPI(FilelionsExtractor("https://filelions.to", "VidHidePro"))
    registerExtractorAPI(FilelionsExtractor("https://filelions.live", "VidHideProLive"))
    registerExtractorAPI(FilelionsExtractor("https://filelions.online", "VidHideProOnline"))
}
