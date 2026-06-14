package com.cimaleek

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class CimaLeekPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(CimaLeek())
        registerSharedExtractors()
    }
}
