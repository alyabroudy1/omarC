package com.bristeg

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.api.Log
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class BristegePlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(Bristege())
    }
}
