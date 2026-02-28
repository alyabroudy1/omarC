package com.watanflix

import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.api.Log
import com.youtube.innertube.InnerTubeClient
import com.youtube.innertube.InnerTubeParser
import com.fasterxml.jackson.annotation.JsonProperty

class Watanflix : BaseProvider() {

    override val baseDomain get() = "watanflix.com"
    override val providerName get() = "Watanflix"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/watanflix.json"

    companion object {
        private const val TAG = "Watanflix"
    }

    override val mainPage = mainPageOf(
        "/en/category/مسلسلات" to "مسلسلات",
        "/en/category/الأفلام" to "أفلام",
        "/en/category/مسرحيات" to "مسرحيات",
        "/en/category/برامج" to "برامج",
        "/en/category/أطفال" to "أطفال"
    )

    override fun getParser(): NewBaseParser {
        return WatanflixParser()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=$data")

        // Extract YouTube video ID from various URL formats
        val videoId = extractVideoId(data) ?: run {
            Log.w(TAG, "loadLinks: Could not extract video ID from $data, falling back to WebView")
            launchWebViewPlayer(data)
            return true
        }
        Log.d(TAG, "loadLinks: videoId=$videoId")

        // ── Step 1: Fetch player data (IOS → ANDROID_TESTSUITE fallback) ──
        val playerJson = InnerTubeClient.getPlayer(videoId)
        if (playerJson == null) {
            Log.e(TAG, "loadLinks: Player API returned null — falling back to WebView")
            launchWebViewPlayer(data)
            return true
        }

        // ── Step 2: Check playability ──
        val status = playerJson.path("playabilityStatus").path("status").textValue()
        if (status != "OK") {
            val reason = playerJson.path("playabilityStatus").path("reason").textValue() ?: "Unknown"
            Log.w(TAG, "loadLinks: Not playable: $status — $reason — falling back to WebView")
            launchWebViewPlayer(data)
            return true
        }

        // ── Step 3: Parse streaming data ──
        val result = InnerTubeParser.parseStreamingData(playerJson)
        var linksEmitted = 0

        // ── Tier 1: HLS manifest for live streams ──
        if (result.hlsManifestUrl != null) {
            callback(
                newExtractorLink(
                    source = "Watanflix",
                    name = "Watanflix Live",
                    url = result.hlsManifestUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://www.youtube.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = buildPlayerHeaders()
                }
            )
            linksEmitted++
            Log.d(TAG, "loadLinks: [Tier 1] HLS manifest emitted — done")
            return true
        }

        // ── Tier 2: DASH manifest from adaptive formats ──
        if (result.adaptiveFormats.isNotEmpty()) {
            val dashUri = com.youtube.innertube.DashManifestGenerator.generateUrl(result.adaptiveFormats)
            if (dashUri != null) {
                callback(
                    newExtractorLink(
                        source = "Watanflix",
                        name = "Watanflix DASH",
                        url = dashUri,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = "https://www.youtube.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = buildPlayerHeaders()
                    }
                )
                linksEmitted++
                Log.d(TAG, "loadLinks: [Tier 2] DASH manifest emitted (${result.adaptiveFormats.size} adaptive streams)")
            } else {
                Log.w(TAG, "loadLinks: [Tier 2] DASH skipped — generator returned null")
            }
        } else {
            Log.w(TAG, "loadLinks: [Tier 2] DASH skipped — no adaptive formats parsed")
        }

        // ── Tier 3: Muxed formats (always emitted alongside DASH for robustness) ──
        for (stream in result.muxedFormats) {
            callback(
                newExtractorLink(
                    source = "Watanflix",
                    name = "Watanflix ${stream.qualityLabel ?: "Auto"}",
                    url = stream.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.youtube.com/"
                    this.quality = mapQuality(stream.qualityLabel)
                    this.headers = buildPlayerHeaders()
                }
            )
            linksEmitted++
            Log.d(TAG, "loadLinks: [Tier 3] Muxed itag=${stream.itag} quality=${stream.qualityLabel}")
        }

        // ── Tier 4: WebView last resort ──
        if (linksEmitted == 0) {
            Log.w(TAG, "loadLinks: No links emitted from any tier — falling back to WebView")
            launchWebViewPlayer(data)
        }

        Log.d(TAG, "loadLinks: Done — $linksEmitted links emitted (${result.adaptiveFormats.size} adaptive, ${result.muxedFormats.size} muxed)")
        return true
    }

    /** Build consistent headers for stream requests. Matches the IOS client used by InnerTubeClient. */
    private fun buildPlayerHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    )

    /**
     * Extract YouTube video ID from various URL formats.
     */
    private fun extractVideoId(url: String): String? {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            url.contains("embed/") -> url.substringAfter("embed/").substringBefore("?")
            url.length == 11 -> url  // Raw video ID
            else -> null
        }
    }

    private fun mapQuality(label: String?): Int = when {
        label == null -> Qualities.Unknown.value
        label.contains("2160") -> Qualities.P2160.value
        label.contains("1440") -> Qualities.P1440.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720") -> Qualities.P720.value
        label.contains("480") -> Qualities.P480.value
        label.contains("360") -> Qualities.P360.value
        label.contains("240") -> Qualities.P240.value
        label.contains("144") -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    private fun launchWebViewPlayer(url: String) {
        CommonActivity.activity?.let { activity ->
            if (activity is android.app.Activity) {
                activity.runOnUiThread {
                    val dialog = com.cloudstream.shared.ui.WebViewPlayerDialog(activity, url)
                    dialog.show()
                }
            }
        }
    }
}
