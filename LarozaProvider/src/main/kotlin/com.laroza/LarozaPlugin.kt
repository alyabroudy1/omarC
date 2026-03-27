package com.laroza

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.ReviewRateExtractor
import com.cloudstream.shared.extractors.SnifferExtractor
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class LarozaPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        // Initialize ActivityProvider safely
        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        // Register extractors
        registerSharedExtractors()

        val sniffer = SnifferExtractor()
        sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { ActivityProvider.currentActivity }
        registerExtractorAPI(sniffer)       // Handles sniffer:// URLs (video sniffing fallback)

        // Register provider
        registerMainAPI(Laroza())
    }
}