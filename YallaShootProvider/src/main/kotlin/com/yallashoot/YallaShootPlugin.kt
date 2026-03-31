package com.yallashoot

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class YallaShootPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(YallaShoot())
        registerSharedExtractors()
    }
}
