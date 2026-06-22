package com.tuniflix
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.Tuniflix
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class tuniflixPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(Tuniflix())
    }
}