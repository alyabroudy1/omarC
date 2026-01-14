package com.faselhd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.faselhd.utils.PluginContext

@CloudstreamPlugin
class FaselHDPlugin: Plugin() {
    
    companion object {
        /** Activity context for ProviderHttpService initialization */
        var activity: Context? = null
            private set
    }
    
    override fun load(context: Context) {
        activity = context
        PluginContext.init(context)
        
        // Register both providers for comparison testing
        registerMainAPI(FaselHD())      // Original provider
        registerMainAPI(FaselHDv2())    // New service-based provider
    }
}
