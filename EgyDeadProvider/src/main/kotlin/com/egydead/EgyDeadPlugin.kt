package com.egydead

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.ReviewRateExtractor
import com.cloudstream.shared.extractors.SnifferExtractor

@CloudstreamPlugin
class EgyDeadPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        registerExtractorAPI(ReviewRateExtractor())
        registerExtractorAPI(com.cloudstream.shared.extractors.SavefilesExtractor())
        registerExtractorAPI(com.cloudstream.shared.extractors.OkPrimeExtractor())

        val sniffer = SnifferExtractor()
        sniffer.videoSnifferEngine = com.cloudstream.shared.webview.VideoSnifferEngine { ActivityProvider.currentActivity }
        registerExtractorAPI(sniffer)

        registerMainAPI(EgyDead())
    }
}
