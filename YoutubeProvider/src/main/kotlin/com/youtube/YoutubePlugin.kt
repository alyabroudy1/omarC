package com.youtube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class YoutubePlugin: Plugin() {
    override fun load(context: Context) {
        // Register provider
        registerMainAPI(YoutubeProvider())
    }
}
