package com.arabseed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.arabseed.extractors.ArabseedLazyExtractor
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider

@CloudstreamPlugin
class ArabseedPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        
        // Initialize ActivityProvider safely
        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }
        
        // Register lazy extractor (handles virtual URLs)
        registerExtractorAPI(ArabseedLazyExtractor())
        
        // Register provider
        registerMainAPI(ArabseedV2())
    }
}

