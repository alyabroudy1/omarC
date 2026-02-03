package com.arabseed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.arabseed.extractors.ArabseedLazyExtractor
import com.arabseed.utils.PluginContext

@CloudstreamPlugin
class ArabseedPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        
        // Initialize ActivityProvider safely
        com.arabseed.utils.ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            com.arabseed.utils.ActivityProvider.setActivity(context)
        }
        
        // Register lazy extractor (handles virtual URLs)
        registerExtractorAPI(ArabseedLazyExtractor())
        
        // Register provider
        registerMainAPI(ArabseedV2())
    }
}
