package com.iptv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.android.ActivityProvider

@CloudstreamPlugin
class IPTVPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)

        ActivityProvider.initCompat(context)
        if (context is android.app.Activity) {
            ActivityProvider.setActivity(context)
        }

        registerMainAPI(IPTVProvider())
    }
}
