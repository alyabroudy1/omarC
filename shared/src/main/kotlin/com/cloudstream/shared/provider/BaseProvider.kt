package com.cloudstream.shared.provider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.ProviderConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.service.ProviderHttpServiceHolder
import com.cloudstream.shared.parsing.ParserInterface.ParsedEpisode
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

abstract class BaseProvider : MainAPI() {
    abstract val providerName: String
    abstract val baseDomain: String
    abstract val githubConfigUrl: String

    override var name: String = providerName
    override var mainUrl: String = "https://$baseDomain"
    override var lang: String = "ar"
    override val hasMainPage: Boolean = true
    override val hasDownloadSupport: Boolean = true
    override val supportedTypes: Set<TvType> = setOf(TvType.TvSeries, TvType.Movie)

    protected abstract fun getParser(): NewBaseParser


    private val httpService by lazy {
        val context = PluginContext.context ?: (com.lagradost.cloudstream3.app as android.content.Context)

        val service = ProviderHttpService.create(
            context = context,
            config = ProviderConfig(
                name = name,
                fallbackDomain = baseDomain,
                githubConfigUrl = githubConfigUrl,
                syncWorkerUrl = getSyncWorkerUrl(),
                skipHeadless = true,
            ),
            parser = getParser(),
            activityProvider = { ActivityProvider.currentActivity }
        )

        ProviderHttpServiceHolder.initialize(service)

        service
    }

    open fun getSyncWorkerUrl(): String = "https://omarstreamcloud.alyabroudy1.workers.dev"

}