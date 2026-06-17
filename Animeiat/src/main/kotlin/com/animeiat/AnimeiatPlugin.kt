package com.asia2tv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.animeiat.AnimeiatProvider

@CloudstreamPlugin
class AnimeiatPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeiatProvider())

    }
}