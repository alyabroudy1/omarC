package com.cimanow
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.witanime.MailruExtractor
import com.witanime.VideaExtractor
import com.witanime. WitAnime
import com.cloudstream.shared.android.PluginContext

@CloudstreamPlugin
class WitAnimePlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(WitAnime())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())

    }
}