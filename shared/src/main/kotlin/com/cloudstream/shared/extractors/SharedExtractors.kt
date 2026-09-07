package com.cloudstream.shared.extractors

import com.cloudstream.shared.core.ProviderRuntime
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Uqload
import com.lagradost.cloudstream3.plugins.Plugin

class MixDropTop : MixDrop() {
    override var mainUrl = "https://mixdrop.top"
}

class UqloadIs : Uqload() {
    override var mainUrl = "https://uqload.is"
}

/**
 * An extension function to easily register all shared extractors at once
 * inside the respective Provider's Plugin.kt file.
 */
fun Plugin.registerSharedExtractors(runtime: ProviderRuntime) {
    registerExtractorAPI(ReviewRateExtractor(runtime))
    registerExtractorAPI(GameHubExtractor(runtime))
    registerExtractorAPI(SavefilesExtractor())
    registerExtractorAPI(OkPrimeExtractor(runtime))
    registerExtractorAPI(Up4FunExtractor())
    registerExtractorAPI(FaselHDExtractor())
    android.util.Log.d("SharedExtractors", "Registering VidobaExtractor...")
    registerExtractorAPI(VidobaExtractor(runtime))
    android.util.Log.d("SharedExtractors", "VidobaExtractor registered successfully")
    registerExtractorAPI(VertyuzExtractor())
    registerExtractorAPI(CswruExtractor())
    
    // Videa.hu extractor (moved from Animerco)
    registerExtractorAPI(VideaExtractor())
    
    // Mail.ru extractor (moved from Animerco)
    registerExtractorAPI(MailruExtractor())
    
    // Bysezejataos (API-based extraction with AES decryption)
    registerExtractorAPI(ByseExtractor("bysezejataos.com", "Bysezejataos", runtime))
    registerExtractorAPI(ByseExtractor("bysezezj.com", "Bysezezj", runtime))
    registerExtractorAPI(ByseExtractor("bysejetz.com", "Bysejetz", runtime))
    registerExtractorAPI(ByseExtractor("bysezataos.com", "Bysezataos", runtime))
    registerExtractorAPI(ByseExtractor("byseztajaos.com", "Byseztajaos", runtime))
    registerExtractorAPI(ByseExtractor("byseztajos.com", "Byseztajos", runtime))
    registerExtractorAPI(ByseExtractor("bysetayico.com", "Bysetayico", runtime))
    
    // EarnVids and its proxies — one list, EARNVIDS_REGISTRATIONS (see EarnVidsExtractor.kt).
    // `fdewsdc.sbs` came from the four deleted ExternalEarnVidsExtractor copies, which special-cased
    // it (referer hijack to shhahid4u.cam) without ever registering it.
    for ((host, displayName) in EARNVIDS_REGISTRATIONS) {
        registerExtractorAPI(EarnVidsExtractor(host, displayName))
    }
    
    val sniffer = SnifferExtractor()
    sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { com.cloudstream.shared.android.ActivityProvider.currentActivity }
    registerExtractorAPI(sniffer)
    // Vidmoly Proxies
    registerExtractorAPI(VidmolyExtractor("vidmoly.net", "VidmolyNet"))
    registerExtractorAPI(VidmolyExtractor("vidmoly.biz", "VidmolyBiz"))

    // MixDrop proxies (built-in MixDrop covers .co/.bz/.ag/.ch/.to, but not .top)
    registerExtractorAPI(MixDropTop())

    // Uqload and proxies (built-in covers uqload.com / uqload.co)
    registerExtractorAPI(UqloadIs())

    // Luluvid
    registerExtractorAPI(LuluvidExtractor())

    // Arab HD / estream (eval-packed JS extraction from eseek/qeseh)
    registerExtractorAPI(ArabHdExtractor())
    registerExtractorAPI(EstreamExtractor())

    // Odnoklassniki API extractor (videoPlayerMetadata API + fallback to embed scraping)
    registerExtractorAPI(OdnoklassnikiApiExtractor())

    // Laroza embed domains (CF-protected — uses the runtime CF bypass)
    registerExtractorAPI(LarozaExtractor("https://mp4.okhd.site", "OkhdSite", runtime))
    registerExtractorAPI(LarozaExtractor("https://rty1.film77.xyz", "Film77", runtime))
    registerExtractorAPI(LarozaExtractor("https://vidspeed.org:2096", "Vidspeed", runtime))

    // AlbaPlayer (AlbaPlayerControl base64 / Clappr.Player M3U8 extraction)
    registerExtractorAPI(AlbaPlayerExtractor())

    // Liiivideo (Playerjs HLS extraction from vipserver.liiivideo.com)
    registerExtractorAPI(LiiivideoExtractor())
}
