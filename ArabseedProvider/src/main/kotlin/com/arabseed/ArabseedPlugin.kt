package com.arabseed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.arabseed.extractors.ArabseedLazyExtractor
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.ReviewRateExtractor
import com.cloudstream.shared.extractors.SnifferExtractor

@CloudstreamPlugin
class ArabseedPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        
        // Initialize ActivityProvider safely
        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }
        
        // Register extractors
        registerExtractorAPI(ArabseedLazyExtractor())  // Handles virtual /get__watch__server/ URLs
        registerExtractorAPI(com.arabseed.extractors.ArabseedVirtualExtractor()) // Handles arabseed-virtual:// URLs
        registerExtractorAPI(ReviewRateExtractor())    // Handles reviewrate.net URLs
        
        val sniffer = SnifferExtractor()
        sniffer.webViewEngine = com.cloudstream.shared.webview.WebViewEngine { ActivityProvider.currentActivity }
        registerExtractorAPI(sniffer)       // Handles sniffer:// URLs (video sniffing fallback)
        
        // Register provider
        registerMainAPI(ArabseedV2())
    }
}


