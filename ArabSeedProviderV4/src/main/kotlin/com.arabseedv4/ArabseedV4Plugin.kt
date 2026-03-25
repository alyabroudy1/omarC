package com.arabseedv4

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.SnifferExtractor

import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class ArabseedV4Plugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        // Initialize ActivityProvider safely
        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        // Dynamically register all shared extractors (Vidmoly, EarnVids, ReviewRate, Sniffer, etc)
        registerSharedExtractors()

        // Register provider
        registerMainAPI(ArabseedV4())
    }
}