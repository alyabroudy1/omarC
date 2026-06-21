package com.cimatn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class CimaTnPlugin : Plugin() {
    override fun load(context: Context) {
        registerSharedExtractors()
        registerMainAPI(CimaTn())
    }
}
