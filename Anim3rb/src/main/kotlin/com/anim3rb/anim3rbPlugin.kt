package com.anime3rb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class anim3rbPlugin : Plugin() {
    override fun load(context: Context) {
        registerSharedExtractors()
        registerMainAPI(Anim3rbProvider())
    }
}
