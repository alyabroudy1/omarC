package com.krmzy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.api.Log
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser

class KrmzyProvider : BaseProvider() {
    override val baseDomain get() = "krmzi.org"
    override val providerName get() = "قرمزي"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/krmzy.json"

    override val mainPage = mainPageOf(
        "$mainUrl/series-list/page/" to "جميع المسلسلات",
    )

    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportsLazySearch = true

    override fun getParser(): NewBaseParser = KrmzyParser()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "KrmzyProvider"
        httpService.ensureInitialized()

        val referer = try {
            val uri = java.net.URI(data)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) { data }

        val episodePage = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e(TAG, "failed to fetch episode page: ${e.message}")
            return false
        }

        val extractorHref = episodePage.selectFirst("a.fullscreen-clickable")?.attr("href")?.trim()
        if (extractorHref.isNullOrBlank()) {
            Log.w(TAG, "no a.fullscreen-clickable found")
            return false
        }

        if (extractorHref.endsWith(".m3u8", ignoreCase = true) || extractorHref.endsWith(".mp4", ignoreCase = true)) {
            callback(newExtractorLink(this.name, this.name, extractorHref) {
                this.referer = referer
                this.quality = getQualityFromName(extractorHref)
            })
            return true
        }

        val extractorPage = try {
            app.get(extractorHref, referer = data).document
        } catch (e: Exception) {
            Log.e(TAG, "failed to fetch extractor page: ${e.message}")
            return false
        }

        val serverItems = extractorPage.select("ul.serversList li")
        if (serverItems.isEmpty()) {
            Log.w(TAG, "no server items found")
            return false
        }

        for (li in serverItems) {
            try {
                val serverId = li.attr("data-server").ifBlank { li.attr("data-server-id") }
                val serverName = li.attr("data-name").ifBlank { li.attr("data-type") }.trim()
                val serverType = serverName.lowercase().trim()

                val embedUrl = when (serverType) {
                    "youtube" -> "https://www.youtube.com/watch?v=$serverId"
                    "youtube_in" -> "https://www.youtube.com/embed/$serverId"
                    "express" -> serverId.ifBlank { null }
                    "dailymotion" -> {
                        val a = li.selectFirst("code a")
                        if (a != null) a.attr("href")
                        else li.selectFirst("code")?.text()?.takeIf { it.isNotBlank() }
                    }
                    "facebook" -> "https://app.videas.fr/embed/media/$serverId"
                    "estream" -> "https://arabveturk.com/embed-$serverId.html"
                    "arab hd", "arabhd", "arab-hd" -> "https://v.turkvearab.com/embed-$serverId.html"
                    "box" -> "https://youdboox.com/embed-$serverId.html"
                    "now" -> "https://extreamnow.org/embed-$serverId.html"
                    "ok" -> "https://ok.ru/videoembed/$serverId"
                    "red hd", "redhd", "red-hd" -> "https://iplayerhls.com/e/$serverId"
                    "pro hd", "prohd", "pro-hd" -> "https://ebtv.upns.live/#$serverId"
                    "pro" -> "https://mdna.upns.online/#$serverId"
                    else -> {
                        val fallbackHref = li.selectFirst("a")?.attr("href")
                        val fallbackData = li.attr("data-src")
                        when {
                            !fallbackHref.isNullOrBlank() -> fallbackHref
                            !fallbackData.isNullOrBlank() -> fallbackData
                            else -> null
                        }
                    }
                }

                if (embedUrl.isNullOrBlank()) continue
                val low = embedUrl.lowercase()

                when {
                    low.contains(".m3u8") || low.endsWith(".mp4") -> {
                        callback(newExtractorLink(this.name, serverName, embedUrl) {
                            this.referer = referer
                            this.quality = getQualityFromName(embedUrl)
                        })
                    }
                    low.contains("youtube.com") || low.contains("youtu.be") -> {
                        callback(newExtractorLink(this.name, "YouTube", embedUrl) {
                            this.referer = referer
                        })
                    }
                    else -> {
                        try {
                            loadExtractor(embedUrl, referer, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.w(TAG, "loadExtractor failed for $serverName: ${e.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "exception processing server: ${t.message}")
            }
        }
        return true
    }
}
