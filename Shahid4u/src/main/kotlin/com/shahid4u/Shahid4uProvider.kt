package com.shahid4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class Shahid4uProvider : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(Shahid4u())

    }
}
