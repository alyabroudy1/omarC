package com.krmzy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.extractors.registerSharedExtractors
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class KrmzyPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerSharedExtractors()
        registerMainAPI(KrmzyProvider())
    }
}
