package com.cimanow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimaNow : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaNowProvider(context))
    }
}
