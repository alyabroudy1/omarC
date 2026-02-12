package com.laroza

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
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
import org.jsoup.nodes.Document
import com.cloudstream.shared.provider.BaseProvider

class Laroza : BaseProvider() {

    override var baseDomain = "laroza.co"
    override var providerName = "laroza"
    override var githubConfigUrl = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/laroza.json"

    override val mainPage = mainPageOf(
        "/category.php?cat=ramadan-2026/" to "Ramadan",
        "/category.php" to "أفلام",
        "/home.24/" to "مسلسلات"
    )

    override fun getParser(): NewBaseParser {
        return LarozaParser()
    }
}