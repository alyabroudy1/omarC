package com.akwam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AkwamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Akwam())
    }
}
