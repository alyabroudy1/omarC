package com.laroza

import com.lagradost.cloudstream3.*
import com.lagradost.api.Log
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.android.ActivityProvider
import com.cloudstream.shared.android.PluginContext
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.cloudstream.shared.ui.DrmPlayerDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import com.cloudstream.shared.provider.BaseProvider

class Laroza : BaseProvider() {

    override val baseDomain get() = "laroza.cfd"
    override val providerName get() = "Laroza"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/laroza.json"



    override val mainPage = mainPageOf(
        "/category.php?cat=ramadan-2026" to "رمضان 2026",
        "/category.php?cat=arabic-movies33" to "أفلام",
        "/category.php?cat=arabic-series46" to "مسلسلات"
    )

    override fun getParser(): NewBaseParser {
        return LarozaParser()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Intercept the callback from BaseProvider
        var handledByDialog = false
        val interceptingCallback: (ExtractorLink) -> Unit = { link ->
            // If it's a Shahid stream (which uses DRM), launch DrmPlayerDialog
            if (link.url.contains("shahid.net") && link.url.contains(".mpd")) {
                Log.d("Laroza", "Intercepted DRM Shahid link, launching DrmPlayerDialog")
                handledByDialog = true
                ActivityProvider.currentActivity?.let { activity ->
                    activity.runOnUiThread {
                        // Pass the referer URL (embed page) to the dialog to load the DRM player
                        val dialogUrl = link.referer ?: link.url
                        val dialog = DrmPlayerDialog(activity, dialogUrl, link.referer)
                        dialog.show()
                    }
                }
            } else {
                // Normal link, pass to ExoPlayer
                callback(link)
            }
        }
        
        val success = super.loadLinks(data, isCasting, subtitleCallback, interceptingCallback)
        
        // If we handled it with the dialog, we consider it a success even if no ExoPlayer links were returned
        return success || handledByDialog
    }
}