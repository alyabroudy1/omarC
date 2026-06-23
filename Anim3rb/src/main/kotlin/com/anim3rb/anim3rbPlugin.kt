package com.anime3rb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class Anim3rbPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        ActivityProvider.initCompat(context)
        registerSharedExtractors()
        registerMainAPI(Anim3rbProvider())
    }
}
