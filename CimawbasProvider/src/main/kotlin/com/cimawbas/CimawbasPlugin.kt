package com.cimawbas

import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class CimawbasPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        val api = Cimawbas()
        registerMainAPI(api)
        registerSharedExtractors(api.runtime)
    }
}
