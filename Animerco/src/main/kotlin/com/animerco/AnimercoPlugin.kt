package com.animerco

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class AnimercoPlugin : Plugin() {
    override fun load(context: Context) {
        registerSharedExtractors()
        registerMainAPI(AnimercoProvider())
    }
}
