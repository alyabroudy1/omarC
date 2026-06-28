package com.eshk
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.extractors.EshkEmbedExtractor
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class eishkPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(eishk())
        registerSharedExtractors()
        registerExtractorAPI(EshkEmbedExtractor())
    }
}
