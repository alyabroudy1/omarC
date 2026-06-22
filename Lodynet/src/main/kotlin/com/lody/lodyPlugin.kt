package com.lody

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.LodyNet
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class LodyPlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(LodyNet())
    }
}
