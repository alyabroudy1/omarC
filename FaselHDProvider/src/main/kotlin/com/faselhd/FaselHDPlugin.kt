package com.faselhd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.faselhd.utils.PluginContext

@CloudstreamPlugin
class FaselHDPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(FaselHD())
    }
}
