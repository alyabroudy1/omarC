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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import com.cloudstream.shared.provider.BaseProvider

class Laroza : BaseProvider() {

    override val baseDomain get() = "laroza.cfd"
    override val providerName get() = "Laroza"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/laroza.json"
    override val searchPath = "/?s="

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
        val parser = LarozaParser()
        val doc = app.get(data).document
        
        val playLink = parser.getPlayerPageUrl(doc)
        val targetDoc = if (!playLink.isNullOrBlank()) {
             Log.d("Laroza", "Found play link: $playLink")
             val url = fixUrl(playLink!!)
             app.get(url, headers = mapOf("Referer" to "https://$baseDomain/")).document
        } else {
             doc
        }

        val urls = parser.extractPlayerUrls(targetDoc)
        Log.d("Laroza", "Found ${urls.size} player URLs")
        
        urls.forEach { 
            loadExtractor(it, "https://$baseDomain/", subtitleCallback, callback)
        }
        
        return urls.isNotEmpty()
    }
}