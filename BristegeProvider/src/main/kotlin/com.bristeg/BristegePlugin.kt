package com.bristeg

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.api.Log

@CloudstreamPlugin
class BristegePlugin: Plugin() {
    override fun load(context: Context) {
        Log.i("Bristege", "Loading Bristege plugin")
        registerMainAPI(Bristege())
    }
}
