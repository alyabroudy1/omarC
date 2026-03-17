package com.akwam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class AkwamPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(Akwam())
    }
}
