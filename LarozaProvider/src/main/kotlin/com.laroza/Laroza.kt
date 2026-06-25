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

    override var name = "لاروزا"
    override val baseDomain get() = "laroza.cfd"
    override val providerName get() = "Laroza"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/laroza.json"
    override val paginationFormat get() = "&page=%d"

// note that mainpage has to contain ? in order for the pagination to work
    override val mainPage = mainPageOf(
        "/newvideos1.php?" to "أحدث الاضافات",
        "/category.php?cat=all_movies_13" to "افلام اجنبي",
        "/category.php?cat=7-aflammdblgh" to "افلام مدبلجة",
        "/moslslat4.php?" to "مسلسلات",
        "/category.php?cat=arabic-series46" to "مسلسلات عربية",
        "/category.php?cat=tv-programs12" to "برامج تلفزيونية"
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
        Log.d("Laroza", "loadLinks START: data='$data'")

        // =============================================
        // NEW STRATEGY: Try extracting embed URLs directly from the page
        // looking for data-embed-url, iframes, and video sources
        // =============================================
        val newStrategyResult = tryNewExtractionStrategy(data, subtitleCallback, callback)
        if (newStrategyResult) {
            Log.d("Laroza", "loadLinks: NEW strategy succeeded")
            return true
        }
        Log.d("Laroza", "loadLinks: NEW strategy failed, falling back to BaseProvider")

        // =============================================
        // FALLBACK: Original BaseProvider strategy (DRM interception kept)
        // =============================================
        var handledByDialog = false
        val interceptingCallback: (ExtractorLink) -> Unit = { link ->
            if (link.name.contains("(DRM Protected)") || (link.referer?.contains("shahid.net") == true && link.url.contains(".mpd"))) {
                Log.d("Laroza", "Intercepted DRM Shahid link, launching DrmPlayerDialog")
                handledByDialog = true
                ActivityProvider.currentActivity?.let { activity ->
                    activity.runOnUiThread {
                        val dialogUrl = link.referer ?: link.url
                        val dialog = DrmPlayerDialog(activity, dialogUrl, link.referer)
                        dialog.show()
                    }
                }
            } else {
                callback(link)
            }
        }

        val success = super.loadLinks(data, isCasting, subtitleCallback, interceptingCallback)
        return success || handledByDialog
    }

    private suspend fun tryNewExtractionStrategy(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val methodTag = "[Laroza][newStrategy]"
        try {
            // Fetch the detail page
            val doc = httpService.getDocument(data) ?: return false
            Log.d(methodTag, "Fetched detail page: ${doc.location()}")

            // Try to find a play/watch page URL
            val playUrl = getParser().getPlayerPageUrl(doc)
            Log.d(methodTag, "Player page URL: $playUrl")

            // Fetch the target page (play page or detail page)
            val targetUrl = if (!playUrl.isNullOrBlank()) {
                val fullUrl = if (playUrl.startsWith("http")) playUrl
                else "$mainUrl/${playUrl.trimStart('/')}"
                Log.d(methodTag, "Fetching player page: $fullUrl")
                fullUrl
            } else null

            val targetDoc = if (targetUrl != null) {
                httpService.getDocument(targetUrl)
            } else doc

            if (targetDoc == null) {
                Log.d(methodTag, "Failed to fetch target doc, trying detail page instead")
                // Fall through to use detail doc
            }

            val actualDoc = targetDoc ?: doc

            // Extract embed URLs using ALL available selectors
            val embedUrls = getParser().extractWatchServersUrls(actualDoc)
            Log.d(methodTag, "Found ${embedUrls.size} embed URL(s)")
            embedUrls.forEachIndexed { i, url ->
                Log.d(methodTag, "  embed[$i]: $url")
            }

            if (embedUrls.isEmpty()) {
                Log.d(methodTag, "No embed URLs found")
                return false
            }

            // Resolve and call loadExtractor for each embed URL
            val foundAny = java.util.concurrent.atomic.AtomicBoolean(false)
            val referer = try {
                val uri = java.net.URI(targetUrl ?: data)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
                "https://$baseDomain/"
            }

            coroutineScope {
                embedUrls.mapIndexed { index, url ->
                    launch {
                        try {
                            val resolved = resolveServerUrl(url, referer)
                            if (!resolved.isNullOrBlank()) {
                                Log.d(methodTag, "Resolved [$index]: ${url.take(60)} -> ${resolved.take(60)}")
                                val links = withTimeoutOrNull(8000L) {
                                    val collected = mutableListOf<ExtractorLink>()
                                    loadExtractor(resolved, referer, subtitleCallback) { link ->
                                        collected.add(link)
                                        callback(link)
                                    }
                                    collected
                                }
                                if (links != null && links.isNotEmpty()) {
                                    Log.d(methodTag, "links found via new strategy for [$index]: ${resolved.take(60)}")
                                    foundAny.set(true)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(methodTag, "Failed to extract [$index]: ${e.message}")
                        }
                    }
                }
            }

            return foundAny.get()
        } catch (e: Exception) {
            Log.w(methodTag, "Strategy failed: ${e.message}")
            return false
        }
    }
}