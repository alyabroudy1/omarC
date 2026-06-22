package com.youtube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class YoutubePlugin : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        // All providers should be added in this manner
        val provider = YoutubeProvider()
        provider.resources = context.resources
        provider.pluginPackageName = context.packageName
        registerMainAPI(provider)
    }
}
