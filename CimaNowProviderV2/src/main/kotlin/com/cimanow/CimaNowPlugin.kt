package com.cimanow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.cloudstream.shared.android.PluginContext
import com.cloudstream.shared.extractors.registerSharedExtractors

@CloudstreamPlugin
class CimaNow : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        val provider = CimaNowProvider()
        provider.context = context
        registerMainAPI(provider)
        // Without this, `loadExtractor` inside this plugin sees an empty registry, so every server the
        // surf hands it falls through to the sniffed stream — uqload, GoVid, Vidmoly, EarnVids, Byse and
        // the rest all have extractors here (`UqloadIs` covers uqload.is specifically) and none of them
        // were reachable. Every other provider plugin in the repo already does this; CimaNow's was the
        // one that did not.
        registerSharedExtractors()
    }
}
