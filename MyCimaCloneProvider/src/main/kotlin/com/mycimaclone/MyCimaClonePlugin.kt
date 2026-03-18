package com.mycimaclone

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.extractors.registerSharedExtractors
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class MyCimaClonePlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(MyCimaClone())
        registerSharedExtractors()
    }
}
