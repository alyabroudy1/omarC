package com.faselHDV2

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.ReviewRateExtractor
import com.cloudstream.shared.extractors.SnifferExtractor

@CloudstreamPlugin
class FaselHDV2Plugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        // Initialize ActivityProvider safely
        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        // Register extractors
        registerExtractorAPI(com.cloudstream.shared.extractors.ReviewRateExtractor())    // Handles reviewrate.net URLs
        registerExtractorAPI(com.cloudstream.shared.extractors.SavefilesExtractor()) // Handles savefiles.com URLs
        registerExtractorAPI(com.cloudstream.shared.extractors.OkPrimeExtractor()) // Handles okprime.site URLs
        registerExtractorAPI(com.cloudstream.shared.extractors.Up4FunExtractor())

        val sniffer = SnifferExtractor()
        sniffer.webViewEngine = com.cloudstream.shared.webview.WebViewEngine { ActivityProvider.currentActivity }
        registerExtractorAPI(sniffer)       // Handles sniffer:// URLs (video sniffing fallback)

        // Register provider
        registerMainAPI(FaselHDV2())
    }
}