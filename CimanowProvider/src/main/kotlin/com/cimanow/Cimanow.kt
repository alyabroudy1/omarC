package com.cimanow

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

class Cimanow : BaseProvider() {

    override val baseDomain get() = "cimanow.cc"
    override val providerName get() = "Cimanow"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/cimanow.json"

    override val mainPage = mainPageOf(
        "/category/الافلام" to "أفلام",
        "/category/المسلسلات" to "مسلسلات",
        "/category/برامج-تلفزيونية" to "برامج",
        "/category/رمضان" to "رمضان"
    )

    override fun getParser(): NewBaseParser {
        return CimanowParser()
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
            if (link.name.contains("(DRM Protected)") || (link.referer?.contains("shahid.net") == true && link.url.contains(".mpd"))) {
                Log.d("Cimanow", "Intercepted DRM Shahid link, launching DrmPlayerDialog")
                handledByDialog = true
                ActivityProvider.currentActivity?.let { activity ->
                    activity.runOnUiThread {
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
        
        return success || handledByDialog
    }
}
