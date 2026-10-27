package com.egydead

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.SnifferExtractor
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class EgyDeadPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        // Shared registry: ReviewRate/Savefiles/OkPrime plus the Byse/EarnVids/
        // Vidmoly/MixDropTop/Uqload/Vidara clones used by this site's watch
        // servers (same pattern as sibling providers)
        registerSharedExtractors()

        val sniffer = SnifferExtractor()
        sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { ActivityProvider.currentActivity }
        registerExtractorAPI(sniffer)

        registerMainAPI(EgyDead())
    }
}
