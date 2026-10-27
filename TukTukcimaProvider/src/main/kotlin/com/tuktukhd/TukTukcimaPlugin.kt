package com.tuktukhd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class TukTukcimaPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        // Full shared registry (Byse/EarnVids/Vidmoly/MixDropTop/Uqload/Vidara
        // clones). Without this, mirror hosts have no extractor and every
        // episode reports "No Links Found" on installs without sibling plugins.
        registerSharedExtractors()
        registerMainAPI(TukTukcima())
    }
}
