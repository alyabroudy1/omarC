package com.tuniflix
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.Tuniflix

@CloudstreamPlugin
class tuniflixPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Tuniflix())
    }
}