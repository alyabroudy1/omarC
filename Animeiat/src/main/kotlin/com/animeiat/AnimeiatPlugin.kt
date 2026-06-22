package com.asia2tv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.animeiat.AnimeiatProvider
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class AnimeiatPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(AnimeiatProvider())

    }
}