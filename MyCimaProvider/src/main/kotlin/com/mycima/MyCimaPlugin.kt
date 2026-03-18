package com.mycima

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class MyCimaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyCima())
        registerSharedExtractors()
    }
}
