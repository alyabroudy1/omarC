package com.faselHDV2

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

class FaselHDV2 : BaseProvider() {

    override val baseDomain get() = "faselhds.biz"
    override val providerName get() = "FaselHDV2"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/faselhd.json"



    override val mainPage = mainPageOf(
        "/all-movies/page/" to "جميع الافلام",
        "/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "/dubbed-movies/page/" to "الأفلام المدبلجة",
        "/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "/series/page/" to "مسلسلات",
        "/recent_series/page/" to "المضاف حديثا",
        "/anime/page/" to "الأنمي",
    )

    override fun getParser(): NewBaseParser {
        return FaselHDV2Parser()
    }
}