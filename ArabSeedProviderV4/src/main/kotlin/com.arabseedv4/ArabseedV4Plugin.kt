package com.arabseedv4

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.SnifferExtractor

@CloudstreamPlugin
class ArabseedV4Plugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        // Initialize ActivityProvider safely
        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        // Register extractors
        registerExtractorAPI(com.cloudstream.shared.extractors.ReviewRateExtractor())    
        registerExtractorAPI(com.cloudstream.shared.extractors.SavefilesExtractor()) 
        registerExtractorAPI(com.cloudstream.shared.extractors.OkPrimeExtractor()) 

        val sniffer = SnifferExtractor()
        sniffer.webViewEngine = com.cloudstream.shared.webview.WebViewEngine { ActivityProvider.currentActivity }
        registerExtractorAPI(sniffer)       

        // Register provider
        registerMainAPI(ArabseedV4())
    }
}